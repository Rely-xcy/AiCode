package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.SessionBriefEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.BackupCrypto
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.BackupSpace
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.ImportPreview
import com.aicode.feature.backup.domain.PartialRestoreException
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.RemoteMountDto
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.SessionBriefDto
import com.aicode.feature.backup.domain.SkillsAgentsConfigDto
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.toMetadata
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val liveNotificationSettingsRepository: LiveNotificationSettingsRepository,
    private val approvalNotificationSettingsRepository: ApprovalNotificationSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val skillRepository: SkillRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val skillConfigRepository: SkillConfigRepository,
    private val agentDefinitionConfigRepository: AgentDefinitionConfigRepository,
    private val sessionBriefDao: SessionBriefDao,
    private val availableSpace: AvailableSpaceProvider
) : BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun currentSchemaVersion(): Int = AgentDatabase.SCHEMA_VERSION

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val pw = password?.takeIf { it.isNotEmpty() }
            ensureExportSpace(encrypted = pw != null, options = options)
            if (pw == null) {
                writeTarGz(output, options)
                return@withContext
            }
            val temp = createTempFile()
            try {
                FileOutputStream(temp).buffered().use { writeTarGz(it, options) }
                FileInputStream(temp).buffered().use { input ->
                    BackupCrypto.encryptStream(input, output, pw)
                }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun prepareImport(input: InputStream, password: CharArray?): File = withContext(Dispatchers.IO) {
        val temp = createTempFile()
        try {
            FileOutputStream(temp).buffered().use { output ->
                val source = input.buffered()
                val pw = password?.takeIf { it.isNotEmpty() }
                if (pw == null) source.copyTo(output) else BackupCrypto.decryptStream(source, output, pw)
            }
            temp
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    override suspend fun exportSession(sessionId: String, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val session = chatSessionDao.getById(sessionId) ?: error("Session not found: $sessionId")
            val temp = createTempFile()
            try {
                FileOutputStream(temp).use { fos ->
                    GzipCompressorOutputStream(fos).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            // 单会话导出只带会话/消息/待办，不带任何设置：写明「没带设置」，
                            // 否则导入时会把设置字段的默认值当成真值回写一遍（见 restoreMeta）。
                            writeMetadataEntry(tar, BackupMetadata(
                                schemaVersion = currentSchemaVersion(),
                                appVersion = appVersionName(),
                                createdAt = System.currentTimeMillis(),
                                appSettingsIncluded = false
                            ))
                            writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                                writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), session.toDto()))
                            }
                            writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = agentMessageDao.getPageBySessionAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().timestamp
                                    lastId = batch.last().id
                                }
                            }
                            writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = todoItemDao.getBySessionPageAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().createdAt
                                    lastId = batch.last().id
                                }
                            }
                        }
                    }
                }
                FileInputStream(temp).use { it.copyTo(output) }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun import(
        input: InputStream,
        password: CharArray?,
        uncompressedBytes: Long,
        selectedWorkspaces: Set<String>?
    ): Result<RestoreStats> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "导入备份开始（${if (pw != null) "加密" else "明文"}${if (selectedWorkspaces != null) "，勾选工作区=${selectedWorkspaces.size}个" else "，全量"}）")
            runCatching {
                // 预检必须在任何写库动作之前：空间不足时直接失败，不留下半套数据。
                ensureRestoreSpace(uncompressedBytes)
                openTar(input, pw).use { source ->
                    restoreFromTar(source.tar, selectedWorkspaces)
                }
            }
            .onSuccess { FileLogger.i(TAG, "导入备份完成：$it") }
            .onFailure { FileLogger.e(TAG, "导入备份失败", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    override suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "导入预览开始（${if (pw != null) "加密" else "明文"}）")
            runCatching {
                openTar(input, pw).use { source ->
                    val tar = source.tar
                    var workspaces: List<WorkspaceBackupMeta> = emptyList()
                    var uncompressedBytes = 0L
                    var entry = tar.nextEntry
                    while (entry != null) {
                        // 把整个 tar 走完：一是拿到全部条目的声明大小做恢复前空间预检，
                        // 二是让 gzip 的完整性校验（尾部 CRC）在写入任何数据之前先跑一遍。
                        uncompressedBytes += entry.size.coerceAtLeast(0L)
                        if (entry.name == FILE_METADATA) {
                            val plain = tar.readBytes()
                            val metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                            checkVersion(metadata.schemaVersion)
                            workspaces = metadata.workspaces
                        }
                        entry = tar.nextEntry
                    }
                    ImportPreview(workspaces, uncompressedBytes)
                }
            }
            .onSuccess { FileLogger.i(TAG, "导入预览完成：${it.workspaces.size} 个工作区") }
            .onFailure { FileLogger.e(TAG, "导入预览失败", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    /** 把导入异常映射为用户可读的 IllegalArgumentException（解密异常原样抛出）。 */
    private fun mapImportError(e: Throwable, pw: CharArray?): Nothing {
        when (e) {
            is BackupDecryptionException -> throw e
            is IllegalStateException -> throw e
            else -> throw IllegalArgumentException(
                if (pw != null) {
                    "备份文件已损坏，或口令与备份文件不匹配"
                } else {
                    "不是有效的 AiCode 备份文件；如果这是加密备份，请输入导出口令"
                },
                e
            )
        }
    }

    // ── 空间预检 ──────────────────────────────────────────────

    /** 恢复要写库：检查数据库所在分区的可用空间。 */
    private fun ensureRestoreSpace(uncompressedBytes: Long) {
        val target = context.getDatabasePath(AgentDatabase.DATABASE_NAME)?.parentFile ?: context.filesDir
        BackupSpace.requireSpace(availableSpace.availableBytes(target), uncompressedBytes)
    }

    /**
     * 导出也要落本地临时文件，因此检查应用缓存分区的可用空间。
     *
     * 明文导出时只有各段 jsonl 会落到缓存（工作区/资产/记忆直接流式写进 tar），按数据库体积估算即可；
     * 加密导出要把整个 tar.gz 先写进缓存，按全部待导出数据估算。tar.gz 是压缩后的产物，
     * 以未压缩体积做基数偏保守，但不会让正常导出因「算不准」而中途盘满。
     */
    private fun ensureExportSpace(encrypted: Boolean, options: BackupOptions) {
        val payload = if (encrypted) estimateExportBytes(options) else databaseBytes()
        if (payload <= 0) return
        BackupSpace.requireSpace(availableSpace.availableBytes(context.cacheDir), payload)
    }

    private fun databaseBytes(): Long {
        val db = context.getDatabasePath(AgentDatabase.DATABASE_NAME) ?: return 0
        return db.length() + File("${db.path}-wal").length()
    }

    /** 粗估导出体积：数据库 + 勾选的工作区/记忆/资产。目录读不到时按 0 计，不阻断导出。 */
    private fun estimateExportBytes(options: BackupOptions): Long {
        var total = databaseBytes()
        if (options.workspaceFiles) {
            backupLocalWorkspaces().forEach { ws ->
                walkWorkspaceFiles(ws) { file, _ -> total += file.length() }
            }
        }
        if (options.memoryFiles) {
            memoryRoots().forEach { total += directoryBytes(it.root.trimEnd('/'), MEMORY_MAX_DEPTH) }
        }
        if (options.skillsAndAgents || options.panelScripts) {
            assetRoots().forEach { root ->
                val enabled = if (root.kind == AssetKind.PANEL_SCRIPT) options.panelScripts else options.skillsAndAgents
                if (enabled) total += directoryBytes(root.root.trimEnd('/'), ASSET_MAX_DEPTH)
            }
        }
        return total
    }

    private fun directoryBytes(base: String, maxDepth: Int): Long = runCatching {
        fileAccess.listFilesRecursive(base, maxDepth).sumOf { relative ->
            val rel = normalizeAssetPath(relative) ?: return@sumOf 0L
            runCatching { fileAccess.fileSize("$base/$rel") }.getOrDefault(0L)
        }
    }.getOrElse { 0L }

    /** 打开 tar 流：先按需解密到临时文件，再解压；调用方负责 [TarSource.close]。 */
    private fun openTar(input: InputStream, pw: CharArray?): TarSource {
        if (pw == null) {
            FileLogger.i(TAG, "明文备份：直接解压 tar.gz")
            val p = BufferedInputStream(input)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), null)
        }
        FileLogger.i(TAG, "加密备份：先解密到临时文件")
        val temp = createTempFile()
        try {
            BufferedInputStream(input).use { src ->
                FileOutputStream(temp).buffered().use { dst -> BackupCrypto.decryptStream(src, dst, pw) }
            }
            val p = FileInputStream(temp).buffered()
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    // ── 导出辅助 ──────────────────────────────────────────────

    private suspend fun writeTarGz(output: OutputStream, options: BackupOptions) {
        val stream = object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
            }

            override fun close() {
                flush()
            }
        }
        stream.buffered().use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    writeMetadataEntry(tar, buildMetadata(options))
                    if (options.workspaceFiles) {
                        writeWorkspaceEntries(tar)
                    }
                    if (options.skillsAndAgents || options.panelScripts) {
                        writeAssetEntries(tar, options)
                    }
                    if (options.memoryFiles) {
                        writeMemoryEntries(tar)
                    }
                    if (options.sessionBriefs) {
                        writeSessionBriefEntries(tar)
                    }
                    if (options.chatHistory) {
                        writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = chatSessionDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), it.toDto())) }
                                lastTs = batch.last().updatedAt
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = agentMessageDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                lastTs = batch.last().timestamp
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = todoItemDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                lastTs = batch.last().createdAt
                                lastId = batch.last().id
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildMetadata(options: BackupOptions): BackupMetadata = BackupMetadata(
        schemaVersion = currentSchemaVersion(),
        appVersion = appVersionName(),
        createdAt = System.currentTimeMillis(),
        providers = if (options.providers) aiProviderDao.getAllProvidersOnce().map { it.toDto() } else emptyList(),
        remoteConnections = if (options.remoteConnections) remoteConnectionDao.getAllConnectionsOnce().map { it.toDto() } else emptyList(),
        remoteMounts = if (options.remoteConnections) remoteConnectionDao.getAllMountsOnce().map { it.toDto() } else emptyList(),
        mcpServers = if (options.mcpServers) mcpConfigRepository.getGlobalServers() else emptyList(),
        globalPermissionRules = if (options.permissionRules) permissionRulesRepository.getGlobalRulesOnce() else emptyList(),
        themeMode = if (options.appSettings) themeSettingsRepository.snapshot() else null,
        themePresetId = if (options.appSettings) themeSettingsRepository.presetSnapshot() else null,
        dynamicColorEnabled = if (options.appSettings) themeSettingsRepository.dynamicColorSnapshot() else false,
        keepaliveEnabled = if (options.appSettings) keepaliveSettingsRepository.snapshot() else false,
        liveNotificationEnabled = if (options.appSettings) liveNotificationSettingsRepository.snapshot() else false,
        approvalNotificationEnabled = if (options.appSettings) approvalNotificationSettingsRepository.snapshot() else false,
        screenOnEnabled = if (options.appSettings) screenOnSettingsRepository.snapshot() else false,
        agentSoundEnabled = if (options.appSettings) agentSoundSettingsRepository.snapshot() else false,
        autoRemoveStaleModels = if (options.appSettings) generalSettingsRepository.autoRemoveStaleModelsSnapshot() else true,
        startupSessionMode = if (options.appSettings) generalSettingsRepository.startupSessionModeSnapshot() else null,
        firstByteTimeoutSec = if (options.appSettings) generalSettingsRepository.firstByteTimeoutSecSnapshot() else 300,
        streamIdleTimeoutSec = if (options.appSettings) generalSettingsRepository.streamIdleTimeoutSecSnapshot() else 0,
        maxNetworkRetries = if (options.appSettings) generalSettingsRepository.maxNetworkRetriesSnapshot() else 6,
        enterToSend = if (options.appSettings) generalSettingsRepository.enterToSendSnapshot() else false,
        compactionThresholdPercent = if (options.appSettings) generalSettingsRepository.compactionThresholdPercentSnapshot() else 85,
        softCompactionThresholdPercent = if (options.appSettings) generalSettingsRepository.softCompactionThresholdPercentSnapshot() else 40,
        sendFileMaxSizeMb = if (options.appSettings) generalSettingsRepository.sendFileMaxSizeMbSnapshot() else 100,
        deleteExternalWorkspaceSessions = if (options.appSettings) generalSettingsRepository.deleteExternalWorkspaceSessionsSnapshot() else false,
        logLevel = if (options.appSettings) logSettingsRepository.snapshot() else null,
        visionProviderId = if (options.appSettings) visionModelSettingsRepository.getVisionProviderId() else "",
        visionModel = if (options.appSettings) visionModelSettingsRepository.getVisionModel() else "",
        compactionProviderId = if (options.appSettings) compactionModelSettingsRepository.getCompactionProviderId() else "",
        compactionModel = if (options.appSettings) compactionModelSettingsRepository.getCompactionModel() else "",
        syncSettings = if (options.appSettings) syncSettingsRepository.snapshot() else null,
        workspaces = if (options.workspaceFiles) collectWorkspaceMetas() else emptyList(),
        // 没勾「应用设置」时上面那一整段字段全是默认值，与「用户就是这么设的」在数据上不可区分，
        // 所以单独记下这次带没带设置；导入侧据此决定要不要回写设置（见 restoreMeta）。
        appSettingsIncluded = options.appSettings,
        // 启停配置跟技能目录同一个开关：没勾就整段为 null，导入侧据此不回写本机配置
        // （不是「用户没有禁用项」——空名单与没禁过在数据上长得一样）。
        skillsAgentsConfig = if (options.skillsAndAgents) SkillsAgentsConfigDto(
            globalSkills = skillConfigRepository.rawConfig(SkillScope.GLOBAL),
            globalAgents = agentDefinitionConfigRepository.rawConfig(AgentDefinitionScope.GLOBAL),
            projectSkills = skillConfigRepository.rawConfig(SkillScope.PROJECT),
            projectAgents = agentDefinitionConfigRepository.rawConfig(AgentDefinitionScope.PROJECT)
        ) else null
    )

    private fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        val content = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8)
        writeTarEntry(tar, FILE_METADATA, content)
    }

    private fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    /**
     * 先将 jsonl 写入一个临时文件，获取确切的 [File.length] 设置 TarArchiveEntry.size，
     * 然后流式拷入 TarArchiveOutputStream，避免在 Header 中 size 设为 0 导致写入越界异常。
     */
    private suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = createTempFile()
        try {
            FileOutputStream(tmp).use { fos ->
                val writer = JsonlWriter(fos)
                block(writer)
                writer.flush()
            }
            val entry = TarArchiveEntry(entryName).apply { size = tmp.length() }
            tar.putArchiveEntry(entry)
            FileInputStream(tmp).use { fis -> fis.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }

    // ── 工作区文件备份 ──────────────────────────────────────────

    /** 仅备份内部本地工作区；外部本地工作区是用户设备上的目录，不属于 App 私有数据，不纳入备份。 */
    private fun backupLocalWorkspaces(): List<Workspace> =
        workspaceRepository.workspaces.value.filter { it.type != WorkspaceType.EXTERNAL_LOCAL }

    /** 第一遍：统计每个本地工作区将备份的文件数（写 metadata 用）。 */
    private fun collectWorkspaceMetas(): List<WorkspaceBackupMeta> =
        backupLocalWorkspaces().mapNotNull { ws ->
            var count = 0
            walkWorkspaceFiles(ws) { _, _ -> count++ }
            if (count > 0) WorkspaceBackupMeta(ws.name, count) else null
        }

    /** 第二遍：把各本地工作区文件写入 tar（`workspaces/<name>/<相对路径>`）。 */
    private fun writeWorkspaceEntries(tar: TarArchiveOutputStream) {
        backupLocalWorkspaces().forEach { ws ->
            walkWorkspaceFiles(ws) { file, parts ->
                writeTarFileEntry(tar, "workspaces/${ws.name}/${parts.joinToString("/")}", file)
            }
        }
    }

    /**
     * 递归遍历本地工作区，按「.gitignore（锚定）+ 同步忽略清单」排除文件；
     * `.git` 目录强制包含（其内部不参与忽略判断）；符号链接跳过（防循环）。
     */
    private fun walkWorkspaceFiles(
        workspace: Workspace,
        onFile: (File, List<String>) -> Unit
    ) {
        val root = File(workspace.path)
        if (!root.isDirectory) return  // 远程工作区或不存在：本地无文件可备份
        val customIgnores = syncSettingsRepository.ignoredPatterns.value
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val gitignorePatterns = parseGitIgnore(File(root, ".gitignore"))

        fun walk(dir: File, relParts: List<String>) {
            dir.listFiles()?.forEach { f ->
                val parts = relParts + f.name
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                if (parts.first() != ".git" &&
                    (customIgnores.any { it in parts } ||
                        GitIgnoreMatcher.isIgnored(gitignorePatterns, parts, anchored = true))
                ) {
                    return@forEach
                }
                if (f.isDirectory) walk(f, parts) else onFile(f, parts)
            }
        }
        walk(root, emptyList())
    }

    /** 解析工作区根 .gitignore：去空行/注释/结尾斜杠。 */
    private fun parseGitIgnore(file: File): List<String> =
        if (!file.isFile) emptyList()
        else file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().removeSuffix("/") }

    private fun writeTarFileEntry(tar: TarArchiveOutputStream, name: String, file: File) {
        val entry = TarArchiveEntry(name).apply { size = file.length() }
        tar.putArchiveEntry(entry)
        FileInputStream(file).use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }

    // ── 技能 / 子代理 / 面板脚本资产 ────────────────────────

    /**
     * 三类资产的目录清单：tar 条目前缀 → 容器路径根，[AssetKind] 决定导入摘要算到哪一类。
     *
     * 全局层是「当前执行环境」的 `~/.aicode/...`，项目层是当前工作区的 `~/workspace/.aicode/...`：
     * 根路径直接取 [SkillRepository] / [AgentDefinitionRepository] 各自的根，与设置页扫描技能、
     * 子代理时用的是同一处定义，不在这里另抄一份。面板脚本目录与
     * ProviderDashboardRunner.listAvailableScripts 取的是同一个 `~/.aicode/scripts`。
     *
     * 两类都经 [FileAccessProvider] 访问，本地/远程同一套读写入口；导入侧不按导出时的勾选过滤——
     * tar 里有什么条目就恢复什么（旧备份没有 `assets/` 条目，自然什么都不做）。
     */
    private fun assetRoots(): List<AssetRoot> = listOf(
        AssetRoot(ASSET_SKILLS_GLOBAL, skillRepository.skillsRoot(SkillScope.GLOBAL), AssetKind.SKILL),
        AssetRoot(ASSET_SKILLS_PROJECT, skillRepository.skillsRoot(SkillScope.PROJECT), AssetKind.SKILL),
        AssetRoot(ASSET_AGENTS_GLOBAL, agentDefinitionRepository.agentsRoot(AgentDefinitionScope.GLOBAL), AssetKind.SUBAGENT),
        AssetRoot(ASSET_AGENTS_PROJECT, agentDefinitionRepository.agentsRoot(AgentDefinitionScope.PROJECT), AssetKind.SUBAGENT),
        AssetRoot(ASSET_SCRIPTS, "${pathHomeResolver.aicodeRoot()}/scripts", AssetKind.PANEL_SCRIPT)
    )

    /** 把勾选的资产目录下的每个文件写成一个 tar 条目（`assets/类别/层级/相对路径`）。 */
    private fun writeAssetEntries(tar: TarArchiveOutputStream, options: BackupOptions) {
        assetRoots()
            .filter { if (it.kind == AssetKind.PANEL_SCRIPT) options.panelScripts else options.skillsAndAgents }
            .forEach { root ->
                val base = root.root.trimEnd('/')
                // 目录不存在、远程未连接等都在这里兜住：一类资产读不到不该让整次导出失败
                val files = runCatching { fileAccess.listFilesRecursive(base, ASSET_MAX_DEPTH) }.getOrElse { e ->
                    FileLogger.w(TAG, "读取资产目录失败，跳过：$base（${e.message}）")
                    emptyList()
                }
                files.forEach { relative ->
                    val rel = normalizeAssetPath(relative)
                    if (rel == null) {
                        FileLogger.w(TAG, "跳过越界的资产路径：$base/$relative")
                    } else {
                        runCatching { fileAccess.readBytes("$base/$rel") }
                            .onSuccess { writeTarEntry(tar, "${root.prefix}$rel", it) }
                            .onFailure { FileLogger.w(TAG, "读取资产文件失败，跳过：$base/$rel（${it.message}）") }
                    }
                }
            }
    }

    /**
     * 归一化资产相对路径：反斜杠转正斜杠、去前导斜杠与 `./`；含 `..` 的越界条目返回 null。
     * 与技能压缩包导入（SkillImporter）同一套防越界规则：导出侧先过滤，导入侧再过滤一次。
     */
    private fun normalizeAssetPath(raw: String): String? {
        val path = raw.replace('\\', '/').trimStart('/').removePrefix("./")
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val cleaned = segments.filter { it.isNotEmpty() && it != "." }
        return if (cleaned.isEmpty()) null else cleaned.joinToString("/")
    }

    /**
     * 还原单个资产条目：`assets/<类别>/<层级>/<相对路径>` 落到对应的容器路径根下。
     *
     * 覆盖同名文件，不删本机多出来的文件（与工作区文件的恢复语义一致）；项目层落到导入时的当前工作区
     * ——导出时生效的是哪个工作区，带的就是那个工作区的项目级技能/子代理。写入失败（权限、远程断开）
     * 只记日志跳过，不让整次导入挂掉：备份里其它段还要照常还原。
     */
    private fun restoreAssetEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry): RestoreStats {
        // 导出的 tar 只写文件条目；手工打的包里若有目录条目，当占位跳过，不建同名空文件
        if (entry.isDirectory) return RestoreStats()
        val target = resolveAssetTarget(entry.name)
        if (target == null) {
            FileLogger.w(TAG, "跳过无法解析的资产条目：${entry.name}")
            return RestoreStats()
        }
        val bytes = tar.readBytes()
        return runCatching { fileAccess.writeBytes(target.path, bytes, overwrite = true) }
            .fold(
                onSuccess = {
                    FileLogger.i(TAG, "恢复资产文件：${target.path}（${bytes.size} 字节）")
                    when (target.kind) {
                        AssetKind.SKILL -> RestoreStats(skillFiles = 1)
                        AssetKind.SUBAGENT -> RestoreStats(subagentFiles = 1)
                        AssetKind.PANEL_SCRIPT -> RestoreStats(panelScriptFiles = 1)
                    }
                },
                onFailure = { e ->
                    FileLogger.w(TAG, "写入资产文件失败，跳过：${target.path}（${e.message}）")
                    RestoreStats()
                }
            )
    }

    /** 把资产条目名解析成目标容器路径 + 类别；前缀不认识或相对路径越界时返回 null。 */
    private fun resolveAssetTarget(entryName: String): AssetTarget? {
        val root = assetRoots().firstOrNull { entryName.startsWith(it.prefix) } ?: return null
        val rel = normalizeAssetPath(entryName.removePrefix(root.prefix)) ?: return null
        return AssetTarget("${root.root.trimEnd('/')}/$rel", root.kind)
    }

    // ── 记忆与会话工作状态 ───────────────────────────────────

    /**
     * 长期记忆目录：全局 + 当前工作区项目级。项目级用与技能/子代理一致的容器路径
     * （`~/workspace/.aicode/memory`），走 [FileAccessProvider]，本地与远程同一套读写入口。
     */
    private fun memoryRoots(): List<MemoryRoot> = listOf(
        MemoryRoot(MEMORY_GLOBAL_PREFIX, "~/.aicode/memory"),
        MemoryRoot(MEMORY_PROJECT_PREFIX, "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/memory")
    )

    /** 把勾选的记忆目录下的每个文件写成一个 tar 条目（`memories/global|project/<相对路径>`）。 */
    private fun writeMemoryEntries(tar: TarArchiveOutputStream) {
        memoryRoots().forEach { root ->
            val base = root.root.trimEnd('/')
            val files = runCatching { fileAccess.listFilesRecursive(base, MEMORY_MAX_DEPTH) }.getOrElse { e ->
                FileLogger.w(TAG, "读取记忆目录失败，跳过：$base（${e.message}）")
                emptyList()
            }
            files.forEach { relative ->
                val rel = normalizeAssetPath(relative)
                if (rel == null) {
                    FileLogger.w(TAG, "跳过越界的记忆路径：$base/$relative")
                } else {
                    runCatching { fileAccess.readBytes("$base/$rel") }
                        .onSuccess { writeTarEntry(tar, "${root.prefix}$rel", it) }
                        .onFailure { FileLogger.w(TAG, "读取记忆文件失败，跳过：$base/$rel（${it.message}）") }
                }
            }
        }
    }

    private suspend fun writeSessionBriefEntries(tar: TarArchiveOutputStream) {
        writeJsonlFileEntry(tar, FILE_SESSION_BRIEFS) { writer ->
            sessionBriefDao.getAllOnce().forEach { writer.writeLine(json.encodeToString(SessionBriefDto.serializer(), it.toDto())) }
        }
    }

    /** 还原单个记忆条目：`memories/<层级>/<相对路径>` 落到对应记忆目录根下，覆盖同名文件。 */
    private fun restoreMemoryEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry): RestoreStats {
        if (entry.isDirectory) return RestoreStats()
        val target = resolveMemoryTarget(entry.name)
        if (target == null) {
            FileLogger.w(TAG, "跳过无法解析的记忆条目：${entry.name}")
            return RestoreStats()
        }
        val bytes = tar.readBytes()
        return runCatching { fileAccess.writeBytes(target, bytes, overwrite = true) }
            .fold(
                onSuccess = {
                    FileLogger.i(TAG, "恢复记忆文件：$target（${bytes.size} 字节）")
                    RestoreStats(memoryFiles = 1)
                },
                onFailure = { e ->
                    FileLogger.w(TAG, "写入记忆文件失败，跳过：$target（${e.message}）")
                    RestoreStats()
                }
            )
    }

    private fun resolveMemoryTarget(entryName: String): String? {
        val root = memoryRoots().firstOrNull { entryName.startsWith(it.prefix) } ?: return null
        val rel = normalizeAssetPath(entryName.removePrefix(root.prefix)) ?: return null
        return "${root.root.trimEnd('/')}/$rel"
    }

    // ── 导入辅助 ──────────────────────────────────────────────

    private suspend fun restoreFromTar(tar: TarArchiveInputStream, selectedWorkspaces: Set<String>?): RestoreStats {
        val restoreMapping = mutableMapOf<String, Workspace>()
        var metadata: BackupMetadata? = null
        var stats = RestoreStats()
        try {
            var entry = tar.nextEntry
            while (entry != null) {
                when (entry.name) {
                    FILE_LEGACY_SNAPSHOT -> {
                        val plain = tar.readBytes()
                        val snapshot = json.decodeFromString(BackupSnapshot.serializer(), String(plain, Charsets.UTF_8))
                        checkVersion(snapshot.schemaVersion)
                        return restoreLegacy(snapshot)
                    }
                    FILE_METADATA -> {
                        val plain = tar.readBytes()
                        metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                        checkVersion(metadata.schemaVersion)
                    }
                    FILE_SESSIONS -> {
                        val count = restoreJsonl(tar, ChatSessionDto.serializer()) { dtos ->
                            chatSessionDao.upsertAll(
                                dtos.map { dto ->
                                    dto.copy(
                                        workspacePath = resolveSessionWorkspace(dto.workspacePath, restoreMapping)
                                    ).toEntity()
                                }
                            )
                            // 只在写库成功后才计数：失败时 partialStats 要反映「真正落库了多少」
                            stats += RestoreStats(chatSessions = dtos.size)
                        }
                        FileLogger.i(TAG, "恢复会话 $count 条")
                    }
                    FILE_MESSAGES -> {
                        val count = restoreJsonl(tar, AgentMessageDto.serializer()) { dtos ->
                            agentMessageDao.insertAll(dtos.map { it.toEntity() })
                            stats += RestoreStats(agentMessages = dtos.size)
                        }
                        FileLogger.i(TAG, "恢复消息 $count 条")
                    }
                    FILE_TODOS -> {
                        val count = restoreJsonl(tar, TodoItemDto.serializer()) { dtos ->
                            todoItemDao.upsertAll(dtos.map { it.toEntity() })
                            stats += RestoreStats(todoItems = dtos.size)
                        }
                        FileLogger.i(TAG, "恢复待办 $count 条")
                    }
                    FILE_SESSION_BRIEFS -> {
                        val count = restoreJsonl(tar, SessionBriefDto.serializer()) { dtos ->
                            // 主键是 sessionId，逐条 REPLACE：重复导入不会产生重复行，
                            // 逐条计数也让「同一批里的第 N 条失败」能反映真正写进去的数量。
                            dtos.forEach {
                                sessionBriefDao.upsert(it.toEntity())
                                stats += RestoreStats(sessionBriefs = 1)
                            }
                        }
                        FileLogger.i(TAG, "恢复会话工作状态 $count 条")
                    }
                    else -> {
                        when {
                            entry.name.startsWith(WORKSPACE_PREFIX) ->
                                stats += restoreWorkspaceEntry(tar, entry.name, selectedWorkspaces, restoreMapping)
                            entry.name.startsWith(ASSET_PREFIX) -> stats += restoreAssetEntry(tar, entry)
                            entry.name.startsWith(MEMORY_PREFIX) -> stats += restoreMemoryEntry(tar, entry)
                        }
                    }
                }
                entry = tar.nextEntry
            }
            val meta = metadata ?: run {
                FileLogger.e(TAG, "导入失败：tar 中缺少 metadata.json")
                error("不是有效的 AiCode 备份文件：缺少 metadata.json")
            }
            FileLogger.i(TAG, "tar 解析完成，开始还原元数据段")
            return stats + restoreMeta(meta)
        } catch (e: PartialRestoreException) {
            // 子段（旧格式 / 元数据段）已有自己的计数：合并，别把外层已写的条目丢掉
            throw PartialRestoreException(stats + e.partialStats, e.cause ?: e)
        } catch (e: Throwable) {
            throw PartialRestoreException(stats, e)
        }
    }

    /** 逐行解析 jsonl 条目，每 [PAGE_SIZE] 条回调一次批量插入；返回该文件的总条数。 */
    private suspend fun <T> restoreJsonl(tar: TarArchiveInputStream, serializer: KSerializer<T>, insert: suspend (List<T>) -> Unit): Int {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        val batch = ArrayList<T>(PAGE_SIZE)
        var count = 0
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            var start = 0
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    line.write(buffer, start, i - start)
                    start = i + 1
                    if (line.size() > 0) {
                        // 注意：ByteArrayOutputStream.toString(Charset) 是 API 33 才有的方法，
                        // 在 Android 13 以下会抛 NoSuchMethodError，必须用 String(byte[], Charset) 构造器。
                        batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
                        line.reset()
                        if (batch.size >= PAGE_SIZE) {
                            count += batch.size
                            insert(batch.toList())
                            batch.clear()
                        }
                    } else {
                        line.reset()
                    }
                }
            }
            line.write(buffer, start, n - start)
        }
        if (line.size() > 0) {
            batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
        }
        if (batch.isNotEmpty()) {
            count += batch.size
            insert(batch.toList())
        }
        return count
    }

    private fun checkVersion(schemaVersion: Int) {
        if (schemaVersion > currentSchemaVersion()) {
            error("备份的数据库版本 v$schemaVersion 高于本应用 v${currentSchemaVersion()}，请升级应用")
        }
    }

    /** 旧格式（单文件 snapshot.json 完整快照）还原。 */
    private suspend fun restoreLegacy(snapshot: BackupSnapshot): RestoreStats {
        var stats = RestoreStats()
        try {
            stats += restoreMeta(snapshot.toMetadata())
            if (snapshot.chatSessions.isNotEmpty()) {
                // 按备份中的工作区路径逐个解析到目标工作区（不存在则自动创建空工作区），不再全部塞进当前工作区。
                val restoreMapping = mutableMapOf<String, Workspace>()
                chatSessionDao.upsertAll(
                    snapshot.chatSessions.map {
                        it.copy(workspacePath = resolveSessionWorkspace(it.workspacePath, restoreMapping)).toEntity()
                    }
                )
                stats += RestoreStats(chatSessions = snapshot.chatSessions.size)
            }
            if (snapshot.agentMessages.isNotEmpty()) {
                agentMessageDao.insertAll(snapshot.agentMessages.map { it.toEntity() })
                stats += RestoreStats(agentMessages = snapshot.agentMessages.size)
            }
            if (snapshot.todoItems.isNotEmpty()) {
                todoItemDao.upsertAll(snapshot.todoItems.map { it.toEntity() })
                stats += RestoreStats(todoItems = snapshot.todoItems.size)
            }
            return stats
        } catch (e: PartialRestoreException) {
            throw e
        } catch (e: Throwable) {
            throw PartialRestoreException(stats, e)
        }
    }

    /**
     * 元数据段还原（小表 + 应用设置），新旧格式共用。
     *
     * 设置段只在备份确实带了它时才回写（[BackupMetadata.appSettingsIncluded]）：
     * 设置字段的「没导出」与「值为默认」在数据上不可区分，无条件回写会把没带设置的备份
     * （单会话导出、或导出时没勾应用设置）变成一次「静默重置」——保活/屏幕常亮/提示音被关、
     * 超时与压缩阈值回到默认，而导入摘要还写着「已覆盖」。小表不靠这个标志：
     * 它们以「列表非空」为写入条件，空列表本就不写。
     */
    private suspend fun restoreMeta(meta: BackupMetadata): RestoreStats {
        FileLogger.i(
            TAG,
            "还原元数据：providers=${meta.providers.size} remoteConnections=${meta.remoteConnections.size} remoteMounts=${meta.remoteMounts.size} " +
                "mcpServers=${meta.mcpServers.size} permissionRules=${meta.globalPermissionRules.size} syncSettings=${meta.syncSettings != null} " +
                "appSettings=${meta.appSettingsIncluded} skillsAgentsConfig=${meta.skillsAgentsConfig != null}"
        )
        // 逐段计数：中途失败时 partialStats 要能说清哪些段已写入、各多少条。
        var stats = RestoreStats()
        try {
            if (meta.providers.isNotEmpty()) {
                aiProviderDao.insertAllProviders(meta.providers.map { it.toEntity() })
                stats += RestoreStats(providers = meta.providers.size)
            }
            if (meta.remoteConnections.isNotEmpty()) {
                remoteConnectionDao.insertAllConnections(meta.remoteConnections.mapNotNull { it.toEntity() })
                stats += RestoreStats(remoteConnections = meta.remoteConnections.size)
            }
            if (meta.remoteMounts.isNotEmpty()) {
                // 指向已移除协议（旧版本的本地通道）的挂载一并跳过，避免外键约束失败。
                val connectionIds = remoteConnectionDao.getAllConnectionsOnce().map { it.id }.toSet()
                remoteConnectionDao.insertAllMounts(
                    meta.remoteMounts.map { it.toEntity() }.filter { it.connectionId in connectionIds }
                )
                stats += RestoreStats(remoteMounts = meta.remoteMounts.size)
            }
            if (meta.mcpServers.isNotEmpty()) {
                mcpConfigRepository.setGlobalServers(meta.mcpServers)
                mcpManager.reload()
                stats += RestoreStats(mcpServers = meta.mcpServers.size)
            }
            if (meta.globalPermissionRules.isNotEmpty()) {
                permissionRulesRepository.setGlobalRules(meta.globalPermissionRules)
                stats += RestoreStats(globalPermissionRules = meta.globalPermissionRules.size)
            }
            // 启停配置：备份没带（字段为 null）时一个字都不写，与本字段出现之前的导入行为逐字一致；
            // 带了就整份覆盖两级文件（备份里的名单为准，本机多出来的禁用项不删）。
            val skillsAgentsConfigRestored = meta.skillsAgentsConfig?.let { restoreSkillsAgentsConfig(it) } ?: false
            if (skillsAgentsConfigRestored) stats += RestoreStats(skillsAgentsConfigRestored = true)
            if (meta.appSettingsIncluded) {
                meta.themeMode?.let { themeSettingsRepository.restore(it) }
                themeSettingsRepository.restoreColors(meta.themePresetId, meta.dynamicColorEnabled)
                keepaliveSettingsRepository.restore(meta.keepaliveEnabled)
                liveNotificationSettingsRepository.restore(meta.liveNotificationEnabled)
                approvalNotificationSettingsRepository.restore(meta.approvalNotificationEnabled)
                screenOnSettingsRepository.restore(meta.screenOnEnabled)
                agentSoundSettingsRepository.restore(meta.agentSoundEnabled)
                generalSettingsRepository.restoreAutoRemoveStaleModels(meta.autoRemoveStaleModels)
                generalSettingsRepository.restoreStartupSessionMode(meta.startupSessionMode)
                generalSettingsRepository.restoreFirstByteTimeoutSec(meta.firstByteTimeoutSec)
                generalSettingsRepository.restoreStreamIdleTimeoutSec(meta.streamIdleTimeoutSec)
                generalSettingsRepository.restoreMaxNetworkRetries(meta.maxNetworkRetries)
                generalSettingsRepository.restoreEnterToSend(meta.enterToSend)
                generalSettingsRepository.restoreCompactionThresholdPercent(meta.compactionThresholdPercent)
                generalSettingsRepository.restoreSoftCompactionThresholdPercent(meta.softCompactionThresholdPercent)
                generalSettingsRepository.restoreSendFileMaxSizeMb(meta.sendFileMaxSizeMb)
                generalSettingsRepository.restoreDeleteExternalWorkspaceSessions(meta.deleteExternalWorkspaceSessions)
                logSettingsRepository.restore(meta.logLevel)
                if (meta.visionProviderId.isNotBlank() || meta.visionModel.isNotBlank()) {
                    visionModelSettingsRepository.setVisionModel(meta.visionProviderId, meta.visionModel)
                }
                if (meta.compactionProviderId.isNotBlank() || meta.compactionModel.isNotBlank()) {
                    compactionModelSettingsRepository.setCompactionModel(meta.compactionProviderId, meta.compactionModel)
                }
                meta.syncSettings?.let { syncSettingsRepository.restore(it) }
                // 设置是一整段回写的，全部写完才算“已覆盖”；中途失败不计，避免摘要说已恢复实际没有。
                stats += RestoreStats(settingsRestored = true)
            }
            return stats
        } catch (e: PartialRestoreException) {
            throw e
        } catch (e: Throwable) {
            throw PartialRestoreException(stats, e)
        }
    }

    /**
     * 还原技能 / 子代理的启停配置（两级各一份，整份覆盖）。
     *
     * @return 是否至少写成功了一份：项目级因工作区未落定被跳过时不算写成功，
     *   否则摘要会把没发生的事算到用户头上（与 [BackupMetadata.appSettingsIncluded] 同一考量）。
     */
    private fun restoreSkillsAgentsConfig(config: SkillsAgentsConfigDto): Boolean {
        var restored = false
        config.globalSkills?.let { if (skillConfigRepository.restoreConfig(it, SkillScope.GLOBAL)) restored = true }
        config.globalAgents?.let { if (agentDefinitionConfigRepository.restoreConfig(it, AgentDefinitionScope.GLOBAL)) restored = true }
        config.projectSkills?.let { if (skillConfigRepository.restoreConfig(it, SkillScope.PROJECT)) restored = true }
        config.projectAgents?.let { if (agentDefinitionConfigRepository.restoreConfig(it, AgentDefinitionScope.PROJECT)) restored = true }
        return restored
    }

    /**
     * 还原单个工作区文件条目：`workspaces/<name>/<相对路径>`。
     * 仅处理勾选的工作区（[selectedWorkspaces] 非 null 时），且只允许写入内部工作区；本地无同名工作区时自动创建，
     * 避免新设备/重装后导入的工作区文件因找不到目标而丢失。
     */
    private suspend fun restoreWorkspaceEntry(
        tar: TarArchiveInputStream,
        entryName: String,
        selectedWorkspaces: Set<String>?,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats {
        val segments = entryName.split("/", limit = 3)
        if (segments.size != 3) return RestoreStats()
        val wsName = segments[1]
        if (selectedWorkspaces != null && wsName !in selectedWorkspaces) return RestoreStats()

        val ws = resolveWorkspaceForRestore(wsName, restoreMapping) ?: return RestoreStats()

        val wsDir = File(ws.path).canonicalFile
        val target = resolveWorkspaceRestoreTarget(wsDir, segments[2]) ?: return RestoreStats()
        val relative = File(segments[2]).toPath().normalize().toString()
        val actualRelative = target.relativeTo(wsDir).invariantSeparatorsPath
        // Git 配置和 hooks 能定义容器内的执行入口；条目路径与实际目标都必须检查，
        // 否则符号链接既能把普通文件映射到敏感位置，也能把敏感位置映射到普通目录。
        val securityEntry = listOf(relative, actualRelative).any { path ->
            val parts = path.split('/')
            parts.indices.any { index ->
                parts[index] == ".git" && index + 1 < parts.size &&
                    (parts[index + 1] == "hooks" ||
                        (parts[index + 1] == "config" && index + 2 == parts.size))
            }
        }
        if (securityEntry) {
            FileLogger.w(TAG, "跳过备份里的安全相关条目：${wsName}/$relative")
            return RestoreStats(skippedSecurityEntries = 1)
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        FileLogger.i(TAG, "恢复工作区文件：${ws.name}/${segments[2]}")
        return RestoreStats(workspaceFiles = 1)
    }

    /**
     * 按备份中的工作区名解析到目标工作区：优先复用本轮导入已解析的映射，其次复用本地同名内部工作区，
     * 都不存在则自动创建一个空内部工作区。工作区文件条目与会话共用同一映射，保证两者落到同一个工作区。
     */
    private suspend fun resolveWorkspaceForRestore(
        wsName: String,
        restoreMapping: MutableMap<String, Workspace>
    ): Workspace? {
        restoreMapping[wsName]?.let { return it }
        val reservedNames = restoreMapping.values.map { it.name }.toSet()
        val existing = workspaceRepository.workspaces.value.firstOrNull {
            it.name == wsName && it.type == WorkspaceType.INTERNAL && it.name !in reservedNames
        }
        val resolved = existing ?: run {
            val occupied = workspaceRepository.workspaces.value.map { it.name }.toSet() + reservedNames
            val targetName = WorkspaceRepository.uniqueName(wsName, occupied)
            workspaceRepository.createWorkspace(targetName) ?: return null
        }
        restoreMapping[wsName] = resolved
        FileLogger.i(TAG, "导入会话目标工作区：${resolved.name} -> ${resolved.path}")
        return resolved
    }

    /**
     * 解析某会话在备份中的原始工作区路径到目标设备的工作区：
     * 1. 现有工作区路径与备份一致（同设备重装场景，内部/外部本地都能对上）直接复用；
     * 2. 否则按路径末段当作工作区名，经 [resolveWorkspaceForRestore] 复用同名内部工作区或自动创建空工作区；
     * 3. 路径为空或解析失败（如无法创建）时兜底回当前工作区（工作区未落定时退回第一个可用工作区）。
     */
    private suspend fun resolveSessionWorkspace(
        backupWorkspacePath: String,
        restoreMapping: MutableMap<String, Workspace>
    ): String {
        if (backupWorkspacePath.isBlank()) return fallbackWorkspacePath()
        workspaceRepository.workspaces.value
            .firstOrNull { it.path == backupWorkspacePath && it.name !in restoreMapping.keys }
            ?.let { return it.path }
        val name = backupWorkspacePath.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return fallbackWorkspacePath()
        return resolveWorkspaceForRestore(name, restoreMapping)?.path ?: fallbackWorkspacePath()
    }

    /**
     * 恢复时的兜底目标工作区：优先当前工作区，未落定时退回第一个可用工作区，都没有才返回空串。
     * 不返回工作区父目录：那不是任何会话所属的工作区，会让会话的项目根指向公共父级。
     */
    private fun fallbackWorkspacePath(): String =
        workspaceRepository.currentPathOrNull()
            ?: workspaceRepository.workspaces.value.firstOrNull { it.available }?.path
            ?: ""

    /** 防路径穿越：检查实际目标仍在工作区内，并让写入复用已检查的目标。 */
    private fun resolveWorkspaceRestoreTarget(wsDir: File, relPath: String): File? {
        if (!wsDir.isDirectory) return null
        val root = wsDir.canonicalPath
        val target = File(wsDir, relPath).canonicalFile
        return target.takeIf { it.path == root || it.path.startsWith(root + File.separator) }
    }

    private fun createTempFile(): File = File.createTempFile("backup", ".tmp", context.cacheDir)

    // ── Entity ↔ DTO 转换 ──────────────────────────────────────

    private fun AIProviderEntity.toDto() = ProviderDto(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.decryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints,
        openaiChatCacheKey = openaiChatCacheKey,
        dashboardScriptPath = dashboardScriptPath,
        dashboardRefreshInterval = dashboardRefreshInterval,
        sortOrder = sortOrder,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = KeystoreCipher.decryptString(apiKeys),
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes,
        scriptParams = KeystoreCipher.decryptString(scriptParams),
        keySwitchStatusCodes = keySwitchStatusCodes
    )

    private fun ProviderDto.toEntity() = AIProviderEntity(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.encryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints ?: true,
        openaiChatCacheKey = openaiChatCacheKey ?: false,
        dashboardScriptPath = dashboardScriptPath ?: "",
        dashboardRefreshInterval = dashboardRefreshInterval ?: 5,
        sortOrder = sortOrder ?: 0,
        multiKeyEnabled = multiKeyEnabled ?: false,
        apiKeys = KeystoreCipher.encryptString(apiKeys ?: ""),
        keyRotationStrategy = keyRotationStrategy ?: "SEQUENTIAL",
        keyFailoverThreshold = keyFailoverThreshold ?: 2,
        keyCooldownMinutes = keyCooldownMinutes ?: 5,
        scriptParams = KeystoreCipher.encryptString(scriptParams ?: ""),
        keySwitchStatusCodes = keySwitchStatusCodes ?: ""
    )

    private fun RemoteConnectionEntity.toDto() = RemoteConnectionDto(
        id, name, protocol.name, host, port, username, authType,
        KeystoreCipher.decryptString(authData), passphrase?.let { KeystoreCipher.decryptString(it) },
        sortOrder
    )

    /** 旧备份可能含已移除的协议（本地通道），此类记录跳过不恢复。 */
    private fun RemoteConnectionDto.toEntity(): RemoteConnectionEntity? {
        val parsed = runCatching { RemoteProtocol.valueOf(protocol) }.getOrNull() ?: return null
        return RemoteConnectionEntity(
            id, name, parsed, host, port, username, authType,
            if (authType.equals("PASSWORD", ignoreCase = true)) KeystoreCipher.encryptString(authData) else authData,
            passphrase?.let { KeystoreCipher.encryptString(it) },
            sortOrder = sortOrder ?: 0
        )
    }

    private fun RemoteMountEntity.toDto() = RemoteMountDto(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    private fun RemoteMountDto.toEntity() = RemoteMountEntity(id, connectionId, remotePath, localMountPath, isActive, autoConnect)

    private fun ChatSessionEntity.toDto() = ChatSessionDto(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType
    )

    private fun ChatSessionDto.toEntity() = ChatSessionEntity(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType
    )

    private fun AgentMessageEntity.toDto() = AgentMessageDto(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker,
        thinkingBlocksJson, modelReminder, isContextExcluded, compactedBySummaryId, isInterjection,
        foldsUpToTimestamp
    )

    private fun AgentMessageDto.toEntity() = AgentMessageEntity(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker,
        thinkingBlocksJson = thinkingBlocksJson, modelReminder = modelReminder,
        isContextExcluded = isContextExcluded, compactedBySummaryId = compactedBySummaryId,
        isInterjection = isInterjection, foldsUpToTimestamp = foldsUpToTimestamp
    )

    private fun TodoItemEntity.toDto() = TodoItemDto(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt, parentId)
    private fun TodoItemDto.toEntity() = TodoItemEntity(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt, parentId)

    private fun SessionBriefEntity.toDto() = SessionBriefDto(sessionId, content, updatedAt)
    private fun SessionBriefDto.toEntity() = SessionBriefEntity(sessionId, content, updatedAt)

    private companion object {
        const val TAG = "BackupManager"
        const val PAGE_SIZE = 500
        const val FILE_METADATA = "metadata.json"
        const val FILE_SESSIONS = "chatSessions.jsonl"
        const val FILE_MESSAGES = "messages.jsonl"
        const val FILE_TODOS = "todoItems.jsonl"
        const val FILE_SESSION_BRIEFS = "sessionBriefs.jsonl"
        const val FILE_LEGACY_SNAPSHOT = "snapshot.json"
        const val WORKSPACE_PREFIX = "workspaces/"

        /** 记忆目录条目前缀，下面按「全局/项目」细分。 */
        const val MEMORY_PREFIX = "memories/"
        const val MEMORY_GLOBAL_PREFIX = "${MEMORY_PREFIX}global/"
        const val MEMORY_PROJECT_PREFIX = "${MEMORY_PREFIX}project/"

        /** 记忆目录遍历深度：根文件算 1 层，够盖住 `.superseded/` 这类子目录。 */
        const val MEMORY_MAX_DEPTH = 4

        /** 技能 / 子代理 / 面板脚本的资产条目前缀，下面按「类别/层级」细分。 */
        const val ASSET_PREFIX = "assets/"
        const val ASSET_SKILLS_GLOBAL = "${ASSET_PREFIX}skills/global/"
        const val ASSET_SKILLS_PROJECT = "${ASSET_PREFIX}skills/project/"
        const val ASSET_AGENTS_GLOBAL = "${ASSET_PREFIX}agents/global/"
        const val ASSET_AGENTS_PROJECT = "${ASSET_PREFIX}agents/project/"
        const val ASSET_SCRIPTS = "${ASSET_PREFIX}scripts/"

        /** 资产目录遍历深度上限：够盖住技能目录里的多层子目录，同时挡住异常的深目录。 */
        const val ASSET_MAX_DEPTH = 8
    }
}

/** 带内部缓冲的 jsonl 写入器：行缓冲满 64KB 时刷入 tar 流，避免逐行写系统调用。 */
private class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}

/** 打开的 tar 流封装：加密导入时附带解密用的临时文件，关闭时一并清理。 */
private class TarSource(
    val tar: TarArchiveInputStream,
    private val temp: File?
) : AutoCloseable {
    override fun close() {
        runCatching { tar.close() }
        temp?.delete()
    }
}

/** 一类可备份资产：tar 条目前缀（带结尾斜杠）、容器路径根、导入摘要计数类别。 */
private class AssetRoot(val prefix: String, val root: String, val kind: AssetKind)

/** 一个记忆目录：tar 条目前缀（带结尾斜杠）与容器路径根。 */
private class MemoryRoot(val prefix: String, val root: String)

/** 资产类别，决定导入摘要把它算进哪一行。 */
private enum class AssetKind { SKILL, SUBAGENT, PANEL_SCRIPT }

/** 资产条目的落点：目标容器路径 + 类别。 */
private class AssetTarget(val path: String, val kind: AssetKind)
