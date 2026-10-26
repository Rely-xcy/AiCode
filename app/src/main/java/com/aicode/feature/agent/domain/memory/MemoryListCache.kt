package com.aicode.feature.agent.domain.memory

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 记忆清单（扫目录得到的那一份）的缓存：目录本身没动过就不重扫。
 *
 * [MemoryFileCache] 只省掉「读文件 + 解析 frontmatter」，扫目录本身仍要逐文件 stat：
 * 命中缓存的一次列清单也要 2~3 次 stat/文件，而这份成本随记忆条数线性增长，
 * 且每次进记忆页、每次列清单都要付一遍。这里的签名只看**目录自身**的 mtime 与 `.md` 条目数
 * （[File.list] 只取名字，不逐条 stat），命中时一次 stat + 一次 getdents 就够。
 *
 * 新鲜度：
 * - 增删 / 改名 / 覆盖写（本仓的写入都走 rename）都会改目录 mtime，签名随之失效；
 * - 进程内的写入（保存 / 编辑 / 删除）另外显式 [invalidate]，兜住「rename 失败退回原地写」那条路；
 * - 原地改写（同一个 inode 上截断重写、目录条目没变）看不出来：记忆页沿用上一次扫出来的内容，
 *   直到目录再有增删、或进程重启。这是省掉 N 次 stat 换来的取舍。
 */
internal object MemoryListCache {

    /** 判新鲜度只看这两个量：条目数兜住「mtime 分辨率不足（同一秒两次增删）」。 */
    private data class Signature(val lastModified: Long, val mdCount: Int)

    private class Entry(val signature: Signature, val memories: List<Memory>)

    /** 只防「换过很多工作区后缓存无限长」，不是性能参数。 */
    private const val MAX_ENTRIES = 64

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 取 [root] 下的记忆清单：签名没变直接返回上一次的结果，否则调用 [load] 重扫。
     *
     * 扫描期间目录被改写就不入缓存——把旧签名贴到新结果上会一直命中下去。
     */
    fun list(root: File, load: () -> List<Memory>): List<Memory> {
        val key = root.absolutePath
        val before = signatureOf(root)
        entries[key]?.let { cached ->
            if (cached.signature == before) return cached.memories
        }

        val memories = load()
        if (signatureOf(root) == before) {
            if (entries.size >= MAX_ENTRIES) entries.clear()
            entries[key] = Entry(before, memories)
        }
        return memories
    }

    /** 写入 / 删除后显式摘掉：目录条目没变的那条写入路径（原地写）靠它兜住。 */
    fun invalidate(root: File) {
        entries.remove(root.absolutePath)
    }

    /** 目录不存在时 [File.list] 返回 null，用 -1 与「空目录」区分开。 */
    private fun signatureOf(root: File): Signature =
        Signature(root.lastModified(), root.list()?.count { it.endsWith(".md") } ?: -1)
}
