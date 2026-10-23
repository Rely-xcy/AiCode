package com.aicode.feature.backup.data

import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.SecretCipher
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出计数与导入落库规则。
 *
 * 覆盖三件曾经静默丢数据的事：解不开的密文导出后仍是原密文且计数 +1；已是密文导入时不再加密；
 * 旧备份缺的代理/请求头字段沿用本机既有值，不被 REPLACE 抹成默认。
 */
class BackupSecretMappingTest {

    private companion object {
        const val PREFIX = "AICODEENC1:"
    }

    /** decryptable=false 模拟「备份来自另一台设备或 Keystore 已重建」。 */
    private fun cipher(decryptable: Boolean = true) = SecretCipher(
        isEncrypted = { it.startsWith(PREFIX) },
        decrypt = { value ->
            if (decryptable) Result.success(value.removePrefix(PREFIX))
            else Result.failure(IllegalStateException("key mismatch"))
        },
        encrypt = { "enc1{$it}" }
    )

    private fun dto(
        apiKey: String = "sk-plain",
        apiKeys: String? = null,
        scriptParams: String? = null,
        proxyEnabled: Boolean? = null,
        proxyPassword: String? = null,
        customHeaders: String? = null,
        userAgent: String? = null
    ) = ProviderDto(
        id = "p1",
        name = "本机",
        type = "OPENAI",
        apiKey = apiKey,
        baseUrl = "https://api.example.com",
        defaultModel = "gpt-4o",
        models = "gpt-4o",
        selectedModel = "gpt-4o",
        apiKeys = apiKeys,
        scriptParams = scriptParams,
        proxyEnabled = proxyEnabled,
        proxyPassword = proxyPassword,
        customHeaders = customHeaders,
        userAgent = userAgent
    )

    private fun existingProvider() = AIProviderEntity(
        id = "p1",
        name = "本机",
        type = "OPENAI",
        apiKey = "${PREFIX}old-key",
        baseUrl = "https://api.example.com",
        defaultModel = "gpt-4o",
        proxyEnabled = true,
        proxyType = "SOCKS",
        proxyHost = "10.0.0.1",
        proxyPort = 1080,
        proxyUsername = "user",
        proxyPassword = "${PREFIX}old-proxy-pw",
        customHeaders = "${PREFIX}old-headers",
        userAgent = "UA-OLD"
    )

    // ── 导出：解不开的密文原样保留并计数 ─────────────────────────────

    @Test
    fun `解不开的密文导出后仍是原密文且计数加一`() {
        val tally = ExportSecretTally { Result.failure(IllegalStateException("key mismatch")) }
        assertEquals("${PREFIX}foreign", tally.export("${PREFIX}foreign"))
        assertEquals(1, tally.undecryptable)
    }

    @Test
    fun `能解密与原本为空都不计数`() {
        val tally = ExportSecretTally { Result.success(it.removePrefix(PREFIX)) }
        assertEquals("sk-mine", tally.export("${PREFIX}sk-mine"))
        assertEquals("", tally.export(""))
        assertEquals(0, tally.undecryptable)
    }

    // ── 导入：已是密文不二次加密 ──────────────────────────────────────

    @Test
    fun `备份里已是密文的敏感字段原样落库并计入需重填`() {
        val mapped = dto(
            apiKey = "${PREFIX}foreign-key",
            apiKeys = "${PREFIX}foreign-list",
            scriptParams = "${PREFIX}foreign-params",
            proxyPassword = "${PREFIX}foreign-pw",
            customHeaders = "${PREFIX}foreign-headers"
        ).toImportedEntity(null, cipher(decryptable = false))

        assertEquals("${PREFIX}foreign-key", mapped.entity.apiKey)
        assertEquals("${PREFIX}foreign-list", mapped.entity.apiKeys)
        assertEquals("${PREFIX}foreign-params", mapped.entity.scriptParams)
        assertEquals("${PREFIX}foreign-pw", mapped.entity.proxyPassword)
        assertEquals("${PREFIX}foreign-headers", mapped.entity.customHeaders)
        assertEquals(5, mapped.undecryptableSecrets)
    }

