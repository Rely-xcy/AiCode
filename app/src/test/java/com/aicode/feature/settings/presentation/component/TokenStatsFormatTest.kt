package com.aicode.feature.settings.presentation.component

import com.aicode.feature.agent.data.local.dao.CallSummary
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Token 统计页的「渠道没上报」展示：缓存读取/写入与命中率在缺数据时显示 -，
 * 不再显示会被误读成「确实没命中」的 0 / 0%。
 */
class TokenStatsFormatTest {

    private fun record(
        inputTokens: Int = 0,
        cachedInputTokens: Int = 0,
        cacheCreationTokens: Int = 0
    ): LlmCallRecordEntity = LlmCallRecordEntity(
        createdAt = 0L,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        cacheCreationTokens = cacheCreationTokens
    )

    private fun summary(inputTokens: Long, cachedInputTokens: Long): CallSummary = CallSummary(
        calls = 1,
        inputTokens = inputTokens,
        outputTokens = 0,
        cachedInputTokens = cachedInputTokens,
        cacheCreationTokens = 0,
        errors = 0,
        avgTtfbMillis = null,
        avgDurationMillis = null
    )

    private fun rate(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value)

    @Test
    fun cacheTokenColumns_showDashWhenProviderReportedNothing() {
        assertEquals("-", formatOptionalTokens(0))
        assertEquals("1234", formatOptionalTokens(1_234))
    }

    @Test
    fun recordHitRate_showsDashWithoutCacheOrInput() {
        assertEquals("-", formatRecordCacheHitRate(record(inputTokens = 1_000, cachedInputTokens = 0)))
        assertEquals("-", formatRecordCacheHitRate(record(inputTokens = 0, cachedInputTokens = 500)))
        assertEquals("-", formatRecordCacheHitRate(record(inputTokens = 0, cachedInputTokens = 0)))
    }

    @Test
    fun recordHitRate_usesReportedCache() {
        assertEquals(rate(80.0), formatRecordCacheHitRate(record(inputTokens = 1_000, cachedInputTokens = 800)))
        // cache 比 input 大时（部分渠道 input 不含缓存）封顶 100%，不出 500%。
        assertEquals(rate(100.0), formatRecordCacheHitRate(record(inputTokens = 100, cachedInputTokens = 5_000)))
    }

    @Test
    fun summaryHitRate_showsDashWithoutCacheOrInput() {
        assertEquals("-", formatCacheHitRate(summary(inputTokens = 1_000, cachedInputTokens = 0)))
        assertEquals("-", formatCacheHitRate(summary(inputTokens = 0, cachedInputTokens = 500)))
        assertEquals("-", formatCacheHitRate(summary(inputTokens = 0, cachedInputTokens = 0)))
    }

    @Test
    fun summaryHitRate_usesReportedCache() {
        assertEquals(rate(50.0), formatCacheHitRate(summary(inputTokens = 2_000, cachedInputTokens = 1_000)))
        assertEquals(rate(100.0), formatCacheHitRate(summary(inputTokens = 100, cachedInputTokens = 5_000)))
    }
}
