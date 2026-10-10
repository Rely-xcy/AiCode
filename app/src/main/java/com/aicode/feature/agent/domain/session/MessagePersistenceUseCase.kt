package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
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
    private val imageBase64Cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_IMAGE_CACHE_ENTRIES
    }
    private var imageCacheBytes = 0L

    private fun cachedImageBase64(key: String): String? = synchronized(imageBase64Cache) { imageBase64Cache[key] }

    private fun cacheImageBase64(key: String, value: String) {
        synchronized(imageBase64Cache) {
            val old = imageBase64Cache.put(key, value)
            imageCacheBytes += value.length - (old?.length ?: 0)
            while (imageCacheBytes > MAX_IMAGE_CACHE_BYTES && imageBase64Cache.isNotEmpty()) {
                val it = imageBase64Cache.entries.iterator()
                val eldest = it.next()
                it.remove()
                imageCacheBytes -= eldest.value.length
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
                toolCallsJson = if (toolCalls.isNotEmpty()) capBytes(json.encodeToString(toolCalls), MAX_SNAPSHOT_BYTES) else null,
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
    suspend fun attachModelReminder(messageId: String, reminder: String) {
        agentMessageDao.updateModelReminder(messageId, capBytes(reminder, MAX_SNAPSHOT_BYTES))
    }

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
            // 聊天按压缩触发时间展示；模型必须先读摘要，再读保留历史。
            val (compaction, retained) = entities.partition { it.isCompactionMarker || it.isContextSummary }
            return compaction + retained
        }

        /** UTF-8 单字符最多占 3 字节，故 [String.length] 的三倍不超上限时无需实际编码即可放行。 */
        private fun definitelyFits(raw: String, maxBytes: Int): Boolean =
            raw.length.toLong() * 3 <= maxBytes

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
        val entities = agentMessageDao.getMessagesBySessionOnce(sessionId)
            .filter { !it.isCompacted && !it.isContextExcluded }
            .let { orderHistoryEntities(it) }

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

        // 读侧去重：模型可见的历史里只留最新的一对（marker + 接手摘要）。
        // 旧摘要的行不会立刻从库里消失——下一次折叠才把它们标 isCompacted，在那之前多份摘要
        // 会一起进上下文（内容重复且过时）。这里只跳过更早的那些行：库不动、isCompacted 不碰、
        // 聊天页不受影响（UI 走 paged 查询）。
        // 判据用「最后出现的一条摘要 + 紧贴它前面的那条 marker」，不比较时间戳：连续折叠产生的
        // 残留对时间戳会完全相同（同一保留区起点），按 ts 比不出先后。配对 marker 取不到时
        // （那行已被标掉或缺失），下面的合成逻辑会给摘要补一条无 id 的 marker。
        val keptSummaryIndex = entities.indexOfLast { it.isContextSummary }
        val keptSummary = entities.getOrNull(keptSummaryIndex)
        val keptMarker = keptSummary
            ?.let { entities.getOrNull(keptSummaryIndex - 1) }
            ?.takeIf { it.isCompactionMarker }

        // 第二遍：构建消息，过滤掉无法配对的工具调用 / 工具结果。
        val result = mutableListOf<AgentMessage>()
        for (e in entities) {
            if (keptSummary != null) {
                if (e.isContextSummary && e.id != keptSummary.id) continue
                if (e.isCompactionMarker && e.id != keptMarker?.id) continue
            }
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val rawContent = if (e.isCompactionMarker) CONTEXT_COMPACTION_MARKER else e.content
                    val attachments = if (!e.isCompactionMarker) {
                        e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                        } ?: emptyList()
                    } else emptyList()

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
                        val previous = result.lastOrNull()
                        if (
                            e.isContextSummary &&
                            !(previous is AgentMessage.UserMessage && previous.content == CONTEXT_COMPACTION_MARKER)
                        ) {
                            result.add(AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER))
                        }
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
                                result = e.content,
                                // 软精简落库的投影：回放时原样带出，前沿因此跨轮次保留（result 仍是原文）。
                                modelResult = e.modelResult
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun AgentAttachment.toAgentImage(): com.aicode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        // 按路径+大小+修改时间缓存 base64：文件未变时直接复用，省去每次 LLM 调用的重读+编码。
        val key = "$localPath:${file.length()}:${file.lastModified()}"
        cachedImageBase64(key)?.let { cached ->
            return com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = cached,
                path = containerPath
            )
        }
        return try {
            val bytes = file.readBytes()
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            cacheImageBase64(key, base64)
            com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = base64,
                path = containerPath
            )
        } catch (e: Exception) {
            null
        }
    }
}
