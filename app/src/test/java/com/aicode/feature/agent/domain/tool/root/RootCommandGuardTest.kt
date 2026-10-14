package com.aicode.feature.agent.domain.tool.root

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
