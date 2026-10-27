package com.aicode.feature.agent.domain.tool.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态变更回读：哪些命令会回读、回读到什么算一致、回读不了时是否如实说未确认。
 *
 * 这些断言直接对应模型的判据——「命令跑了」与「状态真的变了」必须在工具结果里能被区分开。
 */
class DeviceStateReadbackTest {

    private fun probes(command: String): List<String> =
        DeviceStateReadback.checksFor(command)
            .filterIsInstance<DeviceStateReadback.Probe>()
            .map { it.command }

    /** 用固定答案跑一遍回读；答案里没有的命令视为无输出。 */
    private fun report(command: String, answers: Map<String, String>): String? = runBlocking {
        DeviceStateReadback.report(command) { probe ->
            DeviceStateReadback.Observation(answers[probe].orEmpty(), 0)
        }
    }

    // ---- settings ----

    @Test
    fun `settings put 之后回读同名键`() {
        assertEquals(
            listOf("settings get system screen_brightness"),
            probes("settings put system screen_brightness 88")
        )
    }

    @Test
    fun `settings put 回读到同值才算一致`() {
        val same = report("settings put system screen_brightness 88", mapOf("settings get system screen_brightness" to "88"))
        assertTrue(same!!.contains("一致｜"))
        assertTrue(same.contains("88"))

        val different = report("settings put system screen_brightness 88", mapOf("settings get system screen_brightness" to "128"))
        assertTrue(different!!.contains("不一致｜"))
        assertTrue(different.contains("期望 88"))
    }

    @Test
    fun `settings delete 回读到 null 才算一致`() {
        val report = report("settings delete global airplane_mode_on", mapOf("settings get global airplane_mode_on" to "null"))
        assertTrue(report!!.contains("一致｜"))
    }

    @Test
    fun `settings 只读子命令不回读`() {
        assertNull(report("settings get system screen_brightness", emptyMap()))
        assertTrue(DeviceStateReadback.checksFor("settings list system").isEmpty())
    }

    // ---- wm ----

    @Test
    fun `wm size 回读 Override size 行`() {
        assertEquals(listOf("wm size"), probes("wm size 720x1600"))

        val report = report("wm size 720x1600", mapOf("wm size" to "Physical size: 1080x2400\nOverride size: 720x1600"))
        assertTrue(report!!.contains("一致｜"))

        val mismatch = report("wm size 720x1600", mapOf("wm size" to "Physical size: 1080x2400"))
        assertTrue(mismatch!!.contains("不一致｜"))
        assertTrue(mismatch.contains("期望的覆盖值没有出现在回读里"))
    }

    @Test
    fun `wm size reset 回读时 Override 行应消失`() {
        val report = report("wm size reset", mapOf("wm size" to "Physical size: 1080x2400"))
        assertTrue(report!!.contains("一致｜"))

        val mismatch = report("wm size reset", mapOf("wm size" to "Physical size: 1080x2400\nOverride size: 720x1600"))
        assertTrue(mismatch!!.contains("不一致｜"))
    }

    @Test
    fun `wm 只读查询不回读`() {
        assertNull(report("wm size", emptyMap()))
        assertNull(report("wm density -d 2", emptyMap()))
    }

    // ---- am ----

    @Test
    fun `am force-stop 用 pidof 回读`() {
        assertEquals(listOf("pidof com.tencent.mm"), probes("am force-stop com.tencent.mm"))

        val stopped = report("am force-stop com.tencent.mm", mapOf("pidof com.tencent.mm" to ""))
        assertTrue(stopped!!.contains("一致｜"))

        val alive = report("am force-stop com.tencent.mm", mapOf("pidof com.tencent.mm" to "12345"))
        assertTrue(alive!!.contains("不一致｜"))
        assertTrue(alive.contains("进程仍在运行"))
    }

    @Test
    fun `am force-stop 带 user 参数时仍能取到包名`() {
        assertEquals(listOf("pidof com.tencent.mm"), probes("am force-stop --user 0 com.tencent.mm"))
    }

