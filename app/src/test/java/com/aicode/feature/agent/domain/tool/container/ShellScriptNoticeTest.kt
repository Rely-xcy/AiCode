package com.aicode.feature.agent.domain.tool.container

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellScriptNoticeTest {

    @Test
    fun `内联脚本把正文展示出来`() {
        val notice = ShellScriptNotice.forCommand("bash -c 'rm -rf /tmp/x | cat'")!!
        assertTrue(notice.contains("内联脚本"))
        assertTrue(notice.contains("rm -rf /tmp/x | cat"))
    }

    @Test
    fun `脚本文件提示守卫看不到内部`() {
        val notice = ShellScriptNotice.forCommand("sh deploy.sh")!!
        assertTrue(notice.contains("脚本执行"))
        assertTrue(notice.contains("deploy.sh"))
        assertTrue(notice.contains("无法检查脚本内部"))
    }

    @Test
    fun `带路径的解释器也能识别`() {
        assertTrue(ShellScriptNotice.forCommand("/bin/sh x.sh")!!.contains("x.sh"))
        assertTrue(ShellScriptNotice.forCommand("python3 a.py")!!.contains("a.py"))
    }

    @Test
    fun `非脚本命令不提示`() {
        assertNull(ShellScriptNotice.forCommand("cat a.txt"))
        assertNull(ShellScriptNotice.forCommand("./gradlew :app:assembleUniversalDebug"))
        assertNull(ShellScriptNotice.forCommand("sh"))
    }

    @Test
    fun `内联脚本过长时截断`() {
        val long = "x".repeat(2000)
        val notice = ShellScriptNotice.forCommand("sh -c '$long'")!!
        assertTrue(notice.contains("…"))
        assertTrue(notice.length < 2000)
    }
}
