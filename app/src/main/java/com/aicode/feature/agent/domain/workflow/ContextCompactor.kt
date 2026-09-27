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
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ProviderType
import android.os.SystemClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextCompactor @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val modelMetadataService: ModelMetadataService,
    private val systemPromptProvider: SystemPromptProvider,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val compactedHistoryArchive: CompactedHistoryArchive
) {

    private companion object {
        const val TAG = "ContextCompactor"

        /** 软精简时单条工具输出的保留上限（比硬压缩宽松，尽量少丢信息）。 */
        const val SOFT_TRIM_TOOL_CHARS = 3_000

        /** 摘要输入里单条工具输出的保留上限：比发送时宽松得多，摘要才看得到细节。 */
        const val SUMMARY_TOOL_CHARS = 8_000

        /** 发送前兜底：估算需压到窗口的该比例以下，才认为请求发得出去。 */
        const val GUARD_BUDGET_PERCENT = 92

        /** 单条消息截断后的下限，避免截成空壳。 */
        const val MIN_MESSAGE_TOKENS = 64

        /** 截断后单条消息至少保留的字符数。 */
        const val MIN_TRUNCATE_CHARS = 200

        /** 软精简后追加在尾部的标记，用于幂等判断。 */
        const val SOFT_TRIM_MARKER = "\n[工具输出已精简以节省上下文]"
        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    /**
     * 如果消息体总长度超过阈值，则将早期的消息（Head）提取出来，
     * 通过后台 LLM 调用进行结构化摘要，然后替换回原来的位置。
     *
     * 压缩结果持久化到数据库：
     * - 被压缩的 head 部分消息标记 isCompacted=true（不删除，保留数据完整性）
     * - 摘要消息插入数据库，作为压缩后的上下文起点
     * - 重启后 [MessagePersistenceUseCase.buildHistory] 会跳过 isCompacted 的消息，
     *   只回放摘要 + tail 部分
     *
     * @return 压缩后的新列表（如果没有触发压缩则返回原列表的副本）
     */
    suspend fun compactIfNeeded(
        messages: List<AgentMessage>,
        aiProvider: AIProvider,
        sessionId: String? = null,
        force: Boolean = false,
        lastInputTokens: Int = 0,
        /**
         * 触发判断用的窗口来源模型：正常为主聊天模型（决定「上下文快撑满谁」），
         * 与 [aiProvider]（执行摘要生成的压缩专用模型）分离，避免小窗口压缩模型导致过早压缩。
         * 为 null 时回退 [aiProvider]。
         */
        windowProvider: AIProvider? = null,
        /**
         * system prompt + 工具定义的估算 token 量。它们不在 [messages] 里，
         * 但对窗口的占用是实打实的；不传就只能靠 lastInputTokens 间接体现，
         * 而 provider 不回传 usage 时 lastInputTokens 恒为 0，会系统性低估。
         */
        baseOverheadTokens: Int = 0,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage> {
        val windowModel = windowProvider ?: aiProvider
        val windowMetadata = modelMetadataService.resolve(windowModel.providerId, inferProviderType(windowModel), windowModel.model)
        val summaryMetadata = modelMetadataService.resolve(aiProvider.providerId, inferProviderType(aiProvider), aiProvider.model)
        val contextLimit = windowMetadata.contextTokens.takeIf { it > 0 } ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        val estimatedTokens = TokenEstimator.estimateMessages(messages) + baseOverheadTokens
        val tierPolicy = ModelContextPolicy.tierFor(contextLimit)
        // 硬阈值：完整摘要压缩；软阈值：只精简历史工具输出（不调 LLM）。
        // 百分比来自用户设置，同时受窗口档位的绝对 headroom 约束（取更保守的一个）。
        val hardPercent = generalSettingsRepository.compactionThresholdPercent()
        val softPercent = generalSettingsRepository.softCompactionThresholdPercent()
        val hardAllowed = tierPolicy.hardThreshold > 0
        val triggerThreshold = if (hardAllowed) {
            minOf((contextLimit * hardPercent / 100.0).toInt(), tierPolicy.hardThreshold)
        } else {
            0
        }
        // 软阈值必须低于硬阈值，否则软精简永远轮不到（reachedHard 先到）。
        val softCeiling = if (hardAllowed) (triggerThreshold - 1).coerceAtLeast(1) else (contextLimit * 90 / 100)
        val softThreshold = minOf((contextLimit * softPercent / 100.0).toInt(), softCeiling).coerceAtLeast(1)
        // 取上次真实 usage 与本次本地估算的较大值：lastInputTokens 是上一次请求的值，
        // 本轮新塞入的大内容（文件/工具输出/图片）在旧值里看不到，单靠它会把超限请求发出去。
        val currentTokens = maxOf(lastInputTokens.takeIf { it > 0 } ?: 0, estimatedTokens)
        val reachedHard = hardAllowed && currentTokens >= triggerThreshold
        val reachedSoft = currentTokens >= softThreshold
        // 以「单条消息也可能就超过窗口」的姿势早退：只有估算还没逼近窗口时才允许按条数跳过。
        if (messages.size <= 2 && currentTokens < contextLimit) return messages.toList()
        // 软阈值：先静默精简历史里的超长工具输出（不调摘要模型、不发事件、不落库），
        // 只在真正逼近硬上限时才做完整摘要。
        if (!force && !reachedHard && reachedSoft) {
            val trimmed = softTrim(messages)
            if (trimmed !== messages) {
                FileLogger.i(
                    TAG,
                    "会话 ${sessionId ?: "-"} 上下文约 $currentTokens tokens 达软阈值 $softThreshold，精简历史工具输出（未调用摘要模型）"
                )
            }
            return enforceWindowLimit(trimmed, contextLimit, sessionId, baseOverheadTokens)
        }
        if (!force && !reachedHard) return enforceWindowLimit(messages, contextLimit, sessionId, baseOverheadTokens)

        val tokensSource = if (lastInputTokens > 0) "真实 usage" else "本地估算"
        // 窗口来源一并打出来：命中目录（含命中的 provider 与自定义覆盖）还是走了 128k 兜底，
        // 是排查「压缩时机与预期不符」的第一手依据。
        val windowSource = if (windowMetadata.contextTokens > 0) "目录 ${windowMetadata.providerId}" else "128k 兜底"
        FileLogger.i(
            TAG,
            "会话 ${sessionId ?: "-"} 上下文约 $currentTokens tokens（$tokensSource），窗口 $contextLimit（$windowSource），" +
                "${if (force) "手动强制压缩" else "达到压缩触发条件（阈值 $triggerThreshold 或硬上限），触发自动压缩"}。"
        )
        onEvent(AgentEvent.CompactionStarted(currentTokens))

        // 拆分 Head（需要压缩的老数据）和 Tail（保留的新数据）
        // tail 预算按窗口比例算，而不是按硬阈值算：硬阈值是「什么时候压」，
        // tail 是「压完留多少原文」，两者不是一回事。
        var splitIndex = selectTailStartIndex(messages, contextLimit)
        if (force && splitIndex <= 0 && messages.size > 1) {
            splitIndex = messages.size - 1
        }
        if (splitIndex <= 0) {
            onEvent(AgentEvent.CompactionFinished)
            return enforceWindowLimit(messages, contextLimit, sessionId, baseOverheadTokens)
        }

        // 确保 tail 的第一条消息不是孤立的 ToolResultMessage：
        // 如果 tail 以 ToolResultMessage 开头，需要向前回溯到其配对的 AssistantMessage(with toolCalls)，
        // 否则压缩后摘要 assistant 消息不含 toolCalls，导致 tool 消息变成孤立的，API 报 400。
        splitIndex = adjustSplitIndex(messages, splitIndex)

        val head = messages.subList(0, splitIndex)
        val tail = messages.subList(splitIndex, messages.size)
        val previousSummary = extractPreviousSummary(messages)
        val summaryWindowTokens = summaryMetadata.contextTokens.takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        val headForSummary = removeCompactionPairs(head).truncateForSummaryWindow(summaryWindowTokens)
        if (headForSummary.isEmpty()) {
            // 重复压缩时 head 可能只剩旧的 marker+summary 对，删光后无可压缩内容，跳过本轮压缩。
            FileLogger.i(TAG, "无可压缩内容（head 为空），跳过压缩")
            onEvent(AgentEvent.CompactionFinished)
            return enforceWindowLimit(messages, contextLimit, sessionId, baseOverheadTokens)
        }
        // 压缩请求：head 原始消息数组 + 末尾一条压缩指令（Codex 式），tools 不发送。
        // 消息数组保留真实角色结构（user/assistant/tool 配对），比文本化拼接更利于模型理解。
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
            val response = aiProvider.complete(
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
            onEvent(AgentEvent.CompactionFinished)
            // 失败不能裸发：原样发出去必然超窗，先用发送前兜底把请求压进窗口。
            return enforceWindowLimit(messages, contextLimit, sessionId, baseOverheadTokens)
        }

        val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
        runCatching {
            llmCallRecordDao.insert(
                LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = aiProvider.providerId.ifBlank { null },
                    model = aiProvider.model,
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

        // 摘要之外再给一条退路：head 原文归档成文件，模型需要细节时能自己去读。
        // 否则摘要漏掉的信息就永久看不到了（head 已落库但不再回放）。
        val archivePath = compactedHistoryArchive.archive(sessionId, head)
        val summaryContent = if (archivePath != null) {
            summaryResponse +
                "\n\n[本次折叠的 ${head.size} 条历史原文已完整存档到 $archivePath；" +
                "若上面的摘要缺少你需要的细节，直接读取该文件]"
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

        // 持久化压缩结果到数据库
        if (sessionId != null) {
            try {
                val dbEntities = agentMessageDao.getMessagesBySessionOnce(sessionId
                )
                val firstTailId = tail.firstOrNull { msg -> msg.id.isNotEmpty() }?.id
                val tailEntity = if (firstTailId != null) dbEntities.find { it.id == firstTailId } else null
                val cutoffTimestamp = tailEntity?.timestamp ?: System.currentTimeMillis()

                // 将 head 部分的消息标记为已压缩（不删除，保留数据完整性）
                agentMessageDao.markMessagesCompactedBeforeTimestamp(sessionId, cutoffTimestamp)

                // 摘要收尾：marker + summary 时间戳放在 tail 最后一条之后，回放/UI 顺序 = tail → 摘要，
                // 与 Codex 一致（最近消息在前、接手摘要收尾），避免摘要插在历史最前导致观感混乱。
                val tailLastTs = tail.asReversed().firstNotNullOfOrNull { msg ->
                    dbEntities.find { it.id == msg.id }?.timestamp
                }
                val insertBase = maxOf(System.currentTimeMillis(), tailLastTs ?: 0L) + 1
                agentMessageDao.insert(
                    AgentMessageEntity(
                        id = markerId,
                        sessionId = sessionId,
                        role = MessageRole.USER.name,
                        content = CONTEXT_COMPACTION_MARKER,
                        timestamp = insertBase,
                        isCompactionMarker = true
                    )
                )
                agentMessageDao.insert(
                    AgentMessageEntity(
                        id = compactedId,
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT.name,
                        content = compactedMessage.content,
                        timestamp = insertBase + 1,
                        isContextSummary = true
                    )
                )
                FileLogger.i(TAG, "已持久化压缩结果到数据库，会话 $sessionId")
            } catch (e: Exception) {
                FileLogger.e(TAG, "持久化压缩结果失败", e)
            }
        }
        onEvent(AgentEvent.CompactionFinished)

        val newMessages = mutableListOf<AgentMessage>()
        // Codex 式布局：tail（保留的最近消息）在前，摘要收尾。
        newMessages.addAll(tail)
        newMessages.add(markerMessage)
        newMessages.add(compactedMessage)

        // 压完仍逼近窗口时（tail 里压着单条超大内容）只能靠发送前截断兜底。
        return enforceWindowLimit(newMessages, contextLimit, sessionId, baseOverheadTokens)
    }

    /**
     * 软精简：不调 LLM、不落库，只把历史里超长的工具输出截断，降低主上下文冗余。
     * 输入主要来自工具结果（文件内容、命令输出等），这里只做保守截断，不改角色结构、不动 DB。
     * 未产生变化时返回原列表引用，便于调用方判断。
     */
    private fun softTrim(messages: List<AgentMessage>): List<AgentMessage> {
        var changed = false
        val result = messages.map { msg ->
            // 只改喂模型的 modelResult，不动 result（UI/持久化用）。
            // 幂等靠 modelResult 上的标记（不是 result：截断写的是 modelResult，
            // 拿 result 判标记永远不成立），已有紧凑 modelResult 的也会按同一上限复核。
            if (msg is AgentMessage.ToolResultMessage) {
                val current = msg.modelResult ?: msg.result
                if (current.length > SOFT_TRIM_TOOL_CHARS && !current.endsWith(SOFT_TRIM_MARKER)) {
                    changed = true
                    msg.copy(modelResult = current.take(SOFT_TRIM_TOOL_CHARS) + SOFT_TRIM_MARKER)
                } else {
                    msg
                }
            } else {
                msg
            }
        }
        return if (changed) result else messages
    }

    /**
     * 调整拆分索引，确保 tail 不是以 ToolResultMessage 开头。
     *
     * OpenAI API 要求 role: "tool" 消息必须紧接在包含对应 tool_calls 的 assistant 消息之后。
     * 如果 tail 以 ToolResultMessage 开头，压缩后其前面的 assistant 消息（摘要）不含 toolCalls，
     * 该 tool 消息就变成了"孤立"的，API 会报 400 错误。
     *
     * 解决方案：向前回溯，把配对的 AssistantMessage(with toolCalls) 纳入 tail，
     * 确保所有 tool 消息都有配对的 toolCalls。
     */
    private fun adjustSplitIndex(messages: List<AgentMessage>, initialSplitIndex: Int): Int {
        var splitIndex = initialSplitIndex

        // 如果 tail 的第一条消息是 ToolResultMessage，
        // 需要向前找到对应的 AssistantMessage(with toolCalls)
        while (splitIndex > 0 && messages[splitIndex] is AgentMessage.ToolResultMessage) {
            splitIndex--
        }

        // 现在 splitIndex 可能指向一个 AssistantMessage(with toolCalls) 或其他类型消息
        // 如果是含 toolCalls 的 AssistantMessage，它必须和其后的 ToolResultMessage 一起在 tail 中
        if (splitIndex >= 0 && messages[splitIndex] is AgentMessage.AssistantMessage) {
            val assistantMsg = messages[splitIndex] as AgentMessage.AssistantMessage
            if (assistantMsg.toolCalls.isNotEmpty()) {
                // 这个 assistant 和紧随其后的 tool results 必须一起保留在 tail 中
                // splitIndex 已经指向它，无需再调整
                return splitIndex
            }
        }

        // 如果 splitIndex 指向的是一个普通消息（非 tool 相关），直接使用
        return splitIndex
    }

    /** [windowTokens] 传模型上下文窗口：tail 预算按窗口比例算，与硬阈值无关。 */
    private fun selectTailStartIndex(messages: List<AgentMessage>, windowTokens: Int): Int {
        val budget = ModelContextPolicy.preserveRecentTokens(windowTokens)
        var total = 0
        var splitIndex = messages.size

        for (index in messages.indices.reversed()) {
            val next = TokenEstimator.estimateMessage(messages[index])
            if (total + next > budget && splitIndex < messages.size) break
            total += next
            splitIndex = index
        }

        return splitIndex
    }

    /**
     * 发送前兜底：压缩与软精简之后估算仍逼近窗口时，对超长消息本身做硬截断。
     * 压缩只能把老消息收进摘要，动不了「单条消息本身就超窗」——tail 至少要保留一条，
     * 而那条可能是一次巨大的工具输出或超长粘贴。不兜底就只能让请求硬撞窗口上限。
     */
    private fun enforceWindowLimit(
        messages: List<AgentMessage>,
        contextLimit: Int,
        sessionId: String?,
        overheadTokens: Int = 0
    ): List<AgentMessage> {
        if (contextLimit <= 0 || messages.isEmpty()) return messages
        // system prompt 与工具定义不在这份列表里，但占窗口，兜底预算得先把它们扣掉。
        val budget = (contextLimit * GUARD_BUDGET_PERCENT / 100.0).toInt() - overheadTokens
        if (budget <= 0) return messages
        var estimate = TokenEstimator.estimateMessages(messages) + overheadTokens
        if (estimate <= budget) return messages

        val result = messages.toMutableList()
        var changed = false
        // 从最大的开始削，只削到刚好落进预算为止，避免把小消息也一起截短。
        val order = messages.indices.sortedByDescending { TokenEstimator.estimateMessage(messages[it]) }
        for (index in order) {
            if (estimate <= budget) break
            val before = TokenEstimator.estimateMessage(result[index])
            if (before <= MIN_MESSAGE_TOKENS) continue
            val target = maxOf(MIN_MESSAGE_TOKENS, before - (estimate - budget))
            val truncated = truncateMessage(result[index], target)
            if (truncated === result[index]) continue
            result[index] = truncated
            estimate -= (before - TokenEstimator.estimateMessage(truncated))
            changed = true
        }

        if (!changed) return messages
        FileLogger.w(
            TAG,
            "会话 ${sessionId ?: "-"} 压缩后仍超窗口预算（$estimate / $budget tokens），已截断超长消息兜底"
        )
        return result
    }

    /** 把单条消息的正文压到 [budgetTokens] 以内；工具结果改写 modelResult，其余改写 content。 */
    private fun truncateMessage(message: AgentMessage, budgetTokens: Int): AgentMessage {
        val currentTokens = TokenEstimator.estimateMessage(message)
        if (currentTokens <= budgetTokens) return message
        // 按该消息自己的 token/字符密度等比换算，不用固定系数：中文 1 字≈1 token、
        // 拉丁 4 字符≈1 token，固定系数会把中文消息算得截不动。
        val ratio = budgetTokens.toDouble() / currentTokens

        fun limitOf(text: String): Int =
            (text.length * ratio).toInt().coerceAtLeast(MIN_TRUNCATE_CHARS)

        return when (message) {
            is AgentMessage.ToolResultMessage -> {
                val current = message.modelResult ?: message.result
                if (current.length <= limitOf(current)) message
                else message.copy(modelResult = headTailTruncate(current, limitOf(current)))
            }

            is AgentMessage.UserMessage ->
                if (message.content.length <= limitOf(message.content)) message
                else message.copy(content = headTailTruncate(message.content, limitOf(message.content)))

            is AgentMessage.AssistantMessage ->
                if (message.content.length <= limitOf(message.content)) message
                else message.copy(content = headTailTruncate(message.content, limitOf(message.content)))
        }
    }

    /** 保留头尾、中间省略：两端通常含命令/路径与结论，中段是重复的正文。 */
    private fun headTailTruncate(text: String, maxChars: Int): String {
        val head = maxChars * 2 / 3
        val tail = maxChars - head
        return text.take(head) +
            "\n...[内容过长，已省略中间 ${text.length - maxChars} 字符]...\n" +
            text.takeLast(tail)
    }

    private fun messageChars(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage -> message.content.length
        is AgentMessage.AssistantMessage -> {
            message.content.length + message.reasoning.length +
                message.toolCalls.sumOf { it.name.length + it.arguments.toString().length }
        }
        is AgentMessage.ToolResultMessage -> message.toolName.length + message.result.length
    }
    private fun inferProviderType(aiProvider: AIProvider): ProviderType {
        val className = aiProvider::class.simpleName.orEmpty()
        return when {
            "Anthropic" in className -> ProviderType.ANTHROPIC
            "Gemini" in className -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
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
                // 压缩模型可能是纯文本模型，图片不能随请求发过去；
                // 但至少要让它知道这里原本有图，否则摘要里连「有图」这件事都不存在。
                msg is AgentMessage.UserMessage && msg.images.isNotEmpty() ->
                    msg.copy(
                        images = emptyList(),
                        content = msg.content + "\n[此处原有 ${msg.images.size} 张图片，已从摘要输入中省略]"
                    )

                // 摘要输入比发送时宽松得多：2000 字符截出来的摘要看不到细节，正是「模型说没有这段内容」的温床。
                msg is AgentMessage.ToolResultMessage -> {
                    val text = msg.modelResult ?: msg.result
                    if (text.length > SUMMARY_TOOL_CHARS) {
                        msg.copy(modelResult = text.truncateForSummary())
                    } else {
                        msg
                    }
                }

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

    private fun String.truncateForSummary(): String {
        if (length <= SUMMARY_TOOL_CHARS) return this
        return take(SUMMARY_TOOL_CHARS) + "\n[Tool output truncated for compaction]"
    }

    /**
     * 按压缩模型窗口预算截断 head：从新到旧保留消息，超预算丢弃更旧的消息。
     * 预算按 1 字符 ≈ 1 token 的保守口径（[ModelContextPolicy.estimateTokens] 的 4 字符/token
     * 会低估中文 4 倍，截不干净），并预留 30% 给摘要提示词与旧摘要；被丢弃部分由已有摘要兜底。
     */
    private fun List<AgentMessage>.truncateForSummaryWindow(contextTokens: Int): List<AgentMessage> {
        if (isEmpty()) return this
        val budgetChars = (contextTokens * 0.7f).toInt()
        var totalChars = 0
        val kept = mutableListOf<AgentMessage>()
        for (msg in asReversed()) {
            val chars = messageChars(msg)
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
