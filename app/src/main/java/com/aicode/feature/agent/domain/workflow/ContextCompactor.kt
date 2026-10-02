package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

        /**
         * 软精简不动的最近轮数：这几轮里的工具调用与工具输出一律保留。
         * 软线只有 40%，离撞窗还远，没必要为它牺牲还在用的内容。
         */
        const val SOFT_TRIM_PROTECTED_TURNS = 3

        /** 兜底截断的单条消息下限：再短就没法干活了，宁可让它超窗。 */
        const val MIN_MESSAGE_TOKENS = 64
        const val MIN_TRUNCATE_CHARS = 200

        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    /**
     * 软精简：把历史里超长的工具输出与工具参数裁短，直到估算落回 [targetTokens] 以内。
     *
     * 四个要点：
     * 1. 只改喂模型的那一份，不动落库/UI 的内容：工具输出改 `modelResult`（`result` 保持完整），
     *    工具参数改 `modelArguments`（`arguments` 保持完整），两者都是 copy() 出新对象，原对象不动；
     * 2. **只削已结束的轮次**：范围是本轮起点（最后一条用户消息）之前的历史；本轮的工具调用
     *    与工具输出一律不动——刚写进去的文件正文体积最大，只看体量会把它排在第一个削掉，
     *    而下一次调用最需要的恰恰是它（也是"占位符被拄回磁盘"那个 bug 的根源）。
     *    40% 是软线，真逼近窗口时由硬压缩与 enforceWindowLimit 兑底。
     * 3. 每一轮里先处理工具参数（纯冗余：文件已写到磁盘、正文能 read 回来），再处理工具输出；
     *    同一类按长度从大到小裁、够用就停——不做无差别全裁，避免把还有用的输出也削掉；
     * 4. 近期引用只降优先级：最近几轮用过同一文件/同一调用 id 的历史内容排到最后（见
     *    [isRecentlyReferenced]）。它是「模型现在还在对着它干活」的弱证据，不够格免死（软线
     *    本就是该省的地方），但能让体积排序不再拿正在用的内容开刀；跳过哪几条会写进日志；
     * 5. 幂等：已带标记/已重建的不再处理，重复调用不会把内容越裁越短。
     *
     * 未产生变化时返回原列表引用，便于调用方判断要不要更新状态。
     */
    fun softTrim(messages: List<AgentMessage>, targetTokens: Int): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        var estimate = TokenEstimator.estimateMessages(messages)
        if (estimate <= targetTokens) return messages

        var changed = false
        val result = messages.toMutableList()

        // 工具参数投影：write/edit 把整个文件塞进 arguments，而这段正文对模型是纯冗余——
        // 文件已经在磁盘上，需要时 read 回来即可，但每一轮请求都要原样再发一遍。
        // 这就是工具结果那层 modelResult 的对称做法：精简结果写进 modelArguments，
        // arguments 一字不动，所以 UI / 落库 / 真正执行拿到的永远是原文。
        // 幂等靠「永远以 arguments 为输入」：重复精简只会算出同一份副本，不会越削越短。
        // 只削「已结束的轮次」：范围是最近 SOFT_TRIM_PROTECTED_TURNS 轮之前的历史。
        // 最近几轮从用户消息开始的一切——助手发出的工具调用、以及它们的工具输出——一律不动：
        // 1. 它们是模型此刻正在用的内容。刚写进去的文件正文往往体积最大，只看体量会把它排在
        //    第一个削掉，而下一次调用最需要的恰恰是它（“占位符被拄回磁盘”那个 bug 的根源）；
        // 2. 40% 是软线，离窗口上限还远，没理由为此牺牲正在用的内容。真逼近窗口时由硬压缩
        //    （85% / 92%）与 enforceWindowLimit 兑底，那才是该动近几轮的时机。
        val userIndices = messages.indices.filter { messages[it] is AgentMessage.UserMessage }
        val historyEnd = when {
            userIndices.size > SOFT_TRIM_PROTECTED_TURNS ->
                userIndices[userIndices.size - SOFT_TRIM_PROTECTED_TURNS]
            userIndices.isNotEmpty() -> userIndices.first()
            else -> messages.size
        }
        if (historyEnd <= 0) return messages

        // 近期引用：最近几轮还在操作的文件与用过的本地调用 id。命中的历史内容只降级（排到最后），
        // 不做豁免：预算真不够时仍要削得动，否则软精简就失去意义。
        val callPaths = toolCallPathsById(messages)
        val recent = collectRecentReferences(messages, historyEnd)
        val argCandidates = (0 until historyEnd)
            .filter { index ->
                val message = messages[index]
                message is AgentMessage.AssistantMessage && message.toolCalls.isNotEmpty()
            }
            .sortedByDescending { TokenEstimator.estimateMessage(messages[it]) }
        val argDeferred = argCandidates.filter { isRecentlyReferenced(messages[it], recent, callPaths) }
        val resultCandidates = (0 until historyEnd)
            .filter { messages[it] is AgentMessage.ToolResultMessage }
            .sortedByDescending { TokenEstimator.estimateMessage(messages[it]) }
        val resultDeferred = resultCandidates.filter { isRecentlyReferenced(messages[it], recent, callPaths) }

        val trimmedIndices = mutableSetOf<Int>()
        for (index in argCandidates.filterNot { it in argDeferred } + argDeferred) {
            if (estimate <= targetTokens) break
            val message = result[index] as AgentMessage.AssistantMessage
            val rebuilt = message.toolCalls.map { call -> rebuildToolCallArguments(call, SOFT_TRIM_TOOL_CHARS) }
            if (rebuilt == message.toolCalls) continue
            val trimmed = message.copy(toolCalls = rebuilt)
            estimate -= TokenEstimator.estimateMessage(message) - TokenEstimator.estimateMessage(trimmed)
            result[index] = trimmed
            trimmedIndices.add(index)
            changed = true
        }

        for (index in resultCandidates.filterNot { it in resultDeferred } + resultDeferred) {
            if (estimate <= targetTokens) break
            val message = result[index] as AgentMessage.ToolResultMessage
            val current = message.modelResult ?: message.result
            if (current.length <= SOFT_TRIM_TOOL_CHARS || current.endsWith(SOFT_TRIM_MARKER)) continue
            val trimmed = message.copy(modelResult = headTailTrim(current, SOFT_TRIM_TOOL_CHARS) + SOFT_TRIM_MARKER)
            estimate -= TokenEstimator.estimateMessage(message) - TokenEstimator.estimateMessage(trimmed)
            result[index] = trimmed
            trimmedIndices.add(index)
            changed = true
        }

        // 不动的理由要能让人查得下去：只说“有 N 条被保护”没法核，得把工具名、
        // 路径/调用 id 一并打出来。
        val deferred = argDeferred + resultDeferred
        if (changed && deferred.isNotEmpty()) {
            val skipped = deferred.count { it !in trimmedIndices }
            FileLogger.i(
                TAG,
                "软精简把 ${deferred.size} 条近期被引用的历史工具内容排到最后（最近 $SOFT_TRIM_PROTECTED_TURNS 轮用到同一文件或同一调用 id），" +
                    "本次跳过 $skipped 条（估算 $estimate / 目标 $targetTokens）：" +
                    deferred.joinToString("、", limit = 8) { describeToolMessage(messages[it]) }
            )
        }
        return if (changed) result else messages
    }

    /**
     * 最近几轮引用到的文件路径与工具调用 id。
     *
     * 判据只看工具调用参数：它是「模型此刻在操作什么」最直接的证据，不猜语义、不读自然语言，
     * 所以结果确定、可解释。ids 里也收下工具结果自带的 id：同一段历史被重复回放时
     * （压缩失败重试、存档回读）历史消息会与近轮拿到同一个 id，那一份同样属于「还在用」。
     */
    private data class RecentReferences(val paths: Set<String>, val ids: Set<String>)

    private fun collectRecentReferences(messages: List<AgentMessage>, from: Int): RecentReferences {
        val paths = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        for (index in from until messages.size) {
            when (val message = messages[index]) {
                is AgentMessage.AssistantMessage -> message.toolCalls.forEach { call ->
                    ids.add(call.id)
                    toolArgumentPathOf(call.arguments)?.let(paths::add)
                }

                is AgentMessage.ToolResultMessage -> ids.add(message.id)
                is AgentMessage.UserMessage -> {}
            }
        }
        return RecentReferences(paths, ids)
    }

    /** 全部历史里「工具调用 id → 目标路径」：工具结果自身不带路径，靠它的 id 反查回那次调用。 */
    private fun toolCallPathsById(messages: List<AgentMessage>): Map<String, String> {
        val paths = mutableMapOf<String, String>()
        messages.forEach { message ->
            if (message is AgentMessage.AssistantMessage) {
                message.toolCalls.forEach { call ->
                    toolArgumentPathOf(call.arguments)?.let { paths[call.id] = it }
                }
            }
        }
        return paths
    }

    /** 这条历史内容是不是「最近几轮还在引用」的：碰过同一文件，或带同一个工具调用 id。 */
    private fun isRecentlyReferenced(
        message: AgentMessage,
        recent: RecentReferences,
        callPaths: Map<String, String>
    ): Boolean = when (message) {
        is AgentMessage.AssistantMessage ->
            message.id in recent.ids || message.toolCalls.any { call ->
                call.id in recent.ids || toolArgumentPathOf(call.arguments)?.let { it in recent.paths } == true
            }

        is AgentMessage.ToolResultMessage ->
            message.id in recent.ids || callPaths[message.id]?.let { it in recent.paths } == true

        is AgentMessage.UserMessage -> false
    }

    /** 日志里的身份描述：工具名 + 调用 id，够定位是哪一条，不打印正文。 */
    private fun describeToolMessage(message: AgentMessage): String = when (message) {
        is AgentMessage.AssistantMessage ->
            message.toolCalls.joinToString("+") { "${it.name}#${it.id}" }.ifEmpty { "助手消息#${message.id}" }

        is AgentMessage.ToolResultMessage -> "${message.toolName}#${message.id}"
        is AgentMessage.UserMessage -> "用户消息#${message.id}"
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
        // 裁掉开头的孤立 tool 结果后可能什么都不剩：这时只剩一条压缩指令，
        // 调模型也只会得到一段没用的摘要，白花钱
        val trimmedHead = headForSummary.trimLeadingForCompaction()
        if (trimmedHead.isEmpty()) {
            FileLogger.i(TAG, "裁剪孤立 tool 结果后 head 为空，跳过压缩")
            return null
        }
        val summaryRequestMessages = trimmedHead + listOf(
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
            // marker 已落库的标记：插摘要失败时要能把它删掉。声明在 try 外——catch 看不到 try 内的局部变量。
            var markerPersisted = false
            try {
                val dbEntities = agentMessageDao.getMessagesBySessionOnce(sessionId)
                // 内存态消息与库行的 id 不是同一套：tool 行落库是 "tool_<callId>"、内存态是 <callId>；
                // user/assistant 落库是随机 UUID、内存态常为空串。所以逐级兜底匹配，不能只比 id。
                val anchorTs = tail.firstNotNullOfOrNull { msg -> resolveRowTimestamp(dbEntities, msg) }

                // 找不到锚点就放弃这次压缩：只不标记却仍插 marker/summary 会新开一个坑——
                // 那两条的落点只能取当前时间，会排在全部历史之后，回放变成「原文 + 摘要 + tail」，
                // head 没被折叠、摘要还被摆到末尾（正是上面注释里警告的「模型把摘要当成自己上一轮」），
                // 下一轮又会再次触发摘要调用。所以宁可这轮不压（重复总比丢好），直接放弃。
                if (anchorTs == null) {
                    FileLogger.w(TAG, "压缩时匹配不到 tail 对应的库行，放弃本次压缩，会话 $sessionId")
                    onEvent(AgentEvent.CompactionFailed("无法定位保留区起点，已跳过本次压缩"))
                    return null
                }
                // 先插 marker + 摘要，最后才标记 head。
                // 反过来的话（先标记、后插入）插入失败会让 head 已被标成「已压缩」却没有摘要顶上，
                // 那段历史就永久离开了上下文——这是真丢数据。插入失败直接放弃本次压缩，重复好过丢失。
                // 摘要放在 tail 之前：回放/UI 顺序 = 摘要 → tail。
                // 接手摘要作为背景，最后一条仍是用户请求 / tool 结果，模型才会继续干活；
                // 若放在末尾，模型会把摘要当成自己的上一轮，续写一大段后停下。
                val markerTs = (anchorTs - 2).coerceAtLeast(1L)
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
                markerPersisted = true
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

                // 标记放在插入之后：标记失败只是让 head 下一轮再回放一次（重复），不会丢。
                // cutoff 必须用 markerTs 而不是 anchorTs：markerTs / summaryTs 都小于 anchorTs，
                // 用 anchorTs 会把刚插进去的这两行自己也标成 isCompacted，
                // 而回放时会滤掉 isCompacted 的行——摘要就只在内存态活一轮，下一个用户轮次直接消失。
                runCatching { agentMessageDao.markMessagesCompactedBeforeTimestamp(sessionId, markerTs) }
                    .onFailure { FileLogger.w(TAG, "标记已压缩失败，head 下一轮会重复回放一次", it) }

                // 旧摘要显式回收：上面的时间戳标记在保留区起点没有前移时（两次折叠之间新增量小于预算富余）
                // 标不到旧 marker / 旧摘要，上下文里会留下两份重复且过时的接手说明。旧摘要内容已被新摘要
                // 吸收，直接标掉不丢信息。两个 keep id 都非空才执行——空 id 会让排除条件失效，
                // 把刚插入的摘要一起标掉。
                if (markerId.isNotBlank() && compactedId.isNotBlank()) {
                    runCatching {
                        agentMessageDao.markSupersededCompactionRows(
                            sessionId = sessionId,
                            keepMarkerId = markerId,
                            keepSummaryId = compactedId
                        )
                    }
                        .onSuccess { FileLogger.i(TAG, "已回收 $it 行旧摘要") }
                        .onFailure { FileLogger.w(TAG, "回收旧摘要失败，上下文里可能残留重复的接手摘要", it) }
                } else {
                    FileLogger.w(TAG, "压缩结果 id 缺失，跳过旧摘要回收（宁可留重复，不能标掉刚插的摘要）")
                }
            } catch (e: Exception) {
                // 摘要没插进去 = 本次压缩作废（head 也不标记），那先落库的 marker 必须一并删掉：
                // 回放时它是一条孤立的用户消息「What did we do so far?」——历史没被折叠，
                // 模型却会以为用户刚问过这句；removeCompactionPairs 只成对清理，认不出这种孤儿。
                if (markerPersisted) {
                    runCatching { agentMessageDao.deleteMessageById(markerId) }
                        .onFailure { FileLogger.w(TAG, "回滚压缩 marker 失败，回放会多出一条孤立 marker", it) }
                }
                FileLogger.e(TAG, "持久化压缩结果失败，放弃本次压缩（不标记 head）", e)
                return null
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

            is AgentMessage.AssistantMessage -> {
                val trimmedContent =
                    if (message.content.length <= limitOf(message.content)) message.content
                    else headTailTrim(message.content, limitOf(message.content))
                // 工具参数按字段重建。只削正文不动 reasoning：reasoning / signature /
                // thinkingBlocksJson 必须原样回传，动了 DeepSeek 思考模式与 Anthropic thinking 直接 400。
                val trimmedCalls = message.toolCalls.map { call -> rebuildToolCallArguments(call, limitOf(call.arguments.toString())) }
                if (trimmedContent == message.content && trimmedCalls == message.toolCalls) message
                else message.copy(content = trimmedContent, toolCalls = trimmedCalls)
            }
        }
    }

    /**
     * 工具参数按字段重建：把「能重新读回来」的大块正文换成可读摘录（头尾逐字 + 省略说明 +
     * readFile 回读指针），其它超长值头尾截断，小字段（路径、命令开头这类定位信息）原样保留。
     *
     * 结果写进 `modelArguments`，`arguments` 保持原文——执行、UI、落库都读 arguments。
     * 每次都以 `arguments`（原文）为输入重算，所以重复精简是幂等的（不会拿上一次的副本再削）。
     *
     * 为什么给摘录而不是整段占位说明：占位说明把「我写过什么」从模型视野里整个抹掉——
     * 模型看不到文件当时的结构与收尾状态，只能整份重新 read 才敢接着改，也更容易把占位文字
     * 当成正文抄回去。摘录留开头与结尾的逐字内容，中段的大头照旧省下；首行另带一句禁令
     * （执行边界还有一道前缀识别兜底，见 [isOmittedArgumentValue]）。
     *
     * id 与 name 一律不动：tool call 与 tool result 的配对靠 id，动了就成孤儿消息，API 直接 400。
     */
    private fun rebuildToolCallArguments(call: ToolCall, budgetChars: Int): ToolCall {
        if (call.arguments.isEmpty()) return call
        var changed = false
        val path = toolArgumentPathOf(call.arguments)
        val rebuilt = call.arguments.mapValues { (key, value) ->
            val text = value.argumentText()
            val limit = if (BULK_ARG_KEYS.contains(key.lowercase())) {
                // 大块正文类：与其它字段同一套预算口径，另受 BULK_ARG_LIMIT_CHARS 封顶
                minOf(budgetChars, BULK_ARG_LIMIT_CHARS)
            } else {
                budgetChars
            }
            when {
                text.length <= limit -> value

                // 大块正文：换成可读摘录；摘录省不下内容时退回占位说明
                BULK_ARG_KEYS.contains(key.lowercase()) -> {
                    changed = true
                    JsonPrimitive(
                        bulkArgumentExcerptOf(text, path, limit) ?: omittedContentPlaceholder(text.length)
                    )
                }

                // 其它字段（命令、路径等）保留头尾，让模型能认出是哪一条
                else -> {
                    changed = true
                    kotlinx.serialization.json.JsonPrimitive(headTailTrim(text, limit))
                }
            }
        }
        return if (changed) call.copy(modelArguments = rebuilt) else call
    }

    /** 参数里的目标文件路径：摘录的回读指针与「近期引用」判据共用它，口径只有这一处。 */
    private fun toolArgumentPathOf(arguments: Map<String, JsonElement>): String? =
        arguments.entries
            .firstOrNull { it.key.lowercase() in PATH_ARG_KEYS }
            ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

    /**
     * 参数值的文本形态：JSON 字符串取内容本身，其余（数字/布尔/null）取字面量。
     *
     * 不能直接用 `toString()`：JsonPrimitive 的 toString 是 **JSON 序列化**形态——字符串会带上首尾引号，
     * 内部的换行还被打成两个字符 `\n`。摘录要展示的是文件正文，用序列化形态得到的是「一整行 + 首尾各一个
     * 引号」：模型看不出文件结构，省略行数也会算成 0（那串文本里一个真换行都没有）。
     */
    private fun JsonElement.argumentText(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString()

    /**
     * 大块正文的硬上限：超过它就整段换成占位说明（文件已在磁盘上，需要时 read 回来）。
     *
     * 原值 200 过小——正常的 writeFile 正文随手就上千字符，等于"只要压缩一启动就把参数全换掉"，
     * 模型的上下文里再也看不到自己写过什么。而占位说明是**可以被逐字复制的正文形态**，
     * 模型在重写/补写同一文件时会把它当成文件内容拄回去（已发生三次，连新建文件都中招）。
     * 执行边界的 ARG_OMITTED_BY_COMPACTION 守卫负责兜底，这里从源头减少发生机会。
     * 4000 字符约等于一两千 token，需要时仍能省下大头，又不会误伤日常写文件。
     */
    private val BULK_ARG_LIMIT_CHARS = 4_000

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
     * 预算与记账都用 [TokenEstimator] 的估算 token（不是字符数），并预留 30% 给摘要提示词与旧摘要。
     */
    private fun List<AgentMessage>.truncateForSummaryWindow(contextTokens: Int): List<AgentMessage> {
        if (isEmpty()) return this
        val budgetTokens = (contextTokens * 0.7f).toInt()
        var totalTokens = 0
        val kept = mutableListOf<AgentMessage>()
        for (msg in asReversed()) {
            val tokens = TokenEstimator.estimateMessage(msg)
            if (kept.isNotEmpty() && totalTokens + tokens > budgetTokens) break
            totalTokens += tokens
            kept.add(msg)
        }
        val truncated = kept.asReversed()
        if (truncated.size != size) {
            FileLogger.i(TAG, "head 超出压缩模型窗口预算，丢弃 ${size - truncated.size} 条最旧消息（预算约 $budgetTokens tokens）")
        }
        return truncated
    }
}

/**
 * 大块正文类参数：这些字段「能重新读回来」，超限时换成本地摘录（省不下时退回占位说明）。
 *
 * 与 [isOmittedArgumentValue]（执行边界的前缀识别）共用同一份清单，避免两处漂移。
 */
internal val BULK_ARG_KEYS = setOf(
    "content", "old_string", "new_string", "oldstring", "newstring", "text", "newtext"
)

/**
 * 参数里的目标文件路径字段：write / edit 类工具的入参就叫这两个名字，各家的别名一并收进。
 * 摘录的回读指针与「近期引用」判据都按它取值，保证两处指向同一个文件。
 */
private val PATH_ARG_KEYS = setOf("path", "file_path", "filepath", "file")

/**
 * 软精简留下的占位说明（整段替换体）。
 *
 * 文案里必须带那句禁令：模型看到占位说明后，可能把它当成「文件正文」抄进新的 writeFile 调用，
 * 照抄执行就会把占位文字写进磁盘（已实际发生过）。禁令只是降低概率，真正兜底的是执行边界的
 * 前缀识别 [isOmittedArgumentValue]。
 */
internal fun omittedContentPlaceholder(length: Int): String =
    "[已省略 $length 字符，正文不在上下文中，需要时用 read 工具读回；" +
        "严禁把这段占位文字当作参数值回写，它不代表文件内容]"

/**
 * 占位说明的整串形态。
 *
 * 必须整串匹配（用 `Regex.matches()`，不是 `contains`）：用户文件里恰好含这句话属于正常内容，
 * 不能因为出现子串就拦下调用。容错范围限定在模型改写占位说明的常见变体——丢掉方括号、漏掉
 * 「中」、尾部换措辞、末尾多一个句号；尾部不允许换行也不允许出现 `]`，所以「占位说明后面还跟着
 * 真正文」与多行文件都进不了这个模式。
 *
 * 大块正文现在改产摘录（见 [bulkArgumentExcerptOf]），但历史消息与库里仍有这种整段替换的形态，
 * 识别不能丢。
 */
internal val OMITTED_CONTENT_PLACEHOLDER = Regex(
    "^\\[?已省略 \\d+ 字符，正文不在上下文(?:中)?[^\\]\\n]{0,80}\\]?[。.!！]?$"
)

/**
 * 摘录首行的哨兵。
 *
 * 执行边界的守卫靠「值的第一个字符就是它」认出「模型把摘录当正文回写」（见 [isOmittedArgumentValue]）：
 * 整段摘录只由 [bulkArgumentExcerptOf] 产出，真实文件内容不会以它开头。
 * 判据必须落在开头而不是「值里含这句话」——本仓库自己的源码与测试就带着这些字样，
 * 「包含」会把对这类文件的正常编辑全拦掉。
 */
internal const val BULK_EXCERPT_HEAD = "[AiCode 上下文摘录·非完整正文]"

/** 摘录中段省略标记的前缀；模型只抄走这一段（不含首行哨兵）时同样按占位形态拦下。 */
internal const val BULK_EXCERPT_GAP_PREFIX = "…[AiCode 摘录省略"

/** 摘录的预算上限：比一句占位说明大得多（那种约 60 字符），容得下一段可读的头尾。 */
private const val EXCERPT_MAX_CHARS = 1_600

/** 摘录的预算下限：原文刚过闸值时也要留出可读的头尾，而不是只剩一句说明。 */
private const val EXCERPT_MIN_CHARS = 400

/**
 * 大块正文的本地摘录（纯字符串处理，不调模型）：留头留尾 + 一行省略说明 + readFile 回读指针。
 *
 * 为什么不是整段占位：占位把「我写过什么」从模型视野里整个抹掉，模型只能整份 read 回来才敢接着改；
 * 摘录留出开头与结尾的逐字内容，模型能认出文件结构与收尾状态，中段大头照旧省下。
 * 省略了多少必须写出来——不说明的省略会让模型以为文件真的短了一截。
 *
 * 预算按原文的 1/4 收缩（上下限收敛）：刚过闸值时不会只省下一两行，很大的正文又不会占满上下文。
 *
 * @param limit 调用方给出的该字段长度上限；调用方只在 `text.length > limit` 时才调进来。
 * @return 摘录文本；省不下内容（原文本来就短，或 [limit] 太小）时返回 null，由调用方退回占位说明。
 */
internal fun bulkArgumentExcerptOf(text: String, path: String?, limit: Int): String? {
    if (limit <= 0 || text.length <= limit) return null
    val budget = minOf(EXCERPT_MAX_CHARS, limit, maxOf(EXCERPT_MIN_CHARS, text.length / 4))
    val head = text.excerptFromStart(budget * 2 / 3)
    val tail = text.excerptFromEnd(budget - head.length)
    val omitted = text.substring(head.length, text.length - tail.length)
    if (omitted.isEmpty()) return null
    val omittedLines = omitted.count { it == '\n' }
    val excerpt = buildString {
        append(BULK_EXCERPT_HEAD)
        append(" 原文共 ${text.length} 字符，中间省略 $omittedLines 行（${omitted.length} 字符）。")
        if (!path.isNullOrBlank()) append("需要全文时用 readFile 读回 $path；")
        append("严禁把本摘录当正文回写，它不是文件内容。")
        append('\n')
        append(head)
        append('\n')
        append(BULK_EXCERPT_GAP_PREFIX)
        append(" $omittedLines 行（${omitted.length} 字符）]…")
        append('\n')
        append(tail)
    }
    // 摘录必须显著短于原文：调用方（软精简的估算记账、兜底截断的等比换算）都假定换过之后更短。
    if (excerpt.length * 2 > text.length) return null
    return excerpt
}

/** 从开头留 [maxChars] 字符，切点对齐到最后一个换行；正文是一整行（压缩过的 JSON/JS）时保持原样。 */
private fun String.excerptFromStart(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val cut = lastIndexOf('\n', maxChars - 1)
    return if (cut > 0) substring(0, cut) else take(maxChars)
}

/** 从结尾留 [maxChars] 字符，切点对齐到第一个换行。 */
private fun String.excerptFromEnd(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val cut = indexOf('\n', length - maxChars)
    return if (cut in 0 until length - 1) substring(cut + 1) else takeLast(maxChars)
}

/**
 * 参数值是不是「压缩留下的占位说明 / 摘录」（执行边界守卫的判据）。
 *
 * 三种形态，都要求从值的第一个字符起成立：
 * 1. 早期的整段占位说明（[OMITTED_CONTENT_PLACEHOLDER]）——历史消息里还在，模型可能照抄；
 * 2. 摘录首行哨兵 [BULK_EXCERPT_HEAD]——模型把整段摘录当正文回写时，值的开头正好是它；
 * 3. 摘录中段省略标记 [BULK_EXCERPT_GAP_PREFIX]——模型从省略处往后抄。
 *
 * 三条都是前缀/整串判据，不是「包含」：用户文件里恰好写上这句话（包括本仓库讲压缩实现的源码
 * 与测试）属于正常内容，不能因为出现子串就拦下调用。代价是「只抄摘录中段且连省略标记也改写掉」
 * 这种形态仍会漏网，但把判据放宽成「包含」会拿正常编辑去换，得不偿失。
 */
internal fun isOmittedArgumentValue(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return OMITTED_CONTENT_PLACEHOLDER.matches(trimmed) ||
        trimmed.startsWith(BULK_EXCERPT_HEAD) ||
        trimmed.startsWith(BULK_EXCERPT_GAP_PREFIX)
}

/**
 * 挑出「值就是占位说明 / 摘录」的大块正文字段名；没有则返回 null。
 *
 * 只查 [BULK_ARG_KEYS]：这类形态只会出现在这些字段上，收窄范围避免误伤其它字段。
 */
internal fun omittedBulkArgumentKeyOf(arguments: Map<String, JsonElement>): String? =
    arguments.entries.firstOrNull { (key, value) ->
        key.lowercase() in BULK_ARG_KEYS &&
            (value as? JsonPrimitive)?.contentOrNull?.let { isOmittedArgumentValue(it) } == true
    }?.key
