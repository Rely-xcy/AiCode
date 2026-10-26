package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Key 切换的结果，供调用方改写凭据并提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newKey: String,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int
)

/**
 * 多 Key 轮换器：决定某个 provider 在某条会话上该用哪个 API Key，并在 Key 不可用时切到下一个。
 *
 * 状态全部是运行时内存态、不落库：进程重启后回到第一个 Key、冷却记录清空——多 Key 的意义是
 * 遇到鉴权/额度/限流问题时能立刻绕开，没有必须跨进程保持的语义。
 *
 * **会话粘性**是这里的核心约束：服务端 prompt 缓存按 API Key 隔离，同一会话逐请求换 Key 会让
 * 每轮都落到没有缓存的 Key 上，缓存命中率归零、成本和首字延迟一起恶化。因此只在会话首次取
 * Key、或当前 Key 被判定不可用时才换，其余情况一直粘住同一个 Key。
 *
 * **按模型挑 Key**：同一供应商的多个 Key 常常属于不同分组，各自能访问的模型集合不同（一个分组
 * 有某模型的渠道、另一个没有）。选 Key 时用 [KeyModelCatalog] 里的证据过滤：
 * - 已知不能访问该模型（拉到的列表里没有，或上游直接报过「分组没有该模型」）的 Key 不参与；
 * - 已知能访问的 Key 优先于「还没拉到列表」的未知 Key；
 * - 未知 Key 照常参与，首次选中后由 [ProviderKeyModelFetcher] 异步补拉列表，不阻塞请求；
 * - 所有 Key 都已知没有该模型时直接报错，不拿没渠道的 Key 硬打。
 *
 * [model] 传 null（生图、余额查询等旁路）时不做任何模型过滤，行为与只按 Key 轮换时完全一致。
 */
