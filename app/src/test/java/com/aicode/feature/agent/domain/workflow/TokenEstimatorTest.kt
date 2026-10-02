package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {

    @Test
    fun `中文按一字一 token`() {
        assertEquals(5, TokenEstimator.estimateText("上下文压缩"))
    }

    @Test
    fun `拉丁按四字符一 token`() {
        assertEquals(2, TokenEstimator.estimateText("abcdefgh"))
        assertEquals(1, TokenEstimator.estimateText("abc"))
    }

    @Test
    fun `标点空白按两字符一 token`() {
        assertEquals(2, TokenEstimator.estimateText("!!! "))
    }

    @Test
    fun `混合文本按类别分别计数`() {
        // "abc" = ceil(3/4) = 1；"中文" = 2
        assertEquals(3, TokenEstimator.estimateText("abc中文"))
    }

    @Test
    fun `中文按一字一 token，拉丁按四字符一 token`() {
        // 同样 5 个 token：中文只要 5 个字，拉丁要 20 个字符。
        // 统一按「字符数 ÷ 4」会把中文低估约 4 倍（5 个字只算 1），压缩触发随之偏晚。
        assertEquals(5, TokenEstimator.estimateText("上下文压缩"))
        assertEquals(5, TokenEstimator.estimateText("abcdefghijklmnopqrst"))
    }

    @Test
    fun `空串为 0`() {
        assertEquals(0, TokenEstimator.estimateText(""))
    }

    @Test
    fun `工具结果按实际喂模型的 modelResult 估算`() {
        val full = AgentMessage.ToolResultMessage(toolName = "read", result = "x".repeat(40_000), modelResult = "短")
        val projected = AgentMessage.ToolResultMessage(toolName = "read", result = "x".repeat(40_000))
        assertTrue(TokenEstimator.estimateMessage(full) < TokenEstimator.estimateMessage(projected))
        assertTrue(TokenEstimator.estimateMessage(full) < 100)
    }

    @Test
    fun `用户消息按实际喂模型的文本估算（含模式提醒）`() {
        val reminder = "【模式提醒】" + "自动模式正文".repeat(40)
        val bare = AgentMessage.UserMessage(content = "继续")
        val injected = bare.copy(modelReminder = reminder)

        // 估算走 modelFacingContent；拿 content 估算会漏掉提醒，压缩触发时机随之偏晚
        assertEquals(TokenEstimator.estimateText("继续\n\n$reminder"), TokenEstimator.estimateMessage(injected))
        assertTrue(TokenEstimator.estimateMessage(injected) > TokenEstimator.estimateMessage(bare))
    }

    @Test
    fun `思考签名与 thinking 块计入助手消息`() {
        val base = AgentMessage.AssistantMessage(content = "答案", reasoning = "想法")
        val signature = "A".repeat(400)
        val thinkingBlocks = """[{"type":"thinking","thinking":"想","signature":"${"B".repeat(400)}"}]"""
        val withThinking = base.copy(signature = signature, thinkingBlocksJson = thinkingBlocks)

        // 这两段必须原样回传 provider（Anthropic 工具循环），一样占输入 token，漏算会系统性低估
        val delta = TokenEstimator.estimateMessage(withThinking) - TokenEstimator.estimateMessage(base)
        assertTrue(delta > 100, "签名与 thinking 块没被计入，差值只有 $delta")
    }

    @Test
    fun `同一段思考不会算两遍`() {
        val thinking = "想".repeat(100)
        val signature = "A".repeat(400)
        val snapshot = """[{"thinking":"$thinking","signature":"$signature"}]"""
        val message = AgentMessage.AssistantMessage(
            content = "",
            reasoning = thinking,
            signature = signature,
            thinkingBlocksJson = snapshot
        )

        val rebuilt = TokenEstimator.estimateText(thinking) + TokenEstimator.estimateText(signature)
        val snapshotTokens = TokenEstimator.estimateText(snapshot)
        val total = TokenEstimator.estimateMessage(message)

        // 实际发出去的只是其中一种形态，所以取较大值：不少于较大的那份，也不接近两份相加
        assertTrue(total >= maxOf(rebuilt, snapshotTokens))
        assertTrue(total < rebuilt + snapshotTokens, "同一段思考被算了两遍：$total vs ${rebuilt + snapshotTokens}")
    }

    @Test
    fun `图片按长边分三档折算`() {
        // 分档依据见 estimateImageTokens 注释：落在三家 provider 区间的中间偏保守侧
        assertEquals(260, TokenEstimator.imageTokensForSize(300, 200))
        assertEquals(260, TokenEstimator.imageTokensForSize(512, 512))
        assertEquals(1030, TokenEstimator.imageTokensForSize(513, 512))
        assertEquals(1030, TokenEstimator.imageTokensForSize(1024, 768))
        assertEquals(1560, TokenEstimator.imageTokensForSize(1025, 1024))
        // 超过 1K 不再随面积增长（provider 会先把长边缩到约 1.5K 再计费）
        assertEquals(1560, TokenEstimator.imageTokensForSize(4000, 3000))
        // 档位看长边：窄而长的图也算大图
        assertEquals(1560, TokenEstimator.imageTokensForSize(400, 2000))
    }

    @Test
    fun `图片没有 base64 数据时不计入`() {
        // 各 provider 都按 base64 组装图像块，没有 base64 就没有真正发出去的图像数据
        assertEquals(0, TokenEstimator.estimateImageTokens(AgentImage(mimeType = "image/png", base64Data = "")))
    }

    @Test
    fun `带图消息比纯文本消息多算图片，尺寸未知时按中档保守值`() {
        val image = AgentImage(mimeType = "image/png", base64Data = "not-a-real-image")
        val bare = AgentMessage.UserMessage(content = "看看这张图")
        val withImage = bare.copy(images = listOf(image))

        assertTrue(TokenEstimator.estimateMessage(withImage) > TokenEstimator.estimateMessage(bare))
        assertEquals(TokenEstimator.IMAGE_FALLBACK_TOKENS, TokenEstimator.estimateImageTokens(image))
    }

    // ---------- 增量校准：减掉上一轮已确认的高估量（只减不上加） ----------

    @Test
    fun `没有真实 usage 或基线无效时原样返回估算值（退化安全）`() {
        // 该会话首轮、或 provider 不回传 usage：行为必须与没有这个功能完全一致
        assertEquals(12_345, TokenEstimator.calibrated(estimated = 12_345, baselineEstimate = 10_000, baselineUsage = 0))
        assertEquals(0, TokenEstimator.calibrated(estimated = 0, baselineEstimate = 999, baselineUsage = 0))
        // 没有上一轮估算（基线为 0）同样不修
        assertEquals(7_000, TokenEstimator.calibrated(estimated = 7_000, baselineEstimate = 0, baselineUsage = 5_000))
    }

    @Test
    fun `减去上一轮确认的高估量（真机那一轮的数）`() {
        // 上一轮估 523,798 / 真实 352,982（多算 170,816），本轮估 523,798 → 352,982
        assertEquals(352_982, TokenEstimator.calibrated(estimated = 523_798, baselineEstimate = 523_798, baselineUsage = 352_982))
        // 上一轮估 10,000 / 真实 9,000（多算 1,000）→ 本轮 30,000 降到 29,000
        assertEquals(29_000, TokenEstimator.calibrated(estimated = 30_000, baselineEstimate = 10_000, baselineUsage = 9_000))
    }

    @Test
    fun `上一轮低估时不做任何修正，绝不上加`() {
        // 低估那一头由判定式的 max(真实 usage, 估算) 兜住，校准只管往下修
        assertEquals(10_500, TokenEstimator.calibrated(estimated = 10_500, baselineEstimate = 10_000, baselineUsage = 12_000))
        assertEquals(30_000, TokenEstimator.calibrated(estimated = 30_000, baselineEstimate = 10_000, baselineUsage = 30_000))
    }

    @Test
    fun `修正量大于本轮估算时收敛到 1，不会算出 0 或负数`() {
        // 历史被折短 / 回退 / 换模型后基线会过期一轮：修正量可能大于本轮估算
        assertEquals(1, TokenEstimator.calibrated(estimated = 3_000, baselineEstimate = 100_000, baselineUsage = 5_000))
        assertEquals(1, TokenEstimator.calibrated(estimated = 0, baselineEstimate = 100_000, baselineUsage = 5_000))
    }
}
