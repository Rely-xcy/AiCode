package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台让路判定的纯逻辑测试（不依赖设备）。
 *
 * 焦点行取真实 `dumpsys window` 的几种典型形态。
 */
class VirtualScreenForegroundGuardTest {

    @Test
    fun `目标包在真机屏前台时拦下`() {
        val line = "  mCurrentFocus=Window{8f2 u0 com.aicode.beta/com.aicode.MainActivity}"
        assertTrue(focusLineBlocksRealScreen(line, "com.aicode.beta"))
    }

    @Test
    fun `别的包在前台时不拦`() {
        val line = "  mCurrentFocus=Window{8f2 u0 com.android.launcher/com.android.launcher.Launcher}"
        assertFalse(focusLineBlocksRealScreen(line, "com.aicode.beta"))
    }

    @Test
    fun `显式非 0 displayId 时不算真机屏`() {
        val line = "  mFocusedApp=AppWindowToken{abc display:2 com.aicode.beta/.MainActivity}"
        assertFalse(focusLineBlocksRealScreen(line, "com.aicode.beta"))
    }

    @Test
    fun `真机屏写法 display 0 仍拦下`() {
        val line = "  mCurrentFocus=Window{8f2 u0 display 0 com.aicode.beta/com.aicode.MainActivity}"
        assertTrue(focusLineBlocksRealScreen(line, "com.aicode.beta"))
    }
}
