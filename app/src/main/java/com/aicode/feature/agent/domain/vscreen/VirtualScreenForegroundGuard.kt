package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台让路：目标 app 若正在真机屏（display 0）上被用户操作，虚拟屏任务就暂停注入。
 *
 * 判据取系统窗口焦点的 display 与包名：用户在真机上点被测 app 时，AI 同时在虚拟屏里改状态
 * 会互相打架（设计 §4.2.1）。检测不到（命令失败、解析不出）时**不拦**——让路是保护，
 * 不能因为探测失败把正常任务卡死。
 *
 * [temp] root 分支实验件：`dumpsys window` 各版本输出格式有差异，按关键字宽松解析。
 */
@Singleton
class VirtualScreenForegroundGuard @Inject constructor(
    private val rootShell: RootShell
) {

    /** 目标包是否在真机屏（display 0）上有焦点。 */
    suspend fun isBlockingRealScreen(packageName: String): Boolean {
        val dump = rootShell.run(FOCUS_COMMAND, FOCUS_TIMEOUT_MS)
        if (!dump.ok) {
            FileLogger.w(TAG, "前台焦点探测失败，按不拦处理：exit=${dump.exitCode}")
            return false
        }
        val frosted = dump.output.lineSequence()
            .firstOrNull { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            ?: return false
        val blocking = focusLineBlocksRealScreen(frosted, packageName)
        if (blocking) FileLogger.i(TAG, "目标 $packageName 正在真机屏前台，虚拟屏注入暂停")
        return blocking
    }

    /** 给模型的暂停说明。 */
    fun pausedMessage(packageName: String): String =
        "目标 $packageName 正在真机屏（display 0）前台被用户操作，虚拟屏注入已暂停以免互相干扰。" +
            "等用户离开该 app 后再继续；若确认要并行，先让用户手动退出目标 app。"

    private companion object {
        const val TAG = "VirtualScreenForegroundGuard"
        const val FOCUS_TIMEOUT_MS = 8_000L

        /** 取当前焦点窗口。`mCurrentFocus`/`mFocusedApp` 两行都看，兼容不同版本。 */
        const val FOCUS_COMMAND = "dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'"
    }
}

/**
 * 焦点行是否表示「目标 app 在真机屏（display 0）前台」。
 *
 * 抽成纯函数便于单测：焦点行的格式各版本不一，但判定逻辑就两条——
 * 显式写了非 0 displayId 就不算真机屏；行里出现目标包名才算它在前面。
 */
internal fun focusLineBlocksRealScreen(focusLine: String, packageName: String): Boolean {
    if (NON_DEFAULT_DISPLAY_REGEX.containsMatchIn(focusLine)) return false
    return focusLine.contains(packageName)
}

private val NON_DEFAULT_DISPLAY_REGEX = Regex("(?i)display[:= ]*[1-9]\\d*")
