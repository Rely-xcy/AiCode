package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextPolicyTest {

    // ---------- preserveRecentTokens：窗口的 25% 后 clamp 到 [2000, 60000] ----------

    @Test
    fun preserveRecentTokens_zero_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(0))
    }

    /** 低于下界（取四分之一后不足 2000）时被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_belowMinimum_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(7_999))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(1))
    }

    /** 恰好落在下界：无需 clamp。 */
    @Test
    fun preserveRecentTokens_atMinimum_boundary() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(8_000))
    }

    /** 区间内按四分之一取值。 */
    @Test
    fun preserveRecentTokens_inRange_quarterOfWindow() {
        assertEquals(2_500, ModelContextPolicy.preserveRecentTokens(10_000))
        assertEquals(32_000, ModelContextPolicy.preserveRecentTokens(128_000))
    }

    /** 上界边界：1M 窗口取四分之一会超上限，收敛到 60000。 */
    @Test
    fun preserveRecentTokens_aboveMaximum_clampedToMaximum() {
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(240_000))
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(1_000_000))
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(Int.MAX_VALUE))
    }

    /** 负数（理论上不会出现）同样被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_negative_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(-1))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(Int.MIN_VALUE))
    }

    // ---------- tierFor：按窗口大小决定允许哪几级压缩 ----------

    @Test
    fun tierFor_smallWindow_disabled() {
        val policy = ModelContextPolicy.tierFor(16_000)
        assertEquals(ModelContextPolicy.Tier.DISABLED, policy.tier)
        assertEquals(0, policy.hardThreshold)
    }

    @Test
    fun tierFor_below64k_softOnly() {
        val policy = ModelContextPolicy.tierFor(32_000)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, policy.tier)
        assertEquals(0, policy.hardThreshold)
    }

    @Test
    fun tierFor_standard_leaves10kHeadroom() {
        val policy = ModelContextPolicy.tierFor(64_000)
        assertEquals(ModelContextPolicy.Tier.STANDARD, policy.tier)
        assertEquals(54_000, policy.hardThreshold)
    }

    @Test
    fun tierFor_generous_leaves20kHeadroom() {
        val policy = ModelContextPolicy.tierFor(128_000)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, policy.tier)
        assertEquals(108_000, policy.hardThreshold)
        // 1M 窗口同档：上限 = 窗口 - 20k
        assertEquals(980_000, ModelContextPolicy.tierFor(1_000_000).hardThreshold)
    }
}
