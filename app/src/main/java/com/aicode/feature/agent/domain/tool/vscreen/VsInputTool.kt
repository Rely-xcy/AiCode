package com.aicode.feature.agent.domain.tool.vscreen

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.VirtualScreenController
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 在虚拟屏上注入输入。
 *
 * 权限策略默认 AUTO_APPROVE：授权已在 [VsStartTool] 那一处一次性建立，
 * 屏存活期间不必逐次弹窗（设计 §4.1）——屏内操作碰不到真机前台，边界动作才需要把关。
 */
class VsInputTool @Inject constructor(
    private val controller: VirtualScreenController
) : AgentTool() {

    override val name = "vscreen_input"

    override val description =
        "在虚拟屏上注入输入：tap（点击）/ swipe（滑动）/ text（输入文本）/ key（按键）/ back / home。" +
            "坐标是虚拟屏像素坐标（先用 vscreen_observe 截图确认位置）。"

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "动作类型",
            required = true,
            enum = listOf("tap", "swipe", "text", "key", "back", "home")
        ),
        "x" to ToolParameter(name = "x", type = ParameterType.INTEGER, description = "tap 的 x 坐标", required = false),
        "y" to ToolParameter(name = "y", type = ParameterType.INTEGER, description = "tap 的 y 坐标", required = false),
        "x2" to ToolParameter(name = "x2", type = ParameterType.INTEGER, description = "swipe 终点 x 坐标", required = false),
        "y2" to ToolParameter(name = "y2", type = ParameterType.INTEGER, description = "swipe 终点 y 坐标", required = false),
        "duration" to ToolParameter(
            name = "duration",
            type = ParameterType.INTEGER,
            description = "swipe 持续时间（毫秒），默认 300",
            required = false
        ),
        "text" to ToolParameter(name = "text", type = ParameterType.STRING, description = "text 动作要输入的文本", required = false),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "key 动作的按键名（KEYCODE_* 形式，如 KEYCODE_ENTER）",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：action")
        val result = when (action) {
            "tap" -> {
                val x = args["x"]?.jsonPrimitive?.intOrNull
                val y = args["y"]?.jsonPrimitive?.intOrNull
                if (x == null || y == null) return ToolResult.Error("tap 需要 x 与 y")
                controller.tap(x, y)
            }
            "swipe" -> {
                val x1 = args["x"]?.jsonPrimitive?.intOrNull
                val y1 = args["y"]?.jsonPrimitive?.intOrNull
                val x2 = args["x2"]?.jsonPrimitive?.intOrNull
                val y2 = args["y2"]?.jsonPrimitive?.intOrNull
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return ToolResult.Error("swipe 需要 x/y/x2/y2")
                }
                controller.swipe(x1, y1, x2, y2, args["duration"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SWIPE_MS)
            }
            "text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("text 动作需要 text 参数")
                controller.text(text)
            }
            "key" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("key 动作需要 key 参数")
                controller.key(key)
            }
            "back" -> controller.key("KEYCODE_BACK")
            "home" -> controller.key("KEYCODE_HOME")
            else -> return ToolResult.Error("未知 action：$action（支持 tap/swipe/text/key/back/home）")
        }
        return result.fold(
            onSuccess = { ToolResult.Success(JsonPrimitive(it)) },
            onFailure = { ToolResult.Error(it.message ?: "注入失败", code = "VSCREEN_INPUT_FAILED") }
        )
    }

    private companion object {
        const val DEFAULT_SWIPE_MS = 300
    }
}
