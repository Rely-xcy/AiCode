package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File

/**
 * 项目级记忆数据源。由 Repository 动态创建（依赖当前会话的 projectRoot）。
 *
 * 本地模式：存 `<projectRoot>/.aicode/memory/`（跟随工作区目录）；
 * 远程模式：projectRoot 是远程服务器路径，java.io.File 无法在本地创建该目录（保存必失败），
 * 故改存全局配置目录下 `memory/projects/<项目名>-<标识哈希>/`（本地可写、跨连接稳定）。
 * 标识哈希基于「IP:端口:路径」计算，不同服务器上的同名路径（如都叫 /home/u/workspace/default）
 * 也会落到不同目录，不会混淆。
 */
class ProjectMemorySource(
    private val projectRoot: String,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) : MemorySource {

    private val memoryRoot: File by lazy {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(containerInstaller.aicodeDir, "memory/projects"), projectAicodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".aicode/memory")
        }
    }

    override fun listMemories(): List<Memory> {
        if (projectRoot.isBlank() || !memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.PROJECT) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        if (projectRoot.isBlank()) return null
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(
        name: String,
        description: String,
        content: String,
        kind: MemoryKind,
        source: String,
        createdAt: Long
    ): Boolean {
        if (projectRoot.isBlank()) return false
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = existingMemoryFile(name) ?: MemorySource.resolveMemoryFile(memoryRoot, name)
            MemorySource.withFileLock(file) {
                MemorySource.archiveBeforeOverwrite(memoryRoot, file)
                // 覆盖时保留原创建时间与命中统计：它们描述的是「这条记忆本身」，与本次正文无关
                val previous = MemoryParser.parse(file, MemoryScope.PROJECT)
                val created = if (createdAt > 0) createdAt else previous?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
                MemorySource.writeAtomically(
                    file,
                    MemoryParser.format(
                        name = MemorySource.sanitizeName(name),
                        description = description,
                        content = content,
                        kind = kind,
                        source = source,
                        createdAt = created,
                        hitCount = previous?.hitCount ?: 0,
                        lastHitAt = previous?.lastHitAt ?: 0L
                    )
                )
            }
            true
        } catch (e: Exception) {
            FileLogger.e("ProjectMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        if (projectRoot.isBlank()) return false
        // 按真实文件路径删：名字里可能有 sanitize 会改写的字符（点、空格、非 ASCII），
        // 重拼文件名会找不到文件 → 删除静默失败（列表刷新后条目还在）
        val file = existingMemoryFile(name) ?: MemorySource.resolveMemoryFile(memoryRoot, name)
        if (!file.exists()) return false
        return MemorySource.withFileLock(file) {
            // 删除同样留底：用户在记忆页删、模型调 memory(action=delete) 删，都是不可逆动作，
            // 覆盖/编辑/治理都归档，删除没理由例外（旧版本进 .superseded/，随目录限量清理）
            MemorySource.archiveBeforeOverwrite(memoryRoot, file)
            file.delete()
        }
    }

    /** 按解析出的名字找已有文件；找不到返回 null（调用方回退到 sanitize 拼路径）。 */
    private fun existingMemoryFile(name: String): File? =
        listMemories().firstOrNull { it.name == name }?.file
}
