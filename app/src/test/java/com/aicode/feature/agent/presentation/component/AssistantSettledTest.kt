package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮助手消息「是否已在消息列表里就位」（[isAssistantSettled]）的判据：底部残留流式 / 思考气泡
 * 是否该退休，全靠它。
 *
 * 判据认的是 ViewModel 显式交接的行 id（AIAgentViewModel.currentSessionPendingAssistantRowId）：
 * 流式已停（streamingText / streamingReasoning 均为 null）、id 已登记、且该 id 已出现在 messages 里，
 * 三者齐备才算就位。它取代了旧的前缀猜同源 + 双向包含 + 4s 强制退休，故旧的「前缀匹配」「超时兜底」
 * 语义在这里不再覆盖。
 */
class AssistantSettledTest {

    private fun user(id: String) = AgentUIMessage(id = id, role = MessageRole.USER, content = "用户的问题")

    private fun assistant(id: String, content: String = "助手的回复正文") =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = content)

    /** ① 流式已停、id 已登记、该 id 已在 messages 里 → 就位。 */
    @Test
    fun settledWhenNothingStreamingIdRegisteredAndPresent() {
        assertTrue(
            isAssistantSettled(
                streamingText = null,
                streamingReasoning = null,
                pendingRowId = "a1",
                messages = listOf(user("u1"), assistant("a1"))
            )
        )
    }

    /** ② 正文仍在流式写（streamingText != null）：即使 id 已在 messages 里，也不就位。 */
    @Test
    fun notSettledWhileStreamingText() {
        assertFalse(
            isAssistantSettled(
                streamingText = "正在输出的正文",
                streamingReasoning = null,
                pendingRowId = "a1",
                messages = listOf(user("u1"), assistant("a1"))
            )
        )
    }

    /** ② 思考仍在流式写（streamingReasoning != null）：同理不就位。 */
    @Test
    fun notSettledWhileStreamingReasoning() {
        assertFalse(
            isAssistantSettled(
                streamingText = null,
                streamingReasoning = "正在输出的思考",
                pendingRowId = "a1",
                messages = listOf(user("u1"), assistant("a1"))
            )
        )
    }

    /** ③ pendingRowId 为 null（本轮没有登记任何助手行）：不就位，靠「无目标行」信号兜底退休。 */
    @Test
    fun notSettledWhenPendingRowIdNull() {
        assertFalse(
            isAssistantSettled(
                streamingText = null,
                streamingReasoning = null,
                pendingRowId = null,
                messages = listOf(user("u1"), assistant("a1"))
            )
        )
    }

    /** ④ 登记了 id，但该 id 尚未在 messages 里回流：不就位（继续撑住尾巴）。 */
    @Test
    fun notSettledWhenRegisteredIdNotYetInMessages() {
        assertFalse(
            isAssistantSettled(
                streamingText = null,
                streamingReasoning = null,
                pendingRowId = "a1",
                messages = listOf(user("u1"), assistant("a2"))
            )
        )
    }

    /** 列表为空、已登记 id：id 不在其中，不就位。 */
    @Test
    fun notSettledWhenMessagesEmpty() {
        assertFalse(
            isAssistantSettled(
                streamingText = null,
                streamingReasoning = null,
                pendingRowId = "a1",
                messages = emptyList()
            )
        )
    }
}
