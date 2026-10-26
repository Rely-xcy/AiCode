package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一条「写盘失败、等待重试」的记忆。
 *
 * 字段与 [MemoryRepository.saveMemory] 的参数一一对应，重试时原样重放；
 * [attempts] 是已经失败的次数，达到上限后放弃；[createdAt] 是入队时间，补写成功时沿用为创建时间。
 */
@Serializable
data class PendingMemoryEntry(
    val name: String,
    val description: String = "",
    val content: String,
    val scope: String = MemoryScope.GLOBAL.name,
    val projectRoot: String? = null,
    val kind: String = MemoryKind.PROFILE.name,
    val source: String = "",
    val attempts: Int = 0,
    val createdAt: Long = 0L
)

/**
 * 待重试写入队列：JSON Lines，一行一条。
 *
 * 用文件而不是数据库：写入失败可能就是存储层出问题，重试队列不该再依赖同一套设施，
 * 且需求要求不新增表与迁移。文件放在全局记忆根目录下，点开头且非 `.md`，
 * 不会被 [MemorySource.listMemories] 当成一条记忆。
 *
 * 正文里的换行由 JSON 转义成 `\n`，所以「一行一条」不会被多行正文破坏。
 */
@Singleton
class MemoryPendingQueue @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private val lock = Any()

    private val file: File by lazy {
        File(File(containerInstaller.aicodeDir, MEMORY_DIR), PENDING_FILE)
    }

    fun load(): List<PendingMemoryEntry> = synchronized(lock) { readLocked() }

    /**
     * 追加一条待重试记录。
     *
     * 同一条（name + 正文都相同）已经在队列里就不再追加，且**不改动已有记录的 attempts**：
     * 抽取器在同一段历史上反复失败时会多次入队，清零 attempts 会让坏记录永远到不了放弃上限。
     */
    fun enqueue(entry: PendingMemoryEntry) {
        synchronized(lock) {
            val current = readLocked()
            if (current.any { it.name == entry.name && it.content == entry.content }) return
            writeLocked(current + entry)
        }
    }

    /**
     * 原子地「读队列 → 交给 [block] 处理 → 写回新队列」。
     *
     * 重试耗时较长（要逐个写盘），这期间抽取器仍可能往队列里追加新记录；
     * 分成 load + 单独写回的话，最后那次写回会把新追加的记录整段覆盖掉。
     */
    fun <R> update(block: (List<PendingMemoryEntry>) -> Pair<List<PendingMemoryEntry>, R>): R =
        synchronized(lock) {
            val (next, result) = block(readLocked())
            writeLocked(next)
            result
        }

    private fun readLocked(): List<PendingMemoryEntry> {
        val target = file
        if (!target.isFile) return emptyList()
        return try {
            target.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                runCatching { JSON.decodeFromString(PendingMemoryEntry.serializer(), trimmed) }
                    .onFailure { FileLogger.w(TAG, "跳过损坏的待重试记录", it) }
                    .getOrNull()
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取待重试记忆队列失败", e)
            emptyList()
        }
    }

    private fun writeLocked(entries: List<PendingMemoryEntry>) {
        val target = file
        try {
            if (entries.isEmpty()) {
                if (target.exists()) target.delete()
                return
            }
            target.parentFile?.mkdirs()
            val text = entries.joinToString(separator = "\n", postfix = "\n") {
                JSON.encodeToString(PendingMemoryEntry.serializer(), it)
            }
            MemorySource.writeAtomically(target, text)
        } catch (e: Exception) {
            FileLogger.e(TAG, "写入待重试记忆队列失败", e)
        }
    }

    private companion object {
        const val TAG = "MemoryPendingQueue"

        /** 与 [GlobalMemorySource] 的记忆根目录保持一致。 */
        const val MEMORY_DIR = "memory"
        const val PENDING_FILE = ".pending.jsonl"

        /** encodeDefaults：attempts / createdAt 即使为 0 也写出来，队列文件一眼能看懂。 */
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
