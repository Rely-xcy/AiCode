package com.aicode.feature.agent.domain.memory

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 记忆文件的解析结果缓存：文件没变就不重读、不重解析。
 *
 * 记忆是扁平目录下的 `*.md`，每次列清单都要逐文件 `readText` + 解析 frontmatter（SnakeYAML）；
 * 而同一批文件在「进一次记忆页」「每一轮注入」「一次 memory 工具调用」里会被反复列好几遍。
 *
 * 新鲜度按 [File.lastModified] + [File.length] 判定：外部改 / 删（文件管理器、同步工具）
 * 都会被下一次扫描看出来。本进程内的写入由 [invalidate] 显式摘除——同一毫秒内改写、
 * 长度又恰好相同时，mtime 的分辨率看不出来。
 */
internal object MemoryFileCache {

    /** 判新鲜度只看这两个量；长度兜住「mtime 分辨率不足 + 内容等长」的极端情况。 */
    private data class Stamp(val lastModified: Long, val length: Long)

    private class Entry(val stamp: Stamp, val scope: MemoryScope, val memory: Memory)

    /** 只防「换过很多工作区后缓存无限长」，不是性能参数。 */
    private const val MAX_ENTRIES = 2_048

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 与 [MemoryParser.parse] 同语义，命中缓存时省掉整次文件读与 YAML 解析。 */
    fun parse(file: File, scope: MemoryScope): Memory? {
        val key = file.absolutePath
        val before = stampOf(file)
        entries[key]?.let { cached ->
            if (cached.scope == scope && cached.stamp == before) return cached.memory
        }

        val memory = MemoryParser.parse(file, scope)
        if (memory == null) {
            // 读失败 / 文件已删：连旧条目一起丢掉，别让读不到的路径在缓存里装成活着的
            entries.remove(key)
            return null
        }
        // 解析期间被改写就不入缓存：把旧时间戳贴到新内容上会一直命中下去
        if (stampOf(file) == before) {
            if (entries.size >= MAX_ENTRIES) prune()
            entries[key] = Entry(before, scope, memory)
        }
        return memory
    }

    fun invalidate(file: File) {
        entries.remove(file.absolutePath)
    }

    private fun stampOf(file: File): Stamp = Stamp(file.lastModified(), file.length())

    /** 满额时先清掉已不存在的文件（删掉的记忆不该继续占位），仍然满就整体丢弃——重建只是多读一次盘。 */
    private fun prune() {
        entries.keys.removeIf { path -> !File(path).isFile }
        if (entries.size >= MAX_ENTRIES) entries.clear()
    }
}
