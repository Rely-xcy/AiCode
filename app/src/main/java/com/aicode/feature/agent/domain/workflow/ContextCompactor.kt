package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.presentation.MessageRole
import android.os.SystemClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文变换器：只负责「怎么改消息」，不负责「什么时候改」。
 *
 * 阈值、档位、软硬线判定属于策略，归 [com.aicode.feature.agent.domain.engine.modules.CompactionModule]；
 * 本类只提供三个纯变换，便于单独验证：
 * - [softTrim]：不调模型的投影式精简（只改喂模型的 modelResult）；
 * - [compact]：调摘要模型折叠早期对话，并把结果落库；
 * - [enforceWindowLimit]：发送前兜底截断，保证不发出超窗请求。
 *
 * 三个变换都不读设置、不解析模型目录：预算由调用方算好传进来。
 */
@Singleton
class ContextCompactor @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val systemPromptProvider: SystemPromptProvider,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val compactedHistoryArchive: CompactedHistoryArchive
) {

    private companion object {
        const val TAG = "ContextCompactor"

        /** 软精简时单条工具输出的保留上限（比硬压缩宽松，尽量少丢信息）。 */
        const val SOFT_TRIM_TOOL_CHARS = 3_000

        /** 软精简后追加在尾部的标记，用于幂等判断。 */
        const val SOFT_TRIM_MARKER = "\n[工具输出已精简以节省上下文]"

        /** 兜底截断的单条消息下限：再短就没法干活了，宁可让它超窗。 */
        const val MIN_MESSAGE_TOKENS = 64
        const val MIN_TRUNCATE_CHARS = 200

        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    /**
     * 软精简：把历史里超长的工具输出裁短，直到估算落回 [targetTokens] 以内。
     *
     * 三个要点：
     * 1. 只改喂模型的 `modelResult`，不动 `result`（UI 与持久化仍用完整内容）；
     * 2. 按长度从大到小裁、够用就停——不做无差别全裁，避免把还有用的输出也削掉；
     * 3. 幂等：已带标记的不再处理，重复调用不会把内容越裁越短。
     *
     * 未产生变化时返回原列表引用，便于调用方判断要不要更新状态。
     */
    fun softTrim(messages: List<AgentMessage>, targetTokens: Int): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        var estimate = TokenEstimator.estimateMessages(messages)
        if (estimate <= targetTokens) return messages

        val candidates = messages.indices
            .filter { messages[it] is AgentMessage.ToolResultMessage }
            .sortedByDescending { TokenEstimator.estimateMessage(messages[it]) }

        var changed = false
        val result = messages.toMutableList()
        for (index in candidates) {
            if (estimate <= targetTokens) break
            val message = result[index] as AgentMessage.ToolResultMessage
            val current = message.modelResult ?: message.result
            if (current.length <= SOFT_TRIM_TOOL_CHARS || current.endsWith(SOFT_TRIM_MARKER)) continue
            val trimmed = message.copy(modelResult = headTailTrim(current, SOFT_TRIM_TOOL_CHARS) + SOFT_TRIM_MARKER)
            estimate -= TokenEstimator.estimateMessage(message) - TokenEstimator.estimateMessage(trimmed)
            result[index] = trimmed
            changed = true
        }
        return if (changed) result else messages
    }

    /**
     * 发送前兜底：软精简/硬压缩之后估算仍逼近窗口时，对超长消息本身做硬截断。
     *
     * 压缩只能把老消息收进摘要，动不了「单条消息本身就超窗」——tail 至少要保留一条，
     * 而那条可能是一次巨大的工具输出或超长粘贴。不兜底就只能让请求硬撞窗口上限。
     */
    fun enforceWindowLimit(messages: List<AgentMessage>, budgetTokens: Int): List<AgentMessage> {
        if (budgetTokens <= 0 || messages.isEmpty()) return messages
        var estimate = TokenEstimator.estimateMessages(messages)
        if (estimate <= budgetTokens) return messages

        val result = messages.toMutableList()
        var changed = false
        // 从最大的开始削，只削到刚好落进预算为止，避免把小消息也一起截短。
        val order = messages.indices.sortedByDescending { TokenEstimator.estimateMessage(messages[it]) }
        for (index in order) {
            if (estimate <= budgetTokens) break
            val before = TokenEstimator.estimateMessage(result[index])
            if (before <= MIN_MESSAGE_TOKENS) continue
            val target = maxOf(MIN_MESSAGE_TOKENS, before - (estimate - budgetTokens))
            val truncated = truncateMessage(result[index], target)
            if (truncated === result[index]) continue
            result[index] = truncated
            estimate -= (before - TokenEstimator.estimateMessage(truncated))
            changed = true
        }

        if (!changed) return messages
        FileLogger.w(TAG, "压缩后仍超窗口预算（$estimate / $budgetTokens tokens），已截断超长消息兜底")
        return result
    }

    /**
     * 硬压缩：把早期对话（head）折叠成结构化接手摘要，替换回原位。
     *
     * 持久化：head 标记 isCompacted（不删除），摘要以 marker + assistant 两条插到 tail 之前，
     * 重启后回放顺序仍是「摘要 → tail」。
     *
     * @param preserveRecentTokens 保留最近原文的预算（由调用方按窗口档位算好）。
     * @param summaryWindowTokens 摘要模型自己的窗口，用于裁剪送进摘要请求的 head。
     * @return 压缩后的消息列表；无内容可压或调用失败返回 null（调用方保留原消息）。
     */
    suspend fun compact(
        messages: List<AgentMessage>,
        summaryProvider: AIProvider,
        sessionId: String?,
        preserveRecentTokens: Int,
        summaryWindowTokens: Int,
        /**
         * 折叠前的回调：拿到即将被折叠掉的那段历史（head）。
         * 给调用方一个「趁还没丢，先把长期价值捞出来」的机会（如抽取长期记忆）。
         */
        onBeforeFold: (suspend (List<AgentMessage>) -> Unit)? = null,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage>? {
        var splitIndex = selectTailStartIndex(messages, preserveRecentTokens)
        if (splitIndex <= 0) return null
        // tail 不能以孤立的 ToolResultMessage 开头：压缩后其前面是摘要（不含 toolCalls），
        // 该 tool 消息会变成孤儿，API 直接 400。向前回溯到配对的 assistant。
        splitIndex = adjustSplitIndex(messages, splitIndex)
        if (splitIndex <= 0) return null

        val head = messages.subList(0, splitIndex)
        val tail = messages.subList(splitIndex, messages.size)
        // 折叠前先把原文存档：摘要没覆盖到的细节不至于永久丢失，模型需要时可读回来
        val archivePath = compactedHistoryArchive.archive(sessionId, head)
        // 再给调用方一次「捞出长期价值」的机会（记忆抽取）：这段历史马上离开上下文
        if (onBeforeFold != null) {
            runCatching { onBeforeFold(head) }
                .onFailure { FileLogger.w(TAG, "折叠前回调失败，继续压缩", it) }
        }
        val previousSummary = extractPreviousSummary(messages)
        val headForSummary = removeCompactionPairs(head).truncateForSummaryWindow(summaryWindowTokens)
        if (headForSummary.isEmpty()) {
            // 重复压缩时 head 可能只剩旧的 marker+summary 对，删光后无可压缩内容。
            FileLogger.i(TAG, "无可压缩内容（head 为空），跳过压缩")
            return null
        }

        // 压缩请求：head 原始消息数组 + 末尾一条压缩指令（Codex 式），tools 不发送。
        val summaryRequestMessages = headForSummary.trimLeadingForCompaction() + listOf(
            AgentMessage.UserMessage(content = buildSummaryInstruction(previousSummary))
        )

        // 调用统计埋点：压缩也是一次真实 LLM 调用（独立于主循环，kind=compaction）。
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callCompleted = false
        var callUsage: AIResponse? = null

        val summaryResponse = try {
            val response = summaryProvider.complete(
                systemPrompt = "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。",
                messages = summaryRequestMessages,
                tools = emptyList()
            )
            callUsage = response
            callCompleted = true
            response.content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            FileLogger.e(TAG, "压缩上下文失败", e)
            onEvent(AgentEvent.CompactionFailed(callError))
            return null
        }

        val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
        runCatching {
            llmCallRecordDao.insert(
                LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = summaryProvider.providerId.ifBlank { null },
                    model = summaryProvider.model,
                    kind = "compaction",
                    inputTokens = callUsage?.inputTokens ?: 0,
                    outputTokens = callUsage?.outputTokens ?: 0,
                    cachedInputTokens = callUsage?.cachedInputTokens ?: 0,
                    cacheCreationTokens = callUsage?.cacheCreationTokens ?: 0,
                    ttfbMillis = null,
                    durationMillis = durationMillis,
                    status = if (callCompleted) "success" else "error",
                    errorMessage = callError,
                    stopReason = callUsage?.stopReason,
                    createdAt = callStartWall
                )
            )
        }

        FileLogger.i(TAG, "上下文压缩完成，摘要长度：${summaryResponse.length}")

        // 摘要里附上归档路径：这是摘要之外唯一的退路（模型用文件工具就能读）
        val summaryContent = if (archivePath != null) {
            "$summaryResponse\n\n（被折叠的历史原文已存档：$archivePath ，需要核对更早的细节时可读取该文件。）"
        } else {
            summaryResponse
        }

        val markerId = UUID.randomUUID().toString()
        val compactedId = UUID.randomUUID().toString()
        val markerMessage = AgentMessage.UserMessage(
            id = markerId,
            content = CONTEXT_COMPACTION_MARKER
        )
        val compactedMessage = AgentMessage.AssistantMessage(
            id = compactedId,
            content = summaryContent,
            toolCalls = emptyList()
        )

        if (sessionId != null) {
            try {
                val dbEntities = agentMessageDao.getMessagesBySessionOnce(sessionId)
                // 内存态消息与库行的 id 不是同一套：tool 行落库是 "tool_<callId>"、内存态是 <callId>；
                // user/assistant 落库是随机 UUID、内存态常为空串。所以逐级兜底匹配，不能只比 id。
                val anchorTs = tail.firstNotNullOfOrNull { msg -> resolveRowTimestamp(dbEntities, msg) }

                // 找不到锚点就什么都不标记：宁可让 head 下一轮多回放一次（重复总比丢好），
                // 也绝不能用「当前时间」当锚点——它大于会话里所有行，会把要保留的 tail
                // 一起标成已压缩；而 tail 没进归档，下一轮就直接从回放里消失。
                if (anchorTs == null) {
                    FileLogger.w(TAG, "压缩时匹配不到 tail 对应的库行，跳过 isCompacted 标记，会话 $sessionId")
                } else {
                    agentMessageDao.markMessagesCompactedBeforeTimestamp(sessionId, anchorTs)
                }

                // 摘要放在 tail 之前：回放/UI 顺序 = 摘要 → tail。
                // 接手摘要作为背景，最后一条仍是用户请求 / tool 结果，模型才会继续干活；
                // 若放在末尾，模型会把摘要当成自己的上一轮，续写一大段后停下。
                val markerTs = ((anchorTs ?: System.currentTimeMillis()) - 2).coerceAtLeast(1L)
                val summaryTs = markerTs + 1
                agentMessageDao.insert(
                    AgentMessageEntity(
                        id = markerId,
                        sessionId = sessionId,
                        role = MessageRole.USER.name,
                        content = CONTEXT_COMPACTION_MARKER,
                        timestamp = markerTs,
                        isCompactionMarker = true
                    )
                )
                agentMessageDao.insert(
                    AgentMessageEntity(
                        id = compactedId,
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT.name,
                        content = compactedMessage.content,
                        timestamp = summaryTs,
                        isContextSummary = true
                    )
                )
                FileLogger.i(TAG, "已持久化压缩结果到数据库，会话 $sessionId")
            } catch (e: Exception) {
                FileLogger.e(TAG, "持久化压缩结果失败", e)
            }
        }

        return listOf(markerMessage, compactedMessage) + tail
    }

    /**
     * 内存态消息 → 库行时间戳：id 直接匹配、tool 前缀匹配、内容匹配逐级兜底。
     *
     * 内容匹配用**最早**一条：宁可把锚点取早（少标几条 head，下一轮多回放一次），
     * 也不可取晚——取晚就会把 tail 标进去，那是真丢数据。
     */
    private fun resolveRowTimestamp(rows: List<AgentMessageEntity>, message: AgentMessage): Long? {
        rows.firstOrNull { it.id == message.id }?.let { return it.timestamp }
        rows.firstOrNull { it.id == "tool_${message.id}" }?.let { return it.timestamp }
        val content = when (message) {
            is AgentMessage.UserMessage -> message.content
            is AgentMessage.AssistantMessage -> message.content
            is AgentMessage.ToolResultMessage -> message.result
        }
        if (content.isBlank()) return null
        return rows.firstOrNull { it.content == content }?.timestamp
    }

    /** 把单条消息的正文压到 [budgetTokens] 以内；工具结果改写 modelResult，其余改写 content。 */
    private fun truncateMessage(message: AgentMessage, budgetTokens: Int): AgentMessage {
        val currentTokens = TokenEstimator.estimateMessage(message)
        if (currentTokens <= budgetTokens) return message
        // 按该消息自己的 token/字符密度等比换算，不用固定系数：中文 1 字 ≈ 1 token、
        // 拉丁 4 字符 ≈ 1 token，固定系数会把中文消息算得截不动。
        val ratio = budgetTokens.toDouble() / currentTokens

        fun limitOf(text: String): Int =
            (text.length * ratio).toInt().coerceAtLeast(MIN_TRUNCATE_CHARS)

        return when (message) {
            is AgentMessage.ToolResultMessage -> {
                val current = message.modelResult ?: message.result
                if (current.length <= limitOf(current)) message
                else message.copy(modelResult = headTailTrim(current, limitOf(current)))
            }

            is AgentMessage.UserMessage ->
                if (message.content.length <= limitOf(message.content)) message
                else message.copy(content = headTailTrim(message.content, limitOf(message.content)))

            is AgentMessage.AssistantMessage ->
                if (message.content.length <= limitOf(message.content)) message
                else message.copy(content = headTailTrim(message.content, limitOf(message.content)))
        }
    }

    /** 保留头尾、中间省略：两端通常含命令/路径与结论，中段是重复的正文。 */
    private fun headTailTrim(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = maxChars * 2 / 3
        val tail = maxChars - head
        return text.take(head) +
            "\n...[内容过长，已省略中间 ${text.length - maxChars} 字符]...\n" +
            text.takeLast(tail)
    }

    /**
     * 调整拆分索引，确保 tail 不是以 ToolResultMessage 开头。
     *
     * OpenAI API 要求 role: "tool" 消息必须紧接在包含对应 tool_calls 的 assistant 消息之后。
     * 如果 tail 以 ToolResultMessage 开头，压缩后其前面的 assistant 消息（摘要）不含 toolCalls，
     * 该 tool 消息就变成了"孤立"的，API 会报 400 错误。
     */
    private fun adjustSplitIndex(messages: List<AgentMessage>, initialSplitIndex: Int): Int {
        var splitIndex = initialSplitIndex

        while (splitIndex > 0 && messages[splitIndex] is AgentMessage.ToolResultMessage) {
            splitIndex--
        }

        if (splitIndex >= 0 && messages[splitIndex] is AgentMessage.AssistantMessage) {
            val assistantMsg = messages[splitIndex] as AgentMessage.AssistantMessage
            if (assistantMsg.toolCalls.isNotEmpty()) {
                // 这个 assistant 和紧随其后的 tool results 必须一起保留在 tail 中
                return splitIndex
            }
        }

        return splitIndex
    }

    private fun selectTailStartIndex(messages: List<AgentMessage>, budgetTokens: Int): Int {
        var total = 0
        var splitIndex = messages.size

        for (index in messages.indices.reversed()) {
            val next = TokenEstimator.estimateMessage(messages[index])
            if (total + next > budgetTokens && splitIndex < messages.size) break
            total += next
            splitIndex = index
        }

        return splitIndex
    }

    private fun buildSummaryInstruction(previousSummary: String?): String {
        val instruction = if (previousSummary.isNullOrBlank()) {
            "请根据下面的对话历史创建一个新的锚定摘要。"
        } else {
            """
                请根据下面的新对话历史更新已有锚定摘要。
                保留仍然正确的信息，移除过时信息，并合并新事实。

                <previous-summary>
                $previousSummary
                </previous-summary>
            """.trimIndent()
        }

        return systemPromptProvider.resolvePrompt(COMPACT_PROMPT_FILE)
            .replace(LEADING_COMMENT, "")
            .replace("{{INSTRUCTION}}", instruction)
    }

    /**
     * 压缩请求前的清理：截断可能丢弃最旧的 user 消息，导致头部出现孤立的 assistant/tool 消息，
     * 丢到第一条 user 为止；去掉图片与超长工具输出，压缩模型按纯文本做摘要。
     */
    private fun List<AgentMessage>.trimLeadingForCompaction(): List<AgentMessage> {
        val trimmed = dropWhile { it !is AgentMessage.UserMessage }
        return trimmed.map { msg ->
            when {
                msg is AgentMessage.UserMessage && msg.images.isNotEmpty() -> msg.copy(images = emptyList())
                msg is AgentMessage.ToolResultMessage && (msg.modelResult ?: msg.result).length > SOFT_TRIM_TOOL_CHARS ->
                    msg.copy(result = msg.result.take(SOFT_TRIM_TOOL_CHARS) + "\n[Tool output truncated for compaction]")

                else -> msg
            }
        }
    }

    private fun extractPreviousSummary(messages: List<AgentMessage>): String? {
        for (index in messages.indices.reversed()) {
            val current = messages[index]
            val next = messages.getOrNull(index + 1)
            if (
                current is AgentMessage.UserMessage &&
                current.content == CONTEXT_COMPACTION_MARKER &&
                next is AgentMessage.AssistantMessage
            ) {
                return next.content.cleanSummary()
            }
            if (
                current is AgentMessage.AssistantMessage &&
                current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)
            ) {
                return current.content.cleanSummary()
            }
        }
        return null
    }

    private fun removeCompactionPairs(messages: List<AgentMessage>): List<AgentMessage> {
        val result = mutableListOf<AgentMessage>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            val next = messages.getOrNull(index + 1)
            if (
                current is AgentMessage.UserMessage &&
                current.content == CONTEXT_COMPACTION_MARKER &&
                next is AgentMessage.AssistantMessage
            ) {
                index += 2
                continue
            }
            if (
                current is AgentMessage.AssistantMessage &&
                current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)
            ) {
                index++
                continue
            }
            result.add(current)
            index++
        }
        return result
    }

    private fun String.cleanSummary(): String =
        removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart()

    /**
     * 按摘要模型窗口预算截断 head：从新到旧保留消息，超预算丢弃更旧的消息。
     * 预算按 1 字符 ≈ 1 token 的保守口径（[TokenEstimator] 对中文是 1 字/token，
     * 4 字符/token 的口径会截不干净），并预留 30% 给摘要提示词与旧摘要。
     */
    private fun List<AgentMessage>.truncateForSummaryWindow(contextTokens: Int): List<AgentMessage> {
        if (isEmpty()) return this
        val budgetChars = (contextTokens * 0.7f).toInt()
        var totalChars = 0
        val kept = mutableListOf<AgentMessage>()
        for (msg in asReversed()) {
            val chars = TokenEstimator.estimateMessage(msg)
            if (kept.isNotEmpty() && totalChars + chars > budgetChars) break
            totalChars += chars
            kept.add(msg)
        }
        val truncated = kept.asReversed()
        if (truncated.size != size) {
            FileLogger.i(TAG, "head 超出压缩模型窗口预算，丢弃 ${size - truncated.size} 条最旧消息（预算 $budgetChars 字符）")
        }
        return truncated
    }
}
