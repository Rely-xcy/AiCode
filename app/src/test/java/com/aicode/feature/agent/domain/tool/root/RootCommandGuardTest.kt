package com.aicode.feature.agent.domain.tool.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root 命令安全闸门：放行面与拦截面逐条对照。
 *
 * 拦截面关系到「会不会把用户手机搞坏」，每条规则都要有正例与反例。
 */
class RootCommandGuardTest {

    // ---- 必须放行（调试系统的正常用法）----

    @Test
    fun `只读调试命令放行`() {
        assertNull(RootCommandGuard.check("dumpsys display"))
        assertNull(RootCommandGuard.check("getprop ro.build.version.release"))
        assertNull(RootCommandGuard.check("ls -l /sdcard"))
        assertNull(RootCommandGuard.check("cat /proc/meminfo"))
    }

    @Test
    fun `输入注入与截屏放行`() {
        assertNull(RootCommandGuard.check("input -d 2 tap 100 200"))
        assertNull(RootCommandGuard.check("screencap -d 2 -p /sdcard/a.png"))
    }

    @Test
    fun `应用管理与普通数据目录写入放行`() {
        assertNull(RootCommandGuard.check("pm list packages"))
        assertNull(RootCommandGuard.check("am start -n com.android.settings/.Settings"))
        assertNull(RootCommandGuard.check("rm -rf /sdcard/testdir"))
        assertNull(RootCommandGuard.check("cp /sdcard/a.txt /sdcard/b.txt"))
    }

    // ---- 必须拦截 ----

    @Test
    fun `递归删根目录或系统分区被拦`() {
        assertNotNull(RootCommandGuard.check("rm -rf /"))
        assertNotNull(RootCommandGuard.check("rm -rf /*"))
        assertNotNull(RootCommandGuard.check("rm -rf /system"))
        assertNotNull(RootCommandGuard.check("rm -rf /system/bin"))
        assertNotNull(RootCommandGuard.check("rm -rf /data/system"))
        assertNotNull(RootCommandGuard.check("rm -r /vendor"))
    }

    @Test
    fun `刷机与格式化工具被拦`() {
        assertNotNull(RootCommandGuard.check("mkfs.ext4 /dev/block/mmcblk0p1"))
        assertNotNull(RootCommandGuard.check("mke2fs -t ext4 /dev/block/by-name/userdata"))
        assertNotNull(RootCommandGuard.check("fastboot flashing unlock"))
        assertNotNull(RootCommandGuard.check("fastboot flash boot boot.img"))
        assertNotNull(RootCommandGuard.check("parted /dev/block/sda print"))
    }

    @Test
    fun `dd 写块设备被拦`() {
        assertNotNull(RootCommandGuard.check("dd if=/sdcard/boot.img of=/dev/block/by-name/boot"))
        assertNotNull(RootCommandGuard.check("dd if=/dev/zero of=/dev/block/mmcblk0"))
    }

    @Test
    fun `写系统分区被拦`() {
        assertNotNull(RootCommandGuard.check("echo hi > /system/build.prop"))
        assertNotNull(RootCommandGuard.check("echo hi >> /vendor/etc/x"))
        assertNotNull(RootCommandGuard.check("cp hostfile /system/bin/su"))
        assertNotNull(RootCommandGuard.check("chmod 777 /system/bin"))
        assertNotNull(RootCommandGuard.check("mv /sdcard/x /system/x"))
    }

    @Test
    fun `进刷机模式与清数据被拦`() {
        assertNotNull(RootCommandGuard.check("reboot bootloader"))
        assertNotNull(RootCommandGuard.check("reboot edl"))
        assertNotNull(RootCommandGuard.check("reboot recovery"))
        assertNotNull(RootCommandGuard.check("am broadcast -a android.intent.action.MASTER_CLEAR"))
        assertNotNull(RootCommandGuard.check("recovery --wipe_data"))
    }

    @Test
    fun `正常重启不拦（留给用户决定）`() {
        assertNull(RootCommandGuard.check("reboot"))
    }

    @Test
    fun `空命令不拦（由调用方报参数错误）`() {
        assertNull(RootCommandGuard.check(""))
        assertNull(RootCommandGuard.check("   "))
    }

