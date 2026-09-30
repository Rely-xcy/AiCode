package com.aicode.feature.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模式提醒的两份对称：`content` 是「落库/界面那份」，`modelReminder` 是「模型可见的那份」。
 *
 * 这些用例钉的就是注入点（StatefulAgentWorkflow 构造本轮用户消息）产出的形状：
 * 提醒只进新字段、绝不拼进 content；组装请求时再由 [AgentMessage.UserMessage.modelFacingContent]
 * 拼回模型侧文本。此前提醒被拼进 content，界面里就显示成了用户/AI 说的话。
 */
class AgentMessageReminderTest {

    private val reminder = "【模式提醒】## 自动模式（AUTO MODE）\n你处于 AUTO 模式。"

    @Test
    fun `注入提醒后 content 里没有提醒文本，提醒在新字段里`() {
        val message = AgentMessage.UserMessage(content = "把提醒挪走", modelReminder = reminder)

        assertEquals("把提醒挪走", message.content)
        assertFalse(message.content.contains("模式提醒"))
        assertEquals(reminder, message.modelReminder)
    }

    @Test
    fun `发请求时提醒拼回正文末尾`() {
        val message = AgentMessage.UserMessage(content = "把提醒挪走", modelReminder = reminder)

        assertEquals("把提醒挪走\n\n$reminder", message.modelFacingContent)
    }

    @Test
    fun `没有提醒时模型侧文本就是正文`() {
        val message = AgentMessage.UserMessage(content = "普通提问")

        assertNull(message.modelReminder)
        assertEquals("普通提问", message.modelFacingContent)
    }

    @Test
    fun `正文为空时不留前导空行`() {
        val message = AgentMessage.UserMessage(content = "   ", modelReminder = reminder)

        assertEquals(reminder, message.modelFacingContent)
    }

    @Test
    fun `提醒不参与正文相等性判断`() {
        val bare = AgentMessage.UserMessage(content = "同一句话")
        val injected = bare.copy(modelReminder = reminder)

        // 历史重建/界面比对看的是 content，提醒不该让同一条消息判为不同
        assertEquals(bare.content, injected.content)
        assertTrue(injected.modelFacingContent.length > bare.modelFacingContent.length)
    }
}
