package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.domain.workflow.ContextUsage
import com.aicode.feature.agent.domain.workflow.ContextUsageHolder
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ProviderType
import dagger.Lazy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩模块：把「上下文预算」接进引擎。
 *
 * 只做策略——什么时候压、压到哪一档、用哪个模型摘要；具体怎么改消息交给
 * [ContextCompactor]（软精简 / 硬压缩 / 发送前兜底三个纯变换）。
 *
 * 三级预算（都由用户设置的百分比 × 模型窗口算出，再受窗口档位约束；
 * 实际生效值统一由 [ModelContextPolicy.thresholds] 算，界面显示也取同一份，见 [ContextUsageHolder]）：
 * 1. 软线（默认 40%）：只精简历史里的超长工具输出，不调模型、不发事件、界面无感；
 * 2. 硬线（默认 85%）：调摘要模型把早期对话折叠成结构化接手摘要；
 * 3. 兜底线（92%）：前两级都做完仍逼近窗口时，对超长消息本身做硬截断。
 *
 * 为什么要有软精简这一级：上下文利用率超过约 40% 后模型质量就开始下降，而工具输出
 * （文件内容、命令输出）是最大的噪音源。等到 85% 才动它，中间那 45% 的窗口全在噪声里。
 * 软精简零成本（不调模型、不落库），所以能早就早、能省则省。
 */
