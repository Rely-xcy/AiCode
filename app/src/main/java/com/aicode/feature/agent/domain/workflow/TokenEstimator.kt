package com.aicode.feature.agent.domain.workflow

import android.graphics.BitmapFactory
import android.util.Base64
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.modelFacingContent
import com.aicode.feature.agent.domain.tool.effectiveArguments
import com.aicode.feature.agent.domain.tool.modelToolResultText
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * 全项目 token **估算**的唯一入口。
 *
 * 两种数分工（别混用，界面必须让用户分得清是哪种）：
 * - **真实值**：provider 回传的 usage（[com.aicode.feature.agent.domain.provider.AIResponse.inputTokens] 等，
 *   落库见 `LlmCallRecordEntity` 与 `chat_sessions`）。只有在请求已经发出去之后才存在。
 * - **估算值**：本类。用于「请求还没发出去」时的预判——压缩阈值判定、上下文占用百分比、
 *   软精简与兜底截断的取舍。估算不追求精确，只求同量级、且不系统性偏低或偏高。
 *
 * 估算口径（按字符类别加权；不做真 BPE——cl100k 词表约 2MB，为省包体不内置）：
 * - CJK（表意文字、假名、谚文、全角与 CJK 标点）：**1 字 ≈ 1 token**；
 * - 拉丁字母数字：**4 字符 ≈ 1 token**；
 * - 其余（标点、空白、符号）：2 字符 ≈ 1 token。
 *
 * 为什么按类别分开：主流 BPE 词表里 CJK 基本一字一 token（常用词合并省下的有限），拉丁文本约
 * 4 字符一 token。统一按字符数除以 4 会把中文低估到约 1/4——「上下文压缩」5 个字只算 1.25，实际约 5，
 * 阈值判定与实际窗口占用对不上，压缩触发必然偏晚（中文为主的项目里能偏晚 4 倍）。
 *
 * 图片与内嵌 base64 块按尺寸分档折算，见 [estimateImageTokens]。
 */
object TokenEstimator {

    /** 连续字母数字串达到该长度即视为内嵌 base64/二进制块（data URL、思考签名、附件正文），改按图片尺寸折算。 */
    private const val BLOB_RUN_MIN = 256

    /** 图片分档阈值：长边 ≤512 按小图、≤1024 按中图、更大按大图。 */
    internal const val IMAGE_SMALL_MAX_EDGE = 512
    internal const val IMAGE_MEDIUM_MAX_EDGE = 1024
    internal const val IMAGE_SMALL_TOKENS = 260
    internal const val IMAGE_MEDIUM_TOKENS = 1030
    internal const val IMAGE_LARGE_TOKENS = 1560

    /**
     * 拿不到尺寸（解码失败、格式不支持）时的保守值：按中图（≈一张 1024×1024）计，不猜更小。
     * 与 [IMAGE_MEDIUM_TOKENS] 同值——这是「说明不了是什么图」时的中间档，不是额外的一档。
     */
    internal const val IMAGE_FALLBACK_TOKENS = IMAGE_MEDIUM_TOKENS

    /** 图片尺寸估算缓存：base64 解码 + 解析头开销大，同一张图只算一次。 */
    private const val IMAGE_CACHE_LIMIT = 64
    private val imageTokenCache = ConcurrentHashMap<Int, Int>()

    fun estimateMessages(messages: List<AgentMessage>): Int = messages.sumOf { estimateMessage(it) }

