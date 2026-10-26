package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 注入参数拼装的安全回归：这些字符串最终以 `su -c <整串>` 跑在 uid 0 下，
 * 一条用例钉住「payload 不能闭合引号再追加命令」。
 */
class VscreenShellArgsTest {

    @Test
    fun `input 文本按 POSIX 单引号转义，空格转成 %s`() {
        assertEquals("'a%sb'", VscreenShellArgs.inputText("a b"))
        assertEquals("'it'\"'\"'s'", VscreenShellArgs.inputText("it's"))
    }

    @Test
    fun `引号注入被关在单引号里，不能追加命令`() {
        // 未转义时 `x';rm -rf /sdcard;#` 会在 root shell 里断开引号并执行 rm。
        assertEquals(
            "'x'\"'\"';rm%s-rf%s/sdcard;#%s'",
            VscreenShellArgs.inputText("x';rm -rf /sdcard;# ")
        )
    }

    @Test
    fun `按键名只接受字母数字下划线，其余一律拒绝`() {
        assertEquals("KEYCODE_HOME", VscreenShellArgs.keyCode("keycode_home"))
        assertEquals("HOME", VscreenShellArgs.keyCode(" HOME "))
        assertNull(VscreenShellArgs.keyCode("KEYCODE_HOME; rm -rf /data"))
        assertNull(VscreenShellArgs.keyCode("KEYCODE_HOME rm -rf /data"))
        assertNull(VscreenShellArgs.keyCode(""))
    }

    @Test
    fun `包名只接受字母数字下划线点，其余一律拒绝`() {
        assertEquals("com.android.settings", VscreenShellArgs.packageName("com.android.settings"))
        assertNull(VscreenShellArgs.packageName("com.x; rm -rf /data"))
        assertNull(VscreenShellArgs.packageName("com.x/../y"))
        assertNull(VscreenShellArgs.packageName(""))
    }

    @Test
    fun `组件名整体加引号，payload 只当一个参数`() {
        assertEquals("'com.android.settings/.Settings'", VscreenShellArgs.component("com.android.settings/.Settings"))
        assertEquals("'com.x/; rm -rf /data'", VscreenShellArgs.component("com.x/; rm -rf /data"))
    }
}
