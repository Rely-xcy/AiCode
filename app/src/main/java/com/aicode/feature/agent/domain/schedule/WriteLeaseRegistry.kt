package com.aicode.feature.agent.domain.schedule

import com.aicode.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一份写范围租约：某个会话独占某个路径前缀的写权限。 */
data class WriteLease(
    val holderId: String,
    /** 规范化后的绝对路径前缀（以 `/` 结尾），命中即视为落在范围内。 */
    val scopePrefix: String,
    /** 人类可读的范围描述，用于冲突提示。 */
    val scopeLabel: String,
    val acquiredAt: Long
)

/**
 * 写范围租约登记表。
 *
 * 解决的问题很具体：主代理可以并发派发多个子代理（`task` 上限 5），它们共享同一个工作区，
 * 两个子代理同时改同一个文件必然互相覆盖——各自的 `editFile` 都基于自己读到的旧内容。
 *
 * 做法不是"防止并发写"（做不到，也不该做），而是**要求先声明写范围**：子代理开始前申领
 * 路径前缀，别人已持有同一前缀时直接拒绝，冲突在开始前就暴露给主代理，而不是等到两个子代理
 * 把文件改坏之后。
 *
 * 刻意只做内存态、不落盘：租约是"这一次并发批次"里的协调信息，App 重启后所有子代理都已终止，
 * 残留租约只会让主代理凭空被拒。
 */
@Singleton
class WriteLeaseRegistry @Inject constructor() {

    private companion object {
        const val TAG = "WriteLeaseRegistry"

        /**
         * 租约有效期。持有者持续写入会不断刷新；超过这么久没动静就视为已放弃。
         *
         * 这是必需的兑底：子代理异常退出/被取消时没有人会去释放租约，
         * 没有 TTL 的话那条路径会永久被占，主代理凭空被拒。
         */
        const val LEASE_TTL_MS = 10 * 60 * 1000L
    }

    private val leases = ConcurrentHashMap<String, WriteLease>()

    /**
     * 申领写租约。
     *
     * @return 成功返回 null；失败返回已持有重叠范围的租约（供调用方回报冲突原因）。
     */
    fun acquire(holderId: String, path: String, label: String): WriteLease? {
        pruneExpired()
        val prefix = normalize(path)
        val now = System.currentTimeMillis()
        // 自己已持有同一范围：只刷新时间戳（活跃持有者不该因 TTL 被踢掉）。
        leases[holderId]?.takeIf { overlaps(it.scopePrefix, prefix) }?.let {
            leases[holderId] = it.copy(acquiredAt = now)
            return null
        }
        val conflict = leases.values.firstOrNull { other ->
            other.holderId != holderId && overlaps(other.scopePrefix, prefix)
        }
        if (conflict != null) {
            FileLogger.i(
                TAG,
                "写范围冲突：$holderId 想写 $label（$prefix），已被 ${conflict.holderId} 持有（${conflict.scopeLabel}）"
            )
            return conflict
        }
        leases[holderId] = WriteLease(holderId, prefix, label, now)
        return null
    }

    /** 释放租约（子代理结束/失败都要调，否则会一直占着）。 */
    fun release(holderId: String) {
        leases.remove(holderId)
    }

    /**
     * 写入前的准入检查：目标路径是否落在**别人**持有的范围内。
     *
     * @return 冲突租约；无冲突返回 null。同一 holder 自己写自己的范围永远放行。
     */
    fun checkWrite(holderId: String?, path: String): WriteLease? {
        pruneExpired()
        val prefix = normalize(path)
        return leases.values.firstOrNull { other ->
            other.holderId != holderId && overlaps(other.scopePrefix, prefix)
        }
    }

    /** 清掉过期租约（持有者已静默退出）。 */
    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - LEASE_TTL_MS
        leases.entries.removeIf { it.value.acquiredAt < cutoff }
    }

    fun leases(): List<WriteLease> = leases.values.toList()

    /**
     * 两个前缀是否有重叠。只比较前缀包含关系：`a/b/` 与 `a/b/c/` 重叠，
     * `a/b/` 与 `a/bc/` 不重叠（所以必须补尾斜杠，否则 `a/b` 会误判成 `a/bc` 的前缀）。
     */
    private fun overlaps(a: String, b: String): Boolean = a.startsWith(b) || b.startsWith(a)

    /** 目录路径补尾斜杠；把 `..` 归一化掉，避免绕过前缀判断。 */
    private fun normalize(path: String): String {
        val cleaned = path.replace('\\', '/').trim()
        val collapsed = cleaned.split('/').fold(mutableListOf<String>()) { acc, part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.size - 1)
                else -> acc.add(part)
            }
            acc
        }.joinToString("/")
        val withRoot = if (cleaned.startsWith("/")) "/$collapsed" else collapsed
        // 统一按目录前缀比较：是否以 / 结尾不重要，带上是让 overlaps 的判断更直观。
        return if (withRoot.endsWith("/")) withRoot else "$withRoot/"
    }
}