@Singleton
class CompactionModule @Inject constructor(
    /**
     * 用 [Lazy] 注入断开 DI 环：ContextCompactor → SystemPromptProvider → AgentEngine →
     * Set<EngineModule> → 本模块。模块只在真正要压缩时才取实例。
     */
    private val compactor: Lazy<ContextCompactor>,
    private val memoryExtractor: MemoryExtractor,
    /** 同样用 [Lazy]：解析抽取提示词要经 SystemPromptProvider，它又依赖引擎。 */
    private val systemPromptProvider: Lazy<SystemPromptProvider>,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    /** 判定结果发布给界面：显示百分比与触发阈值必须同源。 */
    private val contextUsageHolder: ContextUsageHolder
) : EngineModule {

    override val id = MODULE_ID

    // 最先跑：先把上下文瘦下来，后续模块与提示词注入看到的才是最终形态。
    override val order = 10

    /**
     * 「折叠后仍超线」的位点：会话 id → 那次折叠后的估算 token。
     *
     * 存在的理由：折叠腾出的是 head，而保留区（最近原文）与固定开销有可能把它填满，
     * 此时下一轮再折只会产出一份同样的摘要（同一段 head 早就不在上下文里了），白花一次模型调用、
     * 界面还会反复闪「压缩中」。记下位点就能等上下文真的又长起来再折。
     */
    private val foldedStillOverTokens = ConcurrentHashMap<String, Int>()

    /**
     * 上次硬折叠失败的位点：会话 id → 当时的判定 token。
     *
     * 失败（摘要模型报错、锚点定位不到、裁完 head 为空）不能每轮重试：一次失败就能白花一次模型调用。
     * 但也不能因此把整个模块停掉——这里只抑制「再折一次」，判定、阈值发布、软精简与发送前兜底照跑。
     */
    private val foldFailedTokens = ConcurrentHashMap<String, Int>()

    override suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? {
        val messages = call.messages
        if (messages.isEmpty()) return null
        // 窗口来源：优先主聊天模型（决定「谁快撑满」），退到摘要模型。
        val windowProvider = call.windowProvider ?: call.summaryProvider ?: return null

        val contextLimit = resolveContextTokens(windowProvider)
        // 阈值一律走 ModelContextPolicy.thresholds：它同时被界面百分比取用（见下方 publish），
        // 两处各算一遍就会出现「显示的值没到线、压缩却已经触发」。
        val thresholds = ModelContextPolicy.thresholds(
            contextLimit = contextLimit,
            softPercent = generalSettingsRepository.softCompactionThresholdPercent(),
            hardPercent = generalSettingsRepository.compactionThresholdPercent()
        )
        val hardAllowed = thresholds.hardEnabled
        val hardThreshold = thresholds.hard
        val softThreshold = thresholds.soft

        // 真实 usage 与本地估算取较大值：lastInputTokens 是上一次请求的值，
        // 本轮新塞入的大内容（文件/工具输出/图片）在旧值里看不到，只信它会把超限请求发出去。
        val estimated = TokenEstimator.estimateMessages(messages) + call.overheadTokens
        val currentTokens = maxOf(call.lastInputTokens.takeIf { it > 0 } ?: 0, estimated)
        // 判定算完立即发布给界面：同一个数既决定显示百分比也决定是否触发压缩。
        // 快照按会话分开存，并行跑的子代理各存各的，不会再把前台会话的数顶掉。
        contextUsageHolder.publish(
            ContextUsage.of(
                sessionId = ctx.sessionId,
                realTokens = call.lastInputTokens,
                estimatedTokens = estimated,
                thresholds = thresholds
            )
        )
        val reachedSoft = currentTokens >= softThreshold
        // 折叠被跳过有两种来由，都只针对「再折一次」：上一轮折完仍超线（可折的 head 已经折掉了），
        // 或上一轮硬折叠直接失败。两者都等上下文相对位点又长出 [REFOLD_GROWTH_PERCENT]% 才再试；
        // call.force 不受抑制（用户手动点了就该干活）。判据只决定折不折，模块其余职责照跑——
        // 真撞窗仍由 92% 兜底线与发送前硬截断负责，这里不碰它们。
        val foldedPoint = ctx.sessionId?.let { foldedStillOverTokens[it] }
        val failedPoint = ctx.sessionId?.let { foldFailedTokens[it] }
        val foldDeferred = foldedPoint != null &&
            currentTokens < foldedPoint + foldedPoint * REFOLD_GROWTH_PERCENT / 100
        val retryDeferred = failedPoint != null &&
            currentTokens < failedPoint + failedPoint * REFOLD_GROWTH_PERCENT / 100
        val reachedHard = hardAllowed &&
            (call.force || (currentTokens >= hardThreshold && !foldDeferred && !retryDeferred))
        if (hardAllowed && !call.force && currentTokens >= hardThreshold && (foldDeferred || retryDeferred)) {
            FileLogger.i(
                TAG,
                "会话 ${ctx.sessionId ?: "-"} ${if (retryDeferred) "上次硬折叠失败" else "折叠后仍超线"}" +
                    "（位点 ${if (retryDeferred) failedPoint else foldedPoint} tokens，当前 $currentTokens " +
                    "未再长出 $REFOLD_GROWTH_PERCENT%），本轮跳过重复折叠，只做软精简与发送前兜底"
            )
        }
        // 判定输入与发布结果必须能对上账：环显示偏小时，靠这条日志分清是「估算顶上来的」
        // 还是「界面取错了会话」。只在线以上打，否则每次工具调用都写一条，日志会被判定刷屏。
        if (reachedSoft || reachedHard) {
            FileLogger.i(
                TAG,
                "上下文判定 会话=${ctx.sessionId ?: "-"} 子代理=${ctx.isSubAgent} " +
                    "真实=${call.lastInputTokens} 估算=$estimated（含固定开销 ${call.overheadTokens}）" +
                    "判定=$currentTokens 窗口=$contextLimit 软线=$softThreshold " +
                    "硬线=${if (hardAllowed) hardThreshold.toString() else "未启用"}"
            )
        }

        // 单条消息也可能本身就超窗：只有估算还没逼近窗口时才允许按条数早退。
        if (!call.force && messages.size <= 2 && currentTokens < contextLimit) return null

        var result = messages
        var compacted = false

        if (reachedHard) {
            val summaryProvider = call.summaryProvider
            if (summaryProvider == null) {
                // 没配压缩专用模型：退化成软精简 + 兜底，不硬发摘要请求。
                FileLogger.w(TAG, "达到硬压缩线但无可用的摘要模型，本轮只做软精简")
                result = compactor.get().softTrim(result, targetTokens = softThreshold)
            } else {
                FileLogger.i(
                    TAG,
                    "会话 ${ctx.sessionId ?: "-"} 上下文约 $currentTokens tokens（窗口 $contextLimit，" +
                        "${ModelContextPolicy.tierFor(contextLimit).tier}），" +
                        "${if (call.force) "手动强制压缩" else "达到硬压缩线 $hardThreshold"}，开始折叠早期对话"
                )
                call.onEvent(AgentEvent.CompactionStarted(currentTokens))
                val compactedMessages = compactor.get().compact(
                    messages = result,
                    summaryProvider = summaryProvider,
                    sessionId = ctx.sessionId,
                    preserveRecentTokens = ModelContextPolicy.tierFor(contextLimit).preserveRecentTokens,
                    summaryWindowTokens = resolveContextTokens(summaryProvider),
                    // 折叠前先捞长期价值：这段历史马上离开上下文，里面的决策/纠正/约定
                    // 应该进记忆库而不是只被摘要吞掉。抽取失败不影响压缩本身。
                    // 注意：这一步**不受**「长期记忆自动沉淀」开关控制（用户 2026-09-30 明确要求保持独立）——
                    // 那个开关管的是「对话中主动记 + 定期治理」，压缩抽取属于上下文管理的一环。
                    onBeforeFold = { folded ->
                        val written = memoryExtractor.extract(
                            projectRoot = ctx.projectRoot,
                            history = folded,
                            source = MemoryExtractor.SOURCE_PRE_FOLD,
                            complete = { userPrompt ->
                                summaryProvider.complete(
                                    systemPrompt = systemPromptProvider.get().resolvePrompt(MemoryExtractor.PROMPT_FILE),
                                    messages = listOf(AgentMessage.UserMessage(content = userPrompt)),
                                    tools = emptyList()
                                ).content
                            }
                        )
                        if (written > 0) {
                            FileLogger.i(TAG, "折叠前从被压缩历史里捞出 $written 条长期记忆")
                        }
                    },
                    onEvent = call.onEvent
                )
                if (compactedMessages != null) {
                    result = compactedMessages
                    compacted = true
                    // 只有真的产出结果才报完成：失败时 compact() 已发过 CompactionFailed
                    call.onEvent(AgentEvent.CompactionFinished)
                    // 折叠成功 → 清掉「上次失败」的位点，下次真需要时还能再折
                    ctx.sessionId?.let { foldFailedTokens.remove(it) }
                    // 折叠腾出的空间可能当场被保留区与固定开销吃完（最近原文本身就占满窗口）。
                    // 这里如实记一笔并把位点记住：本轮不再折，接下来几轮也不在原地反复折。
                    val afterFold = TokenEstimator.estimateMessages(result) + call.overheadTokens
                    if (afterFold >= hardThreshold) {
                        FileLogger.w(
                            TAG,
                            "会话 ${ctx.sessionId ?: "-"} 折叠后仍超线：折叠后约 $afterFold tokens" +
                                "（折叠前判定 $currentTokens，硬线 $hardThreshold，窗口 $contextLimit），" +
                                "保留区与固定开销已占满可回收空间，不再重复折叠"
                        )
                        ctx.sessionId?.let { foldedStillOverTokens[it] = afterFold }
                        // 给界面的信号：复用已有的工具卡片通道（无配对的工具行会在回放时被丢掉，
                        // 不进模型上下文，也不会像 CompactionFailed 那样关掉本轮后续所有压缩）。
                        call.onEvent(
                            AgentEvent.ToolCallFinished(
                                id = "compaction-over-line-${UUID.randomUUID()}",
                                toolName = OVER_LINE_TOOL_NAME,
                                result = "折叠后仍超硬线：折叠后约 $afterFold tokens（硬线 $hardThreshold，窗口 $contextLimit）。" +
                                    "最近原文保留区与固定开销已占满可回收空间，不再重复折叠；" +
                                    "继续增长时到达 92% 兜底线会按条硬截断，保证请求不超窗。",
                                isError = false
                            )
                        )
                    } else {
                        ctx.sessionId?.let { foldedStillOverTokens.remove(it) }
                    }
                } else {
                    // 硬压缩没产出结果（摘要模型报错、锚点定位不到、裁剪后 head 为空）时不能就此罢手：
                    // 走到这里说明上下文已在硬线以上，而软精简在 else if 分支里永远轮不到，
                    // 不补这一步本轮就只剩兜底硬截（直接砍消息），体验与信息损失都差得多。
                    FileLogger.w(TAG, "硬压缩未产出结果，退化为软精简")
                    // 记下失败位点：本轮与随后几轮不再原地重试硬折叠（否则每次 LLM 调用都白试一次），
                    // 但软精简与下面的发送前兜底照跑——失败只影响「折不折」，不影响安全网。
                    ctx.sessionId?.let { foldFailedTokens[it] = currentTokens }
                    val trimmed = compactor.get().softTrim(result, targetTokens = softThreshold)
                    if (trimmed !== result) result = trimmed
                }
            }
        } else if (reachedSoft) {
            val trimmed = compactor.get().softTrim(result, targetTokens = softThreshold)
            if (trimmed !== result) {
                FileLogger.i(
                    TAG,
                    "会话 ${ctx.sessionId ?: "-"} 上下文约 $currentTokens tokens 达软线 $softThreshold，" +
                        "精简历史工具输出（未调用摘要模型）"
                )
                result = trimmed
            }
        }

        // 兜底：system prompt 与工具定义也占窗口，预算里先扣掉。
        val guardBudget = contextLimit * ModelContextPolicy.GUARD_BUDGET_PERCENT / 100 - call.overheadTokens
        val guarded = compactor.get().enforceWindowLimit(result, budgetTokens = guardBudget)

        if (!compacted && guarded === messages) return null
        return call.copy(messages = guarded)
    }

    /**
     * 判定用的窗口：模型窗口（裸值，不扣输出预留）。
     *
     * 输出预留已经由档位上限（[ModelContextPolicy.tierFor] 的 hardThreshold = 窗口 − 10K/20K）
     * 与 92% 兜底线承担；这里再扣一道会把 64K 这类小窗口降到 57600 而下探一档
     * （64K → SOFT_ONLY），硬折叠对它们直接失效。
     * 发送前的硬预算闸（workflow 侧的 effectiveInputBudget）是另一回事，不受这里影响。
     */
    private suspend fun resolveContextTokens(provider: AIProvider): Int =
        modelMetadataService
            .resolve(provider.providerId, inferProviderType(provider), provider.model)
            .contextTokens
            .takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS

    /** 会话删除后丢掉它的占用快照，避免表里留着已不存在会话的数。 */
    override suspend fun onSessionDeleted(ctx: EngineContext) {
        contextUsageHolder.remove(ctx.sessionId)
        ctx.sessionId?.let { foldedStillOverTokens.remove(it) }
        ctx.sessionId?.let { foldFailedTokens.remove(it) }
    }

    private fun inferProviderType(provider: AIProvider): ProviderType {
        val className = provider::class.simpleName.orEmpty()
        return when {
            "Anthropic" in className -> ProviderType.ANTHROPIC
            "Gemini" in className -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }

    private companion object {
        const val MODULE_ID = "compaction"
        const val TAG = "CompactionModule"

        /**
         * 折叠没换来空间后，上下文要比那次折叠后的位点再长出这么多百分比才值得再折一次。
         * 按比例而不是固定值：小窗口与大窗口的「一折」量级差很远，比例跟得上。
         */
        const val REFOLD_GROWTH_PERCENT = 5

        /**
         * 「折叠后仍超线」在界面上的卡片名。
         *
         * 不能与 COMPACTION_FAILURE_TOOL_NAME 同值——那是红色失败卡片的识别依据，而这次折叠
         * 本身是成功的（只是没把上下文压到线下）。
         */
        const val OVER_LINE_TOOL_NAME = "上下文压缩提示"
    }
}
