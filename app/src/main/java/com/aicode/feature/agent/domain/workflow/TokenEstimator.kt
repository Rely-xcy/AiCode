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
 * 估算口径（按字符类别加权，并按**连续串长度**分档；不做真 BPE——cl100k 词表约 2MB，为省包体不内置）：
 * - CJK（表意文字、假名、谚文、全角与 CJK 标点）：**1 字 ≈ 1 token**；
 * - 拉丁字母数字：**4.5 字符 ≈ 1 token**（≥[ALNUM_LONG_RUN] 的路径/哈希/URL 取 4，短串保底 1 token）；
 * - 其余（标点、空白、符号）：按串长分档，见 [otherRunCost]。
 *
 * 为什么按类别分开：主流 BPE 词表里 CJK 基本一字一 token（实测 0.93~0.96 字符/token），拉丁文本约
 * 4 字符一 token。统一按字符数除以 4 会把中文低估到约 1/4——「上下文压缩」5 个字只算 1.25，实际约 5，
 * 阈值判定与实际窗口占用对不上，压缩触发必然偏晚（中文为主的项目里能偏晚 4 倍）。
 *
 * 为什么还要按串长分档：BPE 对同一类字符的合并程度随串长差别很大——「其余」这一类改动前一律按
 * 2 字符/token，于是长缩进、注释分隔线、小写标识符这些**可继续合并的长串**被高估 1.5~7 倍。真机实测
 * （同一会话两轮）原始估算稳定是真实值的 1.42~1.45 倍，几乎全部来自这一处与字母数字串的逐串取整。
 * 分档后同一批语料回落到 1.0~1.2 倍；各档的实测依据写在各常量与 [otherRunCost] 的注释里。
 *
 * 余量方向：宁可略高不可明显偏低。判定式是 `max(真实 usage, 校准后估算)`，估高会被下一轮的增量
 * 校准（见 [calibrated]）减掉，估低没有兜底、还会让压缩触发偏晚。所以每个常量都取在实测值的保守一侧，
 * 实测能到 5~6 字符/token 的档位也只取 4.5。
 *
 * 图片与内嵌 base64 块按尺寸分档折算，见 [estimateImageTokens]。
 */
object TokenEstimator {

    /** 连续字母数字串达到该长度即视为内嵌 base64/二进制块（data URL、思考签名、附件正文），改按图片尺寸折算。 */
    private const val BLOB_RUN_MIN = 256

    /**
     * 中等长度字母数字串的换算：**4.5 字符/token**。
     *
     * 实测依据（真机语料：本仓 Kotlin/Java 源码、命令输出、工具 JSON 参数、英文 README，cl100k 分词，
     * 按 token 的字符构成归因）：5~32 字符的词与标识符是 5.6~6.6 字符/token——cl100k 对常见词与
     * camelCase 子词合并得多，「1 token ≈ 4 字符」只对**含空格的整段英文**成立，对纯字母数字串偏保守 20~35%。
     * 取 4.5 而不是实测的 5.6~6.6，是把余量留给比 cl100k 更差的词表（估高可校准，估低没有兜底）。
     */
    private const val ALNUM_CHARS_PER_TOKEN = 4.5

    /** 字母数字串达到该长度即视为路径/哈希/URL 一类不可再合并的长串，换算回落到 [ALNUM_LONG_CHARS_PER_TOKEN]。 */
    private const val ALNUM_LONG_RUN = 33

    /**
     * 长字母数字串（≥[ALNUM_LONG_RUN]）的换算：4 字符/token（与改动前一致）。
     *
     * 这一段实测分化很大：路径/URL 约 3.3~4.5 字符/token，随机 hex 哈希只有约 1.5（哈希几乎不可合并）。
     * 两种内容在长串里都常见，取 4 是折中；随机哈希因此仍会偏低（已知盲区，日志的 alnum 分项能看出来）。
     */
    private const val ALNUM_LONG_CHARS_PER_TOKEN = 4.0

