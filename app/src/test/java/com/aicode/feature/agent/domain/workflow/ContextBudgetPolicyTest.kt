package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextBudgetPolicyTest {

    @Test
    fun `窗口档位按窗口大小分四档`() {
        assertEquals(ModelContextPolicy.Tier.DISABLED, ModelContextPolicy.tierFor(16_000).tier)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, ModelContextPolicy.tierFor(32_000).tier)
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(64_000).tier)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, ModelContextPolicy.tierFor(128_000).tier)
    }

    @Test
    fun `小窗口不启用硬压缩`() {
        assertEquals(0, ModelContextPolicy.tierFor(16_000).hardThreshold)
        assertEquals(0, ModelContextPolicy.tierFor(63_000).hardThreshold)
    }

    @Test
    fun `硬上限按档位留出 headroom`() {
        assertEquals(54_000, ModelContextPolicy.tierFor(64_000).hardThreshold)
        assertEquals(108_000, ModelContextPolicy.tierFor(128_000).hardThreshold)
    }

    @Test
    fun `有效硬线取百分比与档位上限的较小值`() {
        val window = 128_000
        val tier = ModelContextPolicy.tierFor(window)
        // 85% × 128k = 108_800 > 档位上限 108_000 → 取上限
        assertEquals(108_000, minOf(window * 85 / 100, tier.hardThreshold))
        // 50% × 128k = 64_000 < 上限 → 取百分比
        assertEquals(64_000, minOf(window * 50 / 100, tier.hardThreshold))
    }

    @Test
    fun `保留最近原文按窗口四分之一且上下限收敛`() {
        assertEquals(32_000, ModelContextPolicy.preserveRecentTokens(128_000))
        // 1M 窗口：25% 是 250k，收敛到上限 60k
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(1_000_000))
        // 小窗口：25% 低于下限，收敛到 2k
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(4_000))
    }

    @Test
    fun `软精简在预算内时原样返回`() {
        val messages = listOf(tool("a".repeat(400)))
        val result = compactor().softTrim(messages, targetTokens = 10_000)
        assertSame(messages, result)
    }

    @Test
    fun `软精简只裁到够用就停`() {
        // 两条各 40k 字符（约 10k token），目标是裁掉一条多一点就能落回
        val first = tool("a".repeat(40_000))
        val second = tool("b".repeat(40_000))
        val messages = listOf(first, second)

        val result = compactor().softTrim(messages, targetTokens = 12_000)

        // 从最长的开始裁：第一条被裁短，第二条保持原对象不动
        assertNotNull((result[0] as AgentMessage.ToolResultMessage).modelResult)
        assertSame(second, result[1])
    }

    @Test
    fun `软精简幂等且不动 UI 用的完整内容`() {
        val original = tool("a".repeat(40_000))
        val compactor = compactor()

        val once = compactor.softTrim(listOf(original), targetTokens = 1)
        val twice = compactor.softTrim(once, targetTokens = 1)

        // 第二次没有可裁内容 → 返回同一引用
        assertSame(once, twice)
        // result（UI 与持久化）始终是完整内容，只有 modelResult 变短
        val trimmed = once[0] as AgentMessage.ToolResultMessage
        assertEquals(40_000, trimmed.result.length)
        assertTrue(trimmed.modelResult!!.length < 40_000)
    }

    @Test
    fun `不碰非工具消息`() {
        val user = AgentMessage.UserMessage(content = "x".repeat(40_000))
        val result = compactor().softTrim(listOf(user), targetTokens = 1)
        assertSame(user, result[0])
    }

    @Test
    fun `兜底截断保证落进预算`() {
        val messages = listOf<AgentMessage>(
            tool("a".repeat(400_000)),
            AgentMessage.UserMessage(content = "问题")
        )
        val result = compactor().enforceWindowLimit(messages, budgetTokens = 1_000)
        assertTrue(TokenEstimator.estimateMessages(result) <= 1_500)
    }

    @Test
    fun `预算内不触发兜底`() {
        val messages = listOf<AgentMessage>(AgentMessage.UserMessage(content = "短问题"))
        assertSame(messages, compactor().enforceWindowLimit(messages, budgetTokens = 1_000))
    }

    private fun tool(text: String) = AgentMessage.ToolResultMessage(toolName = "read", result = text)

    private fun compactor() = ContextCompactor(
        agentMessageDao = mockk(relaxed = true),
        systemPromptProvider = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        compactedHistoryArchive = mockk(relaxed = true)
    )
}
