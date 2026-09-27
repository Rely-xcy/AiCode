package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextPolicyTest {

    // ---------- preserveRecentTokens：usableTokens * 25% 后 clamp 到 [2000, 60000] ----------

    @Test
    fun preserveRecentTokens_zero_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(0))
    }

    /** 低于下界（取比例后不足 2000）时被 clamp 到下界。 */
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

    /** 区间内按 25% 取。 */
    @Test
    fun preserveRecentTokens_inRange_quarter() {
        assertEquals(2_500, ModelContextPolicy.preserveRecentTokens(10_000))
        assertEquals(19_999, ModelContextPolicy.preserveRecentTokens(79_999))
    }

    /** 128K 窗口应保留 32K 原文，而不是旧实现的固定 20K 上限。 */
    @Test
    fun preserveRecentTokens_largeWindow_scalesWithWindow() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_000))
        assertEquals(32_000, ModelContextPolicy.preserveRecentTokens(128_000))
        assertEquals(50_000, ModelContextPolicy.preserveRecentTokens(200_000))
    }

    /** 超过上界时 clamp 到上界，包括超大值与 Int.MAX_VALUE。 */
    @Test
    fun preserveRecentTokens_aboveMaximum_clampedToMaximum() {
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(240_000))
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(Int.MAX_VALUE))
    }

    /** 负数（理论上不会出现）同样被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_negative_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(-1))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(Int.MIN_VALUE))
    }

    // ---------- tierFor：按窗口分四档 ----------

    @Test
    fun tierFor_smallWindow_disablesHardCompaction() {
        val policy = ModelContextPolicy.tierFor(16_000)
        assertEquals(ModelContextPolicy.Tier.DISABLED, policy.tier)
        assertEquals(0, policy.hardThreshold)
        assertEquals(2_000, policy.preserveRecentTokens)
    }

    @Test
    fun tierFor_32kTo64k_softOnly() {
        val policy = ModelContextPolicy.tierFor(48_000)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, policy.tier)
        assertEquals(0, policy.hardThreshold)
        assertEquals(12_000, policy.preserveRecentTokens)
    }

    @Test
    fun tierFor_64kTo128k_standardWithTenKHeadroom() {
        val policy = ModelContextPolicy.tierFor(100_000)
        assertEquals(ModelContextPolicy.Tier.STANDARD, policy.tier)
        assertEquals(90_000, policy.hardThreshold)
        assertEquals(25_000, policy.preserveRecentTokens)
    }

    @Test
    fun tierFor_128kAndAbove_generousWithTwentyKHeadroom() {
        val policy = ModelContextPolicy.tierFor(128_000)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, policy.tier)
        assertEquals(108_000, policy.hardThreshold)
        assertEquals(32_000, policy.preserveRecentTokens)
    }

    @Test
    fun tierFor_boundaries() {
        assertEquals(ModelContextPolicy.Tier.DISABLED, ModelContextPolicy.tierFor(31_999).tier)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, ModelContextPolicy.tierFor(32_000).tier)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, ModelContextPolicy.tierFor(63_999).tier)
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(64_000).tier)
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(127_999).tier)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, ModelContextPolicy.tierFor(128_000).tier)
    }

    // ---------- estimateTokens：(chars + 3) / 4 向上取整 ----------

    @Test
    fun estimateTokens_zero_isZero() {
        assertEquals(0, ModelContextPolicy.estimateTokens(0))
    }

    /** 恰为 4 的倍数：无需进位。 */
    @Test
    fun estimateTokens_exactMultiple() {
        assertEquals(1, ModelContextPolicy.estimateTokens(4))
        assertEquals(25, ModelContextPolicy.estimateTokens(100))
    }

    /** 有余数时向上取整。 */
    @Test
    fun estimateTokens_roundsUp() {
        assertEquals(1, ModelContextPolicy.estimateTokens(1))
        assertEquals(2, ModelContextPolicy.estimateTokens(5))
        assertEquals(26, ModelContextPolicy.estimateTokens(101))
    }

    /** 超大值不溢出（不能用 Int.MAX_VALUE：+3 会整数溢出成负数）。 */
    @Test
    fun estimateTokens_largeValue() {
        assertEquals(250_000_000, ModelContextPolicy.estimateTokens(1_000_000_000))
    }
}
