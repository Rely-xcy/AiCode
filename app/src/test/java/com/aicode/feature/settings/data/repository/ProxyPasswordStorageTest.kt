package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局代理密码落盘规则的护栏：[readStoredProxyPassword] 的三种输入状态。
 *
 * 旧版本把密码明文写在 SharedPreferences（`proxy_settings.password`）里，现在改为 Keystore 密文。
 * 这里钉三件事：
 *  - 旧明文首次读到仍返回原密码，并给出要回写的密文（迁移不能丢用户已保存的密码）；
 *  - 已是密文只解密、不二次加密（密文的密文本机解不开，等于把密码写死）；
 *  - 空值不产生密文（避免把「未填写」变成非空值）。
 *
 * 加解密实现绑定 Android Keystore，JVM 单测里跑不了，所以按
 * [com.aicode.feature.backup.domain.SecretCipher] 的做法由调用方传入假加解密，这里只测规则本身。
 * 真机上的 Keystore 往返不在本测试覆盖范围内。
 */
class ProxyPasswordStorageTest {

    /** 假密文格式 `SEALED:<序号>:<明文>`：序号让「又加密了一次」这种回归能被断言看见。 */
    private var seals = 0

    private val isEncrypted: (String) -> Boolean = { it.startsWith("SEALED:") }

    private val encrypt: (String) -> String = { plain ->
        if (plain.isEmpty()) plain else "SEALED:${seals++}:$plain"
    }

    private val decrypt: (String) -> String = { stored ->
        stored.removePrefix("SEALED:").substringAfter(':')
    }

    @Test
    fun plaintextLegacy_isReturnedAsIs_andMarkedForEncryption() {
        val read = readStoredProxyPassword("p@ss:w0rd", isEncrypted, decrypt, encrypt)

        assertEquals("p@ss:w0rd", read.plain)
        assertTrue(read.migrateTo!!.startsWith("SEALED:"))
    }

    @Test
    fun migratedValue_roundTripsToTheSamePassword() {
        val read = readStoredProxyPassword("p@ss:w0rd", isEncrypted, decrypt, encrypt)

        assertEquals("p@ss:w0rd", decrypt(read.migrateTo!!))
    }

    @Test
    fun ciphertext_isDecrypted_andNotSealedTwice() {
        val read = readStoredProxyPassword("SEALED:7:p@ss", isEncrypted, decrypt, encrypt)

        assertEquals("p@ss", read.plain)
        assertNull(read.migrateTo)
        assertEquals(0, seals)
    }

    @Test
    fun emptyValue_staysEmpty_withoutCiphertext() {
        val read = readStoredProxyPassword("", isEncrypted, decrypt, encrypt)

        assertEquals("", read.plain)
        assertNull(read.migrateTo)
        assertEquals(0, seals)
    }
}
