package com.aicode.core.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 密钥环与新密文格式的回归测试。
 *
 * 重点保住两条不变量：密文里带的 keyId 决定用哪把钥匙，以及密钥缺失时绝不静默给出错误明文。
 * 前者是「Keystore 没了也能解」的前提，后者是「不覆盖用户旧数据」的前提。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyringStoreTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        KeystoreCipher.init(context)
        clearKeyring(context)
    }

    private fun clearKeyring(context: android.content.Context) {
        context.getSharedPreferences("keyring_meta", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        java.io.File(context.filesDir, "keyring").deleteRecursively()
    }

    @Test
    fun `首次加密会自动生成密钥并写入密文`() {
        val encrypted = KeystoreCipher.encryptString("sk-secret")
        assertTrue(KeystoreCipher.isEncryptedString(encrypted))
        assertTrue(encrypted.startsWith("AICOVEENC2:"))
        assertEquals("sk-secret", KeystoreCipher.decryptString(encrypted))
        assertNotNull(KeyringStore.activeId())
    }

    @Test
    fun `空串不加密 原样返回`() {
        assertEquals("", KeystoreCipher.encryptString(""))
        assertEquals("", KeystoreCipher.decryptString(""))
    }

    @Test
    fun `旧明文不做处理 原样返回`() {
        assertEquals("plain-text", KeystoreCipher.decryptString("plain-text"))
        assertFalse(KeystoreCipher.isEncryptedString("plain-text"))
    }

    @Test
    fun `轮换后新旧密钥都能解开各自的密文`() {
        val oldCipher = KeystoreCipher.encryptString("old-value")
        val oldId = KeyringStore.activeId()
        assertNotNull(oldId)

        KeyringStore.rotate()
        val newId = KeyringStore.activeId()
        assertNotEquals(oldId, newId)

        val newCipher = KeystoreCipher.encryptString("new-value")
        assertEquals("old-value", KeystoreCipher.decryptString(oldCipher))
        assertEquals("new-value", KeystoreCipher.decryptString(newCipher))
        assertTrue(newCipher.contains(":$newId:"))
    }

    @Test
    fun `密钥被删除后 旧密文解密失败且不会被当成空值静默通过`() {
        val encrypted = KeystoreCipher.encryptString("must-survive")
        val id = KeyringStore.activeId()
        assertNotNull(id)

        deleteKeyMaterial(id!!)

        val result = KeystoreCipher.tryDecryptString(encrypted)
        assertTrue("密钥缺失时必须失败而不是返回空串", result.isFailure)
    }

    @Test
    fun `退休密钥在保留期内仍可解密 超过保留期才清理`() {
        val oldCipher = KeystoreCipher.encryptString("retained")
        val oldId = KeyringStore.activeId()!!
        KeyringStore.rotate()

        assertEquals("retained", KeystoreCipher.decryptString(oldCipher))
        assertTrue(KeyringStore.purgeRetired().isEmpty())
        assertTrue(KeyringStore.loadKey(oldId) != null)
    }

    @Test
    fun `有效期为九十天且剩余天数可读`() {
        KeystoreCipher.encryptString("warm-up")
        val entry = KeyringStore.activeEntry()
        assertNotNull(entry)
        val lifetimeDays = (entry!!.expiresAt - entry.createdAt) / (24L * 60 * 60 * 1000)
        assertEquals(90L, lifetimeDays)
        assertTrue((KeyringStore.remainingDays() ?: 0) >= 89)
        assertFalse(KeyringStore.activeExpired())
    }

    @Test
    fun `字节密文同样按 keyId 解 并保留旧文件头识别`() {
        val plain = "private-key-material".toByteArray(Charsets.UTF_8)
        val encrypted = KeystoreCipher.encryptBytes(plain)
        assertTrue(KeystoreCipher.isEncryptedBytes(encrypted))
        assertTrue(String(plain) == String(KeystoreCipher.decryptBytes(encrypted), Charsets.UTF_8))
    }

    private fun deleteKeyMaterial(id: String) {
        val dir = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "keyring"
        )
        java.io.File(dir, "$id.obf").delete()
        java.io.File(dir, "$id.ks").delete()
        assertNull(KeyringStore.loadKey(id))
    }
}
