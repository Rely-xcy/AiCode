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
        "观察虚拟屏当前界面：默认 mode=tree 返回控件树（文本/坐标/可点性，省 token）；" +
            "mode=screenshot 截图存文件并返回路径（用读图工具查看）。" +
            "确认了元素位置后再用 vscreen_input 操作。"

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "mode" to ToolParameter(
            name = "mode",
            type = ParameterType.STRING,
            description = "观察方式：tree（控件树，默认，省 token）/ screenshot（截图落盘）",
            required = false,
            enum = listOf("tree", "screenshot")
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "控件树最大节点数（mode=tree 时生效），默认 $DEFAULT_NODE_LIMIT，上限 $MAX_NODE_LIMIT",
            required = false
        ),
        "outputDir" to ToolParameter(
            name = "outputDir",
            type = ParameterType.STRING,
            description = "截图落盘目录（mode=screenshot 时生效）；省略则写到 <工作区>/.aicode/vscreen",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "tree"
        if (mode == "tree") {
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_NODE_LIMIT)
                .coerceIn(1, MAX_NODE_LIMIT)
            return controller.tree(limit).fold(
                onSuccess = { ToolResult.Success(JsonPrimitive(it)) },
                onFailure = { ToolResult.Error(it.message ?: "读取控件树失败", code = "VSCREEN_TREE_FAILED") }
            )
        }
        if (mode != "screenshot") {
            return ToolResult.Error("未知 mode=$mode（支持 tree / screenshot）")
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

    private companion object {
        const val DEFAULT_NODE_LIMIT = 200
        const val MAX_NODE_LIMIT = 500
    }
}
