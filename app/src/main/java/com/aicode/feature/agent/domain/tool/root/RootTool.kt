package com.aicode.feature.agent.domain.tool.root

import android.content.Context
import com.aicode.R
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val rootShell: RootShell,
    @param:ApplicationContext private val context: Context
) : AgentTool() {

    private companion object {
        const val TAG = "RootTool"
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L

        /** 回读是只读查询，给短超时即可：它挂住时不该把主命令的超时也一起吃完。 */
        const val READBACK_TIMEOUT_MS = 10_000L
    }

    override val name = "Root"

    override val description =
        "通过设备 root（su，uid 0）在宿主 Android 系统上执行**只读/调试类**命令（`dumpsys`/`getprop`/`ls`/`cat`/`ps`/`logcat -d`/`input`/`screencap`/`wm` 查询/`settings get`/`pm list`/`am stack list` 等），" +
            "用于辅助调试宿主系统与虚拟屏自动化测试。需设备已 root，且每次调用都会弹窗确认（不可「始终允许」）。" +
            "**白名单外的命令一律拒绝执行**（不提供解锁参数）：`service` 只放 `list`、`wm`/`cmd window` 只放查询、" +
            "`monkey` 全拒、`am` 只放已评估的子命令（`start`/`force-stop`/`broadcast` 这类会改运行状态的照常放行，但弹窗会标注）。" +
            "需要改系统、刷机、清数据这类操作请让用户在终端自己跑。"

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
        // 会改运行状态的命令（am start 之类）照常放行，但要让用户在弹窗里看见这一点。
        val details = buildString {
            append(context.getString(R.string.tool_root_permission_details, timeoutSeconds))
            RootCommandGuard.advisory(command)?.let { append('\n').append(it) }
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = context.getString(R.string.tool_root_permission_title),
            summary = command,
            details = details,
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：command")

        // 安全闸门先于一切：设备级的不可逆动作（刷机/格式化/写系统分区/清数据/命令拼接）直接拒绝。
        RootCommandGuard.check(command)?.let { rejection ->
            FileLogger.w(TAG, "Root 命令被安全闸门拦下：$command")
            return ToolResult.Error(rejection.reason, code = "ROOT_COMMAND_BLOCKED")
        }

        // 只有白名单（只读/调试类）命令能由 AI 自动执行。
        // 这里**不提供任何解锁参数**：能不能改系统，不由 AI 决定，也不靠「拦得住」——
        // 白名单外的需求一律回给用户，由其自行在终端执行（用户对设备负责）。
        // 首 token 虽在白名单内、但子命令越权（service call / wm size WxH / monkey 等）：给具体原因，
        // 比下面那句通用文案更能让模型换一条合法写法，而不是反复重试。
        RootCommandGuard.rejectionReason(command)?.let { reason ->
            FileLogger.w(TAG, "Root 命令子命令越权被拦下：$command")
            return ToolResult.Error(reason, code = "ROOT_COMMAND_BLOCKED")
        }

        if (!RootCommandGuard.isAllowlisted(command)) {
            return ToolResult.Error(
                "该命令不在可自动执行的白名单内（白名单为只读/调试类：dumpsys/getprop/ls/cat/ps/logcat -d/input/screencap/" +
                    "wm 查询/settings get/pm list/service list/am 的已评估子命令等）。" +
                    "为设备安全，AI 不会以 root 执行白名单外的命令。如需执行，请让用户在容器终端里自己跑。",
                code = "ROOT_COMMAND_NOT_ALLOWLISTED"
            )
        }

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
            //
            // 命令跑完再读一次实际状态：退出码只说明「命令被系统接受」，改状态类命令的成功与否要看回读
            // （见 [DeviceStateReadback]；回读命令本身都是白名单内的只读查询）。
            val readback = DeviceStateReadback.report(command) { probe ->
                val probeResult = rootShell.run(probe, READBACK_TIMEOUT_MS)
                DeviceStateReadback.Observation(probeResult.output, probeResult.exitCode)
            }
            val body = buildString {
                append("exit=").append(result.exitCode).append('\n').append(output)
                if (readback != null) append("\n\n").append(readback)
            }
            ToolResult.Success(JsonPrimitive(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Root exec 失败: $command", e)
            ToolResult.Error("执行 root 命令失败：${e.message}")
        }
    }
}
