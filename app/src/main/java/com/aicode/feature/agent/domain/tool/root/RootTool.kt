package com.aicode.feature.agent.domain.tool.root

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vscreen.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 设备 root 命令工具：以 `su`（uid 0）在**宿主 Android 系统**上执行命令。
 *
 * 与 [com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool] 的分工：
 * - `Shizuku`：以 adb shell（uid 2000）身份，需要用户装并启动 Shizuku；
 * - `Root`（本工具）：以设备 root（Magisk 的 `su`）身份，能力更全（能建虚拟屏、读其它应用私有目录），
 *   不依赖 Shizuku，但要求设备已 root。
 *
 * 权限：一律弹窗（与 Shizuku 同等对待，AUTO 下也弹），不支持「始终允许」——它直接以 root 改宿主系统。
 * 用途：辅助调试宿主系统（`pm`/`am`/`cmd`/`dumpsys`/`input`/`screencap` 等）与虚拟屏自动化测试。
 */
class RootTool @Inject constructor(
    private val rootShell: RootShell
) : AgentTool() {

    private companion object {
        const val TAG = "RootTool"
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Root"

    override val description =
        "通过设备 root（su，uid 0）在宿主 Android 系统上执行命令（`pm`/`am`/`cmd`/`dumpsys`/`input`/`screencap` 等），" +
            "用于辅助调试宿主系统与虚拟屏自动化测试。需设备已 root，且每次调用都会弹窗确认（不可「始终允许」）。" +
            "设备未 root 时会返回明确原因，不要反复重试。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要以 root 身份执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行 root 命令",
            summary = command,
            details = "将以设备 root（uid 0）身份在 Android 系统上执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：command")

        if (!rootShell.isAvailable()) {
            return ToolResult.Error(rootShell.unavailableReason(), code = "ROOT_NOT_AVAILABLE")
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Root exec (timeout=${timeoutMs}ms): $command")
            val result = rootShell.run(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Root exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            // 退出码回给模型：与 Bash 工具一致，非 0 是「命令失败」而不是「工具失败」，
            // 由模型决定要不要重试/换写法（命令本身的语义错误不该被吞成工具错误）。
            ToolResult.Success(JsonPrimitive("exit=${result.exitCode}\n$output"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Root exec 失败: $command", e)
            ToolResult.Error("执行 root 命令失败：${e.message}")
        }
    }
}