    @Test
    fun `备份里是明文时只加密一次`() {
        val mapped = dto(apiKey = "sk-1", apiKeys = "k1", proxyPassword = "pw").toImportedEntity(null, cipher())
        assertEquals("enc1{sk-1}", mapped.entity.apiKey)
        assertEquals("enc1{k1}", mapped.entity.apiKeys)
        assertEquals("enc1{pw}", mapped.entity.proxyPassword)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `本机可解的密文原样保留且不计为需重填`() {
        val mapped = dto(apiKey = "${PREFIX}mine").toImportedEntity(null, cipher())
        assertEquals("${PREFIX}mine", mapped.entity.apiKey)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    // ── 旧备份缺字段：沿用本机既有值 ─────────────────────────────────

    @Test
    fun `旧备份缺代理与请求头字段时沿用本机既有值`() {
        val mapped = dto(apiKey = "sk-plain").toImportedEntity(existingProvider(), cipher())

        assertTrue(mapped.entity.proxyEnabled)
        assertEquals("SOCKS", mapped.entity.proxyType)
        assertEquals("10.0.0.1", mapped.entity.proxyHost)
        assertEquals(1080, mapped.entity.proxyPort)
        assertEquals("user", mapped.entity.proxyUsername)
        // 既有值本身是库内密文：原样带过来，不能再套一层加密
        assertEquals("${PREFIX}old-proxy-pw", mapped.entity.proxyPassword)
        assertEquals("${PREFIX}old-headers", mapped.entity.customHeaders)
        assertEquals("UA-OLD", mapped.entity.userAgent)
        // 备份里给的字段照备份走
        assertEquals("enc1{sk-plain}", mapped.entity.apiKey)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `旧备份缺字段且本机没有同名记录时退回实体默认值`() {
        val mapped = dto().toImportedEntity(null, cipher())
        assertFalse(mapped.entity.proxyEnabled)
        assertEquals("HTTP", mapped.entity.proxyType)
        assertEquals("", mapped.entity.proxyHost)
        assertEquals(0, mapped.entity.proxyPort)
        assertEquals("", mapped.entity.proxyUsername)
        assertEquals("", mapped.entity.proxyPassword)
        assertEquals("", mapped.entity.customHeaders)
        assertEquals("", mapped.entity.userAgent)
    }

    @Test
    fun `备份带了代理字段时以备份为准`() {
        val mapped = dto(proxyEnabled = false, proxyPassword = "pw-new", customHeaders = "{\"X\":\"1\"}")
            .toImportedEntity(existingProvider(), cipher())
        assertFalse(mapped.entity.proxyEnabled)
        assertEquals("enc1{pw-new}", mapped.entity.proxyPassword)
        assertEquals("enc1{{\"X\":\"1\"}}", mapped.entity.customHeaders)
    }

    // ── 远程连接：同一套密文语义 ─────────────────────────────────────

    @Test
    fun `远程连接密码已是密文时原样落库并计入需重填`() {
        val mapped = RemoteConnectionDto(
            id = "c1",
            name = "srv",
            protocol = "SFTP",
            host = "10.0.0.2",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            authData = "${PREFIX}foreign",
            passphrase = "${PREFIX}foreign-pass"
        ).toImportedEntity(cipher(decryptable = false))

        assertEquals("${PREFIX}foreign", mapped.entity?.authData)
        assertEquals("${PREFIX}foreign-pass", mapped.entity?.passphrase)
        assertEquals(2, mapped.undecryptableSecrets)
    }

    @Test
    fun `私钥型连接的路径不加密`() {
        val mapped = RemoteConnectionDto(
            id = "c1",
            name = "srv",
            protocol = "SFTP",
            host = "10.0.0.2",
            port = 22,
            username = "root",
            authType = "KEY",
            authData = "/root/.ssh/id_ed25519"
        ).toImportedEntity(cipher())

        assertEquals("/root/.ssh/id_ed25519", mapped.entity?.authData)
        assertNull(mapped.entity?.passphrase)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `已移除的协议记录直接跳过`() {
        val mapped = RemoteConnectionDto(
            id = "c1",
            name = "local",
            protocol = "LOCAL",
            host = "",
            port = 0,
            username = "",
            authData = ""
        ).toImportedEntity(cipher())

        assertNull(mapped.entity)
        assertEquals(0, mapped.undecryptableSecrets)
    }
}
