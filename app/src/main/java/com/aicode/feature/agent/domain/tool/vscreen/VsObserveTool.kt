package com.aicode.feature.agent.domain.tool.vscreen

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.VirtualScreenController
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 观察虚拟屏：截一帧落盘，返回文件路径。
 *
 * 阶段 1 只做截图模式（设计 §5）；无障碍树模式（低 token 优先、按 displayId 过滤）
 * 放在阶段 2。图片路径交给模型的读图工具看，大图由 ToolOutputStore 指针化，不进上下文正文。
 */
class VsObserveTool @Inject constructor(
    private val controller: VirtualScreenController
) : AbstractContextualTool() {

    override val name = "vscreen_observe"

    override val description =
        "观察虚拟屏当前画面：截图并存成文件，返回文件路径（用 readFile / 读图工具查看）。" +
            "确认了界面元素位置后再用 vscreen_input 操作。"

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "mode" to ToolParameter(
            name = "mode",
            type = ParameterType.STRING,
            description = "观察方式；当前仅支持 screenshot",
            required = false,
            enum = listOf("screenshot")
        ),
        "outputDir" to ToolParameter(
            name = "outputDir",
            type = ParameterType.STRING,
            description = "截图落盘目录；省略则写到 <工作区>/.aicode/vscreen",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "screenshot"
        if (mode != "screenshot") {
            return ToolResult.Error("暂不支持 mode=$mode（当前仅 screenshot）")
        }
        val outputDir = args["outputDir"]?.jsonPrimitive?.contentOrNull
            ?: run {
                val root = context.projectRoot.ifBlank { "/root/workspace" }
                "$root/.aicode/vscreen"
            }
        return controller.screenshot(outputDir).fold(
            onSuccess = { path ->
                ToolResult.Success(JsonPrimitive("虚拟屏截图已保存：$path（用读图工具查看）"))
            },
            onFailure = { ToolResult.Error(it.message ?: "截图失败", code = "VSCREEN_OBSERVE_FAILED") }
        )
    }
}
