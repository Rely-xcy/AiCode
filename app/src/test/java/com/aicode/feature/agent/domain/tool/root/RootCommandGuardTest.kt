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
        assertTrue(RootCommandGuard.isAllowlisted("cmd window displays"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd package clear com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("cmd notification post -S bigtext tag msg"))
    }

    @Test
    fun `pm 只放行查询类子命令`() {
        assertTrue(RootCommandGuard.isAllowlisted("pm list packages -3"))
        assertTrue(RootCommandGuard.isAllowlisted("pm path com.android.settings"))
        assertFalse(RootCommandGuard.isAllowlisted("pm clear com.tencent.mm"))
        assertFalse(RootCommandGuard.isAllowlisted("pm uninstall com.tencent.mm"))
    }

    @Test
    fun `整树删除数据根被拦，子树仍放行`() {
        assertNotNull(RootCommandGuard.check("rm -rf /data"))
        assertNotNull(RootCommandGuard.check("rm -rf /sdcard"))
        assertNotNull(RootCommandGuard.check("rm -rf /sdcard/*"))
        assertNull(RootCommandGuard.check("rm -rf /sdcard/testdir"))
    }
}