    @Test
    fun `am start 不回读但明确说未确认`() {
        assertTrue(probes("am start -n com.android.settings/.Settings").isEmpty())
        val report = report("am start -n com.android.settings/.Settings", emptyMap())
        assertTrue(report!!.contains("未确认｜"))
        assertTrue(report.contains("已执行但未回读"))
    }

    @Test
    fun `am 的其余写子命令也标未确认`() {
        listOf(
            "am broadcast -a com.foo.PING",
            "am startservice -n com.foo/.Svc",
            "am kill-all",
            "am send-trim-memory com.foo RUNNING_LOW"
        ).forEach { command ->
            val report = report(command, emptyMap())
            assertTrue("$command 应标未确认", report!!.contains("未确认｜"))
        }
    }

    @Test
    fun `am 只读子命令不回读`() {
        assertNull(report("am stack list", emptyMap()))
    }

    // ---- pm / cmd / svc / input ----

    @Test
    fun `pm disable 用 pm list packages -d 回读`() {
        assertEquals(listOf("pm list packages -d com.foo"), probes("pm disable com.foo"))

        val disabled = report("pm disable com.foo", mapOf("pm list packages -d com.foo" to "package:com.foo"))
        assertTrue(disabled!!.contains("一致｜"))

        val stillEnabled = report("pm disable com.foo", mapOf("pm list packages -d com.foo" to ""))
        assertTrue(stillEnabled!!.contains("不一致｜"))
        assertTrue(stillEnabled.contains("仍处于启用状态"))
    }

    @Test
    fun `pm clear 与 install 只标未确认`() {
        assertTrue(probes("pm clear com.foo").isEmpty())
        assertTrue(report("pm clear com.foo", emptyMap())!!.contains("未确认｜"))
        assertTrue(report("pm install /sdcard/a.apk", emptyMap())!!.contains("未确认｜"))
    }

    @Test
    fun `cmd uimode night 用同名查询回读`() {
        assertEquals(listOf("cmd uimode night"), probes("cmd uimode night yes"))
        val consistent = report("cmd uimode night yes", mapOf("cmd uimode night" to "Night mode: yes"))
        assertTrue(consistent!!.contains("一致｜"))
        val mismatch = report("cmd uimode night yes", mapOf("cmd uimode night" to "Night mode: no"))
        assertTrue(mismatch!!.contains("不一致｜"))
        assertNull(report("cmd uimode night", emptyMap()))
    }

    @Test
    fun `input 与 svc 标未确认而不是成功`() {
        val input = report("input -d 2 tap 100 200", emptyMap())
        assertTrue(input!!.contains("未确认｜"))
        assertTrue(report("svc wifi enable", emptyMap())!!.contains("未确认｜"))
    }

    // ---- 安全边界与拼装 ----

    @Test
    fun `所有生成的回读命令都在 Root 白名单内`() {
        val commands = listOf(
            "settings put system screen_brightness 88",
            "settings delete global airplane_mode_on",
            "wm size 720x1600",
            "wm density 320",
            "wm size reset",
            "am force-stop com.tencent.mm",
            "pm disable com.foo",
            "pm enable com.foo",
            "pm uninstall com.foo",
            "cmd uimode night yes"
        )
        val readbacks = commands.flatMap { probes(it) }
        assertTrue(readbacks.isNotEmpty())
        readbacks.forEach { command ->
            assertTrue("回读命令 `$command` 必须是白名单内的只读命令", RootCommandGuard.isAllowlisted(command))
        }
    }

    @Test
    fun `分号分隔的多条命令各生成一条检查`() {
        val readbacks = probes("wm size 720x1600; am force-stop com.tencent.mm")
        assertEquals(listOf("wm size", "pidof com.tencent.mm"), readbacks)
    }

    @Test
    fun `只读命令不产生回读块`() {
        listOf("dumpsys display", "getprop ro.product.model", "pm list packages", "cmd wifi status", "pidof com.foo")
            .forEach { command -> assertNull("`$command` 是只读命令，不该加回读", report(command, emptyMap())) }
    }

    @Test
    fun `包名不合法时不拼进回读命令`() {
        assertTrue(probes("am force-stop 'com.foo;rm -rf /sdcard'").isEmpty())
    }
}
