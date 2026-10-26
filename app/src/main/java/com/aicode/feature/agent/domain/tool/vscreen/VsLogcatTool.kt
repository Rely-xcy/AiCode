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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 读虚拟屏目标 app 的 logcat，用于断言「有没有崩/卡」（设计 §3.4 的断言增强）。
 *
 * 省 token 的做法：先给一句结论（发现几条 crash/ANR 关键字），再附末尾原文；
 * 想深挖再带关键字重查。这条能力 shell（uid 2000）做不了——读别的应用日志要更高权限。
 */
class VsLogcatTool @Inject constructor(
    private val controller: VirtualScreenController
) : AbstractContextualTool() {

    override val name = "vscreen_logcat"

    override val description =
        "读取虚拟屏目标 app 最近的 logcat，用于断言崩溃/ANR。返回一句结论 + 末尾日志原文。" +
            "默认读当前虚拟屏上的目标 app。"

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "package" to ToolParameter(
            name = "package",
            type = ParameterType.STRING,
            description = "要读取的目标包名；省略则用当前虚拟屏上的目标 app",
            required = false
        ),
        "lines" to ToolParameter(
            name = "lines",
            type = ParameterType.INTEGER,
            description = "返回的日志末尾行数，默认 $DEFAULT_LINES，上限 $MAX_LINES",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val packageName = args["package"]?.jsonPrimitive?.contentOrNull
        val lines = (args["lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
        return controller.logcat(packageName, lines).fold(
            onSuccess = { ToolResult.Success(JsonPrimitive(it)) },
            onFailure = { ToolResult.Error(it.message ?: "读取 logcat 失败", code = "VSCREEN_LOGCAT_FAILED") }
        )
    }

    private companion object {
        const val DEFAULT_LINES = 200
        const val MAX_LINES = 2_000
    }
}
