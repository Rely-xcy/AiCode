package com.aicode.feature.agent.domain.tool.vscreen

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.VirtualScreenGuard
import com.aicode.feature.agent.domain.vscreen.VirtualScreenManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/**
 * 销毁虚拟屏并收回会话授权。
 *
 * 收尾必须显式调用（设计 §3.3）：屏不销毁会一直占着 displayId 与截图目录；
 * 授权也随 stop 失效，下次要重新确认。
 */
class VsStopTool @Inject constructor(
    private val manager: VirtualScreenManager,
    private val guard: VirtualScreenGuard
) : AgentTool() {

    override val name = "vscreen_stop"

    override val description =
        "销毁虚拟屏并收回本会话的虚拟屏授权。测试结束后必须调用，否则屏会一直存活。"

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = emptyMap()

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId
        val result = manager.stop()
        // 无论销毁成功还是「本来就没开」，授权都必须收回：授权不能越过屏的生命周期。
        if (sessionId != null) guard.revoke(sessionId)
        return result.fold(
            onSuccess = { ToolResult.Success(JsonPrimitive("虚拟屏已销毁，本会话授权已收回。")) },
            onFailure = { ToolResult.Error(it.message ?: "销毁虚拟屏失败", code = "VSCREEN_STOP_FAILED") }
        )
    }
}
