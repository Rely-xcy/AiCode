package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 60_000
    const val CHARS_PER_TOKEN = 4

    /** 保留最近原文的预算占窗口比例：留够原文，压缩后模型才不会"不记得"刚发生的事。 */
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

    fun estimateTokens(chars: Int): Int =
        (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
}