    /**
     * 用上一次请求的真实 usage 增量校准本次估算。
     *
     * 为什么是「补差额」而不是按比值缩放：同一段历史，本地估算与真实值之间常差着一截固定量
     * （本地字符加权与 provider 的 BPE 分词本就对不上），但两轮之间**新增的那部分**量得是准的。
     * 所以只把差额搬过来：`真实 + (本次估算 − 上次估算)`，再与本次估算取大值（宁可早压不可晚压）。
     * 按比值缩放会把「历史长度」本身也乘进去，短上下文那一头会平白多出一大截。
     *
     * 退化安全：`baselineUsage <= 0`（provider 不回传 usage、该会话首轮）时原样返回 [estimated]，
     * 与没有这个功能完全一致。
     *
     * @param baselineEstimate 上一次请求的**原始**估算（未校准值：拿校准后的值当基线，差额会被自己抵消掉）
     * @param baselineUsage 与 [baselineEstimate] 同一次请求 provider 回传的真实输入 token
     */
    fun calibrated(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int =
        if (baselineUsage > 0) {
            maxOf(estimated.toLong(), baselineUsage.toLong() + estimated - baselineEstimate)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } else {
            estimated
        }

    fun estimateMessage(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage ->
            // 用实际喂模型的文本（正文 + 模式提醒），否则估算与实际请求不一致。
            estimateText(message.modelFacingContent) + message.images.sumOf { estimateImageTokens(it) }

        is AgentMessage.AssistantMessage -> {
            // 助手消息有两种组装形态，实际发出去的只是其中之一（见各 provider adapter）：
            // 1. 原生快照（Anthropic 的 thinking/redacted_thinking 块、Gemini 的 parts），含思考文本与签名，
            //    有快照时优先原样回传；
            // 2. 重建（正文 + 思考 + 签名 + 工具参数），旧数据没快照时走这条。
            // 取两者较大值：既不漏算 signature / thinkingBlocksJson（思考模式下会系统性低估），
            // 也不会把同一段思考算两遍。注意 Gemini 的快照里已含正文与 functionCall，正文/参数会被再算一遍，
            // 属偏保守的高估；本估算器不区分 provider，宁可略高。
            val rebuilt = estimateText(message.content) +
                estimateText(message.reasoning) +
                estimateText(message.signature) +
                // 用实际喂模型的参数（modelArguments 优先），否则软精简后估算不会下降。
                message.toolCalls.sumOf { estimateText(it.name) + estimateText(it.effectiveArguments.toString()) }
            maxOf(rebuilt, estimateText(message.thinkingBlocksJson)) +
                message.images.sumOf { estimateImageTokens(it) }
        }

        is AgentMessage.ToolResultMessage -> {
            // 用实际喂模型的那份文本（modelResult 优先，其次文件类工具的投影结果）：
            // 库里的历史行没有 modelResult，直接拿 result 估会把 editFile/writeFile 带回的整份 diff
            // 当成模型收到的内容（实际只收到一句话投影），大文件写过一次就白涨几万 token。
            val text = message.modelResult
                ?: modelToolResultText(message.toolName, message.result)
                ?: message.result
            estimateText(message.toolName) + estimateText(text) + message.images.sumOf { estimateImageTokens(it) }
        }
    }

    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0
        var runStart = 0
        var alnumRun = 0
        var otherRun = 0

        fun flushAlnum(endExclusive: Int) {
            if (alnumRun == 0) return
            tokens += if (alnumRun >= BLOB_RUN_MIN) {
                estimateBase64Blob(text.substring(runStart, endExclusive))
            } else {
                ceil(alnumRun / 4.0).toInt()
            }
            alnumRun = 0
        }

        fun flushOther() {
            if (otherRun == 0) return
            tokens += ceil(otherRun / 2.0).toInt()
            otherRun = 0
        }

        for (index in text.indices) {
            val c = text[index]
            when {
                isWide(c) -> {
                    flushAlnum(index)
                    flushOther()
                    tokens += 1
                }

                isAlnumBlobChar(c) -> {
                    if (alnumRun == 0) runStart = index
                    flushOther()
                    alnumRun++
                }

                else -> {
                    flushAlnum(index)
                    otherRun++
                }
            }
        }
        flushAlnum(text.length)
        flushOther()
        return tokens
    }

