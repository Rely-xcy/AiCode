package com.aicode.feature.agent.domain.mention

import com.aicode.feature.agent.domain.container.CommandEngine
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @ 文件引用的文件清单来源：调用容器内 ripgrep 列出工作区文件，并提供内存模糊匹配。
 *
 * 清单不跨「@ 会话」缓存——每次打开菜单由调用方拉取最新列表，关闭即丢弃；
 * 一次会话内的按键只在内存里对已拉取的列表做匹配，不再触发命令。
 *
 * 会话内对同一份清单的匹配走 [Index]：路径的小写副本只算一次，按键时不再逐条 `lowercase`；
 * 查询变长时只在上一次的命中集里继续筛。
 */
class FileMentionRepository @Inject constructor(
    private val commandEngine: CommandEngine
) {

    /**
     * 最近一份清单的匹配索引。按清单实例识别：一次 `@` 会话里的 [LoadResult.Success.files]
     * 始终是同一个不可变 list，清单换了（重新拉取）索引自然失效。
     * 在 [loadFiles] 的 IO 分支里构建、在调用方线程上读取，故 volatile。
     */
    @Volatile
    private var index: Index? = null

    private class HitSet(val indices: IntArray, val scores: IntArray) {
        /** 下标与分数一一对应，[indices] 为空即无命中。 */
        val isEmpty: Boolean get() = indices.isEmpty()
    }

    private class Index(val files: List<String>, val lowered: List<String>) {
        /** 上一次查询（小写）及其全部命中：新查询是它的延长时只在这批命中里继续筛。 */
        var lastQuery: String = ""
        var last: HitSet = HitSet(IntArray(0), IntArray(0))
    }

    /**
     * 拉取工作区文件清单。走 `rg --files`：默认遵守 .gitignore，
     * 自动剪掉 `.git` / `build` 等生成物目录，避免大仓库全量遍历。
     * 容器未就绪（本地未初始化 / 远程未装 rg）时返回 [LoadResult.Unavailable]。
     */
    suspend fun loadFiles(projectPath: String): LoadResult {
        if (projectPath.isBlank()) return LoadResult.Unavailable
        val result = commandEngine.runCommandSyncIfReady(
            command = RG_FILES_COMMAND,
            projectPath = projectPath,
            timeoutMs = TIMEOUT_MS
        ) ?: return LoadResult.Unavailable
        // rg 无匹配时退出码为 1（这里即空目录），仅 >1 视为执行错误。
        if (isRgMissing(result.output) || result.exitCode == 127) return LoadResult.Unavailable
        if (result.exitCode != null && result.exitCode > 1) {
            return LoadResult.Failed(result.output.ifBlank { "rg 执行失败" })
        }
        // 上万行的切分/去重与小写化不放主线程：调用方从 viewModelScope 恢复到这里时是主线程。
        val files = withContext(Dispatchers.Default) {
            val parsed = result.output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_FILES)
                .toList()
            index = Index(parsed, parsed.map { it.lowercase() })
            parsed
        }
        return LoadResult.Success(files)
    }

    /**
     * 模糊匹配：空 [query] 返回前 [limit] 条；否则按子序列匹配质量排序后截断。
     * 匹配不区分大小写，越连续、层级越浅、路径越短排得越前。
     */
    fun match(files: List<String>, query: String, limit: Int = MAX_RESULTS): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return files.take(limit)
        if (limit <= 0) return emptyList()
        val idx = indexFor(files)
        val lowerQuery = q.lowercase()
        if (lowerQuery != idx.lastQuery) {
            // 子序列匹配对查询延长单调：旧查询的命中集必然是更长的查询的超集，可直接在里面续筛。
            val candidates =
                if (idx.lastQuery.isNotEmpty() && lowerQuery.startsWith(idx.lastQuery)) idx.last else null
            idx.last = collectHits(idx, lowerQuery, candidates)
            idx.lastQuery = lowerQuery
        }
        return selectTop(idx, idx.last, limit)
    }

    /** 复用调用方给的同一个清单实例上的索引；测试或换清单时按需重建。 */
    private fun indexFor(files: List<String>): Index {
        index?.let { if (it.files === files) return it }
        val built = Index(files, files.map { it.lowercase() })
        index = built
        return built
    }

    /** 收集命中下标与分数。[candidates] 为 null 表示扫全量。 */
    private fun collectHits(idx: Index, query: String, candidates: HitSet?): HitSet {
        val total = candidates?.indices?.size ?: idx.files.size
        val indices = IntArray(total)
        val scores = IntArray(total)
        var n = 0
        if (candidates == null) {
            for (i in 0 until total) {
                val score = scoreAt(idx.lowered[i], query)
                if (score != null) {
                    indices[n] = i
                    scores[n] = score
                    n++
                }
            }
        } else {
            for (i in candidates.indices) {
                val score = scoreAt(idx.lowered[i], query)
                if (score != null) {
                    indices[n] = i
                    scores[n] = score
                    n++
                }
            }
        }
        return if (n == total) HitSet(indices, scores) else HitSet(indices.copyOf(n), scores.copyOf(n))
    }

    /**
     * 取排序后的前 [limit] 条：用大小为 limit 的堆选出最优项，避免为上万条命中排全序。
     * 比较顺序与全序排序一致（分数、路径长度、字典序）；路径在清单内唯一，故结果与全序截断相同。
     */
    private fun selectTop(idx: Index, hits: HitSet, limit: Int): List<String> {
        if (hits.isEmpty) return emptyList()
        val size = hits.indices.size
        val capacity = minOf(limit, size)
        val heap = IntArray(capacity)
        var count = 0
        for (p in 0 until size) {
            if (count < capacity) {
                heap[count] = p
                siftUp(idx, hits, heap, count)
                count++
            } else if (isBetter(idx, hits, p, heap[0])) {
                heap[0] = p
                siftDown(idx, hits, heap, count)
            }
        }
        return (0 until count)
            .sortedWith(
                compareBy<Int>(
                    { hits.scores[heap[it]] },
                    { idx.files[hits.indices[heap[it]]].length },
                    { idx.files[hits.indices[heap[it]]] }
                )
            )
            .map { idx.files[hits.indices[heap[it]]] }
    }

    /** 候选 [a] 是否排在 [b] 之前（分数小者优先，其次路径短、字典序小）。 */
    private fun isBetter(idx: Index, hits: HitSet, a: Int, b: Int): Boolean {
        if (hits.scores[a] != hits.scores[b]) return hits.scores[a] < hits.scores[b]
        val pa = idx.files[hits.indices[a]]
        val pb = idx.files[hits.indices[b]]
        if (pa.length != pb.length) return pa.length < pb.length
        return pa < pb
    }

    /** 堆按「差」为上层组织：根是已选中最差的一项，新候选只需与根比较。 */
    private fun isWorse(idx: Index, hits: HitSet, a: Int, b: Int): Boolean = isBetter(idx, hits, b, a)

    private fun siftUp(idx: Index, hits: HitSet, heap: IntArray, from: Int) {
        var child = from
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!isWorse(idx, hits, heap[child], heap[parent])) return
            val swap = heap[child]
            heap[child] = heap[parent]
            heap[parent] = swap
            child = parent
        }
    }

    private fun siftDown(idx: Index, hits: HitSet, heap: IntArray, size: Int) {
        var parent = 0
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            var best = left
            val right = left + 1
            if (right < size && isWorse(idx, hits, heap[right], heap[left])) best = right
            if (!isWorse(idx, hits, heap[best], heap[parent])) return
            val swap = heap[best]
            heap[best] = heap[parent]
            heap[parent] = swap
            parent = best
        }
    }

    /**
     * 在已小写化的路径上做贪心子序列匹配：返回跳跃的空字符总数（越小越好），
     * 不匹配返回 null。查询与路径都已小写，比较与逐条 `lowercase` 等价。
     */
    private fun scoreAt(loweredPath: String, lowerQuery: String): Int? {
        var qi = 0
        var gap = 0
        var lastIndex = -1
        for (i in loweredPath.indices) {
            if (qi < lowerQuery.length && loweredPath[i] == lowerQuery[qi]) {
                if (lastIndex >= 0) gap += i - lastIndex - 1
                lastIndex = i
                qi++
            }
        }
        return if (qi == lowerQuery.length) gap else null
    }

    /** 清单拉取结果。 */
    sealed interface LoadResult {
        /** 成功拉到清单（可能为空）。 */
        data class Success(val files: List<String>) : LoadResult

        /** 容器未就绪或未安装 rg，无法搜索。 */
        data object Unavailable : LoadResult

        /** rg 执行出错。 */
        data class Failed(val message: String) : LoadResult
    }

    private companion object {
        const val RG_FILES_COMMAND = "rg --files --color never"
        const val TIMEOUT_MS = 10_000L
        const val MAX_FILES = 20_000

        /** 菜单一次最多展示的匹配条数。 */
        const val MAX_RESULTS = 50

        fun isRgMissing(output: String): Boolean =
            output.contains("command not found", ignoreCase = true) ||
                output.contains("rg: not found", ignoreCase = true)
    }
}
