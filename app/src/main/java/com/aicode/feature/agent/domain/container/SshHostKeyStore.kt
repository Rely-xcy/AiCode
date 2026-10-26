package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.feature.workspace.domain.remote.formatHostKeyFingerprint
import com.aicode.feature.workspace.domain.remote.hostKeyFingerprintForDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.PublicKey
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

/**
 * 计算主机公钥的 SHA-256 指纹，形如 `SHA256:<base64>`，与 ssh-keygen -lf 的格式一致。
 *
 * 摘要对象必须是 SSH wire 编码（`string 类型名 + 公钥字段`，即 AuthorizedKeys 里 base64 那段），
 * 而不是 JCA 公钥的 `encoded`：sshj 交给我们的是 JCA 公钥，`encoded` 是 X.509 DER，摘要值与
 * ssh-keygen 对不上，用户拿 `ssh-keygen -lf` 核对时会误以为自己连错了服务器。
 */
fun sshHostKeyFingerprint(key: PublicKey): String = formatHostKeyFingerprint(
    net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).getCompactData()
)

/**
 * 旧算法指纹：对 JCA 公钥的 DER 编码（[PublicKey.getEncoded]）做 SHA-256。
 *
 * SHA-256 不可逆，而存量存储里只有指纹串、没有公钥字节，所以算法一换，老用户连自己的服务器都会
 * 撞上「主机密钥已变化」。此函数只为在 [SshHostKeyVerifier.verify] 里识别并静默升级那些老值，
 * 不再用于产生新值。
 */
internal fun sshHostKeyFingerprintDer(key: PublicKey): String = formatHostKeyFingerprint(key.encoded)

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
        if (saved == fingerprint) return true
        // 双算法兼容：两种指纹都由「这次的公钥字节」算出，命中任一种就说明服务器没换密钥。
        // 命中旧算法时顺手把存储值升级成新格式，下次起只走新算法。
        if (saved == sshHostKeyFingerprintDer(key)) {
            store.save(hostname, port, fingerprint)
            return true
        }
        // 更早的版本直接存过公钥 wire blob 本身：它可逆，还原后与本次 blob 的摘要相同即同一把密钥。
        if (hostKeyFingerprintForDisplay(saved) == fingerprint) {
            store.save(hostname, port, fingerprint)
            return true
        }
        val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = true)
        pending[endpoint] = e
        throw e
    }
}
