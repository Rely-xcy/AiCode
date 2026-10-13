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
 * 阈值、档位、硬线判定属于策略，归 [com.aicode.feature.agent.domain.engine.modules.CompactionModule]；
 * 本类只提供两个变换，便于单独验证：
 * - [compact]：调摘要模型折叠早期对话，并把结果落库；
 * - [enforceWindowLimit]：发送前兜底截断，保证不发出超窗请求（不落库）。
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

        /** 送进摘要请求前对单条工具输出的保留上限（只在摘要请求内生效，不改历史）。 */
        const val TRIM_FOR_SUMMARY_CHARS = 3_000

        /** 兜底截断的单条消息下限：再短就没法干活了，宁可让它超窗。 */
        const val MIN_MESSAGE_TOKENS = 64
        const val MIN_TRUNCATE_CHARS = 200

        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"

        /** 链合并（阶段 4）专用提示词：对多份旧摘要再归纳。 */
        const val MERGE_PROMPT_FILE = "agent/compact-chain-merge.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
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
     * 硬压缩：把窗口内最早的原文（head）折叠成一个**新摘要块**，追加到摘要链尾。
     *
     * 持久化：摘要以 marker + assistant 两条追加落库，summary 行带 foldsUpToTimestamp =
     * head 最后一条的 timestamp（覆盖边界）；head 不标 isCompacted、旧摘要不淘汰——
     * 库对「折叠」这个动作而言是纯追加的，已发出的请求前缀（S0..Sk）逐字节稳定。
     * 回放侧（MessagePersistenceUseCase）按块边界裁窗口，被折叠的原文不再重复回放。
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
        // 折叠前的上下文量：提交前要拿它跟折叠后的结果比（不省空间的折叠不提交）。
        val estimatedTokens = TokenEstimator.estimateMessages(messages)
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
        val previousSummary: String? = null
        // 新语义：旧摘要块是冻结的独立块，新块只覆盖本次折掉的原文，不再合并旧摘要
        // （设计 §4.3 第 5 步）。buildSummaryInstruction 的 previous-summary 分支保留，
        // 阶段 3 引入合并时复用；extractPreviousSummary 同理保留但不再被此处调用。
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
        val summaryInstruction = buildSummaryInstruction(previousSummary)
        val summaryRequestMessages = trimmedHead + listOf(
            AgentMessage.UserMessage(content = summaryInstruction)
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
                tools = emptyList(),
                // 压缩请求一次性：它的前缀（整段被折叠的历史）不会被下一次折叠命中
                // （下一次 head 起点不同），写缓存只会白付 1.25×。
                cacheTail = false
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

        // 摘要响应校验一：摘要模型也会被自己的输出上限截断、被安全策略中止，
        // 或干脆不听话去调工具（压缩请求本来就不带工具）。这类响应是「半份摘要」：
        // 拿它换掉整段历史是负收益——原文进了存档，上下文里只留下残缺说明。
        // 判据全部来自已有响应对象，不发额外请求。
        val usage = callUsage
        val summaryIssue = summaryIssueOf(summaryResponse, usage)
        if (summaryIssue != null) {
            FileLogger.w(TAG, "摘要响应不可用，放弃本次压缩（不落库、不标记 head）：$summaryIssue")
            onEvent(AgentEvent.CompactionFailed("摘要响应不可用：$summaryIssue"))
            return null
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

        // 折叠后的摘要链状态（记账用，见下文日志）：声明在函数体顶层——
        // 落库块在 if(sessionId) 里，而记账日志在它之后的外层，声明在块内会作用域不可见（CI 已踩过）。
        var chainBlockCount = 0
        var latestBoundary = 0L
        var stablePrefixTokens = 0

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
                // 先插 marker + 摘要，摘要没插成功则回滚删 marker（head 不标 isCompacted，
                // 不存在「标了却没有摘要顶上」的丢失面；插入失败只意味着本轮没折成，原文仍在窗口里）。
                // 摘要块落点仍在 tail 之前：回放/UI 顺序 = 摘要链 → 剩余窗口。
                val markerTs = (anchorTs - 2).coerceAtLeast(1L)
                val summaryTs = markerTs + 1
                // 覆盖边界 = head 最后一条消息的库行时间戳：该边界之前的原文由本块代表，
                // 回放不再重复带上。匹配不到 head 末尾时退到 marker 前一刻，多回放一次好过丢。
                val headEndTs = head.lastOrNull()
                    ?.let { resolveRowTimestamp(dbEntities, it) }
                    ?: (markerTs - 1).coerceAtLeast(0L)
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
                        isContextSummary = true,
                        foldsUpToTimestamp = headEndTs
                    )
                )
                FileLogger.i(TAG, "已持久化摘要块到数据库，会话 $sessionId，覆盖边界=$headEndTs")

                // 记账用：折叠后的摘要链状态。链内块数 = 未淘汰的摘要行数（含本次新插的），
                // 前缀稳定段 = 摘要块链部分的估算 token（system + 工具定义开销由模块侧另计）。
                // 查库而不是从内存列表数：上一轮的块只落在库里，内存态 messages 不一定还带着。
                chainBlockCount = dbEntities.count { it.isContextSummary && !it.isCompacted } + 1
                latestBoundary = headEndTs
                stablePrefixTokens = TokenEstimator.estimateText(
                    dbEntities.filter { it.isContextSummary && !it.isCompacted }
                        .joinToString("\n") { it.content }
                ) + TokenEstimator.estimateMessage(compactedMessage)
            } catch (e: Exception) {
                // 摘要没插进去 = 本次压缩作废，那先落库的 marker 必须一并删掉：
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
                "摘要输入估算[指令=${TokenEstimator.estimateText(summaryInstruction)} " +
                "内容=${TokenEstimator.estimateMessages(trimmedHead)}] $usageText " +
                "耗时=${durationMillis}ms 折叠条数=${trimmedHead.size} 保留条数=${tail.size} " +
                "摘要长度=${summaryResponse.length}"
        )
        // 摘要链观测（阶段 0）：块数/边界/前缀稳定段从库行计（dbEntities 含本次新块），见 :629-632。
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

        return compactedResult
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
     * 失败语义：任何一步失败都返回 null；提交顺序是「先插新块、后标旧块、标失败删新块」，
     * 任何中间态都不会丢历史。调用方（CompactionModule）在硬折叠判定之前调用本方法，
     * 失败只记日志、下轮再试，不影响主折叠。
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
        if (blocks.isEmpty() || sessionId == null) return null
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

        // 提交顺序与 compact 同源：先插新块，再标旧块；标旧块失败则删掉刚插的新块恢复原链。
        // 中间态是「新旧块同时回放」——语义重复但不丢数据；反过来的顺序（先标后插）一旦插入失败
        // 就会出现「旧块已标、新摘要缺失」的丢失面，回放直接缺掉这段历史。
        try {
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
                    id = summaryId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT.name,
                    content = summaryMessage.content,
                    timestamp = markerTs + 1,
                    isContextSummary = true,
                    foldsUpToTimestamp = boundary
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "链合并落库新摘要失败，放弃本次合并（旧块未标）", e)
            return null
        }
        val marked = runCatching { agentMessageDao.markChainBlocksCompacted(sessionId, removedIds.toList()) }
            .getOrElse {
                FileLogger.w(TAG, "链合并标记旧块失败，回滚新摘要块", it)
                runCatching { agentMessageDao.deleteMessageById(markerId) }
                    .onFailure { FileLogger.w(TAG, "链合并回滚 marker 失败，回放会多出一条孤立 marker", it) }
                runCatching { agentMessageDao.deleteMessageById(summaryId) }
                    .onFailure { FileLogger.w(TAG, "链合并回滚摘要失败，会残留一份未标旧块的新摘要", it) }
                return null
            }
        if (marked == 0) {
            // 标记未命中：旧块可能刚被回退逻辑动过。同样回滚新块，保持链原样。
            runCatching { agentMessageDao.deleteMessageById(markerId) }
            runCatching { agentMessageDao.deleteMessageById(summaryId) }
            FileLogger.w(TAG, "链合并标记旧块未命中任何行，已回滚新块，放弃本次合并")
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
     * 结果直接写进本副本的 `arguments`：[enforceWindowLimit] 是发送前兜底，产出只活在本次请求里
     * （不落库）；每次都以原文重算，重复截断是幂等的。
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
                msg is AgentMessage.ToolResultMessage && msg.result.length > TRIM_FOR_SUMMARY_CHARS ->
                    msg.copy(result = msg.result.take(TRIM_FOR_SUMMARY_CHARS) + "\n[Tool output truncated for compaction]")

                else -> msg
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

    /**
     * head 开头的连续摘要块（marker+summary 对与 legacy 前缀摘要）：新语义下它们是冻结的独立块，
     * 折叠时原样保留在折叠结果的前缀里（新块追加在它们之后），不再被新摘要吸收。
     */
    private fun leadingCompactionPairs(messages: List<AgentMessage>): List<AgentMessage> {
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
