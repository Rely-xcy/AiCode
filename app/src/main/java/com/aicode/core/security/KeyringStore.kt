package com.aicode.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 密钥环里的一条密钥：短 id、创建与到期时间、是否已退休。 */
internal data class KeyringEntry(
    val id: String,
    val createdAt: Long,
    val expiresAt: Long,
    val state: String
) {
    val isActive: Boolean get() = state == STATE_ACTIVE

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_RETIRED = "retired"
    }
}

/**
 * 自备密钥环：业务数据真正的加密密钥。
 *
 * 为什么不让 Android Keystore 直接加密业务数据：root 用户清 `/data/misc/keystore`、
 * TWRP 清数据、或用备份工具只拷 `/data/data` 不拷密钥，都会让 Keystore 里的密钥消失。
 * 一旦消失，用它加密过的历史密文全部不可解。所以数据一律用这里的自备密钥加密，
 * Keystore 只参与「包裹自备密钥本身」这一小段——保险箱丢了，钥匙本体还在另一份混淆副本里。
 *
 * 自备密钥 90 天轮换。旧密钥不会在轮换开始时立即删除：它要留到全部密文重加密成功后
 * 才标 retired，再保留 7 天供回退，最后才真正删掉。中途进程被杀最多造成「一半新一半旧」，
 * 而新旧钥匙都还在，按密文里的 keyId 各解各的，不会丢数据。
 */
internal object KeyringStore {
    private const val TAG = "KeyringStore"
    private const val PREFS = "keyring_meta"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_ENTRIES = "entries"

    /** 包裹自备密钥用的 Keystore 别名。与旧版 aicode_secret_v1 无关，旧别名只用于解 AICODEENC1 密文。 */
    private const val KEYSTORE_ALIAS = "aicove_keyring_v2"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val KEY_BYTES = 32

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val LIFETIME_MS = 90 * DAY_MS
    private const val RETENTION_MS = 7 * DAY_MS

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()

    /** 必须在任何加密/解密之前调用；幂等。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("KeyringStore.init() 未调用")

    private fun keyDir(): File = File(requireContext().filesDir, "keyring").apply { mkdirs() }

    /** 混淆副本：base64 后整串反转，与 git-credentials 同一套写法，防的是肉眼直读而非 root。 */
    private fun obfFile(id: String) = File(keyDir(), "$id.obf")

