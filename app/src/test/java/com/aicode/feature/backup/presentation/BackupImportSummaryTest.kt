package com.aicode.feature.backup.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.R
import com.aicode.feature.backup.domain.RestoreStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 导入摘要里的密钥行。
 *
 * 关键点：这一行不管有没有问题都要出现。为 0 时说明「全部恢复」，否则用户看到的是一行没写密钥的
 * 统计，无法区分「都可用」与「压根没检查」。非 0 时还要带上数量，提示去设置里重填。
 *
 * 走 Robolectric 是因为摘要本身依赖 string 资源；本容器（Android PRoot）加载不了 Robolectric 的
 * native 库，故与 [com.aicode.core.db.MigrationTest] 同一套环境守卫跳过，CI 上真实执行。
 */
@RunWith(AndroidJUnit4::class)
class BackupImportSummaryTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器）", !androidContainer)
        }
    }

    @Test
    fun `没有解不开的密钥时摘要也有密钥行并说明全部恢复`() {
        val summary = buildImportSummary(context, RestoreStats(providers = 2, chatSessions = 1))

        assertTrue(summary.contains(context.getString(R.string.backup_stat_secrets_all_restored)))
    }

    @Test
    fun `有解不开的密钥时摘要给出数量与重填提示`() {
        val summary = buildImportSummary(context, RestoreStats(providers = 1, undecryptableSecrets = 3))

        assertTrue(summary.contains(context.getString(R.string.backup_stat_undecryptable_secrets, 3)))
        assertFalse(summary.contains(context.getString(R.string.backup_stat_secrets_all_restored)))
    }

    @Test
    fun `还原了登录凭据时统计行列出凭据项数`() {
        val lines = buildRestoreStatsLines(context, RestoreStats(providers = 1, loginSecrets = 4))

        assertTrue(lines.contains(context.getString(R.string.backup_stat_login_secrets, 4)))
    }
}