    @Test
    fun `命令拼接与管道进 shell 被拦`() {
        assertNotNull(RootCommandGuard.check("rm -rf \$(echo /system)"))
        assertNotNull(RootCommandGuard.check("ls `echo /system`"))
        assertNotNull(RootCommandGuard.check("eval rm -rf /system"))
        assertNotNull(RootCommandGuard.check("echo cm0gLXJmIC8= | sh"))
    }

    // ---- 白名单（默认只能跑只读/调试类）----

    @Test
    fun `白名单内的命令`() {
        assertTrue(RootCommandGuard.isAllowlisted("dumpsys display"))
        assertTrue(RootCommandGuard.isAllowlisted("input -d 2 tap 1 2"))
        assertTrue(RootCommandGuard.isAllowlisted("screencap -d 2 -p /sdcard/a.png"))
        assertTrue(RootCommandGuard.isAllowlisted("getprop ro.build.version.release"))
        assertTrue(RootCommandGuard.isAllowlisted("pm list packages"))
        assertTrue(RootCommandGuard.isAllowlisted("settings get global airplane_mode_on"))
    }

    @Test
    fun `白名单外的命令直接拒绝`() {
        assertFalse(RootCommandGuard.isAllowlisted("rm -rf /sdcard/x"))
        assertFalse(RootCommandGuard.isAllowlisted("cp /sdcard/a /sdcard/b"))
        assertFalse(RootCommandGuard.isAllowlisted("chmod 777 /sdcard/x"))
        assertFalse(RootCommandGuard.isAllowlisted("reboot"))
        assertFalse(RootCommandGuard.isAllowlisted("toybox touch /sdcard/root-audit-sentinel"))
        assertFalse(RootCommandGuard.isAllowlisted("toybox kill 1234"))
        assertFalse(RootCommandGuard.isAllowlisted("toybox cat /proc/meminfo"))
        assertFalse(RootCommandGuard.isAllowlisted(""))
        // settings 只放只读子命令
        assertFalse(RootCommandGuard.isAllowlisted("settings put global airplane_mode_on 1"))
    }

