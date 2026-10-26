package com.aicode.feature.backup.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldRestoreRemoteMode]：导入后要不要把执行模式切回远程。
 *
 * 纯 JVM 单测：只构造 [BackupMetadata] 数据对象，不碰 ViewModel、不碰 Android 框架与 DB。
 */
class BackupRestoreModeTest {

    private fun meta(executionMode: String?) = BackupMetadata(
        schemaVersion = 1,
        createdAt = 0L,
        executionMode = executionMode
    )

    /** 旧备份（本字段出现之前）没有 executionMode：不动本机模式。 */
    @Test
    fun missing_field_keeps_local_mode() {
        assertFalse(shouldRestoreRemoteMode(meta(null)))
    }

    /** 备份里记着导出时是远程模式：导入后切回远程（只切模式，不发起连接）。 */
    @Test
    fun remote_ssh_is_restored() {
        assertTrue(shouldRestoreRemoteMode(meta(ExecutionMode.REMOTE_SSH.name)))
    }

    /** 备份里记着导出时是本地模式：不切远程，保持导入后用户当前的模式。 */
    @Test
    fun local_mode_is_not_restored() {
        assertFalse(shouldRestoreRemoteMode(meta(ExecutionMode.LOCAL_PROOT.name)))
    }

    /** 无法识别的模式名（例如更新版本 App 导出的新枚举值）：解析失败一律按「不动」处理。 */
    @Test
    fun unknown_mode_is_ignored() {
        assertFalse(shouldRestoreRemoteMode(meta("REMOTE_DOCKER")))
        assertFalse(shouldRestoreRemoteMode(meta("")))
    }
}
