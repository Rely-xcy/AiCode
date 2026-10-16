package com.aicode.feature.agent.domain.tool.root

import com.aicode.feature.agent.domain.vscreen.RootShell
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RootTool] 的参数/授权描述与「无 root 时不给假成功」的行为。
 *
 * 不依赖设备：测试环境里探不到可用的 su，`execute` 应返回明确错误码而不是抛异常。
 */
class RootToolTest {

    private val tool = RootTool(RootShell())

    @Test
    fun `工具名与参数契约`() {
        assertEquals("Root", tool.name)
        assertTrue(tool.parameters.containsKey("command"))
        assertTrue(tool.parameters.containsKey("timeout"))
        assertEquals(true, tool.parameters["command"]?.required)
    }

    @Test
    fun `授权弹窗带上命令与超时`() {
        val request = tool.buildPermissionRequest(
            callId = "call-1",
            args = mapOf("command" to JsonPrimitive("dumpsys display"), "timeout" to JsonPrimitive(30)),
            argsPreview = "dumpsys display"
        )
        assertEquals("Root", request.toolName)
        assertEquals("dumpsys display", request.summary)
        assertTrue(request.details.contains("30 秒"))
    }

    @Test
    fun `缺少 command 时直接报参数错误`() = runBlocking {
        val result = tool.execute(emptyMap())
        assertTrue(result is com.aicode.feature.agent.domain.tool.ToolResult.Error)
    }
}