    /** 空白/标点/符号串的分档上限：≤ 该长度按 2 字符/token（实测 1.6~2.4）。 */
    private const val OTHER_SHORT_RUN_MAX = 4

    /** 空白/标点/符号串的分档上限：≤ 该长度按 4 字符/token（实测 2.4~3.5）。超过即按 [OTHER_LONG_CHARS_PER_TOKEN]。 */
    private const val OTHER_MEDIUM_RUN_MAX = 16

    /** 长空白/标点串（> [OTHER_MEDIUM_RUN_MAX]）的换算：8 字符/token（实测缩进 4~8，注释分隔线约 1 token/7 字符）。 */
    private const val OTHER_LONG_CHARS_PER_TOKEN = 8.0

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

    /**
     * 消息估算缓存：内容键 → token。
     *
     * 为什么要缓存：同一批历史消息每轮要被估好几遍（判定、软精简、发送前兜底各跑一次），
     * 而每次都要逐字符走状态机、工具结果还要先解析 JSON 投影。内容没变就不必重算。
     *
     * 键 = 类型 + 各字段长度签名 + 消息自身的 `hashCode()`，与 [imageTokenCache] 同一套取舍：
     * 单 hash 碰撞概率可忽略，换来的是每轮省下整段历史的遍历。碰撞只会拿到另一条消息的估算值
     * （偏大偏小都可能），而估算本身就是启发式数值，不影响任何硬保证。
     */
    private const val MESSAGE_CACHE_LIMIT = 512
    private val messageTokenCache = ConcurrentHashMap<String, Int>()

    fun estimateMessages(messages: List<AgentMessage>): Int = messages.sumOf { estimateMessage(it) }

