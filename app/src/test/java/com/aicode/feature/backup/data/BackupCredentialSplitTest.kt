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
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.ProviderSecretsDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
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
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 供应商密钥与连接密码从「配置」里拆出来后的导出 / 导入行为。
 *
 * 四条规则错了都是静默事故：
 * 不勾凭据时密钥不该出现在包里；勾了凭据时 metadata 里不能留真值；旧包（密钥在 metadata 里）
 * 必须照旧能导入；新包（密钥在凭据段或根本没带）导入时不能把本机密钥抹成空。
 *
 * 加解密绑定 Android Keystore、JVM 里跑不了，所以端到端用例只用「本机解不开的密文」
 * （导入时原样落库、不需加密），纯规则另见 [BackupSecretMappingTest]。
 */
class BackupCredentialSplitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: BackupManagerImpl

    /** 本机当前数据；测试中途可以直接改成「新设备的空库」或「有旧密钥的库」。 */
    private var providersInDb: List<AIProviderEntity> = emptyList()
    private var connectionsInDb: List<RemoteConnectionEntity> = emptyList()

    private val savedProviders = mutableListOf<AIProviderEntity>()
    private val savedConnections = mutableListOf<RemoteConnectionEntity>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 模拟旧包：不带任何新字段的默认值，解码时只能靠字段默认值兜底。 */
    private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before
    fun setUp() {
        val cacheRoot = tempFolder.newFolder("cache")
        val filesRoot = tempFolder.newFolder("files")
        val prefs = mockk<SharedPreferences> {
            every { all } returns hashMapOf<String, Any>()
        }
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns cacheRoot
            every { filesDir } returns filesRoot
            every { getDatabasePath(any()) } returns File(filesRoot, "databases/aicode_agent_db")
            every { packageName } returns "com.aicode.test"
            // appVersionName 用 runCatching 包着，取不到包信息时退化为空串
            every { packageManager } throws IllegalStateException("unit test")
            every { getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE) } returns prefs
        }
        savedProviders.clear()
        savedConnections.clear()
        manager = buildManager(context)
    }

    @Test
    fun `不勾凭据时供应商密钥与连接密码不出现在导出包里`() = runBlocking {
        providersInDb = listOf(provider())
        connectionsInDb = listOf(passwordConnection())

        val out = ByteArrayOutputStream()
        val stats = manager.export(null, options(loginSecrets = false), out)
        val entries = readTar(out.toByteArray())
        val metadata = String(entries.getValue("metadata.json"), Charsets.UTF_8)

        // 配置字段照常导出
        assertTrue(metadata.contains("供应商A"))
        assertTrue(metadata.contains("https://api.example.com"))
        assertTrue(metadata.contains("10.0.0.2"))
        // 新格式明确标记「密钥不在这份 metadata 里」，导入侧据此不能把空占位当真值
        assertTrue(metadata.contains("\"secretsInSeparateSegment\":true"))
        // 密钥一个都不能出现
        assertFalse(metadata.contains(BROKEN_CIPHER))
        assertFalse(entries.containsKey("secrets/provider-secrets.jsonl"))
        assertFalse(entries.containsKey("secrets/connection-secrets.jsonl"))
        assertTrue(entries.keys.none { it.startsWith("secrets/") })
        assertEquals(0, stats.undecryptableSecrets)
    }

    @Test
    fun `勾选凭据时密钥进凭据段而 metadata 只留配置`() = runBlocking {
        providersInDb = listOf(provider())
        connectionsInDb = listOf(passwordConnection())

        val out = ByteArrayOutputStream()
        val exportStats = manager.export(PASSWORD, options(loginSecrets = true), out)
        val backupBytes = out.toByteArray()
        val plainBytes = manager.prepareImport(ByteArrayInputStream(backupBytes), PASSWORD).let { file ->
            try {
                file.readBytes()
            } finally {
                file.delete()
            }
        }
        val entries = readTar(plainBytes)
        val metadata = String(entries.getValue("metadata.json"), Charsets.UTF_8)

        assertFalse(metadata.contains(BROKEN_CIPHER))
        val providerSecrets = String(entries.getValue("secrets/provider-secrets.jsonl"), Charsets.UTF_8)
        val connectionSecrets = String(entries.getValue("secrets/connection-secrets.jsonl"), Charsets.UTF_8)
        assertTrue(providerSecrets.contains("\"id\":\"p1\""))
        assertTrue(providerSecrets.contains(BROKEN_CIPHER))
        assertTrue(connectionSecrets.contains("\"id\":\"c1\""))
        assertTrue(connectionSecrets.contains(BROKEN_CIPHER))
        // 供应商 5 个密钥字段 + 连接密码与口令各 1
        assertEquals(7, exportStats.undecryptableSecrets)

        // 模拟换设备：导入时本机没有任何既有记录，密钥只能来自凭据段
        providersInDb = emptyList()
        connectionsInDb = emptyList()

        val result = manager.import(ByteArrayInputStream(backupBytes), PASSWORD, plainBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.providers)
        assertEquals(1, stats.remoteConnections)
        assertEquals(2, stats.loginSecrets)
        assertEquals(7, stats.undecryptableSecrets)
        assertEquals(0, stats.skippedLoginSecrets)
        assertEquals(BROKEN_CIPHER, savedProviders.single().apiKey)
        assertEquals(BROKEN_CIPHER, savedProviders.single().proxyPassword)
        assertEquals(BROKEN_CIPHER, savedProviders.single().customHeaders)
        assertEquals(BROKEN_CIPHER, savedProviders.single().apiKeys)
        assertEquals(BROKEN_CIPHER, savedProviders.single().scriptParams)
        assertEquals(BROKEN_CIPHER, savedConnections.single().authData)
        assertEquals(BROKEN_CIPHER, savedConnections.single().passphrase)
    }

    @Test
    fun `旧包把密钥放在 metadata 里时仍能导入`() = runBlocking {
        val metadata = BackupMetadata(
            schemaVersion = AgentDatabase.SCHEMA_VERSION,
            createdAt = 1L,
            providers = listOf(
                ProviderDto(
                    id = "p1",
                    name = "供应商A",
                    type = "OPENAI",
                    apiKey = BROKEN_CIPHER,
                    baseUrl = "https://api.example.com",
                    defaultModel = "gpt-4o",
                    apiKeys = BROKEN_CIPHER,
                    proxyPassword = BROKEN_CIPHER,
                    customHeaders = BROKEN_CIPHER,
                    scriptParams = BROKEN_CIPHER
                )
            ),
            remoteConnections = listOf(
                RemoteConnectionDto(
                    id = "c1",
                    name = "srv",
                    protocol = "SFTP",
                    host = "10.0.0.2",
                    port = 22,
                    username = "root",
                    authType = "PASSWORD",
                    authData = BROKEN_CIPHER,
                    passphrase = BROKEN_CIPHER
                )
            )
        )
        val metadataJson = legacyJson.encodeToString(BackupMetadata.serializer(), metadata)
        // 旧包根本没有这个标记字段，也没有新的凭据段
        assertFalse(metadataJson.contains("secretsInSeparateSegment"))
        val tarBytes = buildTar(mapOf("metadata.json" to metadataJson))

        val result = manager.import(ByteArrayInputStream(tarBytes), null, tarBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.providers)
        assertEquals(1, stats.remoteConnections)
        // 旧包的密钥不算「凭据段还原」，但解不开的字段仍要计数
        assertEquals(0, stats.loginSecrets)
        assertEquals(7, stats.undecryptableSecrets)
        assertEquals(BROKEN_CIPHER, savedProviders.single().apiKey)
        assertEquals(BROKEN_CIPHER, savedProviders.single().proxyPassword)
        assertEquals(BROKEN_CIPHER, savedConnections.single().authData)
        assertEquals(BROKEN_CIPHER, savedConnections.single().passphrase)
    }

    @Test
    fun `新包没勾凭据时导入不会抹掉本机密钥`() = runBlocking {
        providersInDb = listOf(provider())
        connectionsInDb = listOf(passwordConnection())

        val out = ByteArrayOutputStream()
        manager.export(null, options(loginSecrets = false), out)
        val backupBytes = out.toByteArray()

        // 同一台设备上的本机记录仍在（这是「恢复一次配置」而不是换机）
        val result = manager.import(ByteArrayInputStream(backupBytes), null, backupBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.providers)
        assertEquals(1, stats.remoteConnections)
        // 包里没带凭据段：一项凭据都没还原，也不能把空占位写成空串
        assertEquals(0, stats.loginSecrets)
        assertEquals(0, stats.undecryptableSecrets)
        assertEquals(BROKEN_CIPHER, savedProviders.single().apiKey)
        assertEquals(BROKEN_CIPHER, savedProviders.single().proxyPassword)
        assertEquals(BROKEN_CIPHER, savedProviders.single().customHeaders)
        assertEquals(BROKEN_CIPHER, savedConnections.single().authData)
        assertEquals(BROKEN_CIPHER, savedConnections.single().passphrase)
    }

    @Test
    fun `凭据段里有配置段没带的供应商时计入未还原`() = runBlocking {
        val metadata = BackupMetadata(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 0L)
        val tarBytes = buildTar(
            linkedMapOf(
                "metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata),
                "secrets/provider-secrets.jsonl" to (
                    json.encodeToString(
                        ProviderSecretsDto.serializer(),
                        ProviderSecretsDto(id = "ghost", apiKey = BROKEN_CIPHER)
                    ) + "\n"
                )
            )
        )

        val result = manager.import(ByteArrayInputStream(tarBytes), null, tarBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        // 没有宿主记录可写：既不算还原，也不能静默丢掉
        assertEquals(0, stats.loginSecrets)
        assertEquals(1, stats.skippedLoginSecrets)
    }

    // ── 夹具 ──────────────────────────────────────────────────

    private fun provider() = AIProviderEntity(
        id = "p1",
        name = "供应商A",
        type = "OPENAI",
        apiKey = BROKEN_CIPHER,
        apiKeys = BROKEN_CIPHER,
        baseUrl = "https://api.example.com",
        defaultModel = "gpt-4o",
        models = "gpt-4o",
        proxyPassword = BROKEN_CIPHER,
        customHeaders = BROKEN_CIPHER,
        scriptParams = BROKEN_CIPHER
    )

    private fun passwordConnection() = RemoteConnectionEntity(
        id = "c1",
        name = "srv",
        protocol = RemoteProtocol.SFTP,
        host = "10.0.0.2",
        port = 22,
        username = "root",
        authType = "PASSWORD",
        authData = BROKEN_CIPHER,
        passphrase = BROKEN_CIPHER
    )

    private fun options(loginSecrets: Boolean) = BackupOptions(
        providers = true,
        remoteConnections = true,
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
        val credentialRepository = mockk<CredentialRepository> {
            every { getAll() } returns MutableStateFlow<List<GitCredential>>(emptyList())
        }
        val backupTargetDao = mockk<BackupTargetDao> {
            coEvery { getAllOnce() } returns emptyList()
        }
        val aiProviderDao = mockk<AIProviderDao> {
            coEvery { getAllProvidersOnce() } answers { providersInDb }
            coEvery { insertAllProviders(any()) } answers {
                savedProviders += firstArg<List<AIProviderEntity>>()
            }
        }
        val remoteConnectionDao = mockk<RemoteConnectionDao> {
            coEvery { getAllConnectionsOnce() } answers { connectionsInDb }
            coEvery { getAllMountsOnce() } returns emptyList()
            coEvery { insertAllConnections(any()) } answers {
                savedConnections += firstArg<List<RemoteConnectionEntity>>()
            }
        }
        return BackupManagerImpl(
            context = context,
            aiProviderDao = aiProviderDao,
            remoteConnectionDao = remoteConnectionDao,
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
