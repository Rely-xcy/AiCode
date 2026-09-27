package com.aicode.feature.agent.domain.workflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩判定用的上下文占用快照。
 *
 * 这些数字必须由 [_ContextCompactor 的判定路径] 产出：指示器若自己按
 * 「当前聊天模型的元数据窗口」另算一份，就会出现「指示器显示一半、压缩已经触发」
 * 这种 UI 与行为自相矛盾的情况（判定同时受档位硬上限约束，还可能取本地估算）。
 */
data class ContextUsage(
    /** 判定用的占用：真实 usage 与本地估算取大。 */
    val currentTokens: Int,
    /** 判定用的窗口，与压缩同源。 */
    val contextLimit: Int,
    /** 实际生效的硬阈值（已被档位硬上限约束过，可能小于设置的百分比）。 */
    val triggerThreshold: Int,
    /** 实际生效的软阈值。 */
    val softThreshold: Int
) {
    /** 窗口占用进度，与判定同分母。 */
    val progress: Float
        get() = if (contextLimit > 0) (currentTokens.toFloat() / contextLimit).coerceIn(0f, 1f) else 0f
}

data class SessionContextUsage(
    val sessionId: String?,
    val usage: ContextUsage
)

/**
 * 会话上下文占用的最新快照，供 UI（发送键旁的指示器）读取。
 *
 * 只保留最近一次判定的结果：指示器只关心「当前会话现在多满」，
 * 历史值没有意义，留着反而会误导。
 */
@Singleton
class ContextUsageHolder @Inject constructor() {

    private val _latest = MutableStateFlow<SessionContextUsage?>(null)
    val latest: StateFlow<SessionContextUsage?> = _latest.asStateFlow()

    fun publish(sessionId: String?, usage: ContextUsage) {
        _latest.value = SessionContextUsage(sessionId, usage)
    }

    /** 取指定会话的占用；会话不匹配时返回 null（避免把 A 会话的数字显示到 B 会话上）。 */
    fun usageFor(sessionId: String?): ContextUsage? =
        _latest.value?.takeIf { it.sessionId == sessionId }?.usage
}
