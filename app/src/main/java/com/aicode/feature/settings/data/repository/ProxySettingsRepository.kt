package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.core.content.edit
import com.aicode.core.net.ProxyEndpointSpec
import com.aicode.core.net.ProxyScheme
import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.settings.domain.model.ProxyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 默认不走代理的主机/网段列表（含内网 CIDR）。顶层常量：ProxyConfig 主构造函数默认值不能引用 companion 成员。 */
const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1"

/** 全局 HTTP/SOCKS5 代理配置快照。 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** 不走代理的主机列表（逗号分隔，精确匹配 host）。 */
    val noProxy: String = DEFAULT_NO_PROXY
) : ProxyEndpointSpec {

    // 供 core 侧（AppProxy 的代理映像与测试探针）以窄接口读取，不把本仓库类型反向带进底座。
    override val proxyScheme: ProxyScheme
        get() = if (type == ProxyType.SOCKS5) ProxyScheme.SOCKS5 else ProxyScheme.HTTP

    override val proxyHost: String get() = host

    override val proxyPort: Int get() = port

    override val proxyUsername: String get() = username

    override val proxyPassword: String get() = password

    /** 配置是否完整可用：仅当开启且 host 非空、port 合法时才会真正走代理。 */
    val valid: Boolean
        get() = host.isNotBlank() && port in 1..65535
}

/**
 * 解析落盘的代理密码：[plain] 是给内存用的明文，[migrateTo] 非空时调用方须把它写回存储。
 *
 * 旧版本把密码明文写在 SharedPreferences 里；改为 Keystore 密文后，第一次读到明文要加密回写，
 * 否则用户已保存的密码继续以明文留在磁盘上。加解密绑定 Android Keystore（JVM 单测跑不了），
 * 所以这里只接函数参数，规则本身保持纯函数。
 */
internal data class StoredProxyPassword(val plain: String, val migrateTo: String?)

/**
 * 空值不产生密文（避免把「未填写」变成非空值）；已是密文不再加密一次（密文的密文本机解不开）。
 */
internal fun readStoredProxyPassword(
    stored: String,
    isEncrypted: (String) -> Boolean,
    decrypt: (String) -> String,
    encrypt: (String) -> String
): StoredProxyPassword = when {
    stored.isEmpty() -> StoredProxyPassword(stored, null)
    isEncrypted(stored) -> StoredProxyPassword(decrypt(stored), null)
    else -> StoredProxyPassword(stored, encrypt(stored))
}

/**
 * 全局代理设置。用 SharedPreferences 存储（而非 DataStore）：
 * 需要支持 Application.attachBaseContext 阶段的同步读取（见 [AppProxy]），
 * 那里没有任何注入、也等不了异步首帧。
 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig(prefs))
    val config: StateFlow<ProxyConfig> = _config.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setType(type: ProxyType) = update { it.copy(type = type) }
    fun setHost(host: String) = update { it.copy(host = host.trim()) }
    fun setPort(port: Int) = update { it.copy(port = port) }
    fun setUsername(username: String) = update { it.copy(username = username) }
    fun setPassword(password: String) = update { it.copy(password = password) }
    fun setNoProxy(noProxy: String) = update { it.copy(noProxy = noProxy) }

    private fun update(transform: (ProxyConfig) -> ProxyConfig) {
        val current = _config.value
        val next = transform(current)
        prefs.edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putString(KEY_TYPE, next.type.name)
            putString(KEY_HOST, next.host)
            putInt(KEY_PORT, next.port)
            putString(KEY_USERNAME, next.username)
            // 密码按 Keystore 密文落盘。逐字符输入时每次按键都会走到这里，其余字段本来就整份重写，
            // 密码没变就不必再加密一次（每次加密都要过一次 Keystore）。
            if (next.password != current.password) {
                putString(KEY_PASSWORD, KeystoreCipher.encryptString(next.password))
            }
            putString(KEY_NO_PROXY, next.noProxy)
        }
        _config.value = next
    }

    companion object {
        const val PREFS_NAME = "proxy_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_TYPE = "type"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_NO_PROXY = "no_proxy"

        /** 旧版默认绕过列表（无 CIDR 网段）：读到此值视为未自定义，迁移为新默认。 */
        private const val LEGACY_DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"

        /** 同步读取当前配置，供非注入场景（如 Application.attachBaseContext）使用。 */
        fun readConfig(context: Context): ProxyConfig =
            readConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        /**
         * 最近一次解开的密文与其明文。AppProxy 的 ProxySelector 每次选路都同步读配置，
         * 没有这一层缓存的话每条新连接都要走一次 Keystore。
         */
        private val decryptedPassword = AtomicReference<Pair<String, String>?>(null)

        /** 密文解成明文并缓存。缓存键是密文本身：密码改过（密文变了）缓存自然失效。 */
        private fun decryptPassword(ciphertext: String): String {
            decryptedPassword.get()?.let { (sealedValue, plain) -> if (sealedValue == ciphertext) return plain }
            val plain = KeystoreCipher.decryptString(ciphertext)
            decryptedPassword.set(ciphertext to plain)
            return plain
        }

        private fun readConfig(prefs: android.content.SharedPreferences): ProxyConfig {
            val password = readStoredProxyPassword(
                stored = prefs.getString(KEY_PASSWORD, "") ?: "",
                isEncrypted = { KeystoreCipher.isEncryptedString(it) },
                decrypt = { decryptPassword(it) },
                encrypt = { KeystoreCipher.encryptString(it) }
            )
            // 首次读到旧版明文：加密回写（apply，不阻塞首次读取）。
            val migrateTo = password.migrateTo
            if (migrateTo != null) prefs.edit { putString(KEY_PASSWORD, migrateTo) }
            return ProxyConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                type = runCatching { ProxyType.valueOf(prefs.getString(KEY_TYPE, ProxyType.HTTP.name)!!) }
                    .getOrDefault(ProxyType.HTTP),
                host = prefs.getString(KEY_HOST, "") ?: "",
                port = prefs.getInt(KEY_PORT, 0),
                username = prefs.getString(KEY_USERNAME, "") ?: "",
                password = password.plain,
                // 旧版本默认值（无内网网段）迁移为新默认列表，让输入框直接显示完整绕过地址。
                noProxy = (prefs.getString(KEY_NO_PROXY, null) ?: "")
                    .takeUnless { it.isBlank() || it == LEGACY_DEFAULT_NO_PROXY }
                    ?: DEFAULT_NO_PROXY
            )
        }
    }
}
