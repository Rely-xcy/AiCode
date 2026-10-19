package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.CompactionChainBlocksNotMarkedException
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文变换器：只负责「怎么改消息」，不负责「什么时候改」。
 *
 * 阈值、档位、硬线判定属于策略，归 [com.aicode.feature.agent.domain.engine.modules.CompactionModule]；
 * 本类只提供两个变换，便于单独验证：
 * - [compact]：调摘要模型折叠早期对话，并把结果落库；
 * - [enforceWindowLimit]：发送前兜底截断，保证不发出超窗请求（不落库，按轮次冻结产出）。
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

        /** 会话级压缩互斥：同一会话同一时刻只允许一个压缩流程。 */
        private val sessionLocks = ConcurrentHashMap<String, Mutex>()

        /** 送进摘要请求前对单条工具输出的保留上限（只在摘要请求内生效，不改历史）。 */
        const val TRIM_FOR_SUMMARY_CHARS = 3_000

        /** 兜底截断的单条消息下限：再短就没法干活了，宁可让它超窗。 */
        const val MIN_MESSAGE_TOKENS = 64
        const val MIN_TRUNCATE_CHARS = 200

        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"

        /** 链合并（阶段 4）专用提示词：对多份旧摘要再归纳。 */
        const val MERGE_PROMPT_FILE = "agent/compact-chain-merge.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 压缩请求的 system prompt：只做摘要，不继续对话、不调工具。 */
        const val SUMMARY_SYSTEM_PROMPT =
            "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。"

        /**
         * 分块摘要的单块字数目标。
         *
         * 最终摘要上限 1200 字，8 块各写 400 字，合并输入约 3200 字（约 3200 token），
         * 在常见摘要窗口（8k 以上）里装得下；块内指令又要求只覆盖本块，避免每块都复述全文。
         */
        const val CHUNK_SUMMARY_CHAR_BUDGET = 400

        /**
         * 摘要合并的最大层级。8 块最多只需 3 层（8 → 3 → 1），4 留一层余量；
         * 再往上是预算小到装不下的病态窗口，继续递归只是多花钱。
         */
        const val MAX_SUMMARY_MERGE_DEPTH = 4

        /**
         * 摘要窗口预算比例：剩余 30% 留给摘要提示词与旧摘要。
         *
         * 三处共用同一常量，不能各自硬编码——[compact] 用它切分块（保证每块请求装得下）、
         * 分块合并用它算分组预算、[List.truncateForSummaryWindow] 用它做最后一道裁切，
         * 口径漂移就会重新出现「悄悄丢弃原文」。
         */
        const val SUMMARY_BUDGET_RATIO = 0.7f
    }

    /**
     * 发送前兜底：硬压缩之后估算仍逼近窗口时，对超长消息本身做硬截断。
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
        // 只从最老的消息开始截：固定顺序 + 固定预算，同一份输入永远得到同一串字节。
        // 按「最大优先」截会让同一轮相邻两次调用的截点随估算漂移，前缀随之变形。
        val order = messages.indices
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
     * 硬压缩：把窗口内最早的原文（head）折叠成一个**新摘要块**，追加到摘要链尾。
     *
     * 持久化：摘要以 marker + assistant 两条追加落库，summary 行带 foldsUpToTimestamp =
     * head 最后一条的 timestamp（覆盖边界）；head 不标 isCompacted、旧摘要不淘汰——
     * 库对「折叠」这个动作而言是纯追加的，已发出的请求前缀（S0..Sk）逐字节稳定。
     * 回放侧（MessagePersistenceUseCase）按块边界裁窗口，被折叠的原文不再重复回放。
     *
     * @param preserveRecentTokens 保留最近原文的预算（由调用方按窗口档位算好）。
     * @param summaryWindowTokens 摘要模型自己的窗口：head 超过它预算时按它切块，单块请求也按它兜底截断。
     * @return 压缩后的消息列表；无内容可压或调用失败返回 null（调用方保留原消息）。
     */
    suspend fun compact(
        messages: List<AgentMessage>,
        summaryProvider: AIProvider,
        sessionId: String?,
        preserveRecentTokens: Int,
        summaryWindowTokens: Int,
        /** 本次折叠所处的硬线与窗口（判定在模块侧做，只透传过来记账用，不参与任何判断）。 */
        hardThreshold: Int,
        contextLimit: Int,
        /**
         * 触发本次折叠的那个数（模块侧 `currentTokens = max(真实 usage, 校准后估算)`，估算含
         * system 与工具定义开销）。与 [messages] 的本地估算**不是同一个量**：后者只算消息、
         * 不含固定开销，所以它比硬线低几千 token 也可能已经触发折叠。只记账，不参与判断。
         */
        decisionTokens: Int = 0,
        /**
         * 折叠前的回调：拿到即将被折叠掉的那段历史（head）。
         * 给调用方一个「趁还没丢，先把长期价值捞出来」的机会（如抽取长期记忆）。
         */
        onBeforeFold: (suspend (List<AgentMessage>) -> Unit)? = null,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage>? {
        // 会话级互斥：同一会话并发触发压缩时，后到者等前一个结束，避免两轮折叠同一段历史、
        // 或后写覆盖前写的摘要块（V2 3.6）。
        val sessionLock = sessionId?.let { sessionLocks.computeIfAbsent(it) { Mutex() } }
        sessionLock?.lock()
        try {
        // 折叠前的上下文量：提交前要拿它跟折叠后的结果比（不省空间的折叠不提交）。
        val estimatedTokens = TokenEstimator.estimateMessages(messages)
        var splitIndex = selectTailStartIndex(messages, preserveRecentTokens)
        if (splitIndex <= 0) return null
        // tail 不能以孤立的 ToolResultMessage 开头：压缩后其前面是摘要（不含 toolCalls），
        // 该 tool 消息会变成孤儿，API 直接 400。向前回溯到配对的 assistant。
        splitIndex = adjustSplitIndex(messages, splitIndex)
        if (splitIndex <= 0) return null

        // 分块摘要：以前 head 装不进摘要窗口就把 splitIndex 前移（这轮少折一点），超大
        // 会话因此反复「少折一点」压不下去。现在改为按摘要窗口切块、逐块摘要、再把各块
        // 摘要合并成一份：最终仍只落一条摘要行，覆盖边界与落库语义都不变。
        // 单块目标沿用 70% 预算 [SUMMARY_BUDGET_RATIO]，保证每块请求连指令一起装得下。
        val summaryBudgetTokens = (summaryWindowTokens * SUMMARY_BUDGET_RATIO).toInt()

        // 自适应收敛先跑：它只决定「多折能不能换来空间」，与 head 怎么切块无关。
        // 代价：多折进 head 的原文可能因块数上限留在保留区（不丢原文），或单条超长内容
        // 在摘要请求里被截断（存档里仍有原文）。
        val shrink = convergeSplitIndex(messages, splitIndex, estimatedTokens)
        if (shrink.splitIndex != splitIndex) {
            FileLogger.i(
                TAG,
                "保留区自适应收缩：切点 $splitIndex → ${shrink.splitIndex}（${shrink.rounds} 轮）" +
                    " 折后预计 ${projectedCompactedTokens(messages, shrink.splitIndex)} / 原估算 $estimatedTokens tokens" +
                    " 保留条数 ${messages.size - shrink.splitIndex}" +
                    "（地板 $MIN_TAIL_MESSAGES 条 / $MIN_TAIL_TOKENS tokens）"
            )
            splitIndex = shrink.splitIndex
        }

        var head = messages.subList(0, splitIndex)
        var tail = messages.subList(splitIndex, messages.size)

        // 新语义：旧摘要块是冻结的独立块，新块只覆盖本次折掉的原文，不再合并旧摘要
        // （设计 §4.3 第 5 步）。buildSummaryInstruction 的 previous-summary 分支保留，
        // 阶段 3 引入合并时复用；extractPreviousSummary 同理保留但不再被此处调用。
        val summarySource = removeCompactionPairs(head)
        if (summarySource.isEmpty()) {
            // 重复压缩时 head 可能只剩旧的 marker+summary 对，删光后无可压缩内容。
            FileLogger.i(TAG, "无可压缩内容（head 为空），跳过压缩")
            return null
        }

        val needsChunking = summaryBudgetTokens > 0 &&
            TokenEstimator.estimateMessages(summarySource) > summaryBudgetTokens
        val chunks = if (needsChunking) {
            splitIntoSummaryChunks(summarySource, summaryBudgetTokens, MAX_SUMMARY_CHUNKS)
        } else {
            // 装得下就保持原来的单次调用路径（trim + 单条摘要），不为一句话的历史多花调用。
            listOf(summarySource.truncateForSummaryWindow(summaryWindowTokens))
        }

        // 块数上限：head 超过 [MAX_SUMMARY_CHUNKS] 块时只折最旧的这些块，其余原文留在保留区。
        // 这里把切点收回到最后一块末尾，覆盖边界、归档范围、回放裁窗都随之缩小——宁可少折，
        // 也不让一次折叠花掉十几次模型调用。
        if (needsChunking) {
            val covered = chunks.sumOf { it.size }
            if (covered < summarySource.size) {
                val newSplit = adjustSplitIndex(messages, indexAfterFilteredMessages(head, covered))
                if (newSplit <= 0) return null
                FileLogger.i(
                    TAG,
                    "分块摘要达到块数上限 $MAX_SUMMARY_CHUNKS：本轮只折叠最旧 $covered 条原文" +
                        "（切点 $splitIndex → $newSplit），其余留在保留区"
                )
                splitIndex = newSplit
                head = messages.subList(0, splitIndex)
                tail = messages.subList(splitIndex, messages.size)
            }
        }

        // 再给调用方一次「捞出长期价值」的机会（记忆抽取）：这段历史马上离开上下文
        if (onBeforeFold != null) {
            try {
                onBeforeFold(head)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "折叠前回调失败，继续压缩", e)
            }
        }
        // 进度步数：单块只算一次摘要调用；多块是「逐块摘要 + 一次合并」。
        // 只用来给界面报数，不参与任何切分或判定。
        val progressTotalSteps = if (chunks.size == 1) 1 else chunks.size + 1

        val previousSummary: String? = null
        // 结构化文件账本：只从被折叠的这段历史的工具调用参数里取，作为摘要请求的附加输入，
        // 并由代码在摘要末尾追加固定小节（不新增表，靠摘要正文持久化）。
        val chunkLedgers = chunks.map { buildFileLedger(it) }
        val ledgerEntries =
            if (needsChunking) mergeFileLedgers(chunkLedgers) else buildFileLedger(summarySource)

        val summaryStartElapsed = SystemClock.elapsedRealtime()
        val summaryUsages = mutableListOf<AIResponse>()
        var summaryCallCount = 0
        val summaryResponse: String
        if (chunks.size == 1) {
            val trimmedHead =
                chunks.single().trimLeadingForCompaction().fitForSummaryBudget(summaryBudgetTokens)
            if (trimmedHead.isEmpty()) {
                FileLogger.i(TAG, "裁剪孤立 tool 结果后 head 为空，跳过压缩")
                return null
            }
            val summaryInstruction =
                buildSummaryInstruction(previousSummary) + fileLedgerPrompt(ledgerEntries)
            onEvent(AgentEvent.CompactionProgressChanged(step = 1, totalSteps = progressTotalSteps))
            val outcome = requestSummaryCall(
                summaryProvider = summaryProvider,
                sessionId = sessionId,
                requestMessages = trimmedHead + listOf(AgentMessage.UserMessage(content = summaryInstruction)),
                failureLog = "压缩上下文失败"
            )
            summaryCallCount = 1
            outcome.usage?.let { summaryUsages += it }
            if (outcome.content == null) {
                val reason = outcome.error ?: "摘要响应不可用"
                FileLogger.w(TAG, "摘要响应不可用，放弃本次压缩（不落库、不标记 head）：$reason")
                onEvent(AgentEvent.CompactionFailed("摘要响应不可用：$reason"))
                return null
            }
            summaryResponse = outcome.content
        } else {
            val chunkSummaries = mutableListOf<String>()
            for ((index, chunk) in chunks.withIndex()) {
                // 首块沿用整段 head 的老规则（丢掉开头的孤立 tool 结果）；后续块不能丢：
                // 它们可能正好从 assistant 消息开始，丢了就等于这段历史没进摘要。
                val prepared =
                    if (index == 0) chunk.trimLeadingForCompaction()
                    else chunk.stripForSummary()
                val requestHead = prepared.fitForSummaryBudget(summaryBudgetTokens)
                if (requestHead.isEmpty()) {
                    FileLogger.i(
                        TAG,
                        "裁剪孤立 tool 结果后第 ${index + 1}/${chunks.size} 块为空，跳过压缩"
                    )
                    return null
                }
                val instruction = buildChunkSummaryInstruction(index, chunks.size) +
                    fileLedgerPrompt(chunkLedgers[index])
                onEvent(
                    AgentEvent.CompactionProgressChanged(
                        step = index + 1,
                        totalSteps = progressTotalSteps
                    )
                )
                val outcome = requestSummaryCall(
                    summaryProvider = summaryProvider,
                    sessionId = sessionId,
                    requestMessages = requestHead + listOf(AgentMessage.UserMessage(content = instruction)),
                    failureLog = "分块摘要第 ${index + 1}/${chunks.size} 块调用失败"
                )
                summaryCallCount++
                outcome.usage?.let { summaryUsages += it }
                if (outcome.content == null) {
                    val reason = outcome.error ?: "摘要响应不可用"
                    FileLogger.w(
                        TAG,
                        "第 ${index + 1}/${chunks.size} 块摘要不可用，整次折叠放弃（不落库）：$reason"
                    )
                    onEvent(
                        AgentEvent.CompactionFailed(
                            "分块摘要失败（第 ${index + 1}/${chunks.size} 块）：$reason"
                        )
                    )
                    return null
                }
                chunkSummaries += outcome.content
            }
            onEvent(
                AgentEvent.CompactionProgressChanged(
                    step = chunks.size + 1,
                    totalSteps = progressTotalSteps
                )
            )
            val merged = mergeChunkSummaries(
                summaries = chunkSummaries,
                ledgerEntries = ledgerEntries,
                summaryProvider = summaryProvider,
                sessionId = sessionId,
                summaryWindowTokens = summaryWindowTokens
            )
            summaryCallCount += merged.callCount
            summaryUsages += merged.usages
            if (merged.content == null) {
                val reason = merged.error ?: "摘要合并失败"
                FileLogger.w(TAG, "分块摘要合并失败，整次折叠放弃（不落库）：$reason")
                onEvent(AgentEvent.CompactionFailed("分块摘要合并失败：$reason"))
                return null
            }
            summaryResponse = merged.content
        }

        val durationMillis = (SystemClock.elapsedRealtime() - summaryStartElapsed).toInt()
        val usage = aggregateUsage(summaryUsages)

        FileLogger.i(
            TAG,
            "上下文压缩完成，摘要长度：${summaryResponse.length} 分块数=${chunks.size} " +
                "摘要调用次数=$summaryCallCount"
        )

        val markerId = UUID.randomUUID().toString()
        val compactedId = UUID.randomUUID().toString()
        // 账本固定小节由代码追加：不依赖模型在摘要里复述，也不新增表，随摘要正文一起落库。
        val ledgerSection = renderFileLedger(ledgerEntries)
        val summaryForStorage =
            if (ledgerSection.isBlank()) summaryResponse else "$summaryResponse\n\n$ledgerSection"
        val markerMessage = AgentMessage.UserMessage(
            id = markerId,
            content = CONTEXT_COMPACTION_MARKER
        )
        val compactedMessage = AgentMessage.AssistantMessage(
            id = compactedId,
            content = summaryForStorage,
            toolCalls = emptyList()
        )

        // 摘要响应校验二：换上去的结果必须**真的更小**。摘要写长了（模型不听话）、或保留区
        // 本来就占满窗口时，折完反而更大——那就白花一次模型调用、还换来一份更差的历史。
        // 只算不算、不发请求；不省就不提交。
        // 旧摘要块是冻结的独立块：折完的内存态视图 = 旧块 S0..Sk（原样保留）+ 新块 + tail。
        val preservedBlocks = leadingCompactionPairs(head)
        val compactedResult = preservedBlocks + listOf(markerMessage, compactedMessage) + tail
        val compactedTokens = TokenEstimator.estimateMessages(compactedResult)
        if (compactedTokens >= estimatedTokens) {
            FileLogger.w(
                TAG,
                "压缩没换来空间，放弃本次压缩（不落库、不标记 head）：折叠前约 $estimatedTokens tokens，" +
                    "折叠后约 $compactedTokens tokens"
            )
            onEvent(
                AgentEvent.CompactionFailed(
                    "压缩未减小上下文（$estimatedTokens → $compactedTokens tokens），已保留原历史"
                )
            )
            return null
        }

        // 活跃摘要是归档文件的引用方：先读一次库，把摘要正文里出现的归档路径交给 prune 避让。
        // 读库失败/无会话时传 null（prune 整体跳过）——宁可多留几个文件，也不能删掉仍被引用的原文。
        var dbEntities: List<AgentMessageEntity>? = null
        var anchorTs: Long? = null
        var headEndTs = 0L
        if (sessionId != null) {
            try {
                val rows = agentMessageDao.getMessagesBySessionOnce(sessionId)
                // 内存态消息与库行的 id 不是同一套：tool 行落库是 "tool_<callId>"、内存态是 <callId>；
                // user/assistant 落库是随机 UUID、内存态常为空串。所以逐级兜底匹配，不能只比 id。
                val anchor = tail.firstNotNullOfOrNull { msg -> resolveRowTimestamp(rows, msg) }
                // 找不到锚点就放弃这次压缩：只不标记却仍插 marker/summary 会新开一个坑——
                // 那两条的落点只能取当前时间，会排在全部历史之后，回放变成「原文 + 摘要 + tail」，
                // head 没被折叠、摘要还被摆到末尾（正是上面注释里警告的「模型把摘要当成自己上一轮」），
                // 下一轮又会再次触发摘要调用。所以宁可这轮不压（重复总比丢好），直接放弃。
                if (anchor == null) {
                    FileLogger.w(TAG, "压缩时匹配不到 tail 对应的库行，放弃本次压缩，会话 $sessionId")
                    onEvent(AgentEvent.CompactionFailed("无法定位保留区起点，已跳过本次压缩"))
                    return null
                }
                dbEntities = rows
                anchorTs = anchor
                val markerTs = (anchor - 2).coerceAtLeast(1L)
                // 覆盖边界 = head 最后一条消息的库行时间戳：该边界之前的原文由本块代表，
                // 回放不再重复带上。匹配不到 head 末尾时退到 marker 前一刻，多回放一次好过丢。
                headEndTs = head.lastOrNull()
                    ?.let { resolveRowTimestamp(rows, it) }
                    ?: (markerTs - 1).coerceAtLeast(0L)
            } catch (e: Exception) {
                FileLogger.e(TAG, "读取会话消息失败，放弃本次压缩", e)
                return null
            }
        }
        // 归档目录跨会话共享，引用要按「所有会话的活跃摘要」判：只查本会话会误删别的会话仍在引用的文件。
        val referencedArchiveFiles = if (sessionId == null) {
            null
        } else {
            try {
                referencedArchiveFileNames(agentMessageDao.getActiveSummaryContents())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取活跃摘要引用失败，本次跳过归档清理: ${e.message}")
                null
            }
        }
        // 省空间校验通过后才归档：压缩没换来空间时历史并未离开上下文，提前归档只会留下
        // 无人引用的孤儿文件。归档失败时 archivePath 为 null，摘要照常落库。
        val archivePath = compactedHistoryArchive.archive(sessionId, head, referencedArchiveFiles)
        val summaryWithArchive = if (archivePath != null) {
            "$summaryResponse\n\n（被折叠的历史原文已存档：$archivePath ，需要核对更早的细节时可读取该文件。）"
        } else {
            summaryResponse
        }
        // 固定小节放最末：它是代码生成的账本，不依赖模型输出，也不会被模型的收尾句挤到中间。
        val summaryContent = if (ledgerSection.isBlank()) {
            summaryWithArchive
        } else {
            "$summaryWithArchive\n\n$ledgerSection"
        }
        val finalMessage = compactedMessage.copy(content = summaryContent)
        val finalResult = preservedBlocks + listOf(markerMessage, finalMessage) + tail

        // 折叠后的摘要链状态（记账用，见下文日志）：声明在函数体顶层——
        // 落库块在 if(sessionId) 里，而记账日志在它之后的外层，声明在块内会作用域不可见（CI 已踩过）。
        var chainBlockCount = 0
        var latestBoundary = 0L
        var stablePrefixTokens = 0

        if (sessionId != null) {
            try {
                val rows = dbEntities.orEmpty()
                val anchor = anchorTs ?: return null
                // marker + 摘要同一个事务落库：摘要写失败时 marker 一并回滚，
                // 不会留下孤立 marker（回放会把它当成用户刚问过的那句）。
                // head 不标 isCompacted，不存在「标了却没有摘要顶上」的丢失面；
                // 插入失败只意味着本轮没折成，原文仍在窗口里。
                // 摘要块落点仍在 tail 之前：回放/UI 顺序 = 摘要链 → 剩余窗口。
                val markerTs = (anchor - 2).coerceAtLeast(1L)
                val summaryTs = markerTs + 1
                val markerRow = AgentMessageEntity(
                    id = markerId,
                    sessionId = sessionId,
                    role = MessageRole.USER.name,
                    content = CONTEXT_COMPACTION_MARKER,
                    timestamp = markerTs,
                    isCompactionMarker = true
                )
                val summaryRow = AgentMessageEntity(
                    id = compactedId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT.name,
                    content = finalMessage.content,
                    timestamp = summaryTs,
                    isContextSummary = true,
                    foldsUpToTimestamp = headEndTs
                )
                agentMessageDao.insertCompactionPair(markerRow, summaryRow)
                FileLogger.i(TAG, "已持久化摘要块到数据库，会话 $sessionId，覆盖边界=$headEndTs")

                // 记账用：折叠后的摘要链状态。链内块数 = 未淘汰的摘要行数（含本次新插的），
                // 前缀稳定段 = 摘要块链部分的估算 token（system + 工具定义开销由模块侧另计）。
                // 查库而不是从内存列表数：上一轮的块只落在库里，内存态 messages 不一定还带着。
                chainBlockCount = rows.count { it.isContextSummary && !it.isCompacted } + 1
                latestBoundary = headEndTs
                stablePrefixTokens = TokenEstimator.estimateText(
                    rows.filter { it.isContextSummary && !it.isCompacted }
                        .joinToString("\n") { it.content }
                ) + TokenEstimator.estimateMessage(compactedMessage)
            } catch (e: Exception) {
                // 事务已把本次两行一并回滚：不留孤立 marker，也无需再手工补偿。
                FileLogger.e(TAG, "持久化压缩结果失败，放弃本次压缩（不标记 head）", e)
                return null
            }
        }

        // 折叠记账：只写数值与身份，正文一律不打。目的是给下一轮优化（摘要成本、缓存命中、
        // 裁剪力度、折叠效果）留下可横向对比的数据，不参与任何判定。
        // 硬线/窗口由模块透传；「折叠后是否仍超硬线」由模块那条既有日志负责（此处只给折叠后的本地估算）。
        // `usage`（= 本次摘要调用的响应）在上面已声明，这里直接复用。
        val usageText = if (usage == null ||
            (usage.inputTokens == 0 && usage.outputTokens == 0 && usage.cachedInputTokens == 0)
        ) {
            "输入=无回报 输出=无回报 缓存=无回报"
        } else {
            "输入=${usage.inputTokens} 输出=${usage.outputTokens} " +
                "缓存读取=${usage.cachedInputTokens} 缓存写入=${usage.cacheCreationTokens} " +
                "缓存命中=${if (usage.cachedInputTokens > 0) "是" else "否"}"
        }
        FileLogger.i(
            TAG,
            "折叠记账 会话=${sessionId ?: "-"} 折叠前消息估算=$estimatedTokens（仅消息，不含 system/工具开销）" +
                " 触发判定=${if (decisionTokens > 0) decisionTokens.toString() else "未提供"}" +
                " 硬线=$hardThreshold 窗口=$contextLimit " +
                "折叠后=$compactedTokens " +
                "$usageText " +
                "耗时=${durationMillis}ms 折叠条数=${head.size} 保留条数=${tail.size} " +
                "分块数=${chunks.size} 摘要调用次数=$summaryCallCount 涉及文件=${ledgerEntries.size} " +
                "摘要长度=${summaryResponse.length}"
        )
        // 摘要链观测（阶段 0）：块数/边界/前缀稳定段从库行计（查询发生在插入前，块数 +1 补本次）。
        if (chainBlockCount > 0) {
            // 摘要链观测值（阶段 0 新增）：块数 k 与最新边界用来观察合并频率（阶段 3 引入合并时的参数依据）；
            // 「前缀稳定段」估算 = 摘要块链部分（S0..Sk）的 token，用于验证缓存收益
            // （system + 工具定义的开销由请求侧另计，不在本地估算内）。
            FileLogger.i(
                TAG,
                "摘要链观测 会话=${sessionId ?: "-"} 块数=$chainBlockCount 最新边界=$latestBoundary " +
                    "前缀稳定段估算=${stablePrefixTokens}tokens"
            )
        }

        return finalResult
        } finally {
            sessionLock?.unlock()
        }
    }

    /**
     * 摘要响应完整性闸门（折叠与链合并共用）：空 / 无用量 / 带工具调用 / 截断 / 中止，
     * 一律视为不可用。判据全部来自已有响应对象，不发额外请求。
     */
    private fun summaryIssueOf(summaryResponse: String, usage: AIResponse?): String? = when {
        summaryResponse.isBlank() -> "摘要为空"
        usage == null -> "摘要响应未返回用量信息"
        usage.toolCalls.isNotEmpty() ->
            "摘要响应里带了 ${usage.toolCalls.size} 个工具调用（压缩请求不带工具）"
        usage.isTruncated -> "摘要响应被输出上限截断（stopReason=${usage.stopReason}）"
        usage.isAborted -> "摘要响应被服务端中止（stopReason=${usage.stopReason}）"
        else -> null
    }

    /**
     * 一次摘要类调用（分块摘要或合并）的归一结果。
     * [content] 为 null 时 [error] 给出原因；[usage] 用于汇总记账。
     */
    private data class SummaryCallOutcome(
        val content: String?,
        val usage: AIResponse?,
        val error: String?
    )

    private data class SummaryMergeOutcome(
        val content: String?,
        val usages: List<AIResponse>,
        val callCount: Int,
        val error: String?
    )

    /**
     * 发一次压缩类请求并记一笔 kind=compaction 的调用记录。
     *
     * 完整性闸门（空 / 无用量 / 带工具调用 / 截断 / 中止）在每次调用后立即判定：
     * 分块路径上任何一块不合格都不能拿「半份摘要」去换整段历史。
     */
    private suspend fun requestSummaryCall(
        summaryProvider: AIProvider,
        sessionId: String?,
        requestMessages: List<AgentMessage>,
        failureLog: String
    ): SummaryCallOutcome {
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callCompleted = false
        var response: AIResponse? = null
        val content = try {
            val completed = summaryProvider.complete(
                systemPrompt = SUMMARY_SYSTEM_PROMPT,
                messages = requestMessages,
                tools = emptyList(),
                // 压缩请求一次性：它的前缀（整段被折叠的历史）不会被下一次折叠命中
                // （下一次 head 起点不同），写缓存只会白付 1.25×。
                cacheTail = false
            )
            response = completed
            callCompleted = true
            completed.content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            FileLogger.e(TAG, failureLog, e)
            recordCompactionCall(
                summaryProvider = summaryProvider,
                sessionId = sessionId,
                callStartWall = callStartWall,
                durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt(),
                callUsage = null,
                callCompleted = false,
                callError = callError
            )
            return SummaryCallOutcome(null, null, callError)
        }

        val usage = response
        recordCompactionCall(
            summaryProvider = summaryProvider,
            sessionId = sessionId,
            callStartWall = callStartWall,
            durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt(),
            callUsage = usage,
            callCompleted = callCompleted,
            callError = callError
        )
        val issue = summaryIssueOf(content, usage)
        return SummaryCallOutcome(
            content = if (issue == null) content else null,
            usage = usage,
            error = issue
        )
    }

    /**
     * 分块摘要合并：按顺序把各块摘要归纳成一份。
     *
     * 块数不多时就是一次 `compact-chain-merge` 调用；块数多、摘要长到一次装不下时，
     * 先按预算分组合并，再合并分组结果（层数受 [MAX_SUMMARY_MERGE_DEPTH] 限制）。
     * 任一次调用失败都返回 null——整次折叠不落库，不会留下半条摘要。
     */
    private suspend fun mergeChunkSummaries(
        summaries: List<String>,
        ledgerEntries: List<FileLedgerEntry>,
        summaryProvider: AIProvider,
        sessionId: String?,
        summaryWindowTokens: Int
    ): SummaryMergeOutcome {
        val usages = mutableListOf<AIResponse>()
        var callCount = 0
        var level = summaries
        var depth = 0
        val mergeBudgetTokens = (summaryWindowTokens * SUMMARY_BUDGET_RATIO).toInt()
        while (level.size > 1) {
            if (depth >= MAX_SUMMARY_MERGE_DEPTH) {
                return SummaryMergeOutcome(
                    null, usages, callCount,
                    "摘要合并层数超过上限 $MAX_SUMMARY_MERGE_DEPTH"
                )
            }
            // 分组预算按「带账本」的最坏情况算：最终合并会带账本，中间层不带只会更宽松。
            val probeInstruction = buildMergeInstruction(level.size)
            val probeBudget = (
                mergeBudgetTokens -
                    TokenEstimator.estimateText(probeInstruction + fileLedgerPrompt(ledgerEntries))
                ).coerceAtLeast(1)
            val groups = groupSummaryTexts(level, probeBudget)
            if (groups.size == level.size) {
                return SummaryMergeOutcome(
                    null, usages, callCount,
                    "摘要窗口装不下两份分块摘要（预算 $mergeBudgetTokens tokens）"
                )
            }
            val finalMerge = groups.size == 1
            val next = mutableListOf<String>()
            for (group in groups) {
                if (group.size == 1) {
                    next += group.single()
                    continue
                }
                val outcome = requestMergeCall(
                    summaries = group,
                    ledgerEntries = ledgerEntries,
                    includeLedger = finalMerge,
                    summaryProvider = summaryProvider,
                    sessionId = sessionId
                )
                callCount++
                outcome.usage?.let { usages += it }
                if (outcome.content == null) {
                    return SummaryMergeOutcome(null, usages, callCount, outcome.error)
                }
                next += outcome.content
            }
            level = next
            depth++
        }
        return SummaryMergeOutcome(level.single(), usages, callCount, null)
    }

    private suspend fun requestMergeCall(
        summaries: List<String>,
        ledgerEntries: List<FileLedgerEntry>,
        includeLedger: Boolean,
        summaryProvider: AIProvider,
        sessionId: String?
    ): SummaryCallOutcome {
        val material = summaries.flatMap { text ->
            listOf(
                AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER),
                AgentMessage.AssistantMessage(content = text, toolCalls = emptyList())
            )
        }
        val instruction = buildMergeInstruction(summaries.size) +
            if (includeLedger) fileLedgerPrompt(ledgerEntries) else ""
        return requestSummaryCall(
            summaryProvider = summaryProvider,
            sessionId = sessionId,
            requestMessages = material + listOf(AgentMessage.UserMessage(content = instruction)),
            failureLog = "分块摘要合并调用失败"
        )
    }

    /** 按预算把摘要分组；单份超预算时自成一组（下一轮会因无法收敛而整体放弃）。 */
    private fun groupSummaryTexts(
        summaries: List<String>,
        budgetTokens: Int
    ): List<List<String>> {
        val groups = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentTokens = 0
        for (summary in summaries) {
            val tokens = TokenEstimator.estimateText(summary)
            if (current.isNotEmpty() && currentTokens + tokens > budgetTokens) {
                groups.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(summary)
            currentTokens += tokens
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups
    }

    private fun aggregateUsage(usages: List<AIResponse>): AIResponse? {
        if (usages.isEmpty()) return null
        if (usages.size == 1) return usages.single()
        return AIResponse(
            content = "",
            stopReason = "stop",
            inputTokens = usages.sumOf { it.inputTokens },
            outputTokens = usages.sumOf { it.outputTokens },
            cachedInputTokens = usages.sumOf { it.cachedInputTokens },
            cacheCreationTokens = usages.sumOf { it.cacheCreationTokens }
        )
    }

    private fun promptWithInstruction(file: String, instruction: String): String =
        systemPromptProvider.resolvePrompt(file)
            .replace(LEADING_COMMENT, "")
            .replace("{{INSTRUCTION}}", instruction)

    /** 分块摘要指令：只覆盖本块，长度压到合并预算能装下（块数 × 字数 ≪ 摘要窗口）。 */
    private fun buildChunkSummaryInstruction(index: Int, total: Int): String =
        promptWithInstruction(
            COMPACT_PROMPT_FILE,
            "请只总结下面这段对话历史（第 ${index + 1}/$total 块，整段被折叠历史的一部分）。" +
                "只输出这一块的接手摘要，不要合并其它块的内容；控制在 $CHUNK_SUMMARY_CHAR_BUDGET 字以内，" +
                "本块出现的硬约束、禁止项、例外条件与文件路径必须保留。"
        )

    private fun buildMergeInstruction(count: Int): String =
        promptWithInstruction(
            MERGE_PROMPT_FILE,
            "请把下面 $count 份分块摘要按时间先后归纳成一份新的接手摘要。" +
                "这些摘要来自同一段被折叠历史的不同分块，按顺序排列。"
        )

    /** 记录一次压缩类 LLM 调用（折叠与链合并共用，kind=compaction），失败不影响主流程。 */
    private suspend fun recordCompactionCall(
        summaryProvider: AIProvider,
        sessionId: String?,
        callStartWall: Long,
        durationMillis: Int,
        callUsage: AIResponse?,
        callCompleted: Boolean,
        callError: String?
    ) {
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
    }

    /**
     * 摘要链现状（折叠记账与链合并判定共用）。
     *
     * 只统计新语义块（`foldsUpToTimestamp > 0` 且未淘汰）：legacy 单块（边界为 0）在回放侧走
     * 兼容路径（只取最新一个、不抬窗口边界），既不参与边界计算也不能被合并——合并它会把
     * 「head 已标 isCompacted 的旧语义历史」的回放责任转移到一个新块上，而新块的窗口裁剪
     * 规则与旧语义不一致，回退面算不清。
     */
    data class ChainStatus(
        /** 未淘汰的新语义块数（marker+summary 各一行，块数按 summary 行计）。 */
        val blockCount: Int,
        /** 最大的覆盖边界：该边界之前的原文已由摘要链代表。 */
        val latestBoundary: Long,
        /** 摘要块链（S0..Sk）的估算 token，用于对照「摘要区占窗口 15%」的合并线。 */
        val summaryTokens: Int,
        /** 未淘汰的新语义块（按 timestamp 升序），每块 = marker 行（可空）+ summary 行。 */
        val blocks: List<Block>
    ) {
        data class Block(val marker: AgentMessageEntity?, val summary: AgentMessageEntity)
    }

    /** 读库统计摘要链现状；库读取失败返回 null（调用方跳过合并，不影响主流程）。 */
    suspend fun summaryChainStatus(sessionId: String): ChainStatus? {
        if (sessionId.isBlank()) return null
        val rows = runCatching { agentMessageDao.getMessagesBySessionOnce(sessionId) }
            .getOrElse { return null }
        val blocks = mutableListOf<ChainStatus.Block>()
        for ((index, row) in rows.withIndex()) {
            if (!row.isContextSummary || row.isCompacted || row.foldsUpToTimestamp <= 0L) continue
            val marker = rows.getOrNull(index - 1)?.takeIf { it.isCompactionMarker && !it.isCompacted }
            blocks.add(ChainStatus.Block(marker, row))
        }
        return ChainStatus(
            blockCount = blocks.size,
            latestBoundary = blocks.maxOfOrNull { it.summary.foldsUpToTimestamp } ?: 0L,
            summaryTokens = TokenEstimator.estimateText(blocks.joinToString("\n") { it.summary.content }),
            blocks = blocks
        )
    }

    /**
     * 链合并：把摘要链最旧的 [blockCount] 个块（含各自的 marker 行）归纳成**一块**新摘要。
     *
     * 与 [compact] 的差异：输入材料不是原始对话而是旧摘要本身；覆盖边界取被合并块中最大的
     * `foldsUpToTimestamp`；提交动作是把被合并块行（marker + summary）标 `isCompacted = 1`
     * （[AgentMessageDao.markChainBlocksCompacted]，回放侧已过滤）并追加一对新的
     * marker + summary 行——这是设计 §4 里「唯一动前端标记的操作」。
     *
     * 归档取舍：**不重复归档**。被合并块的覆盖范围当初折叠时已各自归档过
     * （每次 [compact] 都把 head 原文写进 CompactedHistoryArchive，路径附在摘要正文里），
     * 合并产出的新摘要把「各份摘要里引用的归档路径」逐条保留（见 compact-chain-merge.md），
     * 原文退路不变；再归档一次只会重复消耗存储配额（归档目录有 32MB/30 文件的保留上限）。
     *
     * 失败语义：任何一步失败都返回 null；新块两行与旧块标记在同一个事务里（[AgentMessageDao.insertChainMergePair]），
     * 标记未命中时事务整体回滚，不会留下孤立新块，也不会出现「旧块已标、新摘要缺失」。
     * 调用方（CompactionModule）在硬折叠判定之前调用本方法，失败只记日志、下轮再试，不影响主折叠。
     *
     * @return 合并后的消息视图；不可合并（块不够、摘要不可用、不省空间）返回 null。
     */
    suspend fun mergeOldestChainBlocks(
        messages: List<AgentMessage>,
        chain: ChainStatus,
        summaryProvider: AIProvider,
        sessionId: String?,
        blockCount: Int
    ): List<AgentMessage>? {
        val blocks = chain.blocks.take(blockCount)
        // 合并单块没有意义（产出的还是一块），块数 < 2 直接不合并——调用方（tryChainMerge）
        // 用 mergeCount>0 已拦过一次，这里再拦一层：直接单测本函数时也保持同样语义。
        if (blocks.size < 2 || sessionId == null) return null
        val boundary = blocks.maxOf { it.summary.foldsUpToTimestamp }

        // 合并请求的输入材料：各旧块的 marker + summary 行 + 一条合并指令。
        // marker 行保留：模型要能看出「这是 N 份独立摘要」，而不是一份散乱的历史。
        val material = blocks.flatMap { block ->
            buildList {
                block.marker?.let {
                    add(AgentMessage.UserMessage(id = it.id, content = it.content))
                }
                add(AgentMessage.AssistantMessage(id = block.summary.id, content = block.summary.content))
            }
        }
        if (material.isEmpty()) return null
        val instruction = systemPromptProvider.resolvePrompt(MERGE_PROMPT_FILE)
            .replace(LEADING_COMMENT, "")
            .replace("{{INSTRUCTION}}", "请把下面 ${blocks.size} 份接手摘要归纳成一份新的接手摘要。")
        val requestMessages = material + listOf(AgentMessage.UserMessage(content = instruction))

        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callCompleted = false
        var callUsage: AIResponse? = null
        val mergeResponse = try {
            val response = summaryProvider.complete(
                systemPrompt = "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。",
                messages = requestMessages,
                tools = emptyList(),
                // 合并同样是一次性调用：被合并块标掉后，这段输入前缀不会再出现。
                cacheTail = false
            )
            callUsage = response
            callCompleted = true
            response.content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            FileLogger.w(TAG, "链合并摘要调用失败，本轮放弃合并（不影响主折叠）", e)
            recordCompactionCall(
                summaryProvider, sessionId, callStartWall,
                (SystemClock.elapsedRealtime() - callStartElapsed).toInt(), callUsage, callCompleted, callError
            )
            return null
        }
        val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
        recordCompactionCall(
            summaryProvider, sessionId, callStartWall, durationMillis,
            callUsage, callCompleted, callError
        )

        // 与折叠同一套完整性闸门：半份合并摘要换掉 N 份旧摘要同样是负收益。
        val usage = callUsage
        val issue = summaryIssueOf(mergeResponse, usage)
        if (issue != null) {
            FileLogger.w(TAG, "链合并摘要响应不可用，放弃本次合并（不标记任何行）：$issue")
            return null
        }

        // 新块落点：最旧被合并块之前（各 -1 防撞），保证回放链内顺序稳定——新块（代表最旧历史）
        // 排在最前。同 timestamp 不冲突（主键是 id，REPLACE 只按 id）；落在更早的普通行附近
        // 也不影响窗口裁剪：回放的窗口起点只看各块的 foldsUpToTimestamp，不看块行自身的时间戳。
        val oldestTs = blocks.first().marker?.timestamp ?: blocks.first().summary.timestamp
        val markerTs = (oldestTs - 1).coerceAtLeast(1L)

        val markerId = UUID.randomUUID().toString()
        val summaryId = UUID.randomUUID().toString()
        val markerMessage = AgentMessage.UserMessage(id = markerId, content = CONTEXT_COMPACTION_MARKER)
        val summaryMessage = AgentMessage.AssistantMessage(id = summaryId, content = mergeResponse, toolCalls = emptyList())

        // 「真的更小了」闸门（与 compact 同源）：合并后的视图必须小于当前视图，否则不提交。
        // 当前视图 = 现有消息；合并后视图 = 去掉被合并块行 + 新块对。
        // 合并后的内存视图与回放一致：新块对在最前（摘要链前置），其余消息原序跟上。
        val removedIds = blocks.flatMap { listOfNotNull(it.marker?.id, it.summary.id) }.toSet()
        val mergedResult = listOf(markerMessage, summaryMessage) +
            messages.filter { it.id !in removedIds }
        val beforeTokens = TokenEstimator.estimateMessages(messages)
        val afterTokens = TokenEstimator.estimateMessages(mergedResult)
        if (afterTokens >= beforeTokens) {
            FileLogger.w(
                TAG,
                "链合并没换来空间，放弃本次合并（不标记任何行）：合并前约 $beforeTokens tokens，合并后约 $afterTokens tokens"
            )
            return null
        }

        // 提交语义与原先一致：新块与旧块标记在同一个事务里，要么全成、要么全不成。
        // 先插新块后标旧块仍需保留在事务方法内部——若先标后插且插入失败，
        // 会出现「旧块已标、新摘要缺失」的丢失面；事务回滚能盖住它，但顺序不改更安全。
        try {
            agentMessageDao.insertChainMergePair(
                marker = AgentMessageEntity(
                    id = markerId,
                    sessionId = sessionId,
                    role = MessageRole.USER.name,
                    content = CONTEXT_COMPACTION_MARKER,
                    timestamp = markerTs,
                    isCompactionMarker = true
                ),
                summary = AgentMessageEntity(
                    id = summaryId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT.name,
                    content = summaryMessage.content,
                    timestamp = markerTs + 1,
                    isContextSummary = true,
                    foldsUpToTimestamp = boundary
                ),
                sessionId = sessionId,
                supersededIds = removedIds.toList()
            )
        } catch (e: CompactionChainBlocksNotMarkedException) {
            // 旧块可能刚被回退逻辑动过：事务已把新块一并回滚，链保持原样。
            FileLogger.w(TAG, "链合并标记旧块未命中任何行，事务已回滚，放弃本次合并", e)
            return null
        } catch (e: Exception) {
            FileLogger.e(TAG, "链合并落库失败，事务已回滚（旧块未标）", e)
            return null
        }

        FileLogger.i(
            TAG,
            "链合并记账 会话=$sessionId 合并前块数=${chain.blockCount} 合并批=${blocks.size} " +
                "合并后块数=${chain.blockCount - blocks.size + 1} 合并前摘要估算=${chain.summaryTokens}tokens " +
                "合并后摘要估算=${TokenEstimator.estimateText(mergeResponse)}tokens " +
                "新边界=$boundary 耗时=${durationMillis}ms 摘要长度=${mergeResponse.length} " +
                "输入估算=${TokenEstimator.estimateMessages(material)}tokens"
        )
        return mergedResult
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

    /** 把单条消息的正文压到 [budgetTokens] 以内；产出是发送前的内存副本（不落库）。 */
    private fun truncateMessage(message: AgentMessage, budgetTokens: Int): AgentMessage {
        val currentTokens = TokenEstimator.estimateMessage(message)
        if (currentTokens <= budgetTokens) return message
        // 按该消息自己的 token/字符密度等比换算，不用固定系数：中文 1 字 ≈ 1 token、
        // 拉丁 4 字符 ≈ 1 token，固定系数会把中文消息算得截不动。
        val ratio = budgetTokens.toDouble() / currentTokens

        /** 工具参数没有单一「文本」，预算按各字段正文长度之和算，故另开一个按长度的入口。 */
        fun limitOfLength(length: Int): Int =
            (length * ratio).toInt().coerceAtLeast(MIN_TRUNCATE_CHARS)

        fun limitOf(text: String): Int = limitOfLength(text.length)

        return when (message) {
            is AgentMessage.ToolResultMessage -> {
                val current = message.result
                if (current.length <= limitOf(current)) message
                else message.copy(result = headTailTrim(current, limitOf(current)))
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
                val trimmedCalls = message.toolCalls.map { call ->
                    // 预算按参数正文的长度算，不能用 JsonPrimitive 的序列化形态：转义把换行算成
                    // 两个字符，总量偏大近一倍，摘录预算跟着虚高（同 [argumentText] 那个坑）。
                    val argsChars = call.arguments.values.sumOf { value -> value.argumentText().length }
                    rebuildToolCallArguments(call, limitOfLength(argsChars))
                }
                if (trimmedContent == message.content && trimmedCalls == message.toolCalls) message
                else message.copy(content = trimmedContent, toolCalls = trimmedCalls)
            }
        }
    }

    /**
     * 工具参数按字段重建：把「能重新读回来」的大块正文换成可读摘录（头尾逐字 + 省略说明 +
     * readFile 回读指针），其它超长值头尾截断，小字段（路径、命令开头这类定位信息）原样保留。
     *
     * 结果直接写进本副本的 `arguments`：这是发送前兜底，产出本身不落库。
     * 同一轮内的多次模型调用共用第一份产出（见 CompactionModule 的轮内冻结），旧消息不再重截；
     * 对同一输入重算也是幂等的，所以缓存与重算得到的是同一串字节。
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
        return if (changed) call.copy(arguments = rebuilt) else call
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
    private fun List<AgentMessage>.trimLeadingForCompaction(): List<AgentMessage> =
        dropWhile { it !is AgentMessage.UserMessage }.stripForSummary()

    /**
     * 去掉图片、截短工具输出（只改发送给摘要模型的副本）。
     *
     * 分块路径上非首块不能像 [trimLeadingForCompaction] 那样丢掉开头的 assistant 消息：
     * 块起点是预算切出来的，可能正好落在 assistant 上，丢掉就等于这段历史没进摘要。
     */
    private fun List<AgentMessage>.stripForSummary(): List<AgentMessage> = map { msg ->
        when {
            msg is AgentMessage.UserMessage && msg.images.isNotEmpty() -> msg.copy(images = emptyList())
            msg is AgentMessage.ToolResultMessage && msg.result.length > TRIM_FOR_SUMMARY_CHARS ->
                msg.copy(
                    result = msg.result.take(TRIM_FOR_SUMMARY_CHARS) +
                        "\n[Tool output truncated for compaction]"
                )
            else -> msg
        }
    }

    /**
     * 单条消息本身就超过摘要窗口预算时，在发送副本上按预算截断。
     *
     * 消息不可再拆，不截断的话这一块请求必然超窗、整次折叠失败；原文仍在归档里，
     * 摘要只需要能读到可辨认的头尾。
     */
    private fun List<AgentMessage>.fitForSummaryBudget(budgetTokens: Int): List<AgentMessage> {
        if (budgetTokens <= 0) return this
        return map { msg ->
            if (TokenEstimator.estimateMessage(msg) > budgetTokens) {
                truncateMessage(msg, budgetTokens)
            } else {
                msg
            }
        }
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

    private fun String.cleanSummary(): String =
        removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart()

    /**
     * 按摘要模型窗口预算截断 head：从新到旧保留消息，超预算丢弃更旧的消息。
     * 预算与记账都用 [TokenEstimator] 的估算 token（不是字符数），并预留 30% 给摘要提示词与旧摘要。
     */
    private fun List<AgentMessage>.truncateForSummaryWindow(contextTokens: Int): List<AgentMessage> {
        if (isEmpty()) return this
        val budgetTokens = (contextTokens * SUMMARY_BUDGET_RATIO).toInt()
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
 * 分块摘要的块数上限。
 *
 * 每多一块就多一次摘要调用，而最终摘要仍只有 1200 字预算——超过 8 块后，各块对最终摘要的
 * 边际贡献趋近于 0，成本却线性上涨。8 块 × 每块 70% 摘要窗口 ≈ 5.6 个摘要窗口的原文，
 * 足以覆盖一次主窗口折叠（主窗口通常是摘要窗口的 3~6 倍）；更多历史本轮不折，原样留在
 * 保留区（不丢），等后面的折叠再处理。
 */
internal const val MAX_SUMMARY_CHUNKS = 8

/**
 * 按 token 预算把待摘要原文切成有序块（纯函数，不发请求）。
 *
 * 规则：
 * - 单块累计不超 [budgetTokens]；单条消息本身就超预算时自成一块（消息不可拆）。
 * - ToolResultMessage 一律并入当前块，绝不作为新块的第一条：它必须紧跟带 toolCalls 的
 *   assistant，否则分块请求本身就是一段孤儿消息，API 直接 400。
 * - 块数超过 [maxChunks] 时只保留最旧的这些块，剩余消息不折（调用方把切点收回，原文留在 tail）。
 *   截断点后紧邻的 tool 结果一并并入最后一块，同样是为了不留下孤儿 tool 消息。
 */
internal fun splitIntoSummaryChunks(
    messages: List<AgentMessage>,
    budgetTokens: Int,
    maxChunks: Int = MAX_SUMMARY_CHUNKS
): List<List<AgentMessage>> {
    if (messages.isEmpty()) return emptyList()
    if (budgetTokens <= 0 || maxChunks <= 0) return listOf(messages)

    val chunks = mutableListOf<MutableList<AgentMessage>>()
    var current = mutableListOf<AgentMessage>()
    var currentTokens = 0
    for (message in messages) {
        val tokens = TokenEstimator.estimateMessage(message)
        val mustJoinCurrent = current.isEmpty() || message is AgentMessage.ToolResultMessage
        if (!mustJoinCurrent && currentTokens + tokens > budgetTokens) {
            chunks.add(current)
            current = mutableListOf()
            currentTokens = 0
        }
        current.add(message)
        currentTokens += tokens
    }
    if (current.isNotEmpty()) chunks.add(current)
    if (chunks.size <= maxChunks) return chunks

    val limited = chunks.take(maxChunks).toMutableList()
    var covered = limited.sumOf { it.size }
    while (covered < messages.size && messages[covered] is AgentMessage.ToolResultMessage) {
        limited.last().add(messages[covered])
        covered++
    }
    return limited
}

/**
 * 「去掉压缩块后」的前 [coveredFiltered] 条消息，对应回原 head 的哪个下标。
 *
 * 块数达到上限时要把 splitIndex 收回到最后一块末尾，而块是在 [removeCompactionPairs]
 * 之后切的；这里按同一套过滤规则走一遍，保证覆盖边界与归档范围指向同一段原文。
 */
internal fun indexAfterFilteredMessages(head: List<AgentMessage>, coveredFiltered: Int): Int {
    var filtered = 0
    var index = 0
    while (index < head.size && filtered < coveredFiltered) {
        val current = head[index]
        val next = head.getOrNull(index + 1)
        when {
            current is AgentMessage.UserMessage &&
                current.content == CONTEXT_COMPACTION_MARKER &&
                next is AgentMessage.AssistantMessage -> index += 2

            current is AgentMessage.AssistantMessage &&
                current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX) -> index++

            else -> {
                filtered++
                index++
            }
        }
    }
    return index
}

/**
 * 调整拆分索引，确保 tail 不是以 ToolResultMessage 开头。
 *
 * OpenAI API 要求 role: "tool" 消息必须紧接在包含对应 tool_calls 的 assistant 消息之后。
 * 如果 tail 以 ToolResultMessage 开头，压缩后其前面的 assistant 消息（摘要）不含 toolCalls，
 * 该 tool 消息就变成了"孤立"的，API 会报 400 错误。
 */
internal fun adjustSplitIndex(messages: List<AgentMessage>, initialSplitIndex: Int): Int {
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

/**
 * head 开头的连续摘要块（marker+summary 对与 legacy 前缀摘要）：新语义下它们是冻结的独立块，
 * 折叠时原样保留在折叠结果的前缀里（新块追加在它们之后），不再被新摘要吸收。
 */
internal fun leadingCompactionPairs(messages: List<AgentMessage>): List<AgentMessage> {
    val blocks = mutableListOf<AgentMessage>()
    var index = 0
    while (index < messages.size) {
        val current = messages[index]
        val next = messages.getOrNull(index + 1)
        if (
            current is AgentMessage.UserMessage &&
            current.content == CONTEXT_COMPACTION_MARKER &&
            next is AgentMessage.AssistantMessage
        ) {
            blocks.add(current)
            blocks.add(next)
            index += 2
            continue
        }
        if (
            current is AgentMessage.AssistantMessage &&
            current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)
        ) {
            blocks.add(current)
            index++
            continue
        }
        break
    }
    return blocks
}

/**
 * 摘要正文长度的保守上界（token）。
 *
 * 折叠收益要按「最坏的摘要」预估：摘要模型被要求全文 ≤1200 字（见 `assets/prompts/agent/compact-summary.md`
 * 的长度预算），中文约 1 字 1 token；再加上存档提示、markdown 结构与「模型轻微超写」的余量，取 1600。
 * 方向必须偏大：估小了会把「实际更大」的折叠当成更小而放行，最终判据仍然会整体放弃这一轮；
 * 估大只是多折几条原文进 head（它们本就该折）。
 */
internal const val SUMMARY_TOKEN_ESTIMATE = 1_600

/**
 * 自适应收缩最多迭代几轮。
 *
 * 单轮已经按 token 差值一次挪到位，剩下的轮数只是给「tail 起点落在工具消息上要往前回溯」这类
 * 挪不动 / 挪少了的情况兜底；再多也只会在同样的地板上打转。
 */
internal const val MAX_SPLIT_SHRINK_ROUNDS = 3

/**
 * 保留区地板（条数：一条用户消息 + 助手回复 + 一轮工具调用与结果），也就是「当前这一轮发生了什么」
 * 的最小单元。再往下缩，接手者连正在做的事都看不到，摘要再准也接不上。
 */
internal const val MIN_TAIL_MESSAGES = 4

/**
 * 保留区地板（token）。
 *
 * 最小档位的保留预算 [com.aicode.feature.settings.domain.model.ModelContextPolicy.MIN_PRESERVE_RECENT_TOKENS]
 * 是 2000，这里允许最多压到它的一半：更早的内容由摘要接手，但最近半档必须仍是原文，
 * 否则「刚说过的话」要靠摘要转述，精度不够。
 */
internal const val MIN_TAIL_TOKENS = 1_000

/** [convergeSplitIndex] 的结果：收缩后的 tail 起点与收敛轮数（0 表示原切点已经是能省空间的）。 */
internal data class SplitShrink(val splitIndex: Int, val rounds: Int)

/**
 * 折叠后的预计大小（token）：旧摘要块 + 标记 + 新摘要（取保守上界 [SUMMARY_TOKEN_ESTIMATE]）+ tail。
 *
 * 与 [ContextCompactor.compact] 末尾那道最终判据算的是同一组量，区别只在于真实摘要正文此刻还不存在，
 * 用它长度的保守上界代替。
 */
internal fun projectedCompactedTokens(messages: List<AgentMessage>, splitIndex: Int): Int {
    if (splitIndex <= 0 || splitIndex >= messages.size) return TokenEstimator.estimateMessages(messages)
    val head = messages.subList(0, splitIndex)
    val tail = messages.subList(splitIndex, messages.size)
    return TokenEstimator.estimateMessages(leadingCompactionPairs(head)) +
        TokenEstimator.estimateMessage(AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER)) +
        SUMMARY_TOKEN_ESTIMATE +
        TokenEstimator.estimateMessages(tail)
}

/**
 * 保留区起点自适应收敛：折完之后必须真的比原来小，否则整轮压缩会在最终判据那里被整体放弃，
 * 历史一条不落地留在窗口里——超大会话就是这样永远压不下来的。
 *
 * 触发场景：摘要模型窗口把 head 卡得很小，tail 于是占掉几乎整段历史（tail 起点被顶到很靠前），
 * 折完只是「摘要 + 几乎原样的 tail」。这里把 tail 起点往后挪（多折、少留），直到折完确实更小。
 *
 * 纯函数：只读 [messages] 与本地估算，不发请求、不碰 IO，便于单测。实际落库仍由
 * [ContextCompactor.compact] 的最终判据把守——这里估偏了也只意味着这轮白算一次。
 */
internal fun convergeSplitIndex(
    messages: List<AgentMessage>,
    initialSplitIndex: Int,
    estimatedTokens: Int
): SplitShrink {
    if (initialSplitIndex <= 0 || initialSplitIndex >= messages.size || estimatedTokens <= 0) {
        return SplitShrink(initialSplitIndex, 0)
    }
    var splitIndex = initialSplitIndex
    var rounds = 0
    while (rounds < MAX_SPLIT_SHRINK_ROUNDS) {
        val projected = projectedCompactedTokens(messages, splitIndex)
        if (projected < estimatedTokens) break
        // +1 是要求严格小于：折完与原来持平同样算没省空间。
        val next = shrinkSplitIndex(messages, splitIndex, projected - estimatedTokens + 1)
        if (next <= splitIndex) break
        splitIndex = next
        rounds++
    }
    return SplitShrink(splitIndex, rounds)
}

/**
 * 把 tail 起点往后挪，直到从 tail 里挪走至少 [tokensToRemove] token 或撞到地板。
 *
 * 两道上限：tail 至少留 [MIN_TAIL_MESSAGES] 条、至少留 [MIN_TAIL_TOKENS] token；挪到的位置还不能是
 * 孤立的 tool 结果（[adjustSplitIndex] 会往前回溯到配对的 assistant，可能挪得比预期少）。
 */
private fun shrinkSplitIndex(
    messages: List<AgentMessage>,
    splitIndex: Int,
    tokensToRemove: Int
): Int {
    val limit = minOf(messages.size - MIN_TAIL_MESSAGES, tailFloorIndex(messages))
    if (limit <= splitIndex) return splitIndex
    var candidate = splitIndex
    var removed = 0
    // 按消息累计而不是按位置比例：单条消息可能就占 tail 的大半，逐条挪才不会一步跨过地板。
    while (candidate < limit && removed < tokensToRemove) {
        removed += TokenEstimator.estimateMessage(messages[candidate])
        candidate++
    }
    return adjustSplitIndex(messages, candidate).coerceAtLeast(splitIndex)
}

/** 满足「tail 至少留 [MIN_TAIL_TOKENS]」的最靠后切点：从末尾往前累计到够数为止。 */
private fun tailFloorIndex(messages: List<AgentMessage>): Int {
    var tokens = 0
    for (index in messages.indices.reversed()) {
        tokens += TokenEstimator.estimateMessage(messages[index])
        if (tokens >= MIN_TAIL_TOKENS) return index
    }
    return 0
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

/** 文件账本里的一次访问类型；label 用于摘要末尾固定小节的展示。 */
internal enum class FileLedgerOperation(val label: String) {
    READ("读"),
    WRITE("写"),
    EDIT("改"),
    SEARCH("搜索"),
    LIST("列目录")
}

internal data class FileLedgerEntry(val path: String, val operation: FileLedgerOperation)

/**
 * 账本条数上限：摘要正文本身 ≤1200 字，固定小节最多占一半左右；更多路径是噪声，
 * 真正需要细节时模型会按路径重新 readFile。
 */
internal const val FILE_LEDGER_MAX_ENTRIES = 24

/** 单条路径长度上限：正常容器路径 <100 字符，超长多半是参数异常。 */
internal const val FILE_LEDGER_MAX_PATH_CHARS = 160

internal const val FILE_LEDGER_HEADING = "## 涉及文件（最近访问优先）"

private val FILE_LEDGER_PATH_TOOLS = mapOf(
    "readFile" to FileLedgerOperation.READ,
    "writeFile" to FileLedgerOperation.WRITE,
    "editFile" to FileLedgerOperation.EDIT
)

private val FILE_LEDGER_DIRECTORY_TOOLS = mapOf(
    "search" to FileLedgerOperation.SEARCH,
    "list" to FileLedgerOperation.LIST
)

/** rg 中带独立参数值的选项：解析 search 的路径时要跳过它们的值，避免把 pattern 当目录。 */
private val RG_OPTIONS_WITH_VALUE = setOf(
    "-e", "--regexp", "-f", "--file", "-g", "--glob", "-t", "--type", "-T", "--type-not",
    "-m", "--max-count", "-A", "--after-context", "-B", "--before-context", "-C", "--context"
)

/** 用选项给 pattern 时，第一个定位参数就是路径，不能再按「第一个是 pattern」丢掉。 */
private val RG_PATTERN_OPTIONS = setOf("-e", "--regexp", "-f", "--file")

/**
 * 从一段历史里的工具调用提取「涉及文件」账本：readFile / writeFile / editFile 的路径，
 * 以及 search / list 的目录。去重后按最近访问排序（最近的在最前）。
 *
 * 只读 assistant 消息的 toolCalls：工具结果里的正文不参与，避免把输出里出现的路径当成访问。
 */
internal fun buildFileLedger(messages: List<AgentMessage>): List<FileLedgerEntry> {
    val latestByPath = LinkedHashMap<String, FileLedgerEntry>()
    for (message in messages) {
        if (message !is AgentMessage.AssistantMessage) continue
        for (call in message.toolCalls) {
            for (entry in ledgerEntriesOf(call)) {
                // 重复访问同一路径时先移除再放回：Map 的插入顺序即「最近访问」顺序。
                latestByPath.remove(entry.path)
                latestByPath[entry.path] = entry
            }
        }
    }
    return latestByPath.values.toList().asReversed().take(FILE_LEDGER_MAX_ENTRIES)
}

/** 合并多段账本（按段从旧到新）：后一段的访问更新，整体仍按最近访问排序。 */
internal fun mergeFileLedgers(ledgers: List<List<FileLedgerEntry>>): List<FileLedgerEntry> {
    val latestByPath = LinkedHashMap<String, FileLedgerEntry>()
    for (ledger in ledgers) {
        for (entry in ledger.asReversed()) {
            latestByPath.remove(entry.path)
            latestByPath[entry.path] = entry
        }
    }
    return latestByPath.values.toList().asReversed().take(FILE_LEDGER_MAX_ENTRIES)
}

/** 渲染成摘要末尾的固定小节；没有记录时返回空串（调用方不加空小节）。 */
internal fun renderFileLedger(entries: List<FileLedgerEntry>): String {
    if (entries.isEmpty()) return ""
    val shown = entries.take(FILE_LEDGER_MAX_ENTRIES)
    return buildString {
        appendLine(FILE_LEDGER_HEADING)
        for (entry in shown) {
            append("- ")
            append(entry.operation.label)
            append("：")
            appendLine(entry.path.take(FILE_LEDGER_MAX_PATH_CHARS))
        }
        if (entries.size > shown.size) {
            appendLine("- （共 ${entries.size} 个，仅列最近 ${shown.size} 个）")
        }
    }.trimEnd()
}

/** 账本作为摘要请求的附加输入；空账本不加任何内容。 */
internal fun fileLedgerPrompt(entries: List<FileLedgerEntry>): String {
    val section = renderFileLedger(entries)
    return if (section.isBlank()) "" else "\n\n$section"
}

private fun ledgerEntriesOf(call: ToolCall): List<FileLedgerEntry> {
    FILE_LEDGER_PATH_TOOLS[call.name]?.let { operation ->
        val path = pathArgumentOf(call.arguments) ?: return emptyList()
        return listOf(FileLedgerEntry(path, operation))
    }
    val operation = FILE_LEDGER_DIRECTORY_TOOLS[call.name] ?: return emptyList()
    val rawArgs = (call.arguments["args"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return directoryTargetsOf(call.name, rawArgs).map { FileLedgerEntry(it, operation) }
}

private fun pathArgumentOf(arguments: Map<String, JsonElement>): String? =
    arguments.entries
        .firstOrNull { it.key.lowercase() in PATH_ARG_KEYS }
        ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * search / list 的参数是 ls / rg 风格的字符串，这里只取「显式写出的路径」：
 * search 的第一个定位参数是 pattern，必须跳过；带独立值的选项（-g/-e 等）的值也跳过。
 */
private fun directoryTargetsOf(toolName: String, rawArgs: String): List<String> {
    val tokens = splitLedgerShellWords(rawArgs)
    val positional = mutableListOf<String>()
    var skipNext = false
    var patternFromOption = false
    for (token in tokens) {
        if (skipNext) {
            skipNext = false
            continue
        }
        if (token.startsWith("-")) {
            if (token in RG_PATTERN_OPTIONS) patternFromOption = true
            if (token in RG_OPTIONS_WITH_VALUE) skipNext = true
            continue
        }
        positional += token
    }
    val candidates =
        if (toolName == "search" && !patternFromOption) positional.drop(1) else positional
    return candidates.filter { looksLikePath(it) }
}

private fun looksLikePath(token: String): Boolean {
    if (token.isBlank()) return false
    return token.startsWith("~") ||
        token == "." ||
        token == ".." ||
        token.startsWith("./") ||
        token.startsWith("../") ||
        token.startsWith("/") ||
        token.contains('/')
}

/** 轻量分词：只处理空白与成对引号，不解释转义；账本只取路径，够用且不会误执行任何东西。 */
private fun splitLedgerShellWords(text: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (ch in text) {
        when {
            quote != null && ch == quote -> quote = null
            quote != null -> current.append(ch)
            ch == '\'' || ch == '"' -> quote = ch
            ch.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

/**
 * 旧软精简（已移除）留下的占位说明（整段替换体）；入口指针化省不下时也退回这一形态。
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
    // 摘录必须显著短于原文：兜底截断的等比换算等调用方都假定换过之后更短。
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
