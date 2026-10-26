package com.aicode.feature.backup.data

import com.aicode.feature.backup.domain.ConnectionSecretsDto
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.ProviderSecretsDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.SecretCipher
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.domain.model.RemoteProtocol
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

    // ── 新格式：密钥拆到凭据段 ─────────────────────────────────────

    @Test
    fun `新格式有凭据记录时以凭据段为准而不读 metadata 空占位`() {
        val mapped = dto(apiKey = "", apiKeys = null, scriptParams = null)
            .toImportedEntity(
                existing = existingProvider(),
                cipher = cipher(),
                secrets = ProviderSecretsDto(
                    id = "p1",
                    apiKey = "sk-from-segment",
                    apiKeys = "k1\nk2",
                    proxyPassword = "pw-from-segment",
                    customHeaders = "{\"X\":\"1\"}",
                    scriptParams = "{\"a\":\"b\"}"
                ),
                secretsSegmented = true
            )

        assertEquals("enc1{sk-from-segment}", mapped.entity.apiKey)
        assertEquals("enc1{k1\nk2}", mapped.entity.apiKeys)
        assertEquals("enc1{pw-from-segment}", mapped.entity.proxyPassword)
        assertEquals("enc1{{\"X\":\"1\"}}", mapped.entity.customHeaders)
        assertEquals("enc1{{\"a\":\"b\"}}", mapped.entity.scriptParams)
        assertTrue(mapped.credentialsRestored)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `新格式缺凭据记录时沿用本机密钥而不是写成空`() {
        val mapped = dto(apiKey = "").toImportedEntity(
            existing = existingProvider(),
            cipher = cipher(),
            secrets = null,
            secretsSegmented = true
        )

        assertEquals("${PREFIX}old-key", mapped.entity.apiKey)
        assertEquals("${PREFIX}old-proxy-pw", mapped.entity.proxyPassword)
        assertEquals("${PREFIX}old-headers", mapped.entity.customHeaders)
        assertFalse(mapped.credentialsRestored)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `新格式凭据记录全空时不算一项凭据但仍会清空`() {
        val mapped = dto(apiKey = "").toImportedEntity(
            existing = existingProvider(),
            cipher = cipher(),
            secrets = ProviderSecretsDto(id = "p1"),
            secretsSegmented = true
        )

        assertEquals("", mapped.entity.apiKey)
        assertEquals("", mapped.entity.proxyPassword)
        assertEquals("", mapped.entity.customHeaders)
        assertFalse(mapped.credentialsRestored)
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
    fun `新格式连接密码从凭据段写回而私钥路径仍在 metadata`() {
        val password = RemoteConnectionDto(
            id = "c1",
            name = "srv",
            protocol = "SFTP",
            host = "10.0.0.2",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            authData = ""
        ).toImportedEntity(
            cipher = cipher(),
            secrets = ConnectionSecretsDto(id = "c1", authData = "secret-pw", passphrase = "pp"),
            secretsSegmented = true
        )

        assertEquals("enc1{secret-pw}", password.entity?.authData)
        assertEquals("enc1{pp}", password.entity?.passphrase)
        assertTrue(password.credentialsRestored)

        val key = RemoteConnectionDto(
            id = "c2",
            name = "srv2",
            protocol = "SFTP",
            host = "10.0.0.3",
            port = 22,
            username = "root",
            authType = "KEY",
            authData = "/root/.ssh/id_ed25519"
        ).toImportedEntity(
            cipher = cipher(),
            secrets = ConnectionSecretsDto(id = "c2", passphrase = "pp2"),
            secretsSegmented = true
        )

        assertEquals("/root/.ssh/id_ed25519", key.entity?.authData)
        assertEquals("enc1{pp2}", key.entity?.passphrase)
        assertTrue(key.credentialsRestored)
    }

    @Test
    fun `新格式缺连接凭据时沿用本机密码与口令`() {
        val existing = RemoteConnectionEntity(
            id = "c1",
            name = "srv",
            protocol = RemoteProtocol.SFTP,
            host = "10.0.0.2",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            authData = "${PREFIX}old-pw",
            passphrase = "${PREFIX}old-pass"
        )

        val mapped = RemoteConnectionDto(
            id = "c1",
            name = "srv",
            protocol = "SFTP",
            host = "10.0.0.2",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            authData = ""
        ).toImportedEntity(cipher(), existing = existing, secrets = null, secretsSegmented = true)

        assertEquals("${PREFIX}old-pw", mapped.entity?.authData)
        assertEquals("${PREFIX}old-pass", mapped.entity?.passphrase)
        assertFalse(mapped.credentialsRestored)
        assertEquals(0, mapped.undecryptableSecrets)
    }

    @Test
    fun `新格式凭据段里的密文解不开时计入需重填`() {
        val mapped = dto(apiKey = "")
            .toImportedEntity(
                existing = null,
                cipher = cipher(decryptable = false),
                secrets = ProviderSecretsDto(id = "p1", apiKey = "${PREFIX}foreign-key"),
                secretsSegmented = true
            )

        assertEquals("${PREFIX}foreign-key", mapped.entity.apiKey)
        assertEquals(1, mapped.undecryptableSecrets)
        assertTrue(mapped.credentialsRestored)
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
