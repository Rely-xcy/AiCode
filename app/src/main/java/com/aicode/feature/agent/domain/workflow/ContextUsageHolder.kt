package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.domain.model.ModelContextPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * [reportedTokens] 进一步给出 [currentTokens] 里真实的那部分：估算把数顶上去时，
 * 界面才能回答「环这么长，其中多少是真发出去过的」。
 */
data class ContextUsage(
    val sessionId: String?,
    /** 判定用的上下文占用（token）：真实 usage 与本地估算取较大值。 */
    val currentTokens: Int,
    /**
     * [currentTokens] 里真实的部分（provider 回传的 usage）。
     *
     * 单留一份是为了让界面能画出「判定值 vs 真实值」两条读数：只给判定值，用户看到环长
     * 就以为真实已用这么多，而实际上可能是本轮新塞入的内容把估算顶上去的。
     */
    val reportedTokens: Int,
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

    /** 真实 usage 占窗口比例；与 [progress] 同分母，两者直接可比。 */
    val reportedProgress: Float
        get() = if (contextLimit > 0) (reportedTokens.toFloat() / contextLimit).coerceIn(0f, 1f) else 0f

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
                reportedTokens = real,
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
 * 上下文占用的单一数据源：压缩模块每次判定后发布，界面按会话取。
 *
 * **按会话分槽**：每个会话一个独立的 [MutableStateFlow]，一次发布只写自己那个槽，会话之间不共享
 * 任何被写的数据——主会话与子会话并发判定时谁也不会把别人的快照顶掉。旧的整表实现是「整表读出来、
 * 改完写回」，两次读之间发生的写入会被这次写回整表覆盖；压缩搬到后台线程后，这类并发写入从「不可能」
 * 变成「常见」，丢快照就跟着变成可达。
 *
 * 槽位是唯一数据源：[usage] 这张全量表每次都按各槽当前值重建（见 [refreshAggregate]），它不是第二份
 * 可写状态，因此并发的多次重建不会互相丢会话。
 *
 * 按会话分开存，而不是只留最近一次：并行跑的子代理、后台会话、用户切走又切回的会话
 * 都会各自判定，塞进同一个槽就会互相顶掉，界面拿到的 id 与当前会话对不上，只能退化成
 * 「上次请求的真实 usage」——那正是「已经触发压缩、环却只显示一小截」的来源。
 */
@Singleton
class ContextUsageHolder @Inject constructor() {

    private val lock = Any()

    /** 会话 id → 槽位。顺序 = 最近一次发布的先后（末尾最新），超上限时先丢最久没发布过的会话。 */
    private val slots = LinkedHashMap<String, Slot>()

    private val _usage = MutableStateFlow<Map<String?, ContextUsage>>(emptyMap())

    /** 各会话最近一次判定发布的快照；从来没有判定过的会话不在表里，界面据此判断「没数可画」。 */
    val usage: StateFlow<Map<String?, ContextUsage>> = _usage.asStateFlow()

    /** 一个会话的槽位：只有该会话自己的发布写它。 */
    private class Slot {
        val usage = MutableStateFlow<ContextUsage?>(null)
    }

    fun publish(usage: ContextUsage) {
        // 没有会话 id 的判定不属于任何界面会话，收下只会让所有会话都取到这份数。
        val id = usage.sessionId ?: return
        val slot = slotOf(id)
        // 只写自己那个槽：旧实现的「读整表 → 改 → 写整表」在这里不再存在。
        slot.usage.value = usage
        refreshAggregate()
    }

    /** 会话删除后丢掉它的快照，否则表里会攒着已不存在会话的数。 */
    fun remove(sessionId: String?) {
        val id = sessionId ?: return
        val removed = synchronized(lock) { slots.remove(id) != null }
        if (removed) refreshAggregate()
    }

    /** 取（必要时新建）某会话的槽位；锁内只动槽位表的结构，不写任何快照。 */
    private fun slotOf(id: String): Slot = synchronized(lock) {
        // 先删再插：让最新发布的排在最后，超上限时先丢最久没发布过的会话。
        val slot = slots.remove(id) ?: Slot()
        slots[id] = slot
        while (slots.size > MAX_SESSIONS) {
            slots.remove(slots.keys.first())
        }
        slot
    }

    /**
     * 用各槽当前值重建 [usage]。
     *
     * 走 [MutableStateFlow.update] 的 CAS 循环而不是直接赋值：重建期间别的会话可能也在重建，
     * 直接赋值会被「读到更旧槽值的那一次重建」覆盖掉。CAS 失败会重跑重建，重跑时再读一遍槽位，
     * 于是最后一次成功的重建必然看到它之前的所有写入。
     */
    private fun refreshAggregate() {
        _usage.update {
            // 先取一份「槽位 → 当前快照」的平面副本（槽位值是 MutableStateFlow，读的是最新值），
            // 再在 CAS 循环外拼表：lambda 里不放 break/continue，语义一眼可见。
            val current = synchronized(lock) { slots.mapValues { it.value.usage.value } }
            val next = HashMap<String?, ContextUsage>(current.size)
            for ((id, snapshot) in current) {
                if (snapshot != null) next[id] = snapshot
            }
            next
        }
    }

    private companion object {
        /** 同时会被看的只有当前会话，其余是给切来切去用的；不设上限会随会话数无界增长。 */
        const val MAX_SESSIONS = 16
    }
}