    /** 被 Keystore 包裹的副本：Keystore 可用时优先用它，丢了再退回混淆副本。 */
    private fun keystoreFile(id: String) = File(keyDir(), "$id.ks")

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(): List<KeyringEntry> {
        val raw = prefs().getString(KEY_ENTRIES, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 4) return@mapNotNull null
            KeyringEntry(
                id = parts[0],
                createdAt = parts[1].toLongOrNull() ?: return@mapNotNull null,
                expiresAt = parts[2].toLongOrNull() ?: return@mapNotNull null,
                state = parts[3]
            )
        }
    }

    private fun saveEntries(list: List<KeyringEntry>) {
        val raw = list.joinToString("\n") { "${it.id}|${it.createdAt}|${it.expiresAt}|${it.state}" }
        prefs().edit().putString(KEY_ENTRIES, raw).apply()
    }

    fun activeId(): String? = prefs().getString(KEY_ACTIVE, null)

    fun activeEntry(): KeyringEntry? {
        val id = activeId() ?: return null
        return entries().firstOrNull { it.id == id && it.isActive }
    }

    /** 当前 active 密钥；没有就先生成一把。 */
    fun activeKey(): Pair<KeyringEntry, SecretKey> {
        val entry = activeEntry() ?: return rotate()
        val key = loadKey(entry.id)
            ?: return rotate()
        return entry to key
    }

    /**
     * 生成一把新密钥并设为 active，旧密钥标 retired。
     *
     * 不在这里做数据重加密：调用方需要先拿到旧密钥把所有密文读出来，再用新密钥写回，
     * 全部成功后才允许 [retire]。这里只负责密钥材料本身。
     */
    fun rotate(): Pair<KeyringEntry, SecretKey> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = entries()
        val used = existing.map { it.id }.toSet()
        val id = nextId(used)
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        storeKey(id, bytes)

        val updated = existing.map {
            if (it.isActive) it.copy(state = KeyringEntry.STATE_RETIRED) else it
        } + KeyringEntry(
            id = id,
            createdAt = now,
            expiresAt = now + LIFETIME_MS,
            state = KeyringEntry.STATE_ACTIVE
        )
        saveEntries(updated)
        prefs().edit().putString(KEY_ACTIVE, id).apply()
        FileLogger.i(TAG, "已生成新密钥 $id（有效期 90 天）")
        id to SecretKeySpec(bytes, "AES")
    }

    fun loadKey(id: String): SecretKey? = synchronized(lock) {
        val bytes = readKeyBytes(id) ?: return null
        SecretKeySpec(bytes, "AES")
    }

    fun markRetired(id: String) = synchronized(lock) {
        saveEntries(entries().map { if (it.id == id) it.copy(state = KeyringEntry.STATE_RETIRED) else it })
    }

    /** 删除已退休且超过保留期的密钥材料。返回删掉的 id。 */
    fun purgeRetired(): List<String> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val expired = entries().filter { !it.isActive && now - it.createdAt > RETENTION_MS }
        if (expired.isEmpty()) return emptyList()
        expired.forEach { entry ->
            obfFile(entry.id).delete()
            keystoreFile(entry.id).delete()
        }
        saveEntries(entries().filterNot { entry -> expired.any { it.id == entry.id } })
        FileLogger.i(TAG, "已清理退休密钥：${expired.joinToString { it.id }}")
        expired.map { it.id }
    }

    /** 到期前需要轮换的密钥：active 且已过有效期。 */
    fun activeExpired(): Boolean {
        val entry = activeEntry() ?: return false
        return System.currentTimeMillis() >= entry.expiresAt
    }

    fun remainingDays(): Long? {
        val entry = activeEntry() ?: return null
        val left = entry.expiresAt - System.currentTimeMillis()
        return if (left <= 0) 0 else left / DAY_MS
    }

    private fun nextId(used: Set<String>): String {
        var i = 1
        while ("k$i" in used) i++
        return "k$i"
    }

    /**
     * 密钥材料双写：Keystore 包裹版 + 混淆副本。
     *
     * 两份都写是刻意的：Keystore 在 root 设备上随时可能被清掉，而混淆副本又不如 Keystore 安全。
     * 读取时优先 Keystore 版，解不开再退混淆版——这正是「设备密钥用不了就用自备密钥」的落点。
     */
    private fun storeKey(id: String, bytes: ByteArray) {
        obfFile(id).writeBytes(obfuscate(bytes))
        val wrapped = runCatching { wrapWithKeystore(bytes) }.getOrNull()
        if (wrapped != null) {
            keystoreFile(id).writeBytes(wrapped)
        } else {
            keystoreFile(id).delete()
            FileLogger.w(TAG, "Keystore 不可用，密钥 $id 仅存混淆副本")
        }
    }

    private fun readKeyBytes(id: String): ByteArray? {
        val ks = keystoreFile(id)
        if (ks.isFile) {
            val unwrapped = runCatching { unwrapWithKeystore(ks.readBytes()) }.getOrNull()
            if (unwrapped != null) return unwrapped
            FileLogger.w(TAG, "Keystore 包裹副本解不开，退回混淆副本：$id")
        }
        val obf = obfFile(id)
        if (!obf.isFile) return null
        return runCatching { deobfuscate(obf.readBytes()) }.getOrNull()
    }

    private fun wrapWithKeystore(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrapWithKeystore(payload: ByteArray): ByteArray {
        require(payload.size > IV_LEN) { "包裹数据过短" }
        val iv = payload.copyOfRange(0, IV_LEN)
        val body = payload.copyOfRange(IV_LEN, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun obfuscate(bytes: ByteArray): ByteArray {
        val encoded = Base64.getEncoder().encode(bytes)
        encoded.reverse()
        return encoded
    }

    private fun deobfuscate(bytes: ByteArray): ByteArray {
        val reversed = bytes.copyOf().also { it.reverse() }
        return Base64.getDecoder().decode(reversed)
    }
}