package com.aicode.core.util

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [Formatters] 的输出口径：单位、空格、小数位与 locale 都与各调用点迁移前的实现逐字符一致，
 * 这里把边界值钉住（0、1023/1024、MB/GB 进位、负数、截断而非四舍五入）。
 *
 * 时间戳默认时区与 locale 都跟随系统，测试统一钉成 UTC + en-US，避免机器间差异。
 */
class FormattersTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    // region formatBytes

    @Test
    fun bytesBelowOneKilobyteStayRawBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun bytesKilobyteAndMegabyteAndGigabyteBoundaries() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        // 1 MiB - 1：仍是 KB 档，四舍五入回 1024.0
        assertEquals("1024.0 KB", formatBytes(1_048_575))
        assertEquals("1.0 MB", formatBytes(1_048_576))
        assertEquals("1.0 GB", formatBytes(1_073_741_824))
        assertEquals("5.5 GB", formatBytes(5_905_580_032))
        // 1 TiB 没有 TB 档，继续用 GB
        assertEquals("1024.0 GB", formatBytes(1_099_511_627_776))
    }

    @Test
    fun bytesUseRequestedLocaleForDecimalSeparator() {
        assertEquals("1.5 KB", formatBytes(1536, Locale.US))
        assertEquals("1,5 KB", formatBytes(1536, Locale.GERMANY))
    }

    @Test
    fun bytesNegativeFallsBackToRawBytes() {
        assertEquals("-1 B", formatBytes(-1))
        assertEquals("-1025 B", formatBytes(-1025))
    }

    // endregion

    // region formatCapacityBytes

    @Test
    fun capacityKeepsTwoDecimalsInGigabytes() {
        assertEquals("1.00 GB", formatCapacityBytes(1_073_741_824))
        assertEquals("1.40 GB", formatCapacityBytes(1_503_238_553))
    }

    @Test
    fun capacityOtherTiersMatchCommonFormat() {
        assertEquals("0 B", formatCapacityBytes(0))
        assertEquals("1023 B", formatCapacityBytes(1023))
        assertEquals("1.0 KB", formatCapacityBytes(1024))
        assertEquals("1.0 MB", formatCapacityBytes(1_048_576))
        assertEquals("1,5 KB", formatCapacityBytes(1536, Locale.GERMANY))
    }

    // endregion

    // region formatCoarseBytes

    @Test
    fun coarseStartsAtKilobyteAndRounds() {
        assertEquals("0 KB", formatCoarseBytes(0))
        // 0.5 KB 按四舍五入进位到 1 KB
        assertEquals("1 KB", formatCoarseBytes(512))
        assertEquals("1 KB", formatCoarseBytes(1023))
        assertEquals("2 KB", formatCoarseBytes(1536))
        assertEquals("1024 KB", formatCoarseBytes(1_048_575))
        assertEquals("1.0 MB", formatCoarseBytes(1_048_576))
        assertEquals("1.0 GB", formatCoarseBytes(1_073_741_824))
    }

    // endregion

    // region formatMegabyteBytes

    @Test
    fun megabyteFormatNeverPromotesToGigabyte() {
        assertEquals("2048.0 MB", formatMegabyteBytes(2_147_483_648L))
    }

    @Test
    fun megabyteFormatBoundaries() {
        assertEquals("0 B", formatMegabyteBytes(0))
        assertEquals("1023 B", formatMegabyteBytes(1023))
        assertEquals("1 KB", formatMegabyteBytes(1024))
        assertEquals("2 KB", formatMegabyteBytes(1536))
        assertEquals("1024 KB", formatMegabyteBytes(1_048_575))
        assertEquals("1.0 MB", formatMegabyteBytes(1_048_576))
        assertEquals("-1 B", formatMegabyteBytes(-1))
    }

    // endregion

    // region formatTokenLimit

    @Test
    fun tokenLimitKeepsSingleDecimalOfPrecision() {
        assertEquals("1.0K", formatTokenLimit(1_050))
        assertEquals("1.5K", formatTokenLimit(1_500))
        // 截断而非四舍五入：12.499 -> 12.4
        assertEquals("12.4K", formatTokenLimit(12_499))
        assertEquals("1.0M", formatTokenLimit(1_048_576))
        assertEquals("1.5M", formatTokenLimit(1_500_000))
        assertEquals("2147.4M", formatTokenLimit(2_147_483_647))
    }

    @Test
    fun tokenLimitWholeValuesHaveNoDecimal() {
        assertEquals("0", formatTokenLimit(0))
        assertEquals("999", formatTokenLimit(999))
        assertEquals("1K", formatTokenLimit(1_000))
        assertEquals("128K", formatTokenLimit(128_000))
        assertEquals("1M", formatTokenLimit(1_000_000))
        assertEquals("-5", formatTokenLimit(-5))
    }

    @Test
    fun tokenLimitTrimZeroDropsWholeDecimal() {
        assertEquals("1K", formatTokenLimitTrimZero(1_050))
        assertEquals("1.5K", formatTokenLimitTrimZero(1_500))
        assertEquals("12.4K", formatTokenLimitTrimZero(12_499))
        assertEquals("1M", formatTokenLimitTrimZero(1_048_576))
        assertEquals("1.5M", formatTokenLimitTrimZero(1_500_000))
        assertEquals("2147.4M", formatTokenLimitTrimZero(2_147_483_647))
        assertEquals("0", formatTokenLimitTrimZero(0))
        assertEquals("1K", formatTokenLimitTrimZero(1_000))
        assertEquals("1M", formatTokenLimitTrimZero(1_000_000))
        assertEquals("-5", formatTokenLimitTrimZero(-5))
    }

    /** 两处标签的口径差异只在「整数位后还有非零小数」时出现，不能互换。 */
    @Test
    fun tokenLimitVariantsDifferOnZeroFraction() {
        assertEquals("1.0K", formatTokenLimit(1_050))
        assertEquals("1K", formatTokenLimitTrimZero(1_050))
        assertEquals("1.0M", formatTokenLimit(1_048_576))
        assertEquals("1M", formatTokenLimitTrimZero(1_048_576))
    }

    // endregion

    // region formatCacheHitRatePercent

    @Test
    fun cacheHitRateMissingCounterShowsDash() {
        assertEquals("-", formatCacheHitRatePercent(0, 0))
        assertEquals("-", formatCacheHitRatePercent(1_000, 0))
        assertEquals("-", formatCacheHitRatePercent(0, 500))
        assertEquals("-", formatCacheHitRatePercent(-1, 5))
        assertEquals("-", formatCacheHitRatePercent(5, -1))
    }

    @Test
    fun cacheHitRateKeepsSingleDecimal() {
        assertEquals("80.0%", formatCacheHitRatePercent(1_000, 800))
        assertEquals("50.0%", formatCacheHitRatePercent(2_000, 1_000))
        assertEquals("66.7%", formatCacheHitRatePercent(3, 2))
        assertEquals("33.3%", formatCacheHitRatePercent(3, 1))
    }

    @Test
    fun cacheHitRateClampsToHundred() {
        assertEquals("100.0%", formatCacheHitRatePercent(100, 5_000))
        assertEquals("100.0%", formatCacheHitRatePercent(1_000, 1_000))
    }

    // endregion

    // region timestamps

    @Test
    fun compactTimestampUsesFixedPattern() {
        assertEquals("19700101-000000", formatCompactTimestamp(0L))
        assertEquals("20231114-221320", formatCompactTimestamp(1_700_000_000_000L))
    }

    @Test
    fun dateTimeSecondsUsesSpacedPattern() {
        assertEquals("1970-01-01 00:00:00", formatDateTimeSeconds(0L))
        assertEquals("2023-11-14 22:13:20", formatDateTimeSeconds(1_700_000_000_000L))
    }

    // endregion
}
