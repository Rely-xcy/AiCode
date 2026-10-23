package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkspaceFileLock"

/**
 * 工作区路径级互斥：把「同一文件的读-改-写」在多会话之间串行化。
 *
 * ### 为什么需要它
 *
 * `editFile` 是典型的 read-modify-write：读原文 → 内存里改 → 整篇写回。两个会话
 * 同时这么做时，后写的一方会覆盖前者的结果（lost update），且**双方都以为自己成功**。
 * `StatefulAgentWorkflow` 里原有的 `pathLocks` 只在**一次 tool batch 的 coroutineScope
 * 内**生效，跨批次、跨会话、跨子代理全部失效——而子代理各自是独立 session，恰恰是最
 * 常见的并发来源。
 *
 * ### 锁键的选取
 *
 * 键必须用 [FileAccessProvider.toDisplayPath] 的结果（`~/workspace/...` 这类**逻辑**路径），
 * **不能**用物理路径：同一个文件在本地模式是宿主绝对路径、PRoot 下是 `/data/...`、
 * 远端 SFTP 下是 `/root/workspace/...`，三者字符串不同，用物理路径会导致锁形同虚设。
 * 逻辑路径在三种后端下对同一文件稳定一致。
 *
 * ### 边界（必须知道）
 *
 * 这是**进程内**的锁，无法拦截：
 * - 容器内 shell 命令写文件（`sed -i`、`cat >`）——它们不经本进程；
 * - 远端服务器上其他进程的写入；
 * - 用户编辑器自己的保存。
 *
 * 这些场景由 `FileVersionRegistry` 的**写前版本校验**事后发现（它校验的是内容而非锁，
 * 因此对"锁管不到的写入"同样有效）。两者是互补关系，不是二选一。
 *
 * ### 实现要点
 *
 * - **多键排序获取**：一次要锁多个路径时按字符串序依次获取，避免两个会话以相反顺序
 *   请求同一组文件而死锁。
 * - **等待者计数回收**：没有等待者时把该路径的锁条目从表中移除，否则长会话会为每个
 *   碰过的路径永久留一条记录（这类实现最常见的泄漏点）。
 * - 不做超时/租约：进程内锁的持有者一旦取消或异常，`withLock` 的 `finally` 一定会释放，
 *   不存在"孤儿锁"；加租约反而会引入"锁还在但被别人抢走"的新竞态。
 */
@Singleton
class WorkspaceFileLock @Inject constructor() {

    /**
     * 逻辑路径 → 锁条目。引用计数与 [Mutex] 放在同一条目里，获取/回收都在条目自身的
     * 监视器内完成，避免「计数已归零但新来的请求已拿到旧 Mutex」这种两把锁并存的情况。
     */
    private val entries = ConcurrentHashMap<String, Entry>()

    private class Entry {
        val mutex = Mutex()
        var refs = 0
        var retired = false
    }

    /**
     * 在指定逻辑路径的互斥保护下执行 [block]。
     *
     * @param keys 逻辑路径（[FileAccessProvider.toDisplayPath] 的结果）。null/空白会被忽略，
     *             因此调用方可以直接传可能为 null 的路径而不必先判空。空集合等价于不加锁。
     */
    suspend fun <T> withLock(keys: Collection<String?>, block: suspend () -> T): T {
        val ordered = keys.asSequence()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

        if (ordered.isEmpty()) return block()

        val acquired = ArrayList<Pair<String, Entry>>(ordered.size)
        try {
            for (key in ordered) {
                val entry = acquireRef(key)
                try {
                    entry.mutex.lock()
                } catch (e: Throwable) {
                    // 等待期间被取消：本次引用必须撤掉，否则条目再无人递减余数。
                    releaseRef(key, entry)
                    throw e
                }
                acquired.add(key to entry)
            }
            logIfSuspicious()
            return block()
        } finally {
            for (i in acquired.indices.reversed()) {
                val (key, entry) = acquired[i]
                entry.mutex.unlock()
                releaseRef(key, entry)
            }
        }
    }

    /** 单键便捷重载。 */
    suspend fun <T> withLock(key: String?, block: suspend () -> T): T = withLock(listOf(key), block)

    /** 当前活跃的锁条目数，供测试与诊断使用。 */
    fun trackedKeyCount(): Int = entries.size

    private fun acquireRef(key: String): Entry {
        while (true) {
            val existing = entries[key]
            if (existing != null) {
                synchronized(existing) {
                    // 条目可能刚被上一个使用者回收，此时不可复用，重取一次。
                    if (!existing.retired && entries[key] === existing) {
                        existing.refs++
                        return existing
                    }
                }
                continue
            }
            val fresh = Entry().apply { refs = 1 }
            if (entries.putIfAbsent(key, fresh) == null) return fresh
        }
    }

    private fun releaseRef(key: String, entry: Entry) {
        synchronized(entry) {
            entry.refs--
            if (entry.refs <= 0) {
                // 先标退再移除：并发取用者看到 retired 会重建条目，不会拿到这一份。
                entry.retired = true
                entries.remove(key, entry)
            }
        }
    }

    /**
     * 条目数异常增长时留一条日志。正常情况下条目数应约等于"同时被操作的文件数"，
     * 若逼近 [SUSPICIOUS_KEY_COUNT] 说明要么确实在批量操作大量文件，要么回收逻辑出了问题——
     * 这条日志是排查后者的唯一线索，因此保留但用 `w` 级别避免刷屏。
     */
    private fun logIfSuspicious() {
        val size = entries.size
        if (size >= SUSPICIOUS_KEY_COUNT) {
            FileLogger.w(TAG, "路径锁条目数偏高: $size（等待者计数回收可能失效，请检查 releaseRef）")
        }
    }

    private companion object {
        /** 条目数达到该阈值时打一条诊断日志。 */
        const val SUSPICIOUS_KEY_COUNT = 2048
    }
}
