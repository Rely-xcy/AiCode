package com.aicode.feature.agent.domain.workflow

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
}
