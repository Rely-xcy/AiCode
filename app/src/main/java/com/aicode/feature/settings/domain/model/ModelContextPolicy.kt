package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 60_000

    /** 兜底线（发送前硬截断超长消息）占窗口的比例：软精简与硬压缩都做完仍超过该比例时才用。 */
    const val GUARD_BUDGET_PERCENT = 92

    /** 保留最近原文的预算占窗口比例：留够原文，压缩后模型才不会「不记得」刚发生的事。 */
    private const val PRESERVE_RECENT_RATIO = 0.25

    /** 窗口档位：决定该窗口下允许哪几级压缩。 */
    enum class Tier {
        /** < 32K：不自动压缩，靠提示用户新建会话。 */
        DISABLED,

        /** 32K–64K：只做软精简（截断历史工具输出），不调摘要模型。 */
        SOFT_ONLY,

        /** 64K–128K：软精简 + 摘要压缩。 */
        STANDARD,

        /** ≥ 128K：软精简 + 摘要压缩，留更宽的 headroom。 */
        GENEROUS
    }

    data class TierPolicy(
        val tier: Tier,
        /** 硬压缩（LLM 摘要）的绝对触发线；0 表示该档不启用。 */
        val hardThreshold: Int,
        /** 该档默认保留的最近原文 token 预算。 */
        val preserveRecentTokens: Int
    )

    fun tierFor(contextWindow: Int): TierPolicy = when {
        contextWindow < 32_000 -> TierPolicy(
            tier = Tier.DISABLED,
            hardThreshold = 0,
            preserveRecentTokens = MIN_PRESERVE_RECENT_TOKENS
        )

        contextWindow < 64_000 -> TierPolicy(
            tier = Tier.SOFT_ONLY,
            hardThreshold = 0,
            preserveRecentTokens = preserveRecentTokens(contextWindow)
        )

        contextWindow < 128_000 -> TierPolicy(
            tier = Tier.STANDARD,
            hardThreshold = contextWindow - 10_000,
            preserveRecentTokens = preserveRecentTokens(contextWindow)
        )

        else -> TierPolicy(
            tier = Tier.GENEROUS,
            hardThreshold = contextWindow - 20_000,
            preserveRecentTokens = preserveRecentTokens(contextWindow)
        )
    }

    /** 按窗口比例保留最近原文，而不是固定 20K——大窗口下固定值会把原文丢得太狠。 */
    fun preserveRecentTokens(usableTokens: Int): Int =
        (usableTokens * PRESERVE_RECENT_RATIO).toInt()
            .coerceIn(MIN_PRESERVE_RECENT_TOKENS, MAX_PRESERVE_RECENT_TOKENS)

    /**
     * 三级预算的实际生效值（token）。压缩判定与界面显示共用这一份，不允许各自再算一遍：
     * 显示的是窗口占用百分比、触发的是这里的阈值，两者分子分母同源才不会出现
     * 「显示 60% 却已经触发压缩」。
     */
    data class Thresholds(
        /** 判定用的模型窗口（与界面百分比的分母同源）。 */
        val contextLimit: Int,
        /** 软精简线：超过就裁历史里的超长工具输出与参数，不调模型。 */
        val soft: Int,
        /** 硬压缩线：超过就调摘要模型折叠早期对话；该档不启用硬压缩时为 0。 */
        val hard: Int,
        /** 该窗口档位是否允许硬压缩（< 32K 的档位不允许）。 */
        val hardEnabled: Boolean,
        /** 兜底线占窗口的比例（[GUARD_BUDGET_PERCENT]）。 */
        val guardPercent: Int = GUARD_BUDGET_PERCENT
    )

    /**
     * 用户设置的两个百分比 → 实际生效阈值。
     *
     * 生效值 = min(窗口 × 用户百分比, 该档位的绝对上限)：档位上限是为了给输出与提示词留 headroom，
     * 大窗口下用户设的 85% 可能被压到更低（如 128K 窗口：85% = 108.8K 被上限 108K 压住）。
     * 软线必须严格低于硬线，否则软精简永远轮不到（硬压缩先到）；窗口太小、档位不允许硬压缩时
     * 软线按窗口 90% 封顶。
     */
    fun thresholds(contextLimit: Int, softPercent: Int, hardPercent: Int): Thresholds {
        val tier = tierFor(contextLimit)
        val hardEnabled = tier.hardThreshold > 0
        val hard = if (hardEnabled) minOf(contextLimit * hardPercent / 100, tier.hardThreshold) else 0
        val softCeiling = if (hardEnabled) (hard - 1).coerceAtLeast(1) else contextLimit * 90 / 100
        val soft = minOf(contextLimit * softPercent / 100, softCeiling).coerceAtLeast(1)
        return Thresholds(
            contextLimit = contextLimit,
            soft = soft,
            hard = hard,
            hardEnabled = hardEnabled
        )
    }

    /**
     * 为输出预留的 token：输出不能占满整窗口，否则输入没地方放。
     *
     * 取窗口的 10%（夹在 1K〜8K，且不超过窗口的 1/4），模型元数据里声明了更小的输出上限就用它：
     * 声明值比预留值大时仍按预留值封顶——预留是输入侧的安全阀，不能反过来被模型宣传值抬走。
     */
    fun outputReserveTokens(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val reserve = (context / 10).coerceIn(1_024, 8_192).coerceAtMost(context / 4)
        return metadata.outputTokens?.takeIf { it > 0 }?.coerceAtMost(reserve) ?: reserve
    }

    /**
     * 实际可用的输入预算 = 模型窗口 − [outputReserveTokens]，再受模型自带的输入上限约束。
     *
     * 发送前的预算拦截与界面的百分比分母都取这一个数：拿裸窗口当分母会把「快撞输入上限」显示成还很宽裕。
     */
    fun effectiveInputBudget(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val shared = (context - outputReserveTokens(metadata)).coerceAtLeast(1)
        return metadata.inputTokens?.takeIf { it > 0 }?.coerceAtMost(shared) ?: shared
    }
}
