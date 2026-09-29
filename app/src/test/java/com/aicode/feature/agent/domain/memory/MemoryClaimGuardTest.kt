package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.model.AgentMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 兜底判据的边界：只抓「声称已经写进去了」，不抓回忆、否定与普通讨论。
 *
 * 漏判只是退回提示词层的行为（多花一次模型往返的代价换不来收益），
 * 误判才会白花一次调用，所以下面「不该触发」的用例比「该触发」的更重要。
 */
class MemoryClaimGuardTest {

    private fun user(text: String) = AgentMessage.UserMessage(content = text)
    private fun assistant(text: String) = AgentMessage.AssistantMessage(content = text)
    private fun memoryToolResult() = AgentMessage.ToolResultMessage(
        toolName = MemoryClaimGuard.TOOL_NAME,
        result = "已成功保存记忆「x」"
    )

    @Test
    fun `声称记住且本轮没调工具时要提醒`() {
        val messages = listOf(user("我爱吃辣"), assistant("我记住了你的口味"))

        assertTrue(MemoryClaimGuard.needsReminder(messages, "我记住了你的口味"))
    }

    @Test
    fun `本轮调过 memory 工具就不再提醒`() {
        val messages = listOf(
            user("我爱吃辣"),
            assistant("好的，我这就记一下"),
            memoryToolResult(),
            assistant("已经记下你的口味了")
        )

        assertFalse(MemoryClaimGuard.needsReminder(messages, "已经记下你的口味了"))
    }

    @Test
    fun `上一轮调过工具不能豁免这一轮`() {
        val messages = listOf(
            user("上一轮的话题"),
            assistant("好的"),
            memoryToolResult(),
            user("我爱吃辣"),
            assistant("记住了，你爱吃辣")
        )

        assertTrue(MemoryClaimGuard.needsReminder(messages, "记住了，你爱吃辣"))
    }

    @Test
    fun `回忆与否定措辞不算声称写入`() {
        val texts = listOf(
            "我记得你之前说过爱吃辣",      // 回忆
            "抱歉，我没记住你说的",
            "这个我记不住",
            "不会记住这种一次性的东西",
            "记忆页里能看到所有条目"        // 普通讨论
        )

        texts.forEach { text ->
            assertFalse(MemoryClaimGuard.claimsMemorized(text), "不该触发: $text")
        }
    }

    @Test
    fun `英文的完成式与空口承诺都算声称`() {
        assertTrue(MemoryClaimGuard.claimsMemorized("Noted."))
        assertTrue(MemoryClaimGuard.claimsMemorized("I've saved that to long-term memory."))
        assertTrue(MemoryClaimGuard.claimsMemorized("I'll remember your preference."))
    }

    @Test
    fun `空回复不触发`() {
        assertFalse(MemoryClaimGuard.needsReminder(listOf(user("随便问点什么")), ""))
    }
}
