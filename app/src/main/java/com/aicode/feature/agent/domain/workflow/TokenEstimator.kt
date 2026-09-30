package com.aicode.feature.agent.domain.workflow

import android.graphics.BitmapFactory
import android.util.Base64
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.modelFacingContent
import com.aicode.feature.agent.domain.tool.effectiveArguments
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * 上下文 token 估算。
 *
 * 不做真 BPE（cl100k 词表约 2MB，为省包体不内置），改用按字符类别加权的启发式：
 * 中文/全角 1 字 ≈ 1 token、拉丁 4 字符 ≈ 1 token、标点空白 2 字符 ≈ 1 token、
 * 超长 base64 串按图片尺寸折算。统一按 4 字符/token 会同时高估中文、低估图片，
 * 压缩触发时机随之偏晚（中文场景能低估 4 倍）。
 */
object TokenEstimator {

    /** 连续字母数字串达到该长度即视为内嵌 base64/二进制块，改按图片尺寸折算。 */
    private const val BLOB_RUN_MIN = 256

    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85
    private const val IMAGE_FALLBACK_TOKENS = 1000

    /** 图片尺寸估算缓存：base64 解码 + 解析头开销大，同一张图只算一次。 */
    private const val IMAGE_CACHE_LIMIT = 64
    private val imageTokenCache = ConcurrentHashMap<Int, Int>()

    fun estimateMessages(messages: List<AgentMessage>): Int = messages.sumOf { estimateMessage(it) }

    fun estimateMessage(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage ->
            // 用实际喂模型的文本（正文 + 模式提醒），否则估算与实际请求不一致。
            estimateText(message.modelFacingContent) + message.images.sumOf { estimateImageTokens(it) }

        is AgentMessage.AssistantMessage ->
            estimateText(message.content) +
                estimateText(message.reasoning) +
                // 用实际喂模型的参数（modelArguments 优先），否则软精简后估算不会下降。
                message.toolCalls.sumOf { estimateText(it.name) + estimateText(it.effectiveArguments.toString()) } +
                message.images.sumOf { estimateImageTokens(it) }

        is AgentMessage.ToolResultMessage -> {
            // 用实际喂模型的那份文本（modelResult 优先），否则软精简后估算不会下降。
            val text = message.modelResult ?: message.result
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
     * 图片 token 估算：按 32×32 网格折算（与主流视觉模型的分块计费口径一致），
     * 长边超过 2048 先缩放，下限 85；解码失败按不透明大块计。
     * 注意 [AgentImage.base64Data] 为空（只留容器内 path）时返回 0，属已知低估。
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

    private fun estimateBase64Blob(blob: String): Int {
        val key = blob.hashCode()
        imageTokenCache[key]?.let { return it }
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
        if (width <= 0 || height <= 0) {
            null
        } else {
            val longEdge = max(width, height).toFloat()
            val scale = if (longEdge > IMAGE_MAX_EDGE) IMAGE_MAX_EDGE / longEdge else 1f
            val scaled = ceil(width * scale / IMAGE_GRID) * ceil(height * scale / IMAGE_GRID)
            max(IMAGE_MIN_TOKENS, scaled.toInt())
        }
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
