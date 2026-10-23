package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
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

/**
 * 导入格式预检：metadata.json 缺失或不在第一条时，必须在写入任何数据之前失败。
 *
 * 回归点：旧实现只在遍历结束后才检查 metadata，缺 metadata 或它排在文件条目之后时，
 * 工作区文件、会话行已经写进本机，用户看到的却只是一句「导入失败」。
 */
class BackupImportFormatGuardTest {

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
    fun `缺 metadata 时导入失败且不写入任何文件`() = runBlocking {
        val result = import(
            entries = linkedMapOf("workspaces/project/notes.txt" to "body"),
            includeMetadata = false
        )

        assertTrue("缺少 metadata.json 必须失败", result.isFailure)
        assertFalse("失败前不得落盘", File(workspaceRoot, "notes.txt").exists())
    }

    @Test
    fun `metadata 排在文件之后时导入失败且不写入任何文件`() = runBlocking {
        val result = import(
            entries = linkedMapOf("workspaces/project/notes.txt" to "body"),
            metadataLast = true
        )

        assertTrue("metadata 不在第一条必须失败", result.isFailure)
        assertFalse(File(workspaceRoot, "notes.txt").exists())
    }

    @Test
    fun `预览与导入使用同一判据`() = runBlocking {
        val bytes = buildArchive(
            linkedMapOf("workspaces/project/notes.txt" to "body"),
            includeMetadata = false
        )

        val preview = manager.previewImport(ByteArrayInputStream(bytes), null)

        assertTrue("缺 metadata 的包预览也必须失败", preview.isFailure)
    }

    @Test
    fun `metadata 在第一条时正常导入`() = runBlocking {
        val result = import(entries = linkedMapOf("workspaces/project/notes.txt" to "body"))

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("body", File(workspaceRoot, "notes.txt").readText())
    }

    private suspend fun import(
        entries: Map<String, String>,
        includeMetadata: Boolean = true,
        metadataLast: Boolean = false
    ) = manager.import(
        input = ByteArrayInputStream(buildArchive(entries, includeMetadata, metadataLast)),
        password = null,
        uncompressedBytes = Long.MAX_VALUE,
        selectedWorkspaces = setOf("project")
    )

    private fun buildArchive(
        entries: Map<String, String>,
        includeMetadata: Boolean = true,
        metadataLast: Boolean = false
    ): ByteArray {
        val metadataJson = Json.encodeToString(
            BackupMetadata.serializer(),
            BackupMetadata(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 0L)
        )
        val ordered = LinkedHashMap<String, String>()
        if (includeMetadata && !metadataLast) ordered["metadata.json"] = metadataJson
        ordered.putAll(entries)
        if (includeMetadata && metadataLast) ordered["metadata.json"] = metadataJson

        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                ordered.forEach { (name, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
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
}
