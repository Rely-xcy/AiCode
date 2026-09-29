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
            val file = existingMemoryFile(name) ?: MemorySource.resolveMemoryFile(memoryRoot, name)
            MemorySource.archiveBeforeOverwrite(memoryRoot, file)
            // 覆盖时保留原创建时间与命中统计：它们描述的是「这条记忆本身」，与本次正文无关
            val previous = MemoryParser.parse(file, MemoryScope.GLOBAL)
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
            true
        } catch (e: Exception) {
            FileLogger.e("GlobalMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        // 按真实文件路径删：名字里可能有 sanitize 会改写的字符（点、空格、非 ASCII），
        // 重拼文件名会找不到文件 → 删除静默失败（列表刷新后条目还在）
        val file = existingMemoryFile(name) ?: MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }

    /** 按解析出的名字找已有文件；找不到返回 null（调用方回退到 sanitize 拼路径）。 */
    private fun existingMemoryFile(name: String): File? =
        listMemories().firstOrNull { it.name == name }?.file
}
