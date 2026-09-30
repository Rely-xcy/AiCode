package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    private companion object {
        /** 命中记账的去重表上限；超限时淘汰最旧的一条（表本身保持有界，不整表清空）。 */
        const val HIT_CACHE_LIMIT = 500
        const val TAG = "MemoryRepository"
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

    fun saveMemory(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        kind: MemoryKind = MemoryKind.NOTE,
        source: String = "",
        createdAt: Long = 0L
    ): Boolean {
        val saved = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content, kind, source, createdAt)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content, kind, source, createdAt)
            }
        }
        if (saved) notifyChanged()
        return saved
    }

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
