package com.aicode.feature.agent.domain.tool.root

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.vscreen.RootShell
import io.mockk.every
import io.mockk.mockk
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

    /** 弹窗文案取自 `strings.xml`，这里只 stub 本测试会读的两条（其余路径不触发）。 */
    private val context = mockk<Context>().apply {
        every { getString(R.string.tool_root_permission_title) } returns "确认执行 root 命令"
        every { getString(R.string.tool_root_permission_details, *anyVararg()) } returns
            "将以设备 root（uid 0）身份在 Android 系统上执行（仅限只读/调试类命令）。\n超时：30 秒"
    }

    private val tool = RootTool(RootShell(), context)

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

    @Test
    fun `子命令越权时直接回具体原因`() = runBlocking {
        // `service call` 首 token 在白名单内，但子命令越权：应回带理由的 ROOT_COMMAND_BLOCKED，
        // 而不是笼统的「不在白名单」。
        val result = tool.execute(mapOf("command" to JsonPrimitive("service call activity 1")))
        assertTrue(result is com.aicode.feature.agent.domain.tool.ToolResult.Error)
        val error = result as com.aicode.feature.agent.domain.tool.ToolResult.Error
        assertEquals("ROOT_COMMAND_BLOCKED", error.code)
        assertTrue(error.message.contains("binder"))
    }

    @Test
    fun `会改运行状态的命令在弹窗里标注`() {
        val request = tool.buildPermissionRequest(
            callId = "call-2",
            args = mapOf("command" to JsonPrimitive("am force-stop com.tencent.mm")),
            argsPreview = "am force-stop com.tencent.mm"
        )
        assertTrue(request.details.contains("改变设备运行状态"))
    }
}