    @Test
    fun `段分隔符后的命令也要各自过白名单`() {
        // 只看首个 token 时这几条会以 ls/dumpsys 混过闸门，后半段却是 uid 0 下的写操作。
        assertFalse(RootCommandGuard.isAllowlisted("ls ;pm clear com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("ls &&rm -rf /data/system"))
        assertFalse(RootCommandGuard.isAllowlisted("dumpsys ;settings put global airplane_mode_on 1"))
        assertTrue(RootCommandGuard.isAllowlisted("ls /sdcard ; getprop ro.build.version.release"))
    }

    @Test
    fun `cmd 只放行只读子命令`() {
        assertTrue(RootCommandGuard.isAllowlisted("cmd package list packages"))
        // `cmd window` 现与 `wm` 共用窗口子命令判定：原来的 `cmd window displays` 断言已改
        // （`displays` 不是 `cmd window` 的子命令），换成真实存在的查询与写分支各一条。
        assertTrue(RootCommandGuard.isAllowlisted("cmd window size"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd window size 1080x1920"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd package clear com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd notification post -S bigtext tag msg"))
    }

    @Test
    fun `pm 只放行查询类子命令`() {
        assertTrue(RootCommandGuard.isAllowlisted("pm list packages -3"))
        assertTrue(RootCommandGuard.isAllowlisted("pm path com.android.settings"))
        assertTrue(RootCommandGuard.isAllowlisted("pm dump com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("pm clear com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("pm uninstall com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("pm install /sdcard/x.apk"))
        assertFalse(RootCommandGuard.isAllowlisted("pm enable com.tencent.mm"))
    }

    @Test
    fun `常用只读调试命令全放行`() {
        // 对应用户反馈「能用的太少」：只要不改状态，调试命令一律可用。
        val commands = listOf(
            "dumpsys window", "logcat -d -t 100", "pidof com.tencent.mm", "ps -A", "top -n 1",
            "getprop ro.product.model", "settings get secure android_id", "ip addr",
            "ifconfig wlan0", "netstat -an", "df -h", "du -sh /sdcard/DCIM", "mount",
            "free -m", "stat /sdcard/a.txt", "id", "date", "uptime", "uname -a", "whoami",
            "md5sum /sdcard/a.txt", "sha256sum /sdcard/a.txt", "ls -l /data/local/tmp",
            "cat /proc/meminfo"
        )
        commands.forEach { assertTrue("应放行：$it", RootCommandGuard.isAllowlisted(it)) }
    }

    // ---- 四个原先「任意子命令都放行」的命令：只收紧、并给出可读原因 ----

    @Test
    fun `wm 只放查询，改显示配置被拒`() {
        assertTrue(RootCommandGuard.isAllowlisted("wm size"))
        assertTrue(RootCommandGuard.isAllowlisted("wm density"))
        assertTrue(RootCommandGuard.isAllowlisted("wm size -d 2"))
        assertTrue(RootCommandGuard.isAllowlisted("wm density -d 2"))
        assertTrue(RootCommandGuard.isAllowlisted("wm help"))

        assertFalse(RootCommandGuard.isAllowlisted("wm size 1080x1920"))
        assertFalse(RootCommandGuard.isAllowlisted("wm density 420"))
        assertFalse(RootCommandGuard.isAllowlisted("wm size reset"))
        assertFalse(RootCommandGuard.isAllowlisted("wm dismiss-keyguard"))
        assertFalse(RootCommandGuard.isAllowlisted("wm user-rotation lock 1"))

        // 拒绝要能说清为什么（会持久改写显示配置），而不是只回一句「不在白名单」
        val reason = RootCommandGuard.rejectionReason("wm size 1080x1920")
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("显示配置"))
        assertNull(RootCommandGuard.rejectionReason("wm size"))
    }

    @Test
    fun `cmd 只放明确只读子命令`() {
        // 裸调用与 help 只打印，放行；带取值就是写分支（前缀放行会连带把写分支放过去）。
        assertTrue(RootCommandGuard.isAllowlisted("cmd power"))
        assertTrue(RootCommandGuard.isAllowlisted("cmd uimode help"))
        assertTrue(RootCommandGuard.isAllowlisted("cmd uimode night"))
        assertTrue(RootCommandGuard.isAllowlisted("cmd deviceidle whitelist"))

        assertFalse(RootCommandGuard.isAllowlisted("cmd power set-mode 2"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd uimode night yes"))
        assertFalse(
            RootCommandGuard.isAllowlisted("cmd webviewupdate set-webview-implementation com.google.android.webview")
        )
        assertFalse(RootCommandGuard.isAllowlisted("cmd deviceidle whitelist +com.tencent.mm"))
        assertNotNull(RootCommandGuard.rejectionReason("cmd uimode night yes"))
    }

    @Test
    fun `cmd window 与 wm 同一套判定`() {
        // 只收紧 wm 而继续放行 cmd window 等于没收紧：两者是同一批窗口管理命令。
        assertTrue(RootCommandGuard.isAllowlisted("cmd window size"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd window size 1080x1920"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd window dismiss-keyguard"))
        assertNotNull(RootCommandGuard.rejectionReason("cmd window size 1080x1920"))
    }

    @Test
    fun `service 只放 list，call 一律拒`() {
        assertTrue(RootCommandGuard.isAllowlisted("service list"))
        assertFalse(RootCommandGuard.isAllowlisted("service call activity 1"))
        assertFalse(RootCommandGuard.isAllowlisted("service call SurfaceFlinger 1"))
        assertFalse(RootCommandGuard.isAllowlisted("service check activity"))

        val reason = RootCommandGuard.rejectionReason("service call activity 1")
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("binder"))
    }

    @Test
    fun `monkey 全拒`() {
        assertFalse(RootCommandGuard.isAllowlisted("monkey -p com.tencent.mm 100"))
        assertFalse(RootCommandGuard.isAllowlisted("monkey --help"))
        assertNotNull(RootCommandGuard.rejectionReason("monkey -p com.tencent.mm 100"))
    }

    @Test
    fun `mount 只放行列挂载点，重挂成可写被拒`() {
        assertTrue(RootCommandGuard.isAllowlisted("mount"))
        assertTrue(RootCommandGuard.isAllowlisted("mount -l"))
        assertTrue(RootCommandGuard.isAllowlisted("mount -t ext4"))

        // 重挂成可写是后续所有系统改写的入口，不能因为 `mount` 在只读名单里就整条放行。
        assertFalse(RootCommandGuard.isAllowlisted("mount -o remount,rw /system"))
        assertFalse(RootCommandGuard.isAllowlisted("mount -o rw,remount /"))
        assertFalse(RootCommandGuard.isAllowlisted("mount /dev/block/by-name/system /mnt"))
        assertNotNull(RootCommandGuard.rejectionReason("mount -o remount,rw /system"))
    }

    @Test
    fun `am 只放已评估子命令，写子命令放行但带提示`() {
        // 启动/停掉目标 app 是基本调试手段：放行，但 advisory 必须提醒会改运行状态。
        assertTrue(RootCommandGuard.isAllowlisted("am start -n com.android.settings/.Settings"))
        assertTrue(RootCommandGuard.isAllowlisted("am force-stop com.tencent.mm"))
        assertTrue(RootCommandGuard.isAllowlisted("am broadcast -a android.intent.action.AIRPLANE_MODE"))
        assertNotNull(RootCommandGuard.advisory("am force-stop com.tencent.mm"))
        assertNotNull(RootCommandGuard.advisory("am start -n com.android.settings/.Settings"))

        // 只读查询照旧放行，且不带 advisory
        assertTrue(RootCommandGuard.isAllowlisted("am stack list"))
        assertTrue(RootCommandGuard.isAllowlisted("am task list"))
        assertTrue(RootCommandGuard.isAllowlisted("am get-config"))
        assertTrue(RootCommandGuard.isAllowlisted("am get-current-user"))
        assertNull(RootCommandGuard.advisory("am stack list"))
        assertFalse(RootCommandGuard.isAllowlisted("am stack move 1 2"))

        // 能加载代码 / attach 进程 / 常驻不退出的：拒绕，且原因说得清是哪一类
        assertFalse(RootCommandGuard.isAllowlisted("am instrument -w com.foo.test/androidx.test.runner.AndroidJUnitRunner"))
        assertFalse(RootCommandGuard.isAllowlisted("am monitor"))
        assertFalse(RootCommandGuard.isAllowlisted("am dumpheap com.tencent.mm /sdcard/h.txt"))
        assertFalse(RootCommandGuard.isAllowlisted("am profile start com.tencent.mm /sdcard/t.trace"))
        assertNotNull(RootCommandGuard.rejectionReason("am instrument -w com.foo.test/x"))
    }

    @Test
    fun `logcat 必须有界`() {
        assertTrue(RootCommandGuard.isAllowlisted("logcat -d"))
        assertTrue(RootCommandGuard.isAllowlisted("logcat -d -t 200"))
        assertTrue(RootCommandGuard.isAllowlisted("logcat -d -t 200 --pid=1234"))
        assertFalse(RootCommandGuard.isAllowlisted("logcat"))
        assertFalse(RootCommandGuard.isAllowlisted("logcat -b all"))
        assertFalse(RootCommandGuard.isAllowlisted("logcat -c"))
        assertNotNull(RootCommandGuard.rejectionReason("logcat"))
    }

    @Test
    fun `子命令约束也按段生效`() {
        // 新规则落在拼接拆分之后：前半段不能替后面的越权子命令打掩护。
        assertFalse(RootCommandGuard.isAllowlisted("ls /sdcard ; service call activity 1"))
        assertFalse(RootCommandGuard.isAllowlisted("dumpsys display && wm size 1080x1920"))
        assertFalse(RootCommandGuard.isAllowlisted("getprop x ; monkey -p com.tencent.mm 10"))
        assertNotNull(RootCommandGuard.rejectionReason("ls /sdcard ; service call activity 1"))
        assertTrue(RootCommandGuard.isAllowlisted("ls /sdcard ; wm size"))
    }

    @Test
    fun `整树删除数据根被拦，子树仍放行`() {
        assertNotNull(RootCommandGuard.check("rm -rf /data"))
        assertNotNull(RootCommandGuard.check("rm -rf /sdcard"))
        assertNotNull(RootCommandGuard.check("rm -rf /sdcard/*"))
        assertNull(RootCommandGuard.check("rm -rf /sdcard/testdir"))
    }
}
