package com.aicode.feature.backup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份敏感字段的读写出入口。
 *
 * 这两条规则一旦写错，用户丢的是 API Key 且备份里没有任何痕迹：
 * 导出把「解不开」写成空串等于删掉密钥；导入把别处密文再加密一次则得到永远解不开的死数据。
 * 加解密实现绑定 Android Keystore，所以这里只测纯函数本身。
 */
class BackupSecretValueTest {

    private val prefix = "AICODEENC1:"

    @Test
    fun `解不开的密文导出时保留原密文并标记`() {
        val stored = "${prefix}broken-ciphertext"
        val exported = exportSecretValue(stored) { Result.failure(IllegalStateException("key mismatch")) }
        assertEquals(stored, exported.value)
        assertTrue(exported.undecryptable)
    }

    @Test
    fun `能解开的密文导出明文且不标记`() {
        val exported = exportSecretValue("${prefix}abc") { Result.success("sk-plain") }
        assertEquals("sk-plain", exported.value)
        assertFalse(exported.undecryptable)
    }

    @Test
    fun `旧明文原样导出`() {
        val exported = exportSecretValue("sk-legacy") { Result.success("sk-legacy") }
        assertEquals("sk-legacy", exported.value)
        assertFalse(exported.undecryptable)
    }

    @Test
    fun `本来就是空时导出空串且不标记`() {
        // 空值没有可导出内容：不当成「解不开」，也不会去调解密器
        val exported = exportSecretValue("") { Result.failure(IllegalStateException("不该被调用")) }
        assertEquals("", exported.value)
        assertFalse(exported.undecryptable)
    }

    @Test
    fun `导入已是密文时不再加密一次`() {
        val stored = "${prefix}foreign"
        val imported = importSecretValue(
            stored,
            isEncrypted = { it.startsWith(prefix) },
            decrypt = { Result.failure(IllegalStateException("key mismatch")) },
            encrypt = { "enc1{$it}" }
        )
        assertEquals(stored, imported.stored)
        // 解不开的密文落库后本机仍解不开：必须报给摘要，让用户去重填
        assertTrue(imported.undecryptable)
    }

    @Test
    fun `导入本机可解的密文时原样保留且不算问题`() {
        val stored = "${prefix}mine"
        val imported = importSecretValue(
            stored,
            isEncrypted = { it.startsWith(prefix) },
            decrypt = { Result.success("sk-mine") },
            encrypt = { "enc1{$it}" }
        )
        assertEquals(stored, imported.stored)
        assertFalse(imported.undecryptable)
    }

    @Test
    fun `导入明文只加密一次`() {
        val imported = importSecretValue(
            "sk-1",
            isEncrypted = { it.startsWith(prefix) },
            decrypt = { error("明文不该走解密") },
            encrypt = { "enc1{$it}" }
        )
        assertEquals("enc1{sk-1}", imported.stored)
        assertFalse(imported.undecryptable)
    }

    @Test
    fun `导入空串保持空且不标记`() {
        val imported = importSecretValue(
            "",
            isEncrypted = { it.startsWith(prefix) },
            decrypt = { error("空串不该走解密") },
            encrypt = { it }
        )
        assertEquals("", imported.stored)
        assertFalse(imported.undecryptable)
    }
}
