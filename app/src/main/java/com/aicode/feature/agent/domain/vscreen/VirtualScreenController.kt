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
 * 截帧走屏自带的 ImageReader（见 [VirtualScreenManager]）；观察的无障碍树通道在 MIUI 上
 * 看不到虚拟屏窗口（设备限制），取不到时如实报错并提示改截图。
 */
@Singleton
class VirtualScreenController @Inject constructor(
    private val rootShell: RootShell,
    private val manager: VirtualScreenManager,
    private val guard: VirtualScreenGuard,
    private val screenshotStore: ScreenshotStore,
    private val foregroundGuard: VirtualScreenForegroundGuard
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
        // 帧从屏自己的 Surface（ImageReader）读：screencap -d 只认 HWC 注册的 display，
        // 对 App 自建的屏会「命令成功但写出 0 字节」（真机验证结论 2026-10-15）。
        manager.captureFrame(target).getOrElse { return Result.failure(it) }
        if (!target.exists() || target.length() == 0L) {
            return Result.failure(IllegalStateException("取帧成功但未落盘：${target.absolutePath}"))
        }
        screenshotStore.warnIfOversized(target)
        screenshotStore.prune(dir)
        FileLogger.i(TAG, "虚拟屏截帧：${target.absolutePath}（${target.length()} 字节）")
        return Result.success(target.absolutePath)
    }

    /**
     * 读虚拟屏目标界面的**控件树**（无障碍）。
     *
     * 比截图省 token：文本 + 坐标就能让模型知道「有哪些按钮、点哪里」；
     * 取不到时不静默降级，把两条可选路径（开无障碍 / 改截图）直说。
     */
    suspend fun tree(limit: Int): Result<String> {
        val screen = manager.ensureAlive().getOrElse { return Result.failure(it) }
        if (!VscreenAccessibilityBridge.isReady) {
            return Result.failure(
                IllegalStateException(
                    "无障碍服务未开启：到「设置 → 无障碍」开启「AiCode 虚拟屏读屏」后重试；或改用 mode=screenshot。"
                )
            )
        }
        val nodes = VscreenAccessibilityBridge.capture(screen.displayId, limit)
        if (nodes.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "displayId=${screen.displayId} 未取到控件节点（界面可能还没画出来，或该 app 未暴露无障碍语义）。可改用 mode=screenshot。"
                )
            )
        }
        return Result.success(formatUiNodes(nodes, limit))
    }

    /**
     * 读目标 app 的 logcat 片段做断言依据（crash / ANR 关键字）。
     *
     * 优先按 pid 过滤（`pidof` 拿不到时退到全量再按包名过滤），返回「结论行 + 末尾原文」：
     * 模型要的是「崩没崩」，不是几百行日志。
     */
    suspend fun logcat(packageName: String?, lines: Int): Result<String> {
        val screen = manager.ensureAlive().getOrElse { return Result.failure(it) }
        val pkg = packageName ?: screen.packageName
        val pid = rootShell.run("pidof $pkg", SHORT_TIMEOUT_MS).output.trim()
        val command = if (pid.isNotEmpty()) {
            "logcat -d -t $lines --pid=$pid"
        } else {
            "logcat -d -t ${lines * 5}"
        }
        val dump = rootShell.run(command, LOGCAT_TIMEOUT_MS)
        if (!dump.ok) {
            return Result.failure(
                IllegalStateException("读取 logcat 失败（exit=${dump.exitCode}）。原始输出：${dump.output.trim().take(400)}")
            )
        }
        val all = dump.output.lineSequence().filter { it.isNotBlank() }.toList()
        val relevant = if (pid.isEmpty()) all.filter { it.contains(pkg) } else all
        val crashes = relevant.filter { line -> CRASH_KEYWORDS.any { line.contains(it) } }
        val head = buildString {
            append("目标 $pkg：")
            if (pid.isEmpty()) append("进程未在运行（pidof 无输出）；") else append("pid=$pid；")
            append(if (crashes.isEmpty()) "未发现 crash/ANR 关键字" else "发现 ${crashes.size} 条 crash/ANR 相关行")
        }
        val body = relevant.takeLast(lines).joinToString("\n")
        return Result.success(buildString {
            append(head)
            if (body.isNotEmpty()) append("\n\n---\n").append(body.take(MAX_RETURN_CHARS))
        })
    }

    private suspend fun inject(action: String, buildCommand: (Int) -> String): Result<String> {
        val screen = manager.ensureAlive().getOrElse { return Result.failure(it) }
        // 前台让路：用户在真机屏上操作被测 app 时先暂停注入（设计 §4.2.1）。
        if (foregroundGuard.isBlockingRealScreen(screen.packageName)) {
            return Result.failure(IllegalStateException(foregroundGuard.pausedMessage(screen.packageName)))
        }
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
        const val SHORT_TIMEOUT_MS = 5_000L
        const val LOGCAT_TIMEOUT_MS = 15_000L
        const val MAX_RETURN_CHARS = 4_000

        val CRASH_KEYWORDS = listOf("FATAL EXCEPTION", "ANR in", "beginning of crash", "SIGSEGV", "Fatal signal")
    }
}
