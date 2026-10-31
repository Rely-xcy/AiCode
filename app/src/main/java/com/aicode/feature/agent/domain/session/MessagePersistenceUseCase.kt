package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.toolCallsFrozenForHistory
import com.aicode.feature.agent.domain.model.toolCallsJsonForStorage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePersistenceUseCase @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** agent_messages 表变更版本号：任何写路径（含 rewind 删除、压缩标记、冷启动清理）触发递增，
     *  作为 [buildHistory] 缓存的失效信号。InvalidationTracker 监听表级变更，覆盖所有 DAO 写入。 */
    private val dbVersion = java.util.concurrent.atomic.AtomicLong(0)
    private val historyCache = HashMap<String, HistoryEntry>()

    private class HistoryEntry(
        val version: Long,
        val pendingToolMarker: String,
        val messages: List<AgentMessage>
    )

    init {
        agentDatabase.invalidationTracker.addObserver(
            object : androidx.room.InvalidationTracker.Observer(arrayOf("agent_messages")) {
                override fun onInvalidated(tables: Set<String>) {
                    dbVersion.incrementAndGet()
                }
            }
        )
    }

    /** 内嵌图片 base64 的 LRU 缓存：key = path:size:lastModified，避免工具循环中
     *  每轮 LLM 调用都重读文件 + base64 编码。带条目与总字节双重上限。 */
    private class CachedImage(val mimeType: String, val base64: String)

    private val imageBase64Cache = object : LinkedHashMap<String, CachedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedImage>?): Boolean =
            size > MAX_IMAGE_CACHE_ENTRIES
    }
    private var imageCacheBytes = 0L

    private fun cachedImageBase64(key: String): CachedImage? = synchronized(imageBase64Cache) { imageBase64Cache[key] }

    private fun cacheImageBase64(key: String, value: CachedImage) {
        synchronized(imageBase64Cache) {
            val old = imageBase64Cache.put(key, value)
            imageCacheBytes += value.base64.length - (old?.base64?.length ?: 0)
            while (imageCacheBytes > MAX_IMAGE_CACHE_BYTES && imageBase64Cache.isNotEmpty()) {
                val it = imageBase64Cache.entries.iterator()
                val eldest = it.next()
                it.remove()
                imageCacheBytes -= eldest.value.base64.length
            }
        }
    }

    // 单调递增时间戳：保证同毫秒内多次落库的顺序稳定（assistant 永远在其 tool 结果之前）。
    @Volatile
    private var lastTimestamp = 0L

    @Synchronized
    fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    suspend fun persist(
        sessionId: String,
        role: MessageRole,
        content: String,
        id: String = UUID.randomUUID().toString(),
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        isError: Boolean = false,
        reasoning: String? = null,
        signature: String? = null,
        thinkingBlocksJson: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cachedInputTokens: Int = 0,
        isCompacted: Boolean = false,
        isContextExcluded: Boolean = false,
        modelReminder: String? = null,
        isInterjection: Boolean = false
    ) {
        agentMessageDao.insert(
            AgentMessageEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                content = sanitizeContent(content),
                timestamp = nextTimestamp(),
                // 工具参数在写入这一刻定型（摘录 + JSON 合法的大小收敛），此后任何一轮请求都不再改写：
                // 请求时再投影会让同一条历史消息在不同轮次变形，使前缀缓存整段失效。
                toolCallsJson = if (toolCalls.isNotEmpty()) {
                    toolCallsJsonForStorage(toolCalls, MAX_SNAPSHOT_BYTES, json)
                } else {
                    null
                },
                toolCallId = toolCallId,
                toolName = toolName,
                toolArgs = toolArgs?.let { capBytes(it, MAX_TOOL_ARGS_BYTES) },
                isError = isError,
                reasoning = reasoning?.let { sanitizeContent(it) },
                signature = signature?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
                thinkingBlocksJson = thinkingBlocksJson?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
                attachmentsJson = if (attachments.isNotEmpty()) capBytes(json.encodeToString(attachments), MAX_ATTACHMENTS_BYTES) else null,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens,
                isCompacted = isCompacted,
                isContextExcluded = isContextExcluded,
                modelReminder = modelReminder,
                isInterjection = isInterjection
            )
        )
    }

    suspend fun updateContent(messageId: String, newContent: String) {
        agentMessageDao.updateMessageContent(messageId, sanitizeContent(newContent))
    }

    /**
     * 把本轮的模式提醒写到该用户行上。提醒只在模式变化时注入一次，故一条消息最多写一次；
     * content 保持用户原话，界面与回放都不认它，只在组装请求时拼回模型侧文本。
     * 按快照上限截断：提醒文本来自可被用户自定义覆盖的 prompts 文件，不能任由它撑大单行。
     */
    suspend fun attachModelReminder(messageId: String, reminder: String): String {
        val stored = capBytes(reminder, MAX_SNAPSHOT_BYTES)
        agentMessageDao.updateModelReminder(messageId, stored)
        return stored
    }

    /**
     * 一条消息在落库那一刻的形态。发送侧必须先过这里再拼请求，落库与发送才是同一串字节。
     *
     * 库里读回来的是截断后的正文/思考与「摘录 + 合法 JSON」的工具参数；若本轮发的是内存原文，
     * 下一轮从库里重建历史时这条消息就变形了，它之后的前缀全部未命中。
     * 这里调用的都是常量上限下的纯函数，且对已经处理过的消息幂等，所以同一消息在任何一轮都得到同一结果。
     */
    fun frozenForHistory(message: AgentMessage): AgentMessage = frozenForHistory(message, json)

    /**
     * 保留具体类型的重载：调用方（插话列表、`ToolBatchFinished`）要的是 `UserMessage`，
     * 泛型版本返回 `AgentMessage` 会让上游的 `List<UserMessage>` 参数与 `content/modelReminder` 解析失败。
     */
    fun frozenForHistory(message: AgentMessage.UserMessage): AgentMessage.UserMessage =
        frozenForHistory(message as AgentMessage, json) as AgentMessage.UserMessage

    companion object {
        /**
         * 单条消息各文本字段的持久化上限（UTF-8 字节数）。远小于 SQLite CursorWindow 单窗口约 2MB
         * 的硬限制，防止超大内容撑爆数据行导致读取消息时崩溃。
         *
         * 按字节而非字符设限：中文等文本单字符最多占 3 字节，字符数上限约束不住真实占用。
         * 也不能只限制单个字段——同一条消息可同时带正文、思考、工具入参等多份大快照，
         * 各字段上限之和（约 870KB）必须整体留在窗口大小之下，否则该行可能因窗口预填充
         * 而无处安放，读取时抛 IllegalStateException「Couldn't read row N, col 0 from CursorWindow」。
         *
         * 870KB 的构成：正文与思考各 [MAX_CONTENT_BYTES]（150KB × 2，截断标记再占几十字节，量级不变）
         * + 工具调用快照/思考块/思考签名/模式提醒各 [MAX_SNAPSHOT_BYTES]（100KB × 4）
         * + 工具入参 [MAX_TOOL_ARGS_BYTES]（150KB）+ 附件 [MAX_ATTACHMENTS_BYTES]（20KB）。
         * 上限一旦调整，必须重算这个和并同步 MessagePersistenceUseCaseTest 里的断言。
         */
        const val MAX_CONTENT_BYTES = 150_000
        const val MAX_SNAPSHOT_BYTES = 100_000
        const val MAX_ATTACHMENTS_BYTES = 20_000
        /** 工具入参上限：与正文同量级，长参数（如大段文件内容）能完整落库、由 UI 限高滚动查看。 */
        const val MAX_TOOL_ARGS_BYTES = 150_000
        const val IMAGE_OMITTED_MARKER = "[图片已省略：内嵌图片数据过大]"
        const val CONTENT_TRUNCATED_MARKER = "…[内容过长，已截断]"
        /** 图片 base64 缓存条目上限。 */
        private const val MAX_IMAGE_CACHE_ENTRIES = 12
        /** 图片 base64 缓存总字节上限（base64 为原始大小的 ~4/3，48MB 约可存 36MB 原始图片）。 */
        private const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

        /** 内嵌 base64 图片 data URL（`data:image/...;base64,...`）。 */
        private val INLINE_BASE64_IMAGE = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\r\n]+""")

        internal fun orderHistoryEntities(entities: List<AgentMessageEntity>): List<AgentMessageEntity> {
            // 输入现在是「边界裁剪后的普通原文行」（不含摘要/marker），时间序即回放序。
            // 保持原实现以防上游仍传入摘要行时顺序不乱（摘要前置）。
            val (compaction, retained) = entities.partition { it.isCompactionMarker || it.isContextSummary }
            return compaction + retained
        }

        /** UTF-8 单字符最多占 3 字节，故 [String.length] 的三倍不超上限时无需实际编码即可放行。 */
        private fun definitelyFits(raw: String, maxBytes: Int): Boolean =
            raw.length.toLong() * 3 <= maxBytes

        /**
         * [frozenForHistory] 的纯函数体：与落库共用同一个 [Json] 配置与同一组上限常量。
         * 对已经是冻结形态的消息幂等——第二次调用不会再改任何字节。
         */
        internal fun frozenForHistory(message: AgentMessage, json: Json): AgentMessage = when (message) {
            is AgentMessage.UserMessage -> message.copy(
                content = sanitizeContent(message.content),
                modelReminder = message.modelReminder?.let { capBytes(it, MAX_SNAPSHOT_BYTES) }
            )
            is AgentMessage.AssistantMessage -> message.copy(
                content = sanitizeContent(message.content),
                // 工具参数按落库快照回解：截断始终发生在 JSON 字符串值内部，结构本身保持合法。
                toolCalls = toolCallsFrozenForHistory(message.toolCalls, MAX_SNAPSHOT_BYTES, json),
                reasoning = sanitizeContent(message.reasoning),
                signature = capBytes(message.signature, MAX_SNAPSHOT_BYTES),
                thinkingBlocksJson = capBytes(message.thinkingBlocksJson, MAX_SNAPSHOT_BYTES)
            )
            is AgentMessage.ToolResultMessage -> message.copy(result = sanitizeContent(message.result))
        }

        /**
         * 落库前的内容净化，为所有 provider/模型提供统一兜底防线：
         * 1. 剥离内嵌的 base64 图片 data URL（替换为占位说明），此类内容本不该进数据库文本；
         * 2. 剥离后仍超长的内容按 UTF-8 字节截断到 [MAX_CONTENT_BYTES]，避免任何超大行触发 CursorWindow 崩溃。
         */
        internal fun sanitizeContent(raw: String): String {
            if (definitelyFits(raw, MAX_CONTENT_BYTES) && !raw.contains("data:image/", ignoreCase = true)) {
                return raw
            }
            val stripped = INLINE_BASE64_IMAGE.replace(raw, IMAGE_OMITTED_MARKER)
            val capped = capBytes(stripped, MAX_CONTENT_BYTES)
            return if (capped.length < stripped.length) capped + CONTENT_TRUNCATED_MARKER else capped
        }

        /**
         * 落库前对 JSON 快照字段（toolCallsJson / thinkingBlocksJson / attachmentsJson）与
         * 思考签名等非展示文本按 UTF-8 字节截断。截断后 JSON 不再可解析，读取方经 runCatching
         * 降级为「无工具调用 / 无思考快照 / 无附件」，而非崩溃；不带截断标记，避免给解析方徒增无意义内容。
         * 截断按码点边界进行，不切出半个代理对。
         */
        internal fun capBytes(raw: String, maxBytes: Int): String {
            if (definitelyFits(raw, maxBytes)) return raw
            var used = 0
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val isPair = c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                val charBytes = when {
                    isPair -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                if (used + charBytes > maxBytes) break
                used += charBytes
                i += if (isPair) 2 else 1
            }
            return if (i >= raw.length) raw else raw.substring(0, i)
        }
    }

    /**
     * 从持久化的消息重建合法的上下文历史。
     * 关键：只保留「assistant 的 tool_call」与「tool 结果」能配对成功的部分，
     * 丢弃任何一方缺失的悬挂项，避免回放出现孤儿 tool_use / tool_result 违反 API 约束。
     * 已被上下文压缩标记的消息（isCompacted=true）与被排除出上下文的消息（isContextExcluded=true，
     * 如 /usage 统计行）都不参与回放。两个标志看的是两件事：前者是「折进摘要、回退时可能恢复」，
     * 后者是「从来不属于对话」（迁移 58 把已升级用户的旧 /usage 行归入后者，这里不看它就会把
     * 统计表格回放进上下文）。
     *
     * 摘要块链：未淘汰的新语义摘要块（foldsUpToTimestamp>0）全部按序前置，原文窗口按最大块
     * 边界裁剪；旧单块（边界为 0）走兼容路径（只取最新一个，原文窗口不裁——head 已被旧规则
     * 标 isCompacted 滤掉，不会重复回放）。
     */
    suspend fun buildHistory(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        // 版本化缓存：agent_messages 表无变更且 marker 相同时直接复用上次重建结果，
        // 避免工具循环中每轮 LLM 调用都全量读库 + 多次遍历 + JSON 解码。
        // InvalidationTracker 覆盖所有写路径（含 rewind 删除、压缩标记、冷启动清理），不会漏失效。
        val version = dbVersion.get()
        synchronized(historyCache) {
            historyCache[sessionId]?.let { cached ->
                if (cached.version == version && cached.pendingToolMarker == pendingToolMarker) {
                    return cached.messages
                }
            }
        }
        val messages = withContext(Dispatchers.IO) {
            buildHistoryUncached(sessionId, pendingToolMarker)
        }
        synchronized(historyCache) {
            historyCache[sessionId] = HistoryEntry(version, pendingToolMarker, messages)
        }
        return messages
    }

    /**
     * 丢掉某会话的历史缓存条目（会话被删除时调用）。
     *
     * [dbVersion] 只能挡住「用过期数据」，挡不住「一直占着」：会话删除后不会再 [buildHistory]，
     * 那条目里的整段历史（含按路径重建的图片 base64）会一直留在内存里，直到进程重启。
     * 所以删除路径要显式清理。回退（改正文、删消息）不需要：那类写入会推进 dbVersion，
     * 下一次 [buildHistory] 用同一个 sessionId 直接覆盖旧条目。
     */
    fun evictHistory(sessionId: String) {
        synchronized(historyCache) { historyCache.remove(sessionId) }
    }

    private suspend fun buildHistoryUncached(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        val allEntities = agentMessageDao.getMessagesBySessionOnce(sessionId)
            .filter { !it.isCompacted && !it.isContextExcluded }

        // 摘要块：isContextSummary 行（含其配对 marker）按 timestamp 升序就是块链的顺序。
        // 已被淘汰的摘要行（isCompacted=1）在上面已被滤掉，不会参与回放。
        // 窗口起点：参与回放的新语义块（foldsUpToTimestamp>0）里最大的覆盖边界。该边界之前的
        // 原文已由摘要块链代表，不再回放；无用块时 boundary = 0，窗口 = 全部原文。
        // 注意旧单块（foldsUpToTimestamp=0，兼容路径）不抬边界：旧语义下 head 已标 isCompacted
        // 被上面滤掉，boundary=0 的过滤不会重复回放它们；把 boundary 抬到旧块的回填边界反而在
        // 残留对共享边界时把新折进去的原文误裁掉，所以旧块一律不参与边界计算。
        val allSummaryPairs = collectSummaryPairs(allEntities)
        val modernSummaryPairs = allSummaryPairs.filter { it.summary.foldsUpToTimestamp > 0L }
        var boundary = modernSummaryPairs.maxOfOrNull { it.summary.foldsUpToTimestamp } ?: 0L
        // 边界异常钳制：备份导入等场景下 timestamp 可能重复/乱序，摘要块的覆盖边界若反而晚于
        // 窗口内普通行的最新时间戳，上面的 `timestamp > boundary` 会把边界之后的原文也全滤掉
        // ——模型上下文只剩摘要、UI 整轮不显示（用户实测「压缩后啥也不显示，直接继续任务」）。
        // 钳到普通行的最新时间戳之前：宁可多回放一次重复，也不丢原文。
        val latestNormalTs = allEntities
            .filter { !it.isCompactionMarker && !it.isContextSummary }
            .maxOfOrNull { it.timestamp } ?: 0L
        if (latestNormalTs in 1..boundary) {
            FileLogger.w(
                "MessagePersistenceUseCase",
                "摘要覆盖边界异常（boundary=$boundary ≥ 最新普通行 ts=$latestNormalTs），已钳制到 latestNormalTs-1：" +
                    "可能是备份导入导致时间戳乱序"
            )
            boundary = latestNormalTs - 1
        }

        // 保留原文：边界之后的普通行（非摘要/marker）。悬挂 tool 配对过滤在下面第二遍完成。
        val entities = orderHistoryEntities(
            allEntities.filter {
                !(it.isCompactionMarker || it.isContextSummary) && it.timestamp > boundary
            }
        )

        // 旧摘要块兼容（foldsUpToTimestamp == 0）：库里的行还是旧语义（单摘要、head 标 isCompacted、
        // 保留区起点未动时残留多对同时间戳的旧对）。这类块不能按新规则多块前置——残留的旧对与存活对
        // 内容重复且过时，多块前置会重复回放。退化为旧规则：只取最新一个旧块。
        val legacyPairs = allSummaryPairs.filter { it.summary.foldsUpToTimestamp == 0L }
        // 新旧混合（升级后同会话里既有旧块又折了新块）：旧块覆盖的是新块边界之前的原文（head 已被
        // 旧规则标掉），新块边界之后的原文只有一份，两边不会重复回放；纯旧块场景同理只取最新一个。
        val replayedPairs = if (legacyPairs.isEmpty()) {
            modernSummaryPairs
        } else {
            modernSummaryPairs + listOf(legacyPairs.last())
        }

        // 第一遍：求 assistant 声明的 toolCallId 与 tool 结果 toolCallId 的交集。
        val declaredIds = mutableSetOf<String>()
        val resultIds = mutableSetOf<String>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.ASSISTANT -> e.toolCallsJson?.let {
                    runCatching { json.decodeFromString<List<ToolCall>>(it) }
                        .getOrNull()?.forEach { tc -> declaredIds.add(tc.id) }
                }
                MessageRole.TOOL -> {
                    // 只有真正完成的结果才计入配对；执行中占位行（完成事件未回来的孤儿）不算。
                    if (!e.content.startsWith(pendingToolMarker) &&
                        !e.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
                    ) {
                        e.toolCallId?.let { resultIds.add(it) }
                    }
                }
                else -> {}
            }
        }
        val validIds = declaredIds intersect resultIds

        // 第二遍：构建消息，过滤掉无法配对的工具调用 / 工具结果。
        val result = mutableListOf<AgentMessage>()

        // 先拼摘要块链：S0..Sk 按序前置（§4.2）。每个块 = marker（缺则合成）+ 摘要。
        for (pair in replayedPairs) {
            result.add(
                AgentMessage.UserMessage(
                    id = pair.marker?.id ?: "",
                    content = CONTEXT_COMPACTION_MARKER
                )
            )
            result.add(
                AgentMessage.AssistantMessage(
                    id = pair.summary.id,
                    content = pair.summary.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart(),
                    reasoning = pair.summary.reasoning ?: "",
                    signature = pair.summary.signature ?: "",
                    thinkingBlocksJson = pair.summary.thinkingBlocksJson ?: ""
                )
            )
        }

        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val attachments = e.attachmentsJson?.let {
                        runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                    } ?: emptyList()

                    val rawContent = e.content
                    val finalContent = if (attachments.isNotEmpty()) {
                        val attachmentText = buildString {
                            append("附件：")
                            attachments.forEach { att ->
                                append('\n')
                                append("- ")
                                append(att.fileName)
                                append("：")
                                append(att.containerPath)
                            }
                        }
                        if (rawContent.isBlank()) attachmentText else "${rawContent.trimEnd()}\n\n$attachmentText"
                    } else {
                        rawContent
                    }

                    val images = attachments.mapNotNull { it.toAgentImage() }

                    result.add(
                        AgentMessage.UserMessage(
                            id = e.id,
                            content = finalContent,
                            images = images,
                            modelReminder = e.modelReminder
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = e.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.filter { it.id in validIds } ?: emptyList()
                    val imageAttachments = e.attachmentsJson?.let {
                        runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                    }.orEmpty()
                    if (e.content.isNotBlank() || toolCalls.isNotEmpty() || imageAttachments.isNotEmpty()) {
                        result.add(
                            AgentMessage.AssistantMessage(
                                id = e.id,
                                content = e.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart(),
                                toolCalls = toolCalls,
                                reasoning = e.reasoning ?: "",
                                signature = e.signature ?: "",
                                thinkingBlocksJson = e.thinkingBlocksJson ?: "",
                                // 附件里的图片按路径重建 base64（带缓存），供下一轮上下文回放。
                                images = imageAttachments.mapNotNull { it.toAgentImage() }
                            )
                        )
                    }
                }
                MessageRole.TOOL -> {
                    val tcId = e.toolCallId
                    if (tcId != null && tcId in validIds) {
                        result.add(
                            AgentMessage.ToolResultMessage(
                                id = tcId,
                                toolName = e.toolName ?: "unknown",
                                result = e.content
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /** 参与回放的一个摘要块：摘要行 + 紧贴它前面的 marker 行（缺失时为 null，拼装时合成）。 */
    private data class SummaryPair(val summary: AgentMessageEntity, val marker: AgentMessageEntity?)

    /**
     * 收集参与回放的摘要块：isContextSummary 行按 timestamp 升序，每块带上紧贴它前面的
     * isCompactionMarker 行（缺失或错位时为 null，拼装侧合成无 id 的 marker，沿用旧兑底）。
     *
     * 判据只认标志位与相邻关系，不比较时间戳：残留对与存活对可能共享同一保留区起点，
     * 按 ts 比不出先后（与旧实现同一坑）。
     */
    private fun collectSummaryPairs(entities: List<AgentMessageEntity>): List<SummaryPair> {
        val pairs = mutableListOf<SummaryPair>()
        for ((index, e) in entities.withIndex()) {
            if (!e.isContextSummary) continue
            val marker = entities.getOrNull(index - 1)?.takeIf { it.isCompactionMarker }
            pairs.add(SummaryPair(e, marker))
        }
        return pairs
    }

    private fun AgentAttachment.toAgentImage(): com.aicode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        // 按路径+大小+修改时间缓存 base64：文件未变时直接复用，省去每次 LLM 调用的重读+编码。
        val key = "$localPath:${file.length()}:${file.lastModified()}"
        cachedImageBase64(key)?.let { cached ->
            return com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = cached.mimeType,
                base64Data = cached.base64,
                path = containerPath
            )
        }
        return try {
            val (mime, base64) = com.aicode.core.util.ImageDownscaler.downscaleToBase64(
                file.readBytes(), mimeType.ifBlank { "image/jpeg" }
            )
            cacheImageBase64(key, CachedImage(mime, base64))
            com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mime,
                base64Data = base64,
                path = containerPath
            )
        } catch (e: Exception) {
            null
        }
    }
}
