package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.SessionBriefEntity
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.InsufficientSpaceException
import com.aicode.feature.backup.domain.PartialRestoreException
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 记忆 / 会话工作状态的备份与恢复，以及恢复前空间预检、失败时的部分写入统计。
 *
 * 用 mockk 顶掉 Room 与文件系统：DAO 的 REPLACE 语义用 MutableMap 模拟，
 * 记忆目录用 [FileAccessProvider] 的读写桩模拟，这样才能断言「重复导入不产生重复行」。
 */
class BackupMemoryAndBriefsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: BackupManagerImpl
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var sessionBriefDao: SessionBriefDao
    private lateinit var availableSpace: FixedSpaceProvider

    private val memoryStore = mutableMapOf<String, String>()
    private val briefStore = mutableMapOf<String, SessionBriefEntity>()

    private val globalNote = "global memory body"
    private val supersededNote = "superseded body"
    private val briefs = listOf(
        SessionBriefEntity(sessionId = "s1", content = "doing A", updatedAt = 1L),
        SessionBriefEntity(sessionId = "s2", content = "doing B", updatedAt = 2L)
    )

    @Before
    fun setUp() {
        val cacheRoot = tempFolder.newFolder("cache")
        val filesRoot = tempFolder.newFolder("files")
        val context = mockk<Context> {
            every { cacheDir } returns cacheRoot
            every { filesDir } returns filesRoot
            every { getDatabasePath(any()) } returns File(filesRoot, "databases/aicode_agent_db")
            every { packageName } returns "com.aicode.test"
            // appVersionName 用 runCatching 包着，取不到包信息时退化为空串
            every { packageManager } throws IllegalStateException("unit test")
        }

        memoryStore.clear()
        briefStore.clear()
        fileAccess = memoryFileAccess()
        sessionBriefDao = mockk<SessionBriefDao> {
            coEvery { getAllOnce() } returns briefs
            coEvery { upsert(any()) } answers {
                val entity = firstArg<SessionBriefEntity>()
                briefStore[entity.sessionId] = entity
            }
        }
        availableSpace = FixedSpaceProvider(Long.MAX_VALUE)
        manager = buildManager(context)
    }

    @Test
    fun `勾选记忆与会话工作状态时导出包含对应条目`() = runBlocking {
        val entries = exportAndRead(memory = true, briefs = true)

        assertEquals(globalNote, entries["memories/global/global-note.md"])
        assertEquals(supersededNote, entries["memories/global/.superseded/old.md"])

        val ids = entries.getValue("sessionBriefs.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject.getValue("sessionId").jsonPrimitive.content }
            .toList()
        assertEquals(listOf("s1", "s2"), ids)
    }

    @Test
    fun `未勾选时导出不含记忆与会话工作状态`() = runBlocking {
        val entries = exportAndRead(memory = false, briefs = false)

        assertTrue(entries.keys.none { it.startsWith("memories/") })
        assertFalse(entries.containsKey("sessionBriefs.jsonl"))
    }

    @Test
    fun `重复导入同一备份不产生重复行`() = runBlocking {
        val bytes = exportBytes(memory = true, briefs = true)

        repeat(2) {
            val result = manager.import(ByteArrayInputStream(bytes), null, bytes.size.toLong())
            assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        }

        assertEquals(
            setOf("~/.aicode/memory/global-note.md", "~/.aicode/memory/.superseded/old.md"),
            memoryStore.keys
        )
        assertEquals(setOf("s1", "s2"), briefStore.keys)
    }

    @Test
    fun `空间不足时拒绝恢复且一条数据都不写`() = runBlocking {
        val bytes = exportBytes(memory = true, briefs = true)
        availableSpace.bytes = 1000

        val result = manager.import(ByteArrayInputStream(bytes), null, uncompressedBytes = 10_000)

        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertTrue(memoryStore.isEmpty())
        assertTrue(briefStore.isEmpty())
    }

    @Test
    fun `恢复中途失败时带出已写入的实体类型与数量`() = runBlocking {
        val bytes = exportBytes(memory = true, briefs = true)
        var upsertCalls = 0
        coEvery { sessionBriefDao.upsert(any()) } answers {
            upsertCalls++
            if (upsertCalls == 2) throw IllegalStateException("disk full")
            val entity = firstArg<SessionBriefEntity>()
            briefStore[entity.sessionId] = entity
        }

        val result = manager.import(ByteArrayInputStream(bytes), null, bytes.size.toLong())

        val error = result.exceptionOrNull()
        assertTrue("expected PartialRestoreException but was $error", error is PartialRestoreException)
        val stats = (error as PartialRestoreException).partialStats
        assertEquals(2, stats.memoryFiles)
        assertEquals(1, stats.sessionBriefs)
        assertTrue(stats.hasWrittenData)
        assertEquals(1, briefStore.size)
    }

    // ── 夹具 ──────────────────────────────────────────────────

    private fun buildManager(context: Context): BackupManagerImpl = BackupManagerImpl(
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
        workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
        fileAccess = fileAccess,
        pathHomeResolver = mockk<PathHomeResolver>(relaxed = true),
        skillRepository = mockk<SkillRepository>(relaxed = true),
        agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true),
        skillConfigRepository = mockk<SkillConfigRepository>(relaxed = true),
        agentDefinitionConfigRepository = mockk<AgentDefinitionConfigRepository>(relaxed = true),
        sessionBriefDao = sessionBriefDao,
        availableSpace = availableSpace
    )

    private fun memoryFileAccess(): FileAccessProvider {
        val access = mockk<FileAccessProvider>()
        every { access.listFilesRecursive("~/.aicode/memory", any()) } returns listOf(
            "global-note.md",
            ".superseded/old.md"
        )
        every { access.listFilesRecursive("~/workspace/.aicode/memory", any()) } returns emptyList()
        every { access.readBytes("~/.aicode/memory/global-note.md") } returns globalNote.toByteArray()
        every { access.readBytes("~/.aicode/memory/.superseded/old.md") } returns supersededNote.toByteArray()
        every { access.fileSize(any()) } returns 8L
        every { access.writeBytes(any(), any(), any()) } answers {
            memoryStore[firstArg<String>()] = secondArg<ByteArray>().decodeToString()
            Unit
        }
        return access
    }

    private fun baseOptions(memory: Boolean, briefs: Boolean) = BackupOptions(
        providers = false,
        remoteConnections = false,
        chatHistory = false,
        mcpServers = false,
        permissionRules = false,
        appSettings = false,
        workspaceFiles = false,
        skillsAndAgents = false,
        panelScripts = false,
        memoryFiles = memory,
        sessionBriefs = briefs
    )

    private suspend fun exportBytes(memory: Boolean, briefs: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        manager.export(null, baseOptions(memory, briefs), out)
        return out.toByteArray()
    }

    private suspend fun exportAndRead(memory: Boolean, briefs: Boolean): Map<String, String> =
        readTar(exportBytes(memory, briefs))

    private fun readTar(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = String(tar.readBytes(), Charsets.UTF_8)
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return entries
    }

    private class FixedSpaceProvider(var bytes: Long) : AvailableSpaceProvider {
        override fun availableBytes(dir: File): Long = bytes
    }
}
