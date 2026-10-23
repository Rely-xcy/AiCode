package com.aicode.feature.backup.data

import android.content.Context
import android.content.SharedPreferences
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupCrypto
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.LoginSecretsPasswordRequiredException
import com.aicode.feature.backup.domain.SecretCipher
import com.aicode.feature.backup.domain.remote.BackupTargetType
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.ExecutionModeRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 登录凭据段的导出 / 导入。
 *
 * 三条规则错了都会静默骗人：勾了「包含登录凭据」却没用口令导出，用户会以为凭据在包里；
 * 解不开的密文写成空值，换机后无从恢复；导入摘要不给凭据行，用户看不出到底带没带。
 *
 * 与 [BackupSecretMappingTest] 同一套办法：加解密实现绑定 Android Keystore、JVM 里跑不了，
 * 所以端到端用例只用「本机解不开的密文」与空口令字段（这两条路径不调 Keystore），
 * 规则本身则由下面的纯函数用例覆盖。
 */
class BackupLoginSecretsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: BackupManagerImpl
    private lateinit var filesRoot: File
    private lateinit var mcpOAuthFile: File

    private val prefsStore = mutableMapOf<String, String>()
    private val savedCredentials = mutableListOf<GitCredential>()
    private val savedTargets = mutableListOf<BackupTargetEntity>()

    /** Keystore 密文文件头；只用它让「解不开」的判定走到解密器（JVM 里必然解不开）。 */
    private val encryptedKeyBytes =
        "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(12) { 1 } + byteArrayOf(2, 3, 4)
    private val mcpFileContent = """{"clients":{},"tokens":{},"serverIssuers":{}}"""

    @Before
    fun setUp() = runBlocking {
        val cacheRoot = tempFolder.newFolder("cache")
        filesRoot = tempFolder.newFolder("files")
        val prefs = fakePrefs()
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns cacheRoot
            every { filesDir } returns filesRoot
            every { getDatabasePath(any()) } returns File(filesRoot, "databases/aicode_agent_db")
            every { packageName } returns "com.aicode.test"
            // appVersionName 用 runCatching 包着，取不到包信息时退化为空串
            every { packageManager } throws IllegalStateException("unit test")
            every { getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE) } returns prefs
        }

        prefsStore.clear()
        savedCredentials.clear()
        savedTargets.clear()
        // 口令列是「本机解不开的密文」：既是导出计数要覆盖的情形，也是不依赖 Keystore 的路径。
        prefsStore["key_k1_name"] = "deploy"
        prefsStore["key_k1_path"] = "${filesRoot.path}/ssh_keys/deploy.pem"
        prefsStore["key_k1_fingerprint"] = "SHA256:abc"
        prefsStore["key_k1_passphrase"] = BROKEN_CIPHER

        File(filesRoot, "ssh_keys").mkdirs()
        File(filesRoot, "ssh_keys/deploy.pem").writeBytes(encryptedKeyBytes)

        mcpOAuthFile = File(File(filesRoot, "aicode").apply { mkdirs() }, "mcp-oauth.json")
        mcpOAuthFile.writeText(mcpFileContent)

        manager = buildManager(context)
    }

    @Test
    fun `勾选包含登录凭据但没有口令时拒绝导出`() = runBlocking {
        val out = ByteArrayOutputStream()

        val error = runCatching { manager.export(null, baseOptions(loginSecrets = true), out) }.exceptionOrNull()

        assertTrue("expected LoginSecretsPasswordRequiredException but was $error", error is LoginSecretsPasswordRequiredException)
        // 一个字节都不该写出：不能先写半包再报错
        assertEquals(0, out.size())
    }

    @Test
    fun `口令为空串同样拒绝导出`() = runBlocking {
        val out = ByteArrayOutputStream()

        val error = runCatching { manager.export(charArrayOf(), baseOptions(loginSecrets = true), out) }.exceptionOrNull()

        assertTrue(error is LoginSecretsPasswordRequiredException)
        assertEquals(0, out.size())
    }

    @Test
    fun `未勾选时备份不含凭据段`() = runBlocking {
        val out = ByteArrayOutputStream()
        manager.export(null, baseOptions(loginSecrets = false), out)

        val entries = readTar(out.toByteArray())

        assertTrue(entries.keys.none { it.startsWith("secrets/") })
        assertFalse(entries.containsKey("secrets/ssh-login-keys.json"))
        assertFalse(entries.containsKey("secrets/git-credentials.jsonl"))
    }

    @Test
    fun `勾选并设置口令后五类凭据都进包且能还原`() = runBlocking {
        val out = ByteArrayOutputStream()
        val exportStats = manager.export(PASSWORD, baseOptions(loginSecrets = true), out)
        val backupBytes = out.toByteArray()
        // 导出的是加密流：解一遍密才能看到 tar 条目（与导入走同一条解密路径）
        val plainBytes = manager.prepareImport(ByteArrayInputStream(backupBytes), PASSWORD).let { file ->
            try {
                file.readBytes()
            } finally {
                file.delete()
            }
        }
        val entries = readTar(plainBytes)

        assertEquals(setOf("git-credentials.jsonl", "backup-targets.jsonl", "ssh-login-keys.json", "mcp-oauth.json"),
            entries.keys.filter { it.startsWith("secrets/") && !it.startsWith("secrets/ssh-keys/") }
                .map { it.removePrefix("secrets/") }.toSet())
        assertTrue(String(entries.getValue("secrets/git-credentials.jsonl"), Charsets.UTF_8).contains("tok-123"))
        assertTrue(String(entries.getValue("secrets/backup-targets.jsonl"), Charsets.UTF_8).contains("dav.example.com"))
        // 解不开的密文按原样进包，不写空值
        assertTrue(String(entries.getValue("secrets/ssh-login-keys.json"), Charsets.UTF_8).contains(BROKEN_CIPHER))
        assertArrayEquals(encryptedKeyBytes, entries.getValue("secrets/ssh-keys/deploy.pem"))
        assertEquals(mcpFileContent, String(entries.getValue("secrets/mcp-oauth.json"), Charsets.UTF_8))
        // 网络备份目标的密码、SSH 口令、私钥文件：三处都解不开
        assertEquals(3, exportStats.undecryptableSecrets)

        // 清掉本机状态，验证确实是从备份还原出来的
        prefsStore.clear()
        File(filesRoot, "ssh_keys/deploy.pem").delete()
        mcpOAuthFile.delete()

        val result = manager.import(ByteArrayInputStream(backupBytes), PASSWORD, plainBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(5, stats.loginSecrets)
        assertEquals(3, stats.undecryptableSecrets)
        assertTrue(stats.hasWrittenData)
        assertEquals("tok-123", savedCredentials.single().token)
        assertEquals("AICODEENC1:broken", savedTargets.single().password)
        assertEquals(BROKEN_CIPHER, prefsStore["key_k1_passphrase"])
        assertArrayEquals(encryptedKeyBytes, File(filesRoot, "ssh_keys/deploy.pem").readBytes())
        assertEquals(mcpFileContent, mcpOAuthFile.readText())
        assertEquals(0, stats.skippedLoginSecrets)
    }

    @Test
    fun `认不出的凭据条目被跳过并计数`() = runBlocking {
        // 更新版本可能加新的凭据类型：旧版本读到不认识的 secrets/ 条目时应跳过而不是整包失败
        val tarBytes = buildTar(
            linkedMapOf(
                "metadata.json" to Json.encodeToString(
                    BackupMetadata.serializer(),
                    BackupMetadata(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 0L)
                ),
                "secrets/future-credential.json" to "{}"
            )
        )
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(tarBytes), encrypted, PASSWORD)
        val bytes = encrypted.toByteArray()

        val result = manager.import(ByteArrayInputStream(bytes), PASSWORD, bytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.skippedLoginSecrets)
        assertEquals(0, stats.loginSecrets)
    }

    // ── 纯规则 ────────────────────────────────────────────────

    @Test
    fun `字节型密文解不开时按原样导出并标记`() {
        val exported = exportSecretBytes(encryptedKeyBytes, isEncrypted = { true }, decrypt = { null })

        assertArrayEquals(encryptedKeyBytes, exported.bytes)
        assertTrue(exported.undecryptable)
    }

    @Test
    fun `字节型密文解得开时导出明文`() {
        val exported = exportSecretBytes(encryptedKeyBytes, isEncrypted = { true }, decrypt = { "PEM".toByteArray() })

        assertArrayEquals("PEM".toByteArray(), exported.bytes)
        assertFalse(exported.undecryptable)
    }

    @Test
    fun `SSH 登录口令列导入时明文加密而密文原样并计数`() {
        val cipher = SecretCipher(
            isEncrypted = { it.startsWith("AICODEENC1:") },
            decrypt = { Result.failure(IllegalStateException("key mismatch")) },
            encrypt = { "enc{$it}" }
        )

        val imported = importSshLoginPrefs(
            mapOf("key_1_name" to "a", "key_1_passphrase" to BROKEN_CIPHER),
            cipher
        )

        assertEquals("a", imported.entries.getValue("key_1_name"))
        assertEquals(BROKEN_CIPHER, imported.entries.getValue("key_1_passphrase"))
        assertEquals(1, imported.undecryptableSecrets)
        assertEquals(1, imported.keyCount)
    }

    @Test
    fun `SSH 登录口令列为明文时导入后加密`() {
        val cipher = SecretCipher(
            isEncrypted = { it.startsWith("AICODEENC1:") },
            decrypt = { error("明文不该走解密") },
            encrypt = { "enc{$it}" }
        )

        val imported = importSshLoginPrefs(mapOf("key_1_name" to "a", "key_1_passphrase" to "pw"), cipher)

        assertEquals("enc{pw}", imported.entries.getValue("key_1_passphrase"))
        assertEquals(0, imported.undecryptableSecrets)
    }

    // ── 夹具 ──────────────────────────────────────────────────

    private fun fakePrefs(): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            prefsStore[firstArg<String>()] = secondArg<String>()
            editor
        }
        every { editor.apply() } just runs
        val prefs = mockk<SharedPreferences>()
        every { prefs.all } answers { HashMap(prefsStore) }
        every { prefs.edit() } returns editor
        return prefs
    }

    private fun baseOptions(loginSecrets: Boolean) = BackupOptions(
        providers = false,
        remoteConnections = false,
        chatHistory = false,
        mcpServers = false,
        permissionRules = false,
        appSettings = false,
        workspaceFiles = false,
        skillsAndAgents = false,
        panelScripts = false,
        memoryFiles = false,
        sessionBriefs = false,
        loginSecrets = loginSecrets
    )

    private fun buildManager(context: Context): BackupManagerImpl {
        val credentials = listOf(
            GitCredential(id = "git.example.com", host = "git.example.com", username = "me", token = "tok-123")
        )
        val targets = listOf(
            BackupTargetEntity(
                id = "t1",
                name = "webdav",
                type = BackupTargetType.WEBDAV,
                endpoint = "https://dav.example.com",
                username = "me",
                password = "AICODEENC1:broken",
                createdAt = 1L
            )
        )
        val credentialRepository = mockk<CredentialRepository> {
            every { getAll() } returns MutableStateFlow(credentials)
            coEvery { save(any()) } answers {
                savedCredentials += firstArg<GitCredential>()
            }
        }
        val backupTargetDao = mockk<BackupTargetDao> {
            coEvery { getAllOnce() } returns targets
            coEvery { upsert(any()) } answers {
                savedTargets += firstArg<BackupTargetEntity>()
            }
        }
        return BackupManagerImpl(
            context = context,
            aiProviderDao = mockk<AIProviderDao>(relaxed = true),
            remoteConnectionDao = mockk<RemoteConnectionDao>(relaxed = true),
            chatSessionDao = mockk<ChatSessionDao>(relaxed = true),
            agentMessageDao = mockk<AgentMessageDao>(relaxed = true),
            todoItemDao = mockk<TodoItemDao>(relaxed = true),
            mcpConfigRepository = mockk<McpConfigRepository>(relaxed = true),
            mcpManager = mockk<McpManager>(relaxed = true),
            permissionRulesRepository = mockk<PermissionRulesRepository>(relaxed = true),
            themeSettingsRepository = mockk<ThemeSettingsRepository>(relaxed = true),
            keepaliveSettingsRepository = mockk<KeepaliveSettingsRepository>(relaxed = true),
            liveNotificationSettingsRepository = mockk<LiveNotificationSettingsRepository>(relaxed = true),
            approvalNotificationSettingsRepository = mockk<ApprovalNotificationSettingsRepository>(relaxed = true),
            screenOnSettingsRepository = mockk<ScreenOnSettingsRepository>(relaxed = true),
            agentSoundSettingsRepository = mockk<AgentSoundSettingsRepository>(relaxed = true),
            generalSettingsRepository = mockk<GeneralSettingsRepository>(relaxed = true),
            logSettingsRepository = mockk<LogSettingsRepository>(relaxed = true),
            visionModelSettingsRepository = mockk<VisionModelSettingsRepository>(relaxed = true),
            compactionModelSettingsRepository = mockk<CompactionModelSettingsRepository>(relaxed = true),
            syncSettingsRepository = mockk<SyncSettingsRepository>(relaxed = true),
            executionModeRepository = mockk<ExecutionModeRepository>(relaxed = true),
            executionModeHolder = mockk<ExecutionModeHolder>(relaxed = true),
            remoteSshConnection = mockk<RemoteSshConnection>(relaxed = true),
            workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
            fileAccess = mockk<FileAccessProvider>(relaxed = true),
            pathHomeResolver = mockk<PathHomeResolver>(relaxed = true),
            skillRepository = mockk<SkillRepository>(relaxed = true),
            agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true),
            skillConfigRepository = mockk<SkillConfigRepository>(relaxed = true),
            agentDefinitionConfigRepository = mockk<AgentDefinitionConfigRepository>(relaxed = true),
            sessionBriefDao = mockk<SessionBriefDao>(relaxed = true),
            credentialRepository = credentialRepository,
            backupTargetDao = backupTargetDao,
            mcpOAuthFile = File(File(context.filesDir, "aicode"), "mcp-oauth.json"),
            availableSpace = object : AvailableSpaceProvider {
                override fun availableBytes(dir: File): Long = Long.MAX_VALUE
            }
        )
    }

    private fun buildTar(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                entries.forEach { (name, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun readTar(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = tar.readBytes()
                    entry = tar.nextEntry
                }
            }
        }
        return entries
    }

    private companion object {
        val PASSWORD = "pw".toCharArray()

        /** 形似 Keystore 字符串密文、在本机必然解不开的值。 */
        const val BROKEN_CIPHER = "AICODEENC1:broken"
    }
}
