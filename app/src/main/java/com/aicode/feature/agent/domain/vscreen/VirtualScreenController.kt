package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单块虚拟屏上的操作编排：注入输入、取帧。
 *
 * 所有注入命令都带 `-d <displayId>`，事件只进虚拟屏，碰不到真机前台。
 * 会话授权与限速由 [VirtualScreenGuard] 把关，本类只负责执行。
 *
 * [temp] root 分支实验件：`input -d` / `screencap -d` 的参数形态各 ROM 略有差异，
 * 阶段 0 会用真机结论校正。
 */
@Singleton
class VirtualScreenController @Inject constructor(
    private val rootShell: RootShell,
    private val manager: VirtualScreenManager,
    private val guard: VirtualScreenGuard,
    private val screenshotStore: ScreenshotStore
) {

    suspend fun tap(x: Int, y: Int): Result<String> =
        inject("tap") { id -> "input -d $id tap $x $y" }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<String> =
        inject("swipe") { id -> "input -d $id swipe $x1 $y1 $x2 $y2 $durationMs" }

    /** 输入文本：空格与常见 shell 元字符做转义后交给 `input text`。 */
    suspend fun text(content: String): Result<String> {
        val escaped = content
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        return inject("text") { id -> "input -d $id text '$escaped'" }
    }

    /** 按键：直接传 KEYCODE_* 名（BACK / HOME / ENTER 等）。 */
    suspend fun key(keyCode: String): Result<String> =
        inject("key") { id -> "input -d $id keyevent $keyCode" }

    /**
     * 取一帧落盘，返回文件路径。
     *
     * 单帧而不是持续流（设计 §4.3）：持续流是耗电与内存双杀；大图交给调用方的
     * [com.aicode.feature.agent.domain.tool.ToolOutputStore] 指针化，不塞进上下文。
     */
    suspend fun screenshot(outputDir: String): Result<String> {
        val screen = manager.ensureAlive().getOrElse { return Result.failure(it) }
        val dir = File(outputDir).apply { mkdirs() }
        val target = File(dir, "vscreen-${screen.displayId}-${System.currentTimeMillis()}.png")
        val capture = rootShell.run(
            "screencap -d ${screen.displayId} -p ${target.absolutePath}",
            CAPTURE_TIMEOUT_MS
        )
        if (!capture.ok) {
            return Result.failure(
                IllegalStateException(
                    "截帧失败（exit=${capture.exitCode}）。原始输出：${capture.output.trim().take(400)}"
                )
            )
        }
        // 命令退出码为 0 也可能没写出文件（个别 ROM 的 screencap -d 行为异常），显式确认。
        if (!target.exists() || target.length() == 0L) {
            return Result.failure(IllegalStateException("截帧命令成功但未落盘：${target.absolutePath}"))
        }
        screenshotStore.warnIfOversized(target)
        screenshotStore.prune(dir)
        FileLogger.i(TAG, "虚拟屏截帧：${target.absolutePath}（${target.length()} 字节）")
        return Result.success(target.absolutePath)
    }

    private suspend fun inject(action: String, buildCommand: (Int) -> String): Result<String> {
        val screen = manager.ensureAlive().getOrElse { return Result.failure(it) }
        if (!guard.isAuthorized(screen.sessionId)) {
            return Result.failure(
                IllegalStateException("本会话尚未获得虚拟屏授权（授权随 vscreen_start 建立、随 vscreen_stop 失效）。")
            )
        }
        guard.awaitInjectionSlot(screen.sessionId)
        val result = rootShell.run(buildCommand(screen.displayId), INJECT_TIMEOUT_MS)
        if (!result.ok) {
            return Result.failure(
                IllegalStateException("注入 $action 失败（exit=${result.exitCode}）。原始输出：${result.output.trim().take(400)}")
            )
        }
        return Result.success("$action 已注入 displayId=${screen.displayId}")
    }

    private companion object {
        const val TAG = "VirtualScreenController"
        const val INJECT_TIMEOUT_MS = 10_000L
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
