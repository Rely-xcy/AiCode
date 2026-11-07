package com.aicode.feature.backup.data

import android.content.Context
import android.content.SharedPreferences
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.McpServerSecretsDto
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
 * MCP 服务器 headers/env 的凭据拆分。
 *
 * mcp.json 里 `headers` / `env` 常直接写着 `Authorization: Bearer ...` 这类令牌，而
 * `options.mcpServers` 默认开启、不要求口令：拆段之前，一次不带口令的导出就会把它们
 * 明文写进 metadata.json。这里锁住四条规则：
 *
 * - 不勾「包含登录凭据」时，metadata 里只有配置，没有 headers/env；
 * - 勾了且带口令时，headers/env 落在 `secrets/mcp-servers.jsonl`；
 * - 导入时从凭据段还原，且不把「没带凭据」当成「用户清空」抹掉本机值；
 * - 拆分之前的老备份（headers 仍在 metadata 里）继续按原值导入。
 */
class BackupMcpSecretsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: BackupManagerImpl
    private lateinit var filesRoot: File

    private val mcpServersInDb = mutableListOf<McpServerConfig>()
    private var savedMcpServers: List<McpServerConfig>? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    @Before
    fun setUp() {
        val cacheRoot = tempFolder.newFolder("cache")
        filesRoot = tempFolder.newFolder("files")
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { all } returns emptyMap<String, Any>()
            every { edit() } returns mockk(relaxed = true)
        }
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns cacheRoot
            every { filesDir } returns filesRoot
            every { getDatabasePath(any()) } returns File(filesRoot, "databases/aicode_agent_db")
            every { packageName } returns "com.aicode.test"
            every { packageManager } throws IllegalStateException("unit test")
            every { getSharedPreferences(SSH_LOGIN_PREFS, Context.MODE_PRIVATE) } returns prefs
        }

        mcpServersInDb.clear()
        mcpServersInDb += McpServerConfig(
            name = "remote",
            url = "https://mcp.example.com",
            headers = mapOf("Authorization" to "Bearer tok-123"),
            enabled = true
        )
        savedMcpServers = null

        manager = buildManager(context)
    }

    @Test
    fun `不勾登录凭据时 metadata 里的 headers 与 env 被清空`() = runBlocking {
        val out = ByteArrayOutputStream()

        manager.export(null, options(loginSecrets = false), out)

        val entries = readTar(out.toByteArray())
        assertFalse(entries.containsKey("secrets/mcp-servers.jsonl"))

        val meta = readMetadata(entries.getValue("metadata.json"))
        assertEquals(1, meta.mcpServers.size)
        assertEquals("https://mcp.example.com", meta.mcpServers.single().url)
        assertTrue("headers 不应进明文包", meta.mcpServers.single().headers.isEmpty())
        assertTrue(meta.mcpServers.single().env.isEmpty())
        // 明文包里任何位置都不该出现令牌原文
        assertTrue(entries.values.none { String(it, Charsets.UTF_8).contains("tok-123") })
    }

    @Test
    fun `勾选登录凭据且带口令时 headers 进凭据段且 metadata 留空占位`() = runBlocking {
        val out = ByteArrayOutputStream()

        manager.export(PASSWORD, options(loginSecrets = true), out)
        val plain = decrypt(manager, out.toByteArray())
        val entries = readTar(plain)

        assertTrue(entries.containsKey("secrets/mcp-servers.jsonl"))
        val secrets = json.decodeFromString(
            McpServerSecretsDto.serializer(),
            String(entries.getValue("secrets/mcp-servers.jsonl"), Charsets.UTF_8).trim()
        )
        assertEquals("remote", secrets.name)
        assertEquals("Bearer tok-123", secrets.headers["Authorization"])

        val meta = readMetadata(entries.getValue("metadata.json"))
        assertTrue(meta.secretsInSeparateSegment)
        assertTrue(meta.mcpServers.single().headers.isEmpty())
    }

    @Test
    fun `导入时从凭据段还原 headers 并计入凭据项`() = runBlocking {
        val out = ByteArrayOutputStream()
        manager.export(PASSWORD, options(loginSecrets = true), out)
        val backupBytes = out.toByteArray()

        // 清掉本机配置，确认还原值来自备份而不是残留
        mcpServersInDb.clear()
        savedMcpServers = null

        val result = manager.import(
            ByteArrayInputStream(backupBytes),
            PASSWORD,
            decrypt(manager, backupBytes).size.toLong()
        )

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.mcpServers)
        assertEquals(1, stats.loginSecrets)
        val restored = savedMcpServers.orEmpty().single()
        assertEquals("Bearer tok-123", restored.headers["Authorization"])
    }

    @Test
    fun `新格式包没带凭据时保留本机 headers`() = runBlocking {
        // 不带口令导出：metadata 里是空占位，凭据段不存在
        val out = ByteArrayOutputStream()
        manager.export(null, options(loginSecrets = false), out)
        val backupBytes = out.toByteArray()

        // 本机已有同名服务器与它的令牌：导入后不能被空占位抹掉
        mcpServersInDb.clear()
        mcpServersInDb += McpServerConfig(
            name = "remote",
            url = "https://mcp.example.com",
            headers = mapOf("Authorization" to "Bearer local-token"),
            enabled = true
        )
        savedMcpServers = null

        val result = manager.import(ByteArrayInputStream(backupBytes), null, backupBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("Bearer local-token", savedMcpServers.orEmpty().single().headers["Authorization"])
        assertEquals(0, result.getOrThrow().loginSecrets)
    }

    @Test
    fun `拆分前的老备份仍从 metadata 读取 headers`() = runBlocking {
        val legacyMeta = BackupMetadata(
            schemaVersion = 1,
            createdAt = 1L,
            mcpServers = listOf(
                McpServerConfig(
                    name = "remote",
                    url = "https://mcp.example.com",
                    headers = mapOf("Authorization" to "Bearer legacy-token")
                )
            ),
            // 老包没有拆分标记
            secretsInSeparateSegment = false
        )
        val backupBytes = buildTar(
            mapOf(
                "metadata.json" to json.encodeToString(BackupMetadata.serializer(), legacyMeta)
            )
        )
        mcpServersInDb.clear()
        savedMcpServers = null

        val result = manager.import(ByteArrayInputStream(backupBytes), null, backupBytes.size.toLong())

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("Bearer legacy-token", savedMcpServers.orEmpty().single().headers["Authorization"])
    }

    private fun options(loginSecrets: Boolean) = BackupOptions(
        providers = false,
        remoteConnections = false,
        chatHistory = false,
        mcpServers = true,
        permissionRules = false,
        appSettings = false,
        workspaceFiles = false,
        skillsAndAgents = false,
        panelScripts = false,
        memoryFiles = false,
        sessionBriefs = false,
        loginSecrets = loginSecrets
    )

    private suspend fun decrypt(manager: BackupManagerImpl, bytes: ByteArray): ByteArray =
        manager.prepareImport(ByteArrayInputStream(bytes), PASSWORD).let { file ->
            try {
                file.readBytes()
            } finally {
                file.delete()
            }
        }

    private fun readMetadata(bytes: ByteArray): BackupMetadata =
        json.decodeFromString(BackupMetadata.serializer(), String(bytes, Charsets.UTF_8))

    private fun buildManager(context: Context): BackupManagerImpl {
        val credentialRepository = mockk<CredentialRepository> {
            every { getAll() } returns MutableStateFlow<List<GitCredential>>(emptyList())
        }
        val backupTargetDao = mockk<BackupTargetDao> {
            coEvery { getAllOnce() } returns emptyList()
        }
        val mcpConfigRepository = mockk<McpConfigRepository>(relaxed = true)
        coEvery { mcpConfigRepository.getGlobalServers() } answers { mcpServersInDb.toList() }
        coEvery { mcpConfigRepository.setGlobalServers(any()) } answers {
            savedMcpServers = firstArg<List<McpServerConfig>>()
        }
        return BackupManagerImpl(
            context = context,
            aiProviderDao = mockk<AIProviderDao>(relaxed = true),
            remoteConnectionDao = mockk<RemoteConnectionDao>(relaxed = true),
            chatSessionDao = mockk<ChatSessionDao>(relaxed = true),
            agentMessageDao = mockk<AgentMessageDao>(relaxed = true),
            todoItemDao = mockk<TodoItemDao>(relaxed = true),
            mcpConfigRepository = mcpConfigRepository,
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
            mcpOAuthFile = File(File(filesRoot, "aicode"), "mcp-oauth.json"),
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
    }
}
