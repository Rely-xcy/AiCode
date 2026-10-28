package com.aicode.feature.agent.domain.tool.vscreen

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.UiTreeSnapshot
import com.aicode.feature.agent.domain.vscreen.VirtualScreenController
import kotlinx.coroutines.delay
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
) : AbstractContextualTool() {

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
        // 先把「要注入什么」定下来，再取注入前快照：参数不合法时不必白读一次控件树。
        val inject: suspend () -> Result<String>
        when (action) {
            "tap" -> {
                val x = args["x"]?.jsonPrimitive?.intOrNull
                val y = args["y"]?.jsonPrimitive?.intOrNull
                if (x == null || y == null) return ToolResult.Error("tap 需要 x 与 y")
                inject = { controller.tap(x, y) }
            }
            "swipe" -> {
                val x1 = args["x"]?.jsonPrimitive?.intOrNull
                val y1 = args["y"]?.jsonPrimitive?.intOrNull
                val x2 = args["x2"]?.jsonPrimitive?.intOrNull
                val y2 = args["y2"]?.jsonPrimitive?.intOrNull
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return ToolResult.Error("swipe 需要 x/y/x2/y2")
                }
                val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SWIPE_MS
                inject = { controller.swipe(x1, y1, x2, y2, duration) }
            }
            "text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("text 动作需要 text 参数")
                inject = { controller.text(text) }
            }
            "key" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("key 动作需要 key 参数")
                inject = { controller.key(key) }
            }
            "back" -> {
                inject = { controller.key("KEYCODE_BACK") }
            }
            "home" -> {
                inject = { controller.key("KEYCODE_HOME") }
            }
            else -> return ToolResult.Error("未知 action：$action（支持 tap/swipe/text/key/back/home）")
        }

        // 注入前先拿一个界面指纹当对照：`input` 的成功只说明事件被接受，没有前后对照就分不出
        // 「命令跑了」和「界面真的变了」（见 controller 的 inject）。
        // 优先用 vscreen_observe 刚留下的那份：模型看准位置再点是最常见的流程，那次读屏不应该白读。
        val before = controller.treeFingerprintForCompare(VERIFY_NODE_LIMIT).getOrNull()
        val injected = inject()
        val message = injected.getOrElse {
            return ToolResult.Error(it.message ?: "注入失败", code = "VSCREEN_INPUT_FAILED")
        }
        return ToolResult.Success(JsonPrimitive(message + verifyUi(before)))
    }

    /**
     * 注入后回读界面指纹，与注入前对比：变了才有「界面已响应」的凭据。
     *
     * 先立即读一次——多数界面在一两帧内就变了，变了就直接下结论，不必固定等一段；
     * 没变才等 [VERIFY_DELAY_MS] 再读一次，仍没变就报「未确认」，不再继续等。
     *
     * 不变不等于失败（可能点在无反应区域、变化更晚，或落在指纹深度之外），所以报「未确认」
     * 而不是「失败」；拿不到对照（无障碍未开启）也报未确认，不靠注入命令的退出码糊过去。
     */
    private suspend fun verifyUi(before: UiTreeSnapshot?): String {
        if (before == null) {
            return "\n[状态回读] 未确认｜界面已响应注入：注入前读不到控件树（无障碍未开启或未取到节点），" +
                "无法回读界面状态；要确认请用 vscreen_observe（mode=tree 或 screenshot）"
        }
        val immediate = controller.treeFingerprint(VERIFY_NODE_LIMIT).getOrNull()
        if (immediate != null && !immediate.sameUiAs(before)) {
            return changed("注入后立即读到变化")
        }
        delay(VERIFY_DELAY_MS)
        val later = controller.treeFingerprint(VERIFY_NODE_LIMIT).getOrElse { failure ->
            return "\n[状态回读] 未确认｜界面已响应注入：注入后回读控件树失败（${failure.message}）"
        }
        if (later.sameUiAs(before)) {
            return "\n[状态回读] 未确认｜界面已响应注入：控件树在注入前后一致（比较前 $VERIFY_NODE_LIMIT 个节点），" +
                "这既可能是没生效，也可能是这一下不改变界面 / 变化更晚或落在比较范围之外——别当成「界面已变」"
        }
        return changed("等 ${VERIFY_DELAY_MS}ms 后读到变化")
    }

    private fun changed(detail: String): String =
        "\n[状态回读] 一致｜界面已响应注入：控件树在注入前后不同（比较前 $VERIFY_NODE_LIMIT 个节点，$detail）"

    private companion object {
        const val DEFAULT_SWIPE_MS = 300

        /**
         * 回读只用来做前后对比，不需要整棵树：节点越少，每次注入附带的读屏开销越低。
         * 与 [UiTreeSnapshot.COMPARE_NODES] 同源，否则读到的深度和指纹的深度对不上。
         */
        const val VERIFY_NODE_LIMIT = UiTreeSnapshot.COMPARE_NODES

        /** 立即读没变时再给界面的宽限：约两帧量级，够普通点击把状态刷出来。 */
        const val VERIFY_DELAY_MS = 120L
    }
}
