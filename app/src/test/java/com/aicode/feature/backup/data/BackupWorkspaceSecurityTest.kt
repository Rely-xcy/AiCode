package com.aicode.feature.backup.data

import android.content.Context
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
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.RestoreStats
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
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupWorkspaceSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: BackupManagerImpl
    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        val cacheRoot = tempFolder.newFolder("cache")
        val filesRoot = tempFolder.newFolder("files")
        workspaceRoot = tempFolder.newFolder("project")
        val context = mockk<Context> {
            every { cacheDir } returns cacheRoot
            every { filesDir } returns filesRoot
            every { getDatabasePath(any()) } returns File(filesRoot, "databases/aicode_agent_db")
        }
        val workspaceRepository = mockk<WorkspaceRepository> {
            every { workspaces } returns MutableStateFlow(
                listOf(Workspace(name = "project", path = workspaceRoot.path))
            )
        }
        manager = buildManager(context, workspaceRepository)
    }

    @Test
    fun `根仓库敏感文件的原名和dot路径都跳过且保留已有文件`() = runBlocking {
        File(workspaceRoot, "dir").mkdirs()
        val paths = listOf(
            ".git/config",
            ".git/./config",
            "./.git/config",
            "dir/../.git/config",
            ".git/hooks/../config",
            ".git/hooks/pre-commit",
            ".git/./hooks/pre-commit",
            ".git/hooks/./pre-push",
            "dir/../.git/hooks/pre-push"
        )
        val targets = paths.map { File(workspaceRoot, it).canonicalFile }.distinct()
        targets.forEach { writeFile(it, "existing") }

        val stats = importEntries(paths.associateWith { "backup" })

        assertEquals(paths.size, stats.skippedSecurityEntries)
        assertEquals(0, stats.workspaceFiles)
        targets.forEach { assertEquals(it.path, "existing", it.readText()) }
    }

    @Test
    fun `嵌套仓库config和hooks按路径段识别且不会创建新文件`() = runBlocking {
        File(workspaceRoot, "repo/dir").mkdirs()
        val paths = listOf(
            "repo/.git/config",
            "repo/.git/./config",
            "repo/dir/../.git/config",
            "repo/.git/hooks/pre-commit",
            "repo/.git/hooks/./pre-push",
            "repo/dir/../.git/hooks/pre-push",
            "repo/nested/.git/config",
            "repo/nested/.git/hooks/pre-commit"
        )

        val stats = importEntries(paths.associateWith { "backup" })

        assertEquals(paths.size, stats.skippedSecurityEntries)
        assertEquals(0, stats.workspaceFiles)
        paths.forEach { assertFalse(it, File(workspaceRoot, it).exists()) }
    }

    @Test
    fun `已有符号链接映射到敏感目标或从敏感路径映出时都跳过`() = runBlocking {
        val config = writeFile(File(workspaceRoot, "repo/.git/config"), "existing config")
        val hook = writeFile(File(workspaceRoot, "repo/.git/hooks/pre-commit"), "existing hook")
        val settings = writeFile(File(workspaceRoot, "settings.txt"), "existing settings")
        val script = writeFile(File(workspaceRoot, "scripts/pre-push"), "existing script")
        Files.createSymbolicLink(File(workspaceRoot, "git-alias").toPath(), config.parentFile.toPath())
        Files.createSymbolicLink(File(workspaceRoot, "config-alias").toPath(), config.toPath())
        Files.createSymbolicLink(File(workspaceRoot, "hooks-alias").toPath(), hook.parentFile.toPath())
        File(workspaceRoot, "mapped/.git").mkdirs()
        Files.createSymbolicLink(File(workspaceRoot, "mapped/.git/config").toPath(), settings.toPath())
        Files.createSymbolicLink(File(workspaceRoot, "mapped/.git/hooks").toPath(), script.parentFile.toPath())
        val paths = listOf(
            "git-alias/config",
            "git-alias/hooks/pre-commit",
            "config-alias",
            "hooks-alias/pre-commit",
            "hooks-alias/new-hook",
            "hooks-alias/../config",
            "mapped/.git/./config",
            "mapped/.git/hooks/pre-push",
            "mapped/.git/hooks/new-hook"
        )

        val stats = importEntries(paths.associateWith { "backup" })

        assertEquals(paths.size, stats.skippedSecurityEntries)
        assertEquals(0, stats.workspaceFiles)
        assertEquals("existing config", config.readText())
        assertEquals("existing hook", hook.readText())
        assertEquals("existing settings", settings.readText())
        assertEquals("existing script", script.readText())
        assertFalse(File(hook.parentFile, "new-hook").exists())
        assertFalse(File(script.parentFile, "new-hook").exists())
    }

    @Test
    fun `canonical边界拒绝dotdot和符号链接逃逸且继续恢复普通文件`() = runBlocking {
        val outside = tempFolder.newFolder("project-sibling")
        val existing = writeFile(File(outside, "existing.txt"), "outside existing")
        Files.createSymbolicLink(File(workspaceRoot, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(workspaceRoot, "file-escape").toPath(), existing.toPath())
        val entries = linkedMapOf(
            "escape/existing.txt" to "backup",
            "escape/new.txt" to "backup",
            "file-escape" to "backup",
            "../project-sibling/existing.txt" to "backup",
            "../project-sibling/new.txt" to "backup",
            "safe.txt" to "restored"
        )

        val stats = importEntries(entries)

        assertEquals(1, stats.workspaceFiles)
        assertEquals(0, stats.skippedSecurityEntries)
        // 越出工作区的条目现在会计入「跳过的工作区文件」：既不写入，也不会默不作声地消失
        assertEquals(entries.size - 1, stats.skippedWorkspaceFiles)
        assertEquals("outside existing", existing.readText())
        assertFalse(File(outside, "new.txt").exists())
        assertEquals("restored", File(workspaceRoot, "safe.txt").readText())
    }

    @Test
    fun `对象refs和普通文件的规范化路径及根内符号链接不误伤`() = runBlocking {
        File(workspaceRoot, "dir").mkdirs()
        val docs = File(workspaceRoot, "docs").apply { mkdirs() }
        Files.createSymbolicLink(File(workspaceRoot, "docs-alias").toPath(), docs.toPath())
        val gitDir = File(workspaceRoot, "repo/.git").apply { mkdirs() }
        Files.createSymbolicLink(File(workspaceRoot, "git-alias").toPath(), gitDir.toPath())
        val entries = linkedMapOf(
            ".git/objects/aa/blob" to "root object",
            ".git/./refs/heads/main" to "root ref",
            "dir/../.git/HEAD" to "root head",
            "repo/.git/objects/bb/blob" to "nested object",
            "repo/.git/refs/heads/main" to "nested ref",
            "git-alias/objects/cc/blob" to "linked object",
            "git-alias/refs/heads/linked" to "linked ref",
            ".git/config.backup" to "config copy",
            ".git/hooks.txt" to "hooks note",
            "repo/not.git/config" to "ordinary config",
            "hooks/pre-commit" to "ordinary hook",
            "config" to "ordinary file",
            "src/./main.txt" to "source",
            "docs-alias/readme.txt" to "linked docs"
        )

        val stats = importEntries(entries)

        assertEquals(entries.size, stats.workspaceFiles)
        assertEquals(0, stats.skippedSecurityEntries)
        entries.forEach { (path, content) ->
            assertEquals(path, content, File(workspaceRoot, path).readText())
        }
        assertEquals("linked docs", File(docs, "readme.txt").readText())
    }

    @Test
    fun `未勾选的工作区不写文件也不计入安全跳过`() = runBlocking {
        val stats = importEntries(
            mapOf(".git/./config" to "backup", "safe.txt" to "backup"),
            selectedWorkspaces = emptySet()
        )

        assertEquals(0, stats.workspaceFiles)
        assertEquals(0, stats.skippedSecurityEntries)
        assertFalse(File(workspaceRoot, ".git").exists())
        assertFalse(File(workspaceRoot, "safe.txt").exists())
    }

    private fun buildManager(context: Context, workspaceRepository: WorkspaceRepository) = BackupManagerImpl(
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
        workspaceRepository = workspaceRepository,
        fileAccess = mockk<FileAccessProvider>(relaxed = true),
        pathHomeResolver = mockk<PathHomeResolver>(relaxed = true),
        skillRepository = mockk<SkillRepository>(relaxed = true),
        agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true),
        skillConfigRepository = mockk<SkillConfigRepository>(relaxed = true),
        agentDefinitionConfigRepository = mockk<AgentDefinitionConfigRepository>(relaxed = true),
        sessionBriefDao = mockk<SessionBriefDao>(relaxed = true),
        availableSpace = object : AvailableSpaceProvider {
            override fun availableBytes(dir: File): Long = Long.MAX_VALUE
        }
    )

    private fun writeFile(file: File, content: String): File = file.apply {
        parentFile?.mkdirs()
        writeText(content)
    }

    private suspend fun importEntries(
        entries: Map<String, String>,
        selectedWorkspaces: Set<String> = setOf("project")
    ): RestoreStats {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val metadata = BackupMetadata(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 0L)
                val metadataJson = Json.encodeToString(BackupMetadata.serializer(), metadata)
                val archiveEntries = linkedMapOf("metadata.json" to metadataJson)
                entries.forEach { (path, content) -> archiveEntries["workspaces/project/$path"] = content }
                archiveEntries.forEach { (name, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        val bytes = output.toByteArray()
        val result = manager.import(ByteArrayInputStream(bytes), null, bytes.size.toLong(), selectedWorkspaces)
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        return result.getOrThrow()
    }
}