@Singleton
class ProviderKeyRotator @Inject constructor(
    private val catalog: KeyModelCatalog,
    private val modelFetcher: ProviderKeyModelFetcher
) {

    /** 由最近一次 [activeKey] 记录的 provider Key 配置，供失败上报时读取候选与冷却时长。 */
    private data class KeySetup(
        val keys: List<ProviderKey>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + Key → 冷却截止时间戳（毫秒）。 */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → Key 值 → 平滑加权轮询的当前权重（运行时内存态）。 */
    private val weightedState = mutableMapOf<String, MutableMap<String, Long>>()

    /** providerId → 最近一次选中的 Key，供余额查询等旁路请求取「当前活动 Key」。 */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → 该会话粘住的 Key。会话数量无上界，用访问序 LRU 兜住内存，
     * 被淘汰的老会话下次请求重新分配 Key 即可，没有正确性问题。
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * 取 [config] 在 [sessionId] 上当前该用的 Key；没有任何可用 Key、或所有 Key 都在冷却时返回 null。
     * 同时把该 provider 的 Key 配置快照下来，供后续 [reportFailure] 使用。
     *
     * [model] 为本次请求要用的模型名，决定“挑哪个 Key”：已知能访问它的 Key 优先、已知不能的
     * 被排除。所有 Key 都已知不能访问 [model] 时抛 [IllegalStateException]（文案含模型名）。
     * 传 null 表示不按模型过滤，行为与以前完全一致。
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?, model: String? = null): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        val modelId = model?.trim()?.takeIf { it.isNotEmpty() }
        val chosen = synchronized(lock) {
            recordSetup(
                providerId = config.id,
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val sticky = bindKey?.let { sessionKeys[it] }?.takeIf { bound ->
                keys.any { it.value == bound } && !isCoolingDown(config.id, bound) &&
                    supportsModel(config.id, bound, modelId)
            }
            // 会话粘性优先：同一会话、同一模型、该 Key 已知能访问它时一直用它（prompt 缓存按 Key 隔离）。
            if (sticky != null) sticky else {
                val picked = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet(), model = modelId)
                if (picked == null) {
                    throwIfNoKeySupportsModel(config, keys, modelId)
                    null
                } else {
                    if (bindKey != null) sessionKeys[bindKey] = picked
                    lastSelected[config.id] = picked
                    picked
                }
            }
        }
        // 懒加载：多 Key 且按模型选时，为这次选中的 Key 补拉一次模型列表。放在锁外由应用级作用域
        // 异步执行，拉不到就按未知处理，不拖住本次请求。
        if (chosen != null && modelId != null && keys.size > 1) {
            modelFetcher.ensureModels(config, chosen, sessionId)
        }
        return chosen
    }

    /**
     * 只读地取该 provider 当前活动的 Key：不建会话绑定、不推进轮询游标。
     * 供余额查询这类旁路请求使用——它们要反映「聊天正在用哪个 Key」，而不该干扰轮换节奏。
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && keys.any { it.value == last } && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it.value) }?.value ?: keys.first().value
        }
    }

    /**
     * 上报一次 Key 级失败（鉴权/额度/限流）：**立即**切到下一个可用 Key、重绑会话，返回切换结果。
     *
     * 没有可切换的候选（候选已试完、或全部在冷却）时返回 null 且**不冷却** [key]：把最后一个
     * 可用 Key 也锁死只会让用户完全不可用。
     *
     * [model] 为本次请求的模型名：切换目标同样要能访问这个模型，不再盲目换一个。传 null 表示
     * 不按模型过滤。[triedKeys] 为本次请求已试过的 Key（含 [key] 自身），避免同一次请求内重复试。
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        key: String,
        triedKeys: Set<String> = emptySet(),
        model: String? = null
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (setup.keys.none { it.value == key }) return null

            // 先确认有可切换的候选，没有就别冷却——把最后一个可用 Key 冷却只会让用户完全不可用
            val next = pick(
                providerId = providerId,
                keys = setup.keys,
                strategy = setup.strategy,
                exclude = triedKeys + key,
                model = model?.trim()?.takeIf { it.isNotEmpty() }
            ) ?: return null
            if (setup.cooldownMillis > 0) {
                cooldownUntil[stateKey(providerId, key)] = System.currentTimeMillis() + setup.cooldownMillis
            }
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            val index = setup.keys.indexOfFirst { it.value == next } + 1
            FileLogger.i(TAG, "Key 切换 provider=$providerId ${key.masked()} → ${next.masked()} (第 $index/${setup.keys.size} 个)")
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /**
     * 记下「[key] 所属分组没有 [model] 的渠道」这条负面证据。**不冷却、也不换 Key**：换到另一个
     * 分组的 Key 同样没有渠道，只会再失败一次；下一次 [activeKey] 会凭这条证据绕开这个 Key，
     * 优先挑已知能访问该模型的 Key。
     */
    fun markModelUnavailable(providerId: String, key: String, model: String) {
        val modelId = model.trim().takeIf { it.isNotEmpty() } ?: return
        catalog.markMissing(providerId, key, modelId)
        FileLogger.w(TAG, "provider=$providerId Key ${key.masked()} 所属分组没有模型 $modelId（不冷却，选 Key 时绕开）")
    }

    /**
     * 按策略挑一个可选 Key：排除 [exclude]（本次已试过）、处于冷却中的、权重 ≤ 0 的，以及
     * [model] 已知不可用的 Key。已知能访问 [model] 的 Key 优先于「列表还没拉到」的未知 Key；
     * 没有候选时返回 null。不做「全冷却就退回最早到期者再试一遍」的回退——那等于拿已知不可用的
     * Key 硬打。
     */
    private fun pick(
        providerId: String,
        keys: List<ProviderKey>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>,
        model: String?
    ): String? {
        val eligible = keys.filter {
            it.weight > 0 && it.value !in exclude && !isCoolingDown(providerId, it.value)
        }
        val scored = eligible.map { key ->
            key to if (model == null) KeyModelSupport.AVAILABLE else catalog.support(providerId, key.value, model)
        }.filter { (_, support) -> support != KeyModelSupport.MISSING }
        if (scored.isEmpty()) return null
        val participants = scored
            .filter { (_, support) -> support == KeyModelSupport.AVAILABLE }
            .ifEmpty { scored }
            .map { (key, _) -> key }
        return when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> participants.first().value
            KeyRotationStrategy.ROUND_ROBIN -> weightedRoundRobin(providerId, participants)
        }
    }

    /**
     * 平滑加权轮询（SWRR）：每轮给各候选加上自身权重、取当前权重最大者，再给选中者减去候选总权重。
     * 无需按权重展开成虚拟列表，权重可到百万级；分布均匀且不会一直落在同一个 Key 上。
     */
    private fun weightedRoundRobin(providerId: String, participants: List<ProviderKey>): String {
        val current = weightedState.getOrPut(providerId) { mutableMapOf() }
        var total = 0L
        var best = participants.first()
        var bestWeight = Long.MIN_VALUE
        for (candidate in participants) {
            val next = (current[candidate.value] ?: 0L) + candidate.weight
            current[candidate.value] = next
            total += candidate.weight
            if (next > bestWeight) {
                bestWeight = next
                best = candidate
            }
        }
        current[best.value] = (current[best.value] ?: 0L) - total
        return best.value
    }

    /** 该 Key 是否可能访问 [model]：模型未知（null）或证据不是「已知不可用」。 */
    private fun supportsModel(providerId: String, key: String, model: String?): Boolean =
        model == null || catalog.support(providerId, key, model) != KeyModelSupport.MISSING

    /**
     * 没有选中任何 Key 时区分原因：所有可调度的 Key 都已知访问不了 [model] 就直接报错，
     * 而不是退回「Key 全在冷却」这种误导文案、更不是拿没渠道的 Key 硬试。
     */
    private fun throwIfNoKeySupportsModel(config: AIProviderConfig, keys: List<ProviderKey>, model: String?) {
        if (model == null) return
        val selectable = keys.filter { it.weight > 0 }
        if (selectable.isEmpty() || selectable.any { supportsModel(config.id, it.value, model) }) return
        throw IllegalStateException(
            "「${config.name}」的 ${selectable.size} 个 Key 所属分组都没有模型 $model 的可用渠道，" +
                "该模型可能需要换一个分组的 Key"
        )
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    /** 记录 Key 配置快照；Key 列表变化时清掉已移除 Key 的模型证据。 */
    private fun recordSetup(providerId: String, keys: List<ProviderKey>, strategy: KeyRotationStrategy, cooldownMillis: Long) {
        val previous = setups.put(providerId, KeySetup(keys, strategy, cooldownMillis))
        if (previous != null && previous.keys.map { it.value }.toSet() != keys.map { it.value }.toSet()) {
            catalog.forgetKeys(providerId, keys.map { it.value }.toSet())
        }
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
