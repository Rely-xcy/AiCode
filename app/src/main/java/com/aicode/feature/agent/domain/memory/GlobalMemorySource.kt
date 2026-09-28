package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalMemorySource @Inject constructor(
    private val containerInstaller: ContainerInstaller
) : MemorySource {

    private val memoryRoot: File by lazy {
        File(containerInstaller.aicodeDir, "memory").also { it.mkdirs() }
    }

    override fun listMemories(): List<Memory> {
        if (!memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.GLOBAL) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
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
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            MemorySource.archiveBeforeOverwrite(memoryRoot, file)
            // 覆盖时保留原创建时间，避免每次更新都把「这条记了多久」重置掉
            val previous = MemoryParser.parse(file, MemoryScope.GLOBAL)?.createdAt ?: 0L
            val created = if (createdAt > 0) createdAt else previous.takeIf { it > 0 } ?: System.currentTimeMillis()
            file.writeText(
                MemoryParser.format(MemorySource.sanitizeName(name), description, content, kind, source, created)
            )
            true
        } catch (e: Exception) {
            FileLogger.e("GlobalMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }
}
