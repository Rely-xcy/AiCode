package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val pendingQueue: MemoryPendingQueue
) {
    internal companion object {
        /** 命中记账的去重表上限；超限时淘汰最旧的一条（表本身保持有界，不整表清空）。 */
        private const val HIT_CACHE_LIMIT = 500
        private const val TAG = "MemoryRepository"

        /**
         * 写入前判重的正文相似阈值。
         *
         * 与 [MemoryCurator] 合并用的 DUPLICATE_THRESHOLD 同为 0.75：写入侧和治理侧必须同一口径，
         * 否则会出现「写的时候没认出来，下一次治理又被合并」的反复。
         */
        internal const val CONTENT_DUPLICATE_THRESHOLD = 0.75

        /** 只比较正文前若干字符：与 [MemoryCurator] 的扫描口径一致，也把判重成本钉死。 */
        internal const val CONTENT_SCAN_CHARS = 1_000

        /** 一条 pending 记录最多重试几次，超过就放弃并记日志。 */
        private const val MAX_PENDING_ATTEMPTS = 5

        /**
         * 记忆名归一化：先按落盘文件名同一套规则（[MemorySource.sanitizeName]）去掉非法字符，
         * 再忽略大小写。`Foo Bar` 与 `FOO-BAR` 归一到同一个名字。
         */
        internal fun normalizeName(name: String): String = MemorySource.sanitizeName(name).lowercase()

        /** 正文 token 集合的 Jaccard 相似度；任一方为空返回 0（两条空正文不该算重复）。 */
        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val intersection = a.count { it in b }
            val union = a.size + b.size - intersection
            return if (union == 0) 0.0 else intersection.toDouble() / union
        }

        /** 写入前判重用：正文 Jaccard 达到阈值即视为同一条。 */
        internal fun isDuplicateContent(existingContent: String, newContent: String): Boolean =
            jaccard(tokensOf(existingContent), tokensOf(newContent)) >= CONTENT_DUPLICATE_THRESHOLD

        private fun tokensOf(content: String): Set<String> =
            MemoryRanker.tokenize(content.take(CONTENT_SCAN_CHARS))
    }

    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /**
     * 记忆被写入 / 编辑 / 删除时发信号。
     *
     * 注入方（MemoryModule）按会话缓存注入内容以保持 system prompt 稳定，
     * 若不知道内容变了，新记忆要等到换会话才生效——主动记忆（模型调工具写）就白写了。
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    init {
        // 启动后第一次记忆读取之前补写上次失败的抽取结果：仓库单例构造先于任何 listMemories，
        // 此时补进来的条目与正常写入的一样会被后续读取看到，读取逻辑本身不用动。
        runCatching { retryPendingMemories() }
            .onFailure { FileLogger.w(TAG, "启动时补写待重试记忆失败", it) }
    }

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。kind 非空时只返回该类型。 */
    fun listMemories(projectRoot: String?, kind: MemoryKind? = null): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）
        val deduped = allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
        return if (kind == null) deduped else deduped.filter { it.kind == kind }
    }

    /**
     * 本会话已记过账的记忆（session:name），避免同一会话反复写盘。
     *
     * 用 LinkedHashSet 保插入序：表满时淘汰**最旧**的一条。原来超限就整表 clear，
     * 会把刚加进去的当前 key 一起丢掉，同一会话里紧接着再记同一条又算首次命中，
     * hitCount 虚高——而 hitCount 是 [MemoryRanker] 判降权、[MemoryCurator] 判退场的输入。
     */
    private val recordedHits: MutableSet<String> =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    /**
     * 记一次去重命中，true 表示本会话首次。判重、插入、淘汰在同一把锁里完成：
     * 分成几步的话，并发下刚 add 的 key 可能已被别的线程淘汰，同一条被重复记账。
     */
    private fun markRecorded(key: String): Boolean = synchronized(recordedHits) {
        if (!recordedHits.add(key)) return@synchronized false
        if (recordedHits.size > HIT_CACHE_LIMIT) {
            val oldest = recordedHits.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        true
    }

    /**
     * 记录一次「被用上」：注入进上下文，或模型主动读。
     *
     * 统计的是**会话数**而不是次数：按次数记会退化成「聊得越久越重要」，
     * 按会话数记才是「多少个不同场景里真的用到了它」。同一会话只记一次。
     * 写盘失败只记日志——统计不能反过来影响注入本身。
     *
     * 读-改-写全程持文件锁（[MemorySource.withFileLock]）：这条路径在 IO 协程里异步跑，
     * 而模型可能正同时调 memory 工具写盘，不加锁就是拿旧内容把刚保存的正文盖回去。
     */
    fun recordHits(memories: List<Memory>, sessionId: String?) {
        memories.forEach { memory ->
            val file = memory.file ?: return@forEach
            val key = "${sessionId.orEmpty()}:${memory.name}"
            if (!markRecorded(key)) return@forEach
            runCatching {
                MemorySource.withFileLock(file) {
                    val parsed = MemoryParser.parse(file, memory.scope)
                    when {
                        parsed == null ->
                            FileLogger.w(TAG, "记录命中时读不到记忆: ${memory.name}")

                        // frontmatter 解析失败时各字段全空。此时重写会把旧元数据抹掉（且不归档），
                        // 宁可少记一次命中，也不能拿「空解析结果」当真写回去。
                        parsed.description.isEmpty() && parsed.source.isEmpty() && parsed.createdAt == 0L ->
                            FileLogger.w(TAG, "记忆元数据解析为空，跳过命中记账以免覆盖: ${memory.name}")

                        else -> MemorySource.writeAtomically(
                            file,
                            MemoryParser.format(
                                name = parsed.name,
                                description = parsed.description,
                                content = parsed.content,
                                kind = parsed.kind,
                                source = parsed.source,
                                createdAt = parsed.createdAt,
                                hitCount = parsed.hitCount + 1,
                                lastHitAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }.onFailure { FileLogger.w(TAG, "记录记忆命中失败: ${memory.name}", it) }
        }
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    /**
     * 保存一条记忆：写入前先在同一 scope / 同一 projectRoot 里查重，命中就更新原条目。
     *
     * 判重规则（只在写入侧，读取路径不动）：规范化 name 相等，或正文 Jaccard ≥ 0.75。
     * 命中时沿用原条目的 name（slug）、created_at、类型与置顶状态——换名新建会让同一条结论
     * 在清单里出现两遍，直到 24 小时后的治理才被合并。
     */
    fun saveMemory(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        kind: MemoryKind = MemoryKind.NOTE,
        source: String = "",
        createdAt: Long = 0L,
        pinned: Boolean? = null
    ): Boolean {
        val duplicate = findDuplicate(name, content, scope, projectRoot)
        val targetName = duplicate?.name ?: name
        // 新描述为空时沿用原描述：只改正文时不该把描述抹成空
        val targetDescription = description.ifBlank { duplicate?.description.orEmpty() }
        val targetKind = duplicate?.kind ?: kind
        val targetCreatedAt = duplicate?.createdAt?.takeIf { it > 0 } ?: createdAt

        val saved = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(
                targetName, targetDescription, content, targetKind, source, targetCreatedAt, pinned
            )
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(
                    targetName, targetDescription, content, targetKind, source, targetCreatedAt, pinned
                )
            }
        }
        if (saved) notifyChanged()
        return saved
    }

    /** 同 scope、同 projectRoot 下查找与本次写入重复的既有条目；没有返回 null。 */
    private fun findDuplicate(
        name: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?
    ): Memory? {
        val existing = listForScope(scope, projectRoot)
        if (existing.isEmpty()) return null
        val normalized = normalizeName(name)
        return existing.firstOrNull { normalizeName(it.name) == normalized }
            ?: existing.firstOrNull { isDuplicateContent(it.content, content) }
    }

    private fun listForScope(scope: MemoryScope, projectRoot: String?): List<Memory> = when (scope) {
        MemoryScope.GLOBAL -> globalMemorySource.listMemories()
        MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }
            ?.let { projectSource(it).listMemories() }
            .orEmpty()
    }

    /**
     * 重放写失败的记忆（pending 队列）。
     *
     * 触发点两处：治理周期到来时（MemoryModule.onTurnCompleted）与仓库构造时（第一次读记忆之前）。
     * 幂等：同 scope 下已有「规范化 name 相同 + 正文哈希相同」的条目，说明这条其实已经落盘，直接丢弃。
     * 单条失败 attempts + 1，达到 [MAX_PENDING_ATTEMPTS] 次后放弃并记日志。
     *
     * @return 本次成功补写的条数。
     */
    fun retryPendingMemories(): Int {
        val recovered = pendingQueue.update { entries ->
            if (entries.isEmpty()) return@update emptyList<PendingMemoryEntry>() to 0
            val remaining = mutableListOf<PendingMemoryEntry>()
            var savedCount = 0
            entries.forEach { entry ->
                val scope = scopeOf(entry.scope)
                if (hasLanded(entry, scope)) {
                    FileLogger.i(TAG, "待重试记忆已有同内容条目，丢弃: ${entry.name}")
                    return@forEach
                }
                val saved = saveMemory(
                    name = entry.name,
                    description = entry.description,
                    content = entry.content,
                    scope = scope,
                    projectRoot = entry.projectRoot,
                    kind = kindOf(entry.kind),
                    source = entry.source,
                    createdAt = entry.createdAt
                )
                when {
                    saved -> savedCount++
                    entry.attempts + 1 >= MAX_PENDING_ATTEMPTS ->
                        FileLogger.w(TAG, "待重试记忆连续 ${entry.attempts + 1} 次写入失败，放弃: ${entry.name}")
                    else -> remaining += entry.copy(attempts = entry.attempts + 1)
                }
            }
            remaining to savedCount
        }
        if (recovered > 0) FileLogger.i(TAG, "补写失败的记忆 $recovered 条")
        return recovered
    }

    /** 已落盘判定：name 归一后相同，且正文哈希相同。 */
    private fun hasLanded(entry: PendingMemoryEntry, scope: MemoryScope): Boolean {
        val normalized = normalizeName(entry.name)
        val hash = contentHash(entry.content)
        return listForScope(scope, entry.projectRoot).any {
            normalizeName(it.name) == normalized && contentHash(it.content) == hash
        }
    }

    private fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun scopeOf(raw: String): MemoryScope =
        runCatching { MemoryScope.valueOf(raw.uppercase()) }.getOrDefault(MemoryScope.GLOBAL)

    private fun kindOf(raw: String): MemoryKind =
        runCatching { MemoryKind.valueOf(raw.uppercase()) }.getOrDefault(MemoryKind.PROFILE)

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        val result = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
        if (result is MemoryEditResult.Success) notifyChanged()
        return result
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        val deleted = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
        if (deleted) notifyChanged()
        return deleted
    }
}
