package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun estimateText_empty_isZero() {
        assertEquals(0, TokenEstimator.estimateText(""))
    }

    /** 中文按 1 字 1 token，不再被 4 字符/token 的旧口径低估。 */
    @Test
    fun estimateText_cjk_countsPerChar() {
        assertEquals(5, TokenEstimator.estimateText("上下文压缩"))
    }

    /** 拉丁按 4 字符 1 token 向上取整。 */
    @Test
    fun estimateText_latin_quartersUp() {
        assertEquals(2, TokenEstimator.estimateText("hello"))
        assertEquals(1, TokenEstimator.estimateText("hi"))
    }

    /** 标点与空白按 2 字符 1 token。 */
    @Test
    fun estimateText_punctuation_halved() {
        assertEquals(2, TokenEstimator.estimateText(",.;!"))
    }

    /** 中英混排：中文按字、拉丁按 4 字符、空格计半。 */
    @Test
    fun estimateText_mixed() {
        assertEquals(5, TokenEstimator.estimateText("测试 hello"))
    }

    /** 超长 base64 串走图片折算路径；单测环境解析不出尺寸，退化为 4 字符 1 token。 */
    @Test
    fun estimateText_longBase64Run_usesBlobPath() {
        assertEquals(256, TokenEstimator.estimateText("A".repeat(1024)))
    }

    /** 无 base64 数据的图片（只留容器 path）计 0，属已知低估。 */
    @Test
    fun estimateImageTokens_withoutBase64_isZero() {
        val image = AgentImage(mimeType = "image/png", base64Data = "")
        assertEquals(0, TokenEstimator.estimateImageTokens(image))
    }

    /** 解码不出尺寸的图片按固定成本计，不能当 0。 */
    @Test
    fun estimateImageTokens_undecodable_fallsBackToFixedCost() {
        val image = AgentImage(mimeType = "image/png", base64Data = "not-an-image")
        assertEquals(1000, TokenEstimator.estimateImageTokens(image))
    }

    /** 工具结果用实际喂模型的 modelResult，而不是完整的 result。 */
    @Test
    fun estimateMessage_toolResult_prefersModelResult() {
        val message = AgentMessage.ToolResultMessage(
            toolName = "readFile",
            result = "x".repeat(4_000),
            modelResult = "yyyy"
        )
        assertEquals(3, TokenEstimator.estimateMessage(message))
    }

    /** 同一张图重复估算走缓存，结果稳定。 */
    @Test
    fun estimateImageTokens_isStableAcrossCalls() {
        val image = AgentImage(mimeType = "image/png", base64Data = "not-an-image")
        assertEquals(TokenEstimator.estimateImageTokens(image), TokenEstimator.estimateImageTokens(image))
    }
}
