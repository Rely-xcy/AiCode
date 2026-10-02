package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 喂模型的工具结果投影：正常时省 token，带插话通知时必须让位给完整结果。
 *
 * 通知只挂在 transport JSON 顶层，投影文本里没有它；而通知在注入后就被 ack，模型这一份丢了不会重发。
 */
class ModelProjectionTest {

    @Test
    fun editFileSuccess_isProjected() {
        val text = modelToolResultText("editFile", """{"status":"success","data":{"path":"/ws/a.kt","replacements":1,"added_lines":3,"removed_lines":1}}""")
            .orEmpty()

        assertTrue(text.contains("Edited file successfully: /ws/a.kt"))
    }

    @Test
    fun resultCarryingNotifications_fallsBackToFullResult() {
        assertNull(
            modelToolResultText(
                "editFile",
                """{"status":"success","data":{"path":"/ws/a.kt","replacements":1},"notifications":[{"seq":1,"text":"先别改 B"}]}"""
            )
        )
        assertNull(
            modelToolResultText(
                "todo",
                """{"status":"success","data":{"message":"已更新「A」","text":"[x] A"},"notifications":[{"seq":2,"text":"先别改 B"}]}"""
            )
        )
    }

    @Test
    fun toolsWithoutProjection_returnNull() {
        assertNull(modelToolResultText("readFile", """{"status":"success","data":{"content":"x"}}"""))
        // 失败结果本来就不投影
        assertNull(modelToolResultText("editFile", """{"status":"error","message":"找不到文件","code":"NOT_FOUND"}"""))
    }
}
