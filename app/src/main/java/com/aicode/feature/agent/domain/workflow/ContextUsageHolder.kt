package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.domain.model.ModelContextPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次压判定后的上下文占用快照。
 *
 * 存在的理由：界面上「上下文已用百分之几」必须和触发压缩用的是同一套数，否则会出现
 * 「显示 60% 却已经触发压缩」——判定用的是 max(真实 usage, 本地估算 + 固定开销)，
 * 界面只拿真实 usage 就必然偏小（本轮新塞入的大内容还没发出去过，真实 usage 里看不到）。
 * 所以判定算完就把数值原样发布出来，界面按 [sessionId] 取，全链路只有一个数在算。
 *
 * [source] 让界面能分清显示的是真实值还是估算值（用户提出的硬要求：别把估算当真实展示）。
 */
data class ContextUsage(
    val sessionId: String?,
    /** 判定用的上下文占用（token）。 */
    val currentTokens: Int,
    /** 判定用的模型窗口，也是界面百分比的分母。 */
    val contextLimit: Int,
    /** 实际生效的软精简线（已被档位上限约束过）。 */
    val softThreshold: Int,
    /** 实际生效的硬压缩线；[hardEnabled] 为 false 时为 0。 */
    val hardThreshold: Int,
    val hardEnabled: Boolean,
    val source: Source
) {
    enum class Source {
        /** provider 回传的真实 usage（上一次请求的输入 token）。 */
        REPORTED,

        /** 本地估算：比真实 usage 大，说明本轮内容还没发出去过。 */
        ESTIMATE
    }

    /** true 表示这个数来自本地估算，界面必须标出来。 */
    val isEstimated: Boolean get() = source == Source.ESTIMATE

    /** 占窗口比例，指示器直接用（分子分母与压缩判定同源）。 */
    val progress: Float
        get() = if (contextLimit > 0) (currentTokens.toFloat() / contextLimit).coerceIn(0f, 1f) else 0f

    companion object {
        /**
         * 真实 usage 与本地估算取较大值，并记下占用的来源。
         *
         * 为什么不无条件用真实值：[realTokens] 是**上一次**请求的数，本轮新塞入的大内容
         * （文件、工具输出、图片）在它里面看不到，只信它会把超窗请求直接发出去。
         * 取大值偏保守（宁可早压缩），且来源随取到的那个值走，标注不会说谎。
         */
        fun of(
            sessionId: String?,
            realTokens: Int,
            estimatedTokens: Int,
            thresholds: ModelContextPolicy.Thresholds
        ): ContextUsage {
            val real = realTokens.coerceAtLeast(0)
            val estimated = estimatedTokens.coerceAtLeast(0)
            val current = maxOf(real, estimated)
            return ContextUsage(
                sessionId = sessionId,
                currentTokens = current,
                contextLimit = thresholds.contextLimit,
                softThreshold = thresholds.soft,
                hardThreshold = thresholds.hard,
                hardEnabled = thresholds.hardEnabled,
                source = if (current > real) Source.ESTIMATE else Source.REPORTED
            )
        }
    }
}

/**
 * 上下文占用的单一数据源：压缩模块每次判定后发布，界面订阅。
 *
 * 只保留最近一次：前台同一时刻只有一个会话在跑。子代理会话不发布（由调用方判断），
 * 否则并行跑的子代理会把前台会话的数顶掉，指示器在两个数之间来回跳。
 */
@Singleton
class ContextUsageHolder @Inject constructor() {

    private val _usage = MutableStateFlow<ContextUsage?>(null)

    /** 最近一次判定发布的快照；本次运行还没判定过时为 null。 */
    val usage: StateFlow<ContextUsage?> = _usage.asStateFlow()

    fun publish(usage: ContextUsage) {
        _usage.value = usage
    }
}
