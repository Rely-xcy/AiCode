package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 历史数据兼容：早期版本把模式提醒拼在用户消息正文末尾落库，界面渲染时按 [MODE_REMINDER_PREFIX]
 * 折成一条灰色提示条，气泡只显示用户原话。新数据不会走这条路（提醒在 modelReminder 字段里，
 * 界面完全不显示）。
 */
class LegacyModeReminderTest {

    private fun entity(content: String, modelReminder: String? = null) = AgentMessageEntity(
        id = "m1",
        sessionId = "s1",
        role = MessageRole.USER.name,
        content = content,
        timestamp = 1L,
        modelReminder = modelReminder
    )

    @Test
    fun `按前缀切出用户原话与提醒`() {
        val split = splitLegacyModeReminder("把提醒挪走\n\n【模式提醒】## 自动模式\n你处于 AUTO 模式。")

        assertEquals("把提醒挪走", split?.first)
        assertEquals("【模式提醒】## 自动模式\n你处于 AUTO 模式。", split?.second)
    }

    @Test
    fun `正文里没有前缀就不折`() {
        assertNull(splitLegacyModeReminder("普通提问"))
    }

    @Test
    fun `整条正文就是提醒时用户原话为空`() {
        val split = splitLegacyModeReminder("【模式提醒】AUTO 模式正文")

        assertEquals("", split?.first)
        assertEquals("【模式提醒】AUTO 模式正文", split?.second)
    }

    @Test
    fun `历史行渲染：提醒折出气泡，气泡只剩用户原话`() {
        val ui = entity("把提醒挪走\n\n【模式提醒】AUTO 模式正文").toUIMessage()

        assertEquals("把提醒挪走", ui.content)
        assertEquals("【模式提醒】AUTO 模式正文", ui.legacyModeReminder)
    }

    @Test
    fun `新数据：提醒在独立字段时不产生提示条`() {
        val ui = entity("把提醒挪走", modelReminder = "【模式提醒】AUTO 模式正文").toUIMessage()

        assertEquals("把提醒挪走", ui.content)
        assertNull(ui.legacyModeReminder)
    }
}
