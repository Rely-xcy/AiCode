package com.aicode.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 字节数转可读体积：GB / MB / KB 依次降级，各保留一位小数，不足 1 KB 时按字节原样输出。
 *
 * [locale] 决定小数点字符（部分区域用逗号），调用点原本固定 Locale.US 的必须显式传入，
 * 否则系统区域一变输出就会变。[gigabyteDecimals] 只影响 GB 档。
 */
fun formatBytes(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
    gigabyteDecimals: Int = 1
): String = when {
    bytes >= 1L shl 30 -> String.format(locale, "%.${gigabyteDecimals}f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(locale, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(locale, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** 存储容量：GB 档留两位小数（容量量级大，一位小数看不出差别）。 */
fun formatCapacityBytes(bytes: Long, locale: Locale = Locale.US): String =
    formatBytes(bytes, locale, gigabyteDecimals = 2)

/**
 * 备份空间核对用的粗粒度体积：最小单位是 KB、KB 位取整（这类数值都在百 MB 以上，
 * 小数位没有信息量），不足 1 KB 也按 0 KB 显示。
 */
fun formatCoarseBytes(bytes: Long, locale: Locale = Locale.US): String = when {
    bytes >= 1L shl 30 -> String.format(locale, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(locale, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(locale, "%.0f KB", bytes / (1L shl 10).toDouble())
}

/** 附件体积：最高只到 MB（附件有上传上限，不会出现 GB），KB 位取整，不足 1 KB 按字节输出。 */
fun formatMegabyteBytes(bytes: Long, locale: Locale = Locale.US): String = when {
    bytes >= 1L shl 20 -> String.format(locale, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(locale, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * 模型 token 上限标签：1M / 1.5M / 128K，非整数值最多留一位小数（截断，不进位）。
 * 整千整百万走整数除法，避免 128000 被浮点格式化成 128.0K。
 */
fun formatTokenLimit(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "${tokens / 1_000_000.0}M".trimToSingleDecimal()
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "${tokens / 1_000.0}K".trimToSingleDecimal()
    else -> tokens.toString()
}

/**
 * 同 [formatTokenLimit]，但整数位后不留 `.0`：1M 而非 1.0M。
 * 两处标签的历史口径不同（100 万零几千这类值会差一个 `.0`），输出不能互换。
 */
fun formatTokenLimitTrimZero(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimToSingleDecimal().removeSuffix(".0") + "M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "${tokens / 1_000.0}".trimToSingleDecimal().removeSuffix(".0") + "K"
    else -> tokens.toString()
}

/** 截断到小数点后一位（不进位）。 */
private fun String.trimToSingleDecimal(): String = replace(Regex("(\\.\\d)\\d+"), "$1")

/**
 * 缓存命中率：缓存输入 / 总输入（inputTokens 已含缓存命中部分，各 provider 适配器统一该口径）。
 * 计数器不为正表示渠道没上报，返回 "-" 而不是误导性的 0.0%；上限夹到 100% 兜住服务端异常值。
 *
 * 汇总查询返回 Long，单次调用实体返回 Int；Long 是核心实现，Int 重载只负责无损提升，不能反过来
 * 把汇总值强转 Int——长周期统计可能溢出。
 */
fun formatCacheHitRatePercent(inputTokens: Long, cachedInputTokens: Long): String {
    if (inputTokens <= 0L || cachedInputTokens <= 0L) return "-"
    val rate = min(cachedInputTokens * 100.0 / inputTokens, 100.0)
    return String.format(Locale.getDefault(), "%.1f%%", rate)
}

fun formatCacheHitRatePercent(inputTokens: Int, cachedInputTokens: Int): String =
    formatCacheHitRatePercent(inputTokens.toLong(), cachedInputTokens.toLong())

/** 文件名用时间戳：yyyyMMdd-HHmmss，无空格，固定 Locale.US 以免数字形态跟随区域变化。 */
fun formatCompactTimestamp(epochMillis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMillis))

/** 崩溃/日志描述里的时间戳：yyyy-MM-dd HH:mm:ss，跟随系统区域。 */
fun formatDateTimeSeconds(epochMillis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
