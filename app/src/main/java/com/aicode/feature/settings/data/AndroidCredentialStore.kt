package com.aicode.feature.settings.data

import android.content.Context
import com.aicode.core.security.CredentialStore
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialStore] 的落地：遍历所有存着密文的地方，把它们交给密钥轮换服务。
 *
 * 覆盖范围与 [SecretEncryptionMigrator] 一致（那一处负责「明文→密文」的首次迁移，
 * 这里负责「旧密钥→新密钥」的轮换），另外补上迁移器没碰的 SharedPreferences 口令字段。
 *
 * 按**行**收集、按**行**写回：供应商一行有五个敏感字段，逐字段各写一次库会让
 * 同一行被反复 REPLACE 整表，途中任何并发写入都会被抹掉。改成一行只写一次、
 * 五个字段一起带上，写回前先重新读一次该行拿最新值，避免覆盖用户在此期间的改动。
 *
 * 全部方法都是 suspend：DAO 是 suspend 的，写回回调也必须能挂起。
 */
@Singleton
class AndroidCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val backupTargetDao: BackupTargetDao
) : CredentialStore {

    override suspend fun collectEncryptedFields(
        skipped: (Int) -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ): String? {
        var skippedCount = 0
        val countSkipped: () -> Unit = {
            skippedCount++
            skipped(skippedCount)
        }
        val steps: List<suspend () -> Unit> = listOf(
            { collectProviders(countSkipped, onField) },
            { collectConnections(countSkipped, onField) },
            { collectBackupTargets(countSkipped, onField) },
            { collectSharedPreferences(countSkipped, onField) },
            { collectSshKeyFiles(countSkipped, onField) }
        )
        for (step in steps) {
            val failure = runCatching { step() }.exceptionOrNull()
            if (failure != null) {
                FileLogger.w(TAG, "轮换收集失败", failure)
                return "收集密文失败：${failure.message ?: failure::class.simpleName}"
            }
        }
        return null
    }

    // ---------- Room：供应商 ----------

    private suspend fun collectProviders(
        countSkipped: () -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ) {
        aiProviderDao.getAllProvidersOnce().forEach { p ->
            // 一行里哪些字段是密文，先解出来；解不开的字段保持原样不动。
            val plainApiKey = p.apiKey.decryptIfCipher()
            val plainApiKeys = p.apiKeys.decryptIfCipher()
            val plainProxy = p.proxyPassword.decryptIfCipher()
            val plainHeaders = p.customHeaders.decryptIfCipher()
            val plainScript = p.scriptParams.decryptIfCipher()

            val anyPlain = listOf(plainApiKey, plainApiKeys, plainProxy, plainHeaders, plainScript)
                .any { it != null }
            if (!anyPlain) {
                // 是密文却解不开，说明这条已因密钥丢失变成孤儿，计入跳过。
                if (isCipher(p.apiKey) || isCipher(p.apiKeys)) countSkipped()
                return@forEach
            }

            val label = "provider ${p.name}"
            val writeBack: suspend () -> Boolean = writeBack@{
                val latest = aiProviderDao.getAllProvidersOnce().firstOrNull { it.id == p.id }
                    ?: return@writeBack false
                val rewritten = latest.copy(
                    apiKey = plainApiKey?.let { KeystoreCipher.encryptString(it) } ?: latest.apiKey,
                    apiKeys = plainApiKeys?.let { KeystoreCipher.encryptString(it) } ?: latest.apiKeys,
                    proxyPassword = plainProxy?.let { KeystoreCipher.encryptString(it) } ?: latest.proxyPassword,
                    customHeaders = plainHeaders?.let { KeystoreCipher.encryptString(it) } ?: latest.customHeaders,
                    scriptParams = plainScript?.let { KeystoreCipher.encryptString(it) } ?: latest.scriptParams
                )
                aiProviderDao.insertAllProviders(
                    aiProviderDao.getAllProvidersOnce().map { if (it.id == p.id) rewritten else it }
                )
                // 回读校验：至少要确认 apiKey 能解回原文。
                plainApiKey?.let {
                    KeystoreCipher.tryDecryptString(rewritten.apiKey).getOrNull() == it
                } ?: true
            }
            val plaintext = plainApiKey ?: plainApiKeys ?: plainProxy ?: plainHeaders ?: plainScript
            if (plaintext == null) return@forEach
            onField(label, plaintext, writeBack)
        }
    }

    // ---------- Room：远程连接 ----------

    private suspend fun collectConnections(
        countSkipped: () -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ) {
        remoteConnectionDao.getAllConnectionsOnce().forEach { c ->
            // 密钥模式的 authData 是明文私钥路径，不是密文，别动它。
            val authIsPassword = c.authType.equals("password", ignoreCase = true)
            val plainAuth = if (authIsPassword) c.authData.decryptIfCipher() else null
            val plainPass = c.passphrase?.decryptIfCipher()
            if (plainAuth == null && plainPass == null) {
                if (authIsPassword && isCipher(c.authData)) countSkipped()
                return@forEach
            }

            val label = "connection ${c.name}"
            val writeBack: suspend () -> Boolean = writeBack@{
                val latest = remoteConnectionDao.getAllConnectionsOnce().firstOrNull { it.id == c.id }
                    ?: return@writeBack false
                val rewritten = latest.copy(
                    authData = plainAuth?.let { KeystoreCipher.encryptString(it) } ?: latest.authData,
                    passphrase = plainPass?.let { KeystoreCipher.encryptString(it) } ?: latest.passphrase
                )
                remoteConnectionDao.insertAllConnections(
                    remoteConnectionDao.getAllConnectionsOnce().map { if (it.id == c.id) rewritten else it }
                )
                plainAuth?.let {
                    KeystoreCipher.tryDecryptString(rewritten.authData).getOrNull() == it
                } ?: true
            }
            val plaintext = plainAuth ?: plainPass
            if (plaintext == null) return@forEach
            onField(label, plaintext, writeBack)
        }
    }

    // ---------- Room：备份目标 ----------

    private suspend fun collectBackupTargets(
        countSkipped: () -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ) {
        backupTargetDao.getAllOnce().forEach { t ->
            val plainPassword = t.password.decryptIfCipher()
            val plainSecret = t.secretKey.decryptIfCipher()
            val plainToken = t.sessionToken.decryptIfCipher()
            if (plainPassword == null && plainSecret == null && plainToken == null) {
                if (isCipher(t.password) || isCipher(t.secretKey)) countSkipped()
                return@forEach
            }

            val label = "backup target ${t.name}"
            val writeBack: suspend () -> Boolean = writeBack@{
                val latest = backupTargetDao.getAllOnce().firstOrNull { it.id == t.id }
                    ?: return@writeBack false
                val rewritten = latest.copy(
                    password = plainPassword?.let { KeystoreCipher.encryptString(it) } ?: latest.password,
                    secretKey = plainSecret?.let { KeystoreCipher.encryptString(it) } ?: latest.secretKey,
                    sessionToken = plainToken?.let { KeystoreCipher.encryptString(it) } ?: latest.sessionToken
                )
                backupTargetDao.upsert(rewritten)
                plainPassword?.let {
                    KeystoreCipher.tryDecryptString(rewritten.password).getOrNull() == it
                } ?: true
            }
            val plaintext = plainPassword ?: plainSecret ?: plainToken
            if (plaintext == null) return@forEach
            onField(label, plaintext, writeBack)
        }
    }

    // ---------- SharedPreferences ----------

    private fun collectSharedPreferences(
        countSkipped: () -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ) {
        // 全局代理密码
        val proxyPrefs = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
        val proxyCipher = proxyPrefs.getString(PROXY_PASSWORD_KEY, null)
        if (proxyCipher != null && isCipher(proxyCipher)) {
            val plain = decryptOrNull(proxyCipher)
            if (plain == null) {
                countSkipped()
            } else {
                onField("proxy password", plain) {
                    val current = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                        .getString(PROXY_PASSWORD_KEY, null)
                    // 期间用户可能已经改过口令，别用旧值覆盖。
                    if (current != proxyCipher) return@onField false
                    context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(PROXY_PASSWORD_KEY, KeystoreCipher.encryptString(plain)).apply()
                    val stored = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                        .getString(PROXY_PASSWORD_KEY, null)
                    KeystoreCipher.tryDecryptString(stored ?: "").getOrNull() == plain
                }
            }
        }

        // SSH 登录密钥的口令：key_<id>_passphrase
        val sshPrefs = context.getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE)
        sshPrefs.all.keys.filter { it.endsWith(SSH_PASSPHRASE_SUFFIX) }.forEach { key ->
            val cipher = sshPrefs.getString(key, null) ?: return@forEach
            if (!isCipher(cipher)) return@forEach
            val plain = decryptOrNull(cipher)
            if (plain == null) {
                countSkipped()
                return@forEach
            }
            onField("ssh login passphrase $key", plain) {
                val store = context.getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE)
                if (store.getString(key, null) != cipher) return@onField false
                store.edit().putString(key, KeystoreCipher.encryptString(plain)).apply()
                KeystoreCipher.tryDecryptString(store.getString(key, null) ?: "").getOrNull() == plain
            }
        }
    }

    // ---------- 文件：SSH 私钥本体 ----------

    private fun collectSshKeyFiles(
        countSkipped: () -> Unit,
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ) {
        val files = File(context.filesDir, SSH_KEYS_DIR).listFiles() ?: return
        files.filter { it.isFile }.forEach { file ->
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (!KeystoreCipher.isEncryptedBytes(bytes)) return@forEach
            val plain = runCatching { KeystoreCipher.decryptBytes(bytes) }.getOrNull()
            if (plain == null || plain.isEmpty()) {
                FileLogger.w(TAG, "跳过解不开的私钥文件：${file.name}")
                countSkipped()
                return@forEach
            }
            val text = String(plain, Charsets.UTF_8)
            onField("ssh key file ${file.name}", text) {
                file.writeBytes(KeystoreCipher.encryptBytes(plain))
                // 回读校验：写回的新密文必须能解回原文。
                val reread = runCatching { KeystoreCipher.decryptBytes(file.readBytes()) }.getOrNull()
                reread != null && String(reread, Charsets.UTF_8) == text
            }
        }
    }

    // ---------- 工具 ----------

    private fun isCipher(value: String): Boolean = KeystoreCipher.isEncryptedString(value)

    /** 非密文或解不开都返回 null——调用方据此判断这一项是否要跳过。 */
    private fun String.decryptIfCipher(): String? =
        if (isCipher(this)) decryptOrNull(this) else null

    private fun decryptOrNull(value: String): String? =
        KeystoreCipher.tryDecryptString(value).getOrNull()

    private companion object {
        const val TAG = "AndroidCredentialStore"
        const val PROXY_PREFS = "proxy_settings"
        const val PROXY_PASSWORD_KEY = "proxy_password"
        const val SSH_LOGIN_PREFS = "ssh_login_keys"
        const val SSH_PASSPHRASE_SUFFIX = "_passphrase"
        const val SSH_KEYS_DIR = "ssh_keys"
    }
}
