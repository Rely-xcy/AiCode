package com.aicode.feature.settings.data

import android.content.Context
import com.aicode.core.security.CredentialStore
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 */
@Singleton
class AndroidCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val backupTargetDao: BackupTargetDao
) : CredentialStore {

    override fun collectEncryptedFields(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ): String? = runBlocking(Dispatchers.IO) {
        var failure: String? = null
        runCatching { collectProviders(onField) }.onFailure {
            FileLogger.w(TAG, "轮换收集供应商字段失败", it)
            failure = "供应商字段收集失败"
        }
        if (failure == null) {
            runCatching { collectConnections(onField) }.onFailure {
                FileLogger.w(TAG, "轮换收集连接字段失败", it)
                failure = "连接字段收集失败"
            }
        }
        if (failure == null) {
            runCatching { collectBackupTargets(onField) }.onFailure {
                FileLogger.w(TAG, "轮换收集备份目标字段失败", it)
                failure = "备份目标字段收集失败"
            }
        }
        if (failure == null) {
            runCatching { collectSharedPreferences(onField) }.onFailure {
                FileLogger.w(TAG, "轮换收集口令字段失败", it)
                failure = "口令字段收集失败"
            }
        }
        if (failure == null) {
            runCatching { collectSshKeyFiles(onField) }.onFailure {
                FileLogger.w(TAG, "轮换收集 SSH 私钥文件失败", it)
                failure = "SSH 私钥文件收集失败"
            }
        }
        failure
    }

    // ---------- Room：供应商 ----------

    private suspend fun collectProviders(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ) {
        aiProviderDao.getAllProvidersOnce().forEach { p ->
            // 一行里哪些字段是密文，先解出来；解不开的字段保持原样不动。
            val plainApiKey = p.apiKey.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainApiKeys = p.apiKeys.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainProxy = p.proxyPassword.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainHeaders = p.customHeaders.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainScript = p.scriptParams.takeIf { isCipher(it) }?.let { decryptOrNull(it) }

            val anyPlain = listOf(plainApiKey, plainApiKeys, plainProxy, plainHeaders, plainScript)
                .any { it != null }
            if (!anyPlain) return@forEach

            val label = "provider ${p.name}"
            // 写回：一行一次，把五个字段一起更新。
            val writeBack: () -> Boolean = writeBack@{
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
            onField(label, plainApiKey ?: plainApiKeys ?: plainProxy ?: plainHeaders ?: plainScript.orEmpty(), writeBack)
        }
    }

    // ---------- Room：远程连接 ----------

    private suspend fun collectConnections(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ) {
        remoteConnectionDao.getAllConnectionsOnce().forEach { c ->
            // 密钥模式的 authData 是明文私钥路径，不是密文，别动它。
            val authIsPassword = c.authType.equals("password", ignoreCase = true)
            val plainAuth = if (authIsPassword && isCipher(c.authData)) decryptOrNull(c.authData) else null
            val plainPass = c.passphrase?.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            if (plainAuth == null && plainPass == null) return@forEach

            val label = "connection ${c.name}"
            val writeBack: () -> Boolean = writeBack@{
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
            onField(label, plainAuth ?: plainPass.orEmpty(), writeBack)
        }
    }

    // ---------- Room：备份目标 ----------

    private suspend fun collectBackupTargets(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ) {
        backupTargetDao.getAllOnce().forEach { t ->
            val plainPassword = t.password.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainSecret = t.secretKey.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            val plainToken = t.sessionToken.takeIf { isCipher(it) }?.let { decryptOrNull(it) }
            if (plainPassword == null && plainSecret == null && plainToken == null) return@forEach

            val label = "backup target ${t.name}"
            val writeBack: () -> Boolean = writeBack@{
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
            onField(label, plainPassword ?: plainSecret ?: plainToken.orEmpty(), writeBack)
        }
    }

    // ---------- SharedPreferences ----------

    private fun collectSharedPreferences(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ) {
        // 全局代理密码
        context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).let { prefs ->
            val cipher = prefs.getString(PROXY_PASSWORD_KEY, null)
            if (cipher != null && isCipher(cipher)) {
                decryptOrNull(cipher)?.let { plain ->
                    onField("proxy password", plain) {
                        val current = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                            .getString(PROXY_PASSWORD_KEY, null)
                        // 期间用户可能已经改过口令，别用旧值覆盖。
                        if (current != cipher) return@onField false
                        context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                            .putString(PROXY_PASSWORD_KEY, KeystoreCipher.encryptString(plain)).apply()
                        KeystoreCipher.tryDecryptString(
                            context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                                .getString(PROXY_PASSWORD_KEY, null)
                        ).getOrNull() == plain
                    }
                }
            }
        }

        // SSH 登录密钥的口令：key_<id>_passphrase
        context.getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE).let { prefs ->
            prefs.all.keys.filter { it.endsWith(SSH_PASSPHRASE_SUFFIX) }.forEach { key ->
                val cipher = prefs.getString(key, null) ?: return@forEach
                if (!isCipher(cipher)) return@forEach
                decryptOrNull(cipher)?.let { plain ->
                    onField("ssh login passphrase $key", plain) {
                        val store = context.getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE)
                        if (store.getString(key, null) != cipher) return@onField false
                        store.edit().putString(key, KeystoreCipher.encryptString(plain)).apply()
                        KeystoreCipher.tryDecryptString(store.getString(key, null)).getOrNull() == plain
                    }
                }
            }
        }
    }

    // ---------- 文件：SSH 私钥本体 ----------

    private fun collectSshKeyFiles(
        onField: (label: String, plaintext: String, writeBack: () -> Boolean) -> Unit
    ) {
        val dir = File(context.filesDir, SSH_KEYS_DIR)
        val files = dir.listFiles() ?: return
        files.filter { it.isFile }.forEach { file ->
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (!KeystoreCipher.isEncryptedBytes(bytes)) return@forEach
            val plain = runCatching { KeystoreCipher.decryptBytes(bytes) }.getOrNull()
            if (plain == null || plain.isEmpty()) {
                FileLogger.w(TAG, "跳过解不开的私钥文件：${file.name}")
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

    /** 解不开就返回 null——那条是坏数据，跳过即可，不该让整轮失败。 */
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