    /**
     * 用上一轮**已确认的高估量**往下修正本轮估算。
     *
     * 为什么只往下修：本地估算系统性偏高（BPE 与字符加权对不上），而判定式是
     * `max(真实 usage, 估算)` —— 低估的那一头由真实值自己兜住，校准再往上加只会让触发更早。
     * 所以这里只减 `baselineEstimate − baselineUsage`（上一轮确认多算的那部分），
     * 绝不上加，也就不可能把请求顶到窗口之外。
     *
     * 退化安全：`baselineUsage <= 0`（provider 不回传 usage、该会话首轮）或基线无效时
     * 原样返回 [estimated]，与没有这个功能完全一致。
     *
     * 边界：修正量大于本轮估算时收敛到 1（不会算出 0 或负数）—— 历史被折短、回退或换模型后
     * 会有一轮基线过期，此时判定仍取 `max(真实, 校准估算)`，不会因此少算占用。
     *
     * @param baselineEstimate 上一次请求的**原始**估算（未校准值）
     * @param baselineUsage 与 [baselineEstimate] 同一次请求 provider 回传的真实输入 token
     */
    fun calibrated(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int {
        if (baselineUsage <= 0 || baselineEstimate <= 0) return estimated
        val overEstimate = (baselineEstimate - baselineUsage).coerceAtLeast(0)
        return (estimated - overEstimate).coerceAtLeast(1)
    }

    fun estimateMessage(message: AgentMessage): Int {
        val key = messageCacheKey(message)
        messageTokenCache[key]?.let { return it }
        val tokens = estimateMessageUncached(message)
        if (messageTokenCache.size >= MESSAGE_CACHE_LIMIT) messageTokenCache.clear()
        messageTokenCache[key] = tokens
        return tokens
    }

    /** [estimateMessage] 的缓存键：长度签名打头，让只差内容的同形消息尽量分开。 */
    private fun messageCacheKey(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage ->
            "u:${message.modelFacingContent.length}:${message.images.size}:${message.hashCode()}"

        is AgentMessage.AssistantMessage ->
            "a:${message.content.length}:${message.reasoning.length}:${message.signature.length}:" +
                "${message.thinkingBlocksJson?.length ?: -1}:${message.toolCalls.size}:${message.hashCode()}"

        is AgentMessage.ToolResultMessage ->
            "t:${message.toolName}:${message.result.length}:${message.modelResult?.length ?: -1}:" +
                "${message.images.size}:${message.hashCode()}"
    }

    private fun estimateMessageUncached(message: AgentMessage): Int = ceil(messageCost(message).total).toInt()

    /**
     * 一条消息的成本构成。文本选择必须与实际发出去的形态一致（各分支的理由见下），
     * 估算值与日志归因共用本函数。
     *
     * 与改动前的差别：改前是「逐段估算后相加」，现在是「整条消息求和后取整」。逐段取整会把取整误差累加
     * 成一个稳定高估（一条带 4 个工具调用的助手消息最多多算 6 token），而估算本身经不起来源不明的常量偏移。
     */
    private fun messageCost(message: AgentMessage): TextCost = when (message) {
        is AgentMessage.UserMessage -> {
            // 用实际喂模型的文本（正文 + 模式提醒），否则估算与实际请求不一致。
            val cost = textCost(message.modelFacingContent)
            cost.image += message.images.sumOf { estimateImageTokens(it) }
            cost
        }

        is AgentMessage.AssistantMessage -> {
            // 助手消息有两种组装形态，实际发出去的只是其中之一（见各 provider adapter）：
            // 1. 原生快照（Anthropic 的 thinking/redacted_thinking 块、Gemini 的 parts），含思考文本与签名，
            //    有快照时优先原样回传；
            // 2. 重建（正文 + 思考 + 签名 + 工具参数），旧数据没快照时走这条。
            // 取两者较大值：既不漏算 signature / thinkingBlocksJson（思考模式下会系统性低估），
            // 也不会把同一段思考算两遍。注意 Gemini 的快照里已含正文与 functionCall，正文/参数会被再算一遍，
            // 属偏保守的高估；本估算器不区分 provider，宁可略高。
            val rebuilt = textCost(message.content)
            rebuilt.add(textCost(message.reasoning))
            rebuilt.add(textCost(message.signature))
            message.toolCalls.forEach { call ->
                rebuilt.add(textCost(call.name))
                // 用实际喂模型的参数（modelArguments 优先），否则软精简后估算不会下降。
                rebuilt.add(textCost(call.effectiveArguments.toString()))
            }
            val snapshot = textCost(message.thinkingBlocksJson)
            val winner = if (rebuilt.total >= snapshot.total) rebuilt else snapshot
            winner.image += message.images.sumOf { estimateImageTokens(it) }
            winner
        }

        is AgentMessage.ToolResultMessage -> {
            // 用实际喂模型的那份文本（modelResult 优先，其次文件类工具的投影结果）：
            // 库里的历史行没有 modelResult，直接拿 result 估会把 editFile/writeFile 带回的整份 diff
            // 当成模型收到的内容（实际只收到一句话投影），大文件写过一次就白涨几万 token。
            val text = message.modelResult
                ?: modelToolResultText(message.toolName, message.result)
                ?: message.result
            val cost = textCost(message.toolName)
            cost.add(textCost(text))
            cost.image += message.images.sumOf { estimateImageTokens(it) }
            cost
        }
    }

    /**
     * 判定日志用的估算构成：把 [messages] 的估算按分支拆开，用来定位「估算与真实对不上」是归哪一类内容。
     *
     * **只读数**：不参与任何判定，也不影响 [estimateMessages]。分支各自求和后取整，与逐条取整的
     * [estimateMessages] 相比有 ≤ 消息数 的取整差（日志里两个数都能看到，不必强行对平）。
     *
     * 不做缓存：调用点只在判定到线以上时跑一次（见 CompactionModule），不是每轮都算的开销。
     */
    internal fun breakdown(messages: List<AgentMessage>): Breakdown {
        val cost = TextCost()
        messages.forEach { cost.add(messageCost(it)) }
        return Breakdown(
            cjk = ceil(cost.cjk).toInt(),
            alnum = ceil(cost.alnum).toInt(),
            other = ceil(cost.other).toInt(),
            image = cost.image,
            messages = messages.size
        )
    }

    /** [breakdown] 的返回值：消息估算的分支构成。 */
    internal data class Breakdown(
        val cjk: Int,
        val alnum: Int,
        val other: Int,
        val image: Int,
        val messages: Int
    )

    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        val cost = textCost(text)
        return ceil(cost.text).toInt()
    }

