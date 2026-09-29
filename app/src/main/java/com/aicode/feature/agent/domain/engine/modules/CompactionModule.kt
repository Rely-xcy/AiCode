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
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ProviderType
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩模块：把「上下文预算」接进引擎。
 *
 * 只做策略——什么时候压、压到哪一档、用哪个模型摘要；具体怎么改消息交给
 * [ContextCompactor]（软精简 / 硬压缩 / 发送前兜底三个纯变换）。
 *
 * 三级预算（都由用户设置的百分比 × 模型窗口算出，再受窗口档位约束）：
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
    private val memorySettings: MemorySettingsRepository
) : EngineModule {

    override val id = MODULE_ID

    // 最先跑：先把上下文瘦下来，后续模块与提示词注入看到的才是最终形态。
    override val order = 10

    override suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? {
        val messages = call.messages
        if (messages.isEmpty()) return null
        // 窗口来源：优先主聊天模型（决定「谁快撑满」），退到摘要模型。
        val windowProvider = call.windowProvider ?: call.summaryProvider ?: return null

        val contextLimit = resolveContextTokens(windowProvider)
        val tier = ModelContextPolicy.tierFor(contextLimit)
        val hardAllowed = tier.hardThreshold > 0
        val hardThreshold = if (hardAllowed) {
            minOf(contextLimit * generalSettingsRepository.compactionThresholdPercent() / 100, tier.hardThreshold)
        } else {
            0
        }
        // 软线必须低于硬线，否则软精简永远轮不到（硬压缩先到）。
        // 窗口太小、档位不允许硬压缩时，软线按窗口 90% 封顶。
        val softCeiling = if (hardAllowed) (hardThreshold - 1).coerceAtLeast(1) else contextLimit * 90 / 100
        val softThreshold = minOf(
            contextLimit * generalSettingsRepository.softCompactionThresholdPercent() / 100,
            softCeiling
        ).coerceAtLeast(1)

        // 真实 usage 与本地估算取较大值：lastInputTokens 是上一次请求的值，
        // 本轮新塞入的大内容（文件/工具输出/图片）在旧值里看不到，只信它会把超限请求发出去。
        val estimated = TokenEstimator.estimateMessages(messages) + call.overheadTokens
        val currentTokens = maxOf(call.lastInputTokens.takeIf { it > 0 } ?: 0, estimated)
        val reachedHard = hardAllowed && (call.force || currentTokens >= hardThreshold)
        val reachedSoft = currentTokens >= softThreshold

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
                    "会话 ${ctx.sessionId ?: "-"} 上下文约 $currentTokens tokens（窗口 $contextLimit，${tier.tier}），" +
                        "${if (call.force) "手动强制压缩" else "达到硬压缩线 $hardThreshold"}，开始折叠早期对话"
                )
                call.onEvent(AgentEvent.CompactionStarted(currentTokens))
                val compactedMessages = compactor.get().compact(
                    messages = result,
                    summaryProvider = summaryProvider,
                    sessionId = ctx.sessionId,
                    preserveRecentTokens = tier.preserveRecentTokens,
                    summaryWindowTokens = resolveContextTokens(summaryProvider),
                    // 折叠前先捞长期价值：这段历史马上离开上下文，里面的决策/纠正/约定
                    // 应该进记忆库而不是只被摘要吞掉。抽取失败不影响压缩本身。
                    // 但「长期记忆自动沉淀」关着时一条都不写——该开关的语义是「不在后台自动记东西」，
                    // 压缩触不触发不该绕过它，否则用户关了开关、记忆仍在惄惄地变。
                    onBeforeFold = { folded ->
                        val autoDistill = runCatching { memorySettings.autoDistillEnabled() }.getOrDefault(false)
                        if (autoDistill) {
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
                        }
                    },
                    onEvent = call.onEvent
                )
                if (compactedMessages != null) {
                    result = compactedMessages
                    compacted = true
                    // 只有真的产出结果才报完成：失败时 compact() 已发过 CompactionFailed
                    call.onEvent(AgentEvent.CompactionFinished)
                } else {
                    // 硬压缩没产出结果（摘要模型报错、锚点定位不到、裁剪后 head 为空）时不能就此罢手：
                    // 走到这里说明上下文已在硬线以上，而软精简在 else if 分支里永远轮不到，
                    // 不补这一步本轮就只剩兜底硬截（直接砍消息），体验与信息损失都差得多。
                    FileLogger.w(TAG, "硬压缩未产出结果，退化为软精简")
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
        val guardBudget = contextLimit * GUARD_BUDGET_PERCENT / 100 - call.overheadTokens
        val guarded = compactor.get().enforceWindowLimit(result, budgetTokens = guardBudget)

        if (!compacted && guarded === messages) return null
        return call.copy(messages = guarded)
    }

    private suspend fun resolveContextTokens(provider: AIProvider): Int =
        modelMetadataService
            .resolve(provider.providerId, inferProviderType(provider), provider.model)
            .contextTokens
            .takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS

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

        /** 兜底线：压缩与软精简都做完仍超过窗口的该比例时，直接截断超长消息。 */
        const val GUARD_BUDGET_PERCENT = 92
    }
}
