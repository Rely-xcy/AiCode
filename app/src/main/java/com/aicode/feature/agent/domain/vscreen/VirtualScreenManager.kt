package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏生命周期：创建 / 查询 / 销毁，并持有「当前屏」这一份全局状态。
 *
 * 同一时刻只允许一块屏（设计 §4.3）：单屏足够，多屏只会让焦点与资源管理复杂化。
 *
 * 命令模板走 root 通道（[RootShell]）。**这些模板是实验性的、未在真机验证过**——
 * 阶段 0 的三条闸门（能否创建非 0 displayId 交互屏、`input -d` 是否生效、帧能否取到）
 * 正是用来验证它们的；验证结论出来前，失败原因原样回给模型，不做掩盖。
 *
 * [temp] root 分支实验件。
 */
@Singleton
class VirtualScreenManager @Inject constructor(
    private val rootShell: RootShell
) {

    /** 一块存活的虚拟屏。 */
    data class Screen(
        val displayId: Int,
        val sessionId: String,
        val packageName: String,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    var current: Screen? = null
        private set

    /** 是否存在存活中的屏（工具侧做会话授权与并发判定用）。 */
    val hasScreen: Boolean get() = current != null

    /**
     * 创建虚拟屏并在其上启动目标 app。
     *
     * 参数全部显式传入（阶段 0 demo 也走同一条路径，不硬编码包名/尺寸）。
     */
    suspend fun start(
        sessionId: String,
        packageName: String,
        activity: String?,
        width: Int,
        height: Int,
        density: Int
    ): Result<Screen> {
        if (current != null) {
            return Result.failure(IllegalStateException("已有虚拟屏在运行（displayId=${current?.displayId}），先 vscreen_stop 再开新的。"))
        }
        if (!rootShell.isAvailable()) {
            return Result.failure(IllegalStateException("root 通道不可用：${rootShell.unavailableReason()}"))
        }

        val name = "aicode-vscreen-${System.currentTimeMillis()}"
        val create = rootShell.run(
            CREATE_TEMPLATE.format(name, width, height, density),
            CREATE_TIMEOUT_MS
        )
        val displayId = parseDisplayId(create.output)
        if (!create.ok || displayId == null) {
            return Result.failure(
                IllegalStateException(
                    "创建虚拟屏失败（exit=${create.exitCode}）。原始输出：${create.output.trim().take(400)}"
                )
            )
        }

        val launch = rootShell.run(launchCommand(displayId, packageName, activity), LAUNCH_TIMEOUT_MS)
        if (!launch.ok) {
            // 启动失败就把刚建的屏收拾掉，不留半成品。
            rootShell.run(REMOVE_TEMPLATE.format(displayId), REMOVE_TIMEOUT_MS)
            return Result.failure(
                IllegalStateException(
                    "在虚拟屏上启动 $packageName 失败（exit=${launch.exitCode}）。原始输出：${launch.output.trim().take(400)}"
                )
            )
        }

        val screen = Screen(displayId = displayId, sessionId = sessionId, packageName = packageName)
        current = screen
        FileLogger.i(TAG, "虚拟屏已就绪：displayId=$displayId，目标=$packageName")
        return Result.success(screen)
    }

    /** 销毁当前屏。没有屏时返回失败（调用方据此提示「本来就没开」）。 */
    suspend fun stop(): Result<Unit> {
        val screen = current ?: return Result.failure(IllegalStateException("当前没有存活的虚拟屏。"))
        val remove = rootShell.run(REMOVE_TEMPLATE.format(screen.displayId), REMOVE_TIMEOUT_MS)
        current = null
        if (!remove.ok) {
            return Result.failure(
                IllegalStateException(
                    "销毁虚拟屏失败（exit=${remove.exitCode}）。原始输出：${remove.output.trim().take(400)}"
                )
            )
        }
        FileLogger.i(TAG, "虚拟屏已销毁：displayId=${screen.displayId}")
        return Result.success(Unit)
    }

    /** 取当前屏，没有时返回失败并给出可照做的提示。 */
    fun requireScreen(): Result<Screen> =
        current?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("当前没有存活的虚拟屏，先调用 vscreen_start 创建。"))

    private fun launchCommand(displayId: Int, packageName: String, activity: String?): String =
        if (activity != null) {
            "am start --display $displayId -n $packageName/$activity"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
        }

    /** 从命令输出里抠 displayId：不同 ROM 输出格式不一，按「display + 数字」宽松匹配。 */
    private fun parseDisplayId(output: String): Int? =
        DISPLAY_ID_REGEX.find(output)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        const val TAG = "VirtualScreenManager"

        const val CREATE_TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val REMOVE_TIMEOUT_MS = 10_000L

        /** [temp] 未验证：`cmd display create-virtual-display` 并非所有 ROM 都提供同名子命令。 */
        const val CREATE_TEMPLATE = "cmd display create-virtual-display --name %s --width %d --height %d --density %d"

        /** [temp] 未验证：与 CREATE_TEMPLATE 对称的销毁命令。 */
        const val REMOVE_TEMPLATE = "cmd display remove-virtual-display --display %d"

        val DISPLAY_ID_REGEX = Regex("(?i)display[\\s:=#]+(\\d+)")
    }
}