    /**
     * 图片 token 估算：按长边分三档给固定值（[IMAGE_SMALL_TOKENS] / [IMAGE_MEDIUM_TOKENS] / [IMAGE_LARGE_TOKENS]）。
     *
     * **这是近似值，不要当精确账单用。** 三家 provider 的真实规则本就不同，同一张 1024×1024 的图
     * 从 765 到 1400 token 都有，任何单一公式都对不上全部：
     * - Anthropic：≈ w×h/750，长边先缩到 1568px（512²≈350、1024²≈1400、缩顶后约 2400）；
     * - OpenAI：85 + 170×块数，块为 512px（512²≈255、1024²≈765、1536²≈1615、2048²≈2805）；
     * - Gemini：768px 一块、每块约 258 token（512²≈258、1024²≈1032、2048²≈2322）。
     *
     * 分档取值落在各家的中间偏保守侧，且随尺寸单调增长：≤512 → 260（三家 255/258/350，取中位）、
     * ≤1024 → 1030（765/1032/1400，取中位）、更大 → 1560（OpenAI 1536² 的 1615 与 Gemini 的 1032 之间，
     * Anthropic 缩顶后约 2400，取中等偏保守）。超过 1K 后不再随面积增长：provider 都会在服务端把长边
     * 缩到约 1.5K 再计费，成本基本封顶，按面积线性外推会高得离谱（4000×3000 的图能算到 1.6 万）。
     *
     * [AgentImage.base64Data] 为空时计 0：各 provider 都按 base64 组装图像块（见各 adapter 的
     * image block / image_url），没有 base64 就没有真正发出去的图像数据，也就不占输入 token。
     * 尺寸解不出来时按 [IMAGE_FALLBACK_TOKENS] 计，宁可略高不漏算。
     */
    fun estimateImageTokens(image: AgentImage): Int {
        val data = image.base64Data
        if (data.isEmpty()) return 0
        val key = data.hashCode()
        imageTokenCache[key]?.let { return it }
        val tokens = decodeImageTokens(data) ?: IMAGE_FALLBACK_TOKENS
        if (imageTokenCache.size >= IMAGE_CACHE_LIMIT) imageTokenCache.clear()
        imageTokenCache[key] = tokens
        return tokens
    }

    /**
     * 尺寸 → token 的纯换算（单独摘出来是为了可单测：JVM 单测里 BitmapFactory 是空实现，解码段测不到）。
     */
    internal fun imageTokensForSize(width: Int, height: Int): Int {
        val longEdge = max(width, height)
        return when {
            longEdge <= IMAGE_SMALL_MAX_EDGE -> IMAGE_SMALL_TOKENS
            longEdge <= IMAGE_MEDIUM_MAX_EDGE -> IMAGE_MEDIUM_TOKENS
            else -> IMAGE_LARGE_TOKENS
        }
    }

    private fun estimateBase64Blob(blob: String): Int {
        val key = blob.hashCode()
        imageTokenCache[key]?.let { return it }
        // 内嵌 base64 块大多就是图片（data URL）；不是图片（如思考签名）时按字符数折算。
        val tokens = decodeImageTokens(blob) ?: ceil(blob.length / 4.0).toInt()
        if (imageTokenCache.size >= IMAGE_CACHE_LIMIT) imageTokenCache.clear()
        imageTokenCache[key] = tokens
        return tokens
    }

    private fun decodeImageTokens(payload: String): Int? = try {
        val raw = payload.substringAfter("base64,", payload)
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) null else imageTokensForSize(width, height)
    } catch (e: Exception) {
        null
    }

    private fun isAlnumBlobChar(c: Char): Boolean =
        (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '='

    /** CJK 表意文字、假名、谚文、全角与 CJK 标点：这些字符在 BPE 里基本各占 1 token。 */
    private fun isWide(c: Char): Boolean {
        val code = c.code
        return code in 0x3000..0x303F ||
            code in 0x3040..0x30FF ||
            code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF ||
            code in 0xFF00..0xFFEF
    }
}
