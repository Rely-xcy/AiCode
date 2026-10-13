package com.aicode.feature.agent.domain.tool.vscreen

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.VirtualScreenGuard
import com.aicode.feature.agent.domain.vscreen.VirtualScreenManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 创建虚拟屏并在其上启动目标 app。
 *
 * 这是虚拟屏功能的**唯一 ASK 点**（设计 §4.1）：用户放行这一场，本会话后续的
 * input / observe 免弹窗，直到 vscreen_stop 收走授权。
 */
class VsStartTool @Inject constructor(
    private val manager: VirtualScreenManager,
    private val guard: VirtualScreenGuard
) : AbstractContextualTool() {

    override val name = "vscreen_start"

    override val description =
        "在一块用户看不见的虚拟屏上启动目标 app，用于自动化测试（AI 自己在屏里操作、观察，真机前台零感知）。" +
            "需要设备已 root；创建成功返回 displayId，后续用 vscreen_input / vscreen_observe 操作该屏，用完必须 vscreen_stop。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "package" to ToolParameter(
            name = "package",
            type = ParameterType.STRING,
            description = "目标 app 包名，如 com.example.app",
            required = true
        ),
        "activity" to ToolParameter(
            name = "activity",
            type = ParameterType.STRING,
            description = "要启动的 Activity 全名（相对包名，如 .MainActivity）。省略则按 LAUNCHER 入口启动。",
            required = false
        ),
        "width" to ToolParameter(
            name = "width",
            type = ParameterType.INTEGER,
            description = "虚拟屏宽度（像素），默认 $DEFAULT_WIDTH",
            required = false
        ),
        "height" to ToolParameter(
            name = "height",
            type = ParameterType.INTEGER,
            description = "虚拟屏高度（像素），默认 $DEFAULT_HEIGHT",
            required = false
        ),
        "density" to ToolParameter(
            name = "density",
            type = ParameterType.INTEGER,
            description = "虚拟屏密度（dpi），默认 $DEFAULT_DENSITY",
            required = false
        ),
        "lifetimeMinutes" to ToolParameter(
            name = "lifetimeMinutes",
            type = ParameterType.INTEGER,
            description = "单屏存活上限（分钟），默认 $DEFAULT_LIFETIME_MINUTES，上限 $MAX_LIFETIME_MINUTES；到点后下一次操作会自动销毁该屏。",
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: "未知包名"
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "创建虚拟屏（隐身测试屏）",
            summary = "在虚拟屏上启动 $pkg",
            details = "将创建一块用户不可见的虚拟显示屏并在其上启动目标 app；本场会话内后续操作不再逐次确认，" +
                "直到 vscreen_stop。虚拟屏上的操作不会影响真机前台。",
            argsPreview = argsPreview
        )
    }

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文，无法建立会话级授权。")
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：package")

        guard.blockReason(pkg)?.let { return ToolResult.Error(it, code = "VSCREEN_BLOCKED") }

        val activity = args["activity"]?.jsonPrimitive?.contentOrNull
        val width = args["width"]?.jsonPrimitive?.intOrNull ?: DEFAULT_WIDTH
        val height = args["height"]?.jsonPrimitive?.intOrNull ?: DEFAULT_HEIGHT
        val density = args["density"]?.jsonPrimitive?.intOrNull ?: DEFAULT_DENSITY
        val lifetimeMinutes = (args["lifetimeMinutes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIFETIME_MINUTES)
            .coerceIn(1, MAX_LIFETIME_MINUTES)

        return manager.start(
            sessionId = sessionId,
            packageName = pkg,
            activity = activity,
            width = width,
            height = height,
            density = density,
            lifetimeMs = lifetimeMinutes * 60_000L
        ).fold(
            onSuccess = { screen ->
                // 授权随屏生效：只有真建成了才放行后续操作。
                guard.authorize(sessionId)
                ToolResult.Success(
                    JsonPrimitive(
                        "虚拟屏已创建：displayId=${screen.displayId}，目标=$pkg（${width}x$height @${density}dpi）。" +
                            "用 vscreen_observe 截图查看当前界面，用 vscreen_input 操作；结束务必 vscreen_stop。"
                    )
                )
            },
            onFailure = { ToolResult.Error(it.message ?: "创建虚拟屏失败", code = "VSCREEN_START_FAILED") }
        )
    }

    private companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DENSITY = 320
        const val DEFAULT_LIFETIME_MINUTES = 30
        const val MAX_LIFETIME_MINUTES = 180
    }
}