    /**
     * 逐字符走一遍文本，按分支累计（未取整的）成本。
     *
     * 估算与日志归因（[breakdown]）共用这一条路径：两处各写一套字符分类与分档，早晚会漂移成对不上的两个数。
     */
    private fun textCost(text: String): TextCost {
        val cost = TextCost()
        var runStart = 0
        var alnumRun = 0
        var otherRun = 0

        fun flushAlnum(endExclusive: Int) {
            if (alnumRun == 0) return
            cost.alnum += if (alnumRun >= BLOB_RUN_MIN) {
                estimateBase64Blob(text.substring(runStart, endExclusive)).toDouble()
            } else {
                alnumRunCost(alnumRun)
            }
            alnumRun = 0
        }

        fun flushOther() {
            if (otherRun == 0) return
            cost.other += otherRunCost(otherRun)
            otherRun = 0
        }

        for (index in text.indices) {
            val c = text[index]
            when {
                isWide(c) -> {
                    flushAlnum(index)
                    flushOther()
                    cost.cjk += 1.0
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
        return cost
    }

    /**
     * 字母数字串的换算。
     *
     * 短串保底 1 token（实测 1~4 字符的串只值 0.5~0.9 token）：这个保底是留给 CSV、hex 转储、IP 列表、
     * 表格这类「短串密集」内容的——实测它们整体只估到 0.91~1.0，保底降到 0.5 会直接压到 0.68~0.75（明显低估）。
     */
    private fun alnumRunCost(run: Int): Double =
        if (run >= ALNUM_LONG_RUN) {
            run / ALNUM_LONG_CHARS_PER_TOKEN
        } else {
            maxOf(1.0, run / ALNUM_CHARS_PER_TOKEN)
        }

    /**
     * 空白/标点/符号串的换算：**按串长分档**。
     *
     * 为什么不能用一个常数：BPE 对这类字符的合并程度随串长变化很大（cl100k 实测）——
     * 1~4 字符约 1.6~2.4 字符/token，5~16 字符约 2.4~3.5，17~32 字符约 3~3.5，更长的缩进与注释分隔线
     * 约 7~10。改动前一律按 2 字符/token，Kotlin 六层嵌套的缩进、`// *****` 分隔线这类长串会被高估 2~7 倍。
     */
    private fun otherRunCost(run: Int): Double = when {
        run <= OTHER_SHORT_RUN_MAX -> ceil(run / 2.0)
        run <= OTHER_MEDIUM_RUN_MAX -> ceil(run / 4.0)
        else -> ceil(run / OTHER_LONG_CHARS_PER_TOKEN)
    }

    /**
     * 一段文本按分支拆开的（未取整）成本。
     *
     * 取整只在整段/整条消息上做一次：逐串取整会把「1 个字符至少 1 token」的保底误差累加成一个可观的高估
     * （Kotlin 源码语料实测：逐串取整比按串长换算高 10% 以上）。
     */
    private class TextCost {
        var cjk = 0.0
        var alnum = 0.0
        var other = 0.0
        var image = 0

        /** 文本部分（不含图片）。 */
        val text: Double get() = cjk + alnum + other

        /** 含图片的合计，用于助手消息两种组装形态取大（见 [messageCost]）。 */
        val total: Double get() = text + image

        fun add(cost: TextCost) {
            cjk += cost.cjk
            alnum += cost.alnum
            other += cost.other
            image += cost.image
        }
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
