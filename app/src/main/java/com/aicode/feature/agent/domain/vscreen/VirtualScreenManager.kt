package com.aicode.feature.agent.domain.vscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏生命周期：创建 / 查询 / 销毁，并持有「当前屏」这一份全局状态。
 *
 * 同一时刻只允许一块屏（设计 §4.3）：单屏足够，多屏只会让焦点与资源管理复杂化。
 *
 * **建屏走本进程的 [DisplayManager.createVirtualDisplay] API**——真机验证结论（2026-10-14，
 * MIUI 14 / Android 13）：shell 侧没有任何「创建虚拟屏」的命令（AOSP 的 `cmd display` 没有
 * `create-virtual-display`，MIUI 也没加），原方案的 `cmd display create-virtual-display` 是
 * 臆造命令、真机必失败。API 建屏不需要 root：App 作为屏的 owner 天然有权限，Surface 传 null
 *（不渲染、零耗电，观察走无障碍树 / `screencap -d`）。
 *
 * root 通道仍保留，但只负责**屏上操作**：`am start --display`、`input -d`、`screencap -d`、
 * `logcat`——这些命令真机已验证有效（见 [VirtualScreenController]）。
 */
@Singleton
class VirtualScreenManager @Inject constructor(
    private val rootShell: RootShell,
    @ApplicationContext private val appContext: Context
) {

    /** 一块存活的虚拟屏。 */
    data class Screen(
        val displayId: Int,
        val sessionId: String,
        val packageName: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        /** 存活上限到期时间戳（含）：到点后下一次工具调用会把它自动销毁。 */
        val expiresAtMs: Long = createdAtMs + DEFAULT_LIFETIME_MS
    )

    @Volatile
    var current: Screen? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null

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
        density: Int,
        lifetimeMs: Long = DEFAULT_LIFETIME_MS
    ): Result<Screen> {
        if (current != null) {
            return Result.failure(IllegalStateException("已有虚拟屏在运行（displayId=${current?.displayId}），先 vscreen_stop 再开新的。"))
        }
        if (!rootShell.isAvailable()) {
            return Result.failure(IllegalStateException("root 通道不可用：${rootShell.unavailableReason()}"))
        }

        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return Result.failure(IllegalStateException("取不到 DisplayManager，无法创建虚拟屏。"))
        val name = "aicode-vscreen-${System.currentTimeMillis()}"
        val display: VirtualDisplay? = try {
            // 不带 TRUSTED（需系统权限 ADD_TRUSTED_DISPLAY，普通 app 拿不到，会被 CTS 确认的
            // SecurityException 拒）：PUBLIC + OWN_CONTENT_ONLY 即可让第三方 Activity 落屏。
            // Surface 传 null：不渲染、零耗电；观察走无障碍树 / screencap -d。
            dm.createVirtualDisplay(
                name,
                width,
                height,
                density,
                null as Surface?,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            )
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("创建虚拟屏失败：${e.message}", e))
        }
        // displayId 从 display 对象拿；createVirtualDisplay 返回 null 仅在极端异常时。
        val displayId = display?.display?.displayId
            ?: run {
                display?.release()
                return Result.failure(IllegalStateException("创建虚拟屏返回空 display。"))
            }

        val launch = rootShell.run(launchCommand(displayId, packageName, activity), LAUNCH_TIMEOUT_MS)
        if (!launch.ok) {
            // 启动失败就把刚建的屏收拾掉，不留半成品。
            display.release()
            return Result.failure(
                IllegalStateException(
                    "在虚拟屏上启动 $packageName 失败（exit=${launch.exitCode}）。原始输出：${launch.output.trim().take(400)}"
                )
            )
        }

        val screen = Screen(
            displayId = displayId,
            sessionId = sessionId,
            packageName = packageName,
            expiresAtMs = System.currentTimeMillis() + lifetimeMs.coerceAtLeast(MIN_LIFETIME_MS)
        )
        virtualDisplay = display
        current = screen
        FileLogger.i(TAG, "虚拟屏已就绪：displayId=$displayId，目标=$packageName")
        return Result.success(screen)
    }

    /** 销毁当前屏。没有屏时返回失败（调用方据此提示「本来就没开」）。 */
    suspend fun stop(): Result<Unit> {
        val screen = current ?: return Result.failure(IllegalStateException("当前没有存活的虚拟屏。"))
        virtualDisplay?.release()
        virtualDisplay = null
        current = null
        FileLogger.i(TAG, "虚拟屏已销毁：displayId=${screen.displayId}")
        return Result.success(Unit)
    }

    /**
     * 取当前屏并做存活检查：超过存活上限就地销毁（懒检查）。
     *
     * 用惰性检查而不是后台定时器：忘了 stop 的兜底只需在「下一次真要用屏」时生效，
     * 后台计时器反而要额外管生命周期与线程。
     */
    suspend fun ensureAlive(): Result<Screen> {
        val screen = current ?: return Result.failure(IllegalStateException("当前没有存活的虚拟屏。"))
        if (System.currentTimeMillis() > screen.expiresAtMs) {
            val minutes = (screen.expiresAtMs - screen.createdAtMs) / 60_000
            stop()
            return Result.failure(
                IllegalStateException("虚拟屏已超过存活上限（${minutes}分钟）被自动销毁，需要时重新 vscreen_start。")
            )
        }
        // 屏可能被系统回收（内存紧张）：display 对象还活着但 displayId 已无效时，视为不存在。
        val display = virtualDisplay?.display
        if (display == null || !display.isValid) {
            stop()
            return Result.failure(IllegalStateException("虚拟屏已被系统回收（display 不再有效），需要时重新 vscreen_start。"))
        }
        return Result.success(screen)
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

    private companion object {
        const val TAG = "VirtualScreenManager"

        const val LAUNCH_TIMEOUT_MS = 20_000L

        /** 单屏默认存活上限（30 分钟）：忘 stop 的兜底，可由任务参数放宽。 */
        const val DEFAULT_LIFETIME_MS = 30 * 60 * 1000L
        const val MIN_LIFETIME_MS = 60 * 1000L
    }
}
