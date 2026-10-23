package com.aicode.feature.agent.domain.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** SSH 主机指纹的持久化存储，key 为 `host:port`。 */
interface SshHostKeyStore {
    fun get(host: String, port: Int): String?
    fun save(host: String, port: Int, fingerprint: String)
    fun remove(host: String, port: Int)
    fun entries(): Map<String, String>
}

private const val PREFS_NAME = "ssh_host_keys"

/** 基于应用私有 SharedPreferences 的实现。 */
@Singleton
class SharedPrefsSshHostKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : SshHostKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(host: String, port: Int): String = "$host:$port"

    override fun get(host: String, port: Int): String? = preferences.getString(key(host, port), null)

    override fun save(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString(key(host, port), fingerprint).apply()
    }

    override fun remove(host: String, port: Int) {
        preferences.edit().remove(key(host, port)).apply()
    }

    override fun entries(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()
}

/** 计算主机公钥的 SHA-256 指纹，形如 `SHA256:<base64>`，与 ssh-keygen -lf 的格式一致。 */
fun sshHostKeyFingerprint(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** 主机密钥需要用户确认（首次连接或已保存指纹变化）。changed=false 首次，true 指纹变化。 */
class SshHostKeyPendingException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
) : Exception("SSH 主机密钥需要确认: $host:$port")

class SshHostKeyVerifier @Inject constructor(
    private val store: SshHostKeyStore
) : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    // sshj 会包装校验异常，连接层需另外取详情；并发主机不能共用一个待确认槽位。
    private val pending = ConcurrentHashMap<Pair<String, Int>, SshHostKeyPendingException>()

    fun consumePending(host: String, port: Int): SshHostKeyPendingException? =
        pending.remove(host to port)

    fun pendingFor(host: String, port: Int): SshHostKeyPendingException? =
        pending[host to port]

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val endpoint = hostname to port
        pending.remove(endpoint)
        val fingerprint = sshHostKeyFingerprint(key)
        val saved = store.get(hostname, port)
        if (saved == null) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = false)
            pending[endpoint] = e
            throw e
        }
        if (saved != fingerprint) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = true)
            pending[endpoint] = e
            throw e
        }
        return true
    }
}
