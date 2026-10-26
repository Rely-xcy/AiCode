package com.aicode.feature.settings.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/** 一个 Key 对某个模型的可用性证据。 */
enum class KeyModelSupport {
    /** 已知能访问：拉到的该 Key 模型列表里包含它。 */
    AVAILABLE,

    /** 已知不能访问：上游明确报过「分组没有该模型」，或拉到的列表里没有它。 */
    MISSING,

    /** 未知：还没成功拉过该 Key 的模型列表，或已有缓存已过期。选 Key 时按「可能可用」参与。 */
    UNKNOWN
}

/**
 * 「Key × 模型」可用性缓存（纯内存，进程重启即清空）。
 *
 * 同一供应商的多个 Key 往往属于不同分组，各自能访问的模型集合并不相同：用某个 Key 调
 * `/v1/models` 拿到的列表就是「它能访问哪些模型」的正面证据；上游 503「No available channel
 * for model X under group Y」则是负面证据（这个 Key 的分组没有 X）。选 Key 时按证据过滤，
 * 没有证据的 Key 照常参与，避免把请求卡在补拉列表上。
 *
 * 两类证据都只保留 10 分钟（[MODELS_TTL_MILLIS]）：分组配置、渠道上下线都会变，过期后回到
 * 「未知」并允许重新拉取。落库没有意义——它只是运行时路由状态，进程重启后重新学习一次即可，
 * 与 [ProviderKeyRotator] 的冷却记录同语义。
 */
@Singleton
class KeyModelCatalog @Inject constructor() {

    private data class KeyEntry(
        /** 最近一次成功拉到的模型集合；null 表示还没成功拉到过。 */
        var models: Set<String>? = null,
        /** [models] 的拉取时刻。 */
        var fetchedAt: Long = 0L,
        /** 最近一次发起拉取的时刻（成功与失败都算），用于限频。 */
        var attemptedAt: Long = 0L,
        /** 上游明确说过「没有这个模型」的时刻，按模型记录。 */
        val missingAt: MutableMap<String, Long> = mutableMapOf()
    )

    private val lock = Any()

    /** providerId → Key → 该 Key 的模型证据。 */
    private val entries = mutableMapOf<String, MutableMap<String, KeyEntry>>()

    /**
     * 某个 Key 对 [model] 的可用性。
     *
     * 上游实际报过「没有这个模型」时以它为准，即使列表里写着有：列表可能不区分分组，或分组
     * 配置刚改过还没生效，而 503 是这一次真实请求的结果。
     *
     * [now] 可注入，供测试推进时间验证过期。
     */
    fun support(
        providerId: String,
        key: String,
        model: String,
        now: Long = System.currentTimeMillis()
    ): KeyModelSupport {
        synchronized(lock) {
            val entry = entries[providerId]?.get(key) ?: return KeyModelSupport.UNKNOWN
            val missingAt = entry.missingAt[model]
            if (missingAt != null) {
                if (isFresh(missingAt, now)) return KeyModelSupport.MISSING
                entry.missingAt.remove(model)
            }
            val models = entry.models ?: return KeyModelSupport.UNKNOWN
            if (!isFresh(entry.fetchedAt, now)) return KeyModelSupport.UNKNOWN
            return if (model in models) KeyModelSupport.AVAILABLE else KeyModelSupport.MISSING
        }
    }

    /** 最近一次成功拉到的模型集合；没拉到过或已过期时为 null（调用方按「未知」处理）。 */
    fun modelsOf(
        providerId: String,
        key: String,
        now: Long = System.currentTimeMillis()
    ): Set<String>? {
        synchronized(lock) {
            val entry = entries[providerId]?.get(key) ?: return null
            val models = entry.models ?: return null
            return if (isFresh(entry.fetchedAt, now)) models else null
        }
    }

    /** 是否该为这个 Key 发起一次列表拉取：从未拉过、或上次尝试已过 10 分钟。 */
    fun shouldFetch(
        providerId: String,
        key: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(lock) {
            val attemptedAt = entries[providerId]?.get(key)?.attemptedAt ?: 0L
            return attemptedAt == 0L || !isFresh(attemptedAt, now)
        }
    }

    /** 登记「已发起拉取」：失败也占住这个窗口，避免对拉不到列表的 Key 反复发请求。 */
    fun markFetchStarted(
        providerId: String,
        key: String,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            entry(providerId, key).attemptedAt = now
        }
    }

    /** 记下一次成功拉取的模型列表，同时刷新限频时间并清掉过期负面证据。 */
    fun recordModels(
        providerId: String,
        key: String,
        models: Set<String>,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val entry = entry(providerId, key)
            entry.models = models
            entry.fetchedAt = now
            entry.attemptedAt = now
            entry.missingAt.entries.removeAll { !isFresh(it.value, now) }
        }
    }

    /** 记下「这个 Key 所属分组没有 [model]」这条负面证据。 */
    fun markMissing(
        providerId: String,
        key: String,
        model: String,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val entry = entry(providerId, key)
            entry.missingAt[model] = now
            entry.missingAt.entries.removeAll { !isFresh(it.value, now) }
        }
    }

    /** 丢掉配置里已不存在的 Key 的记录：换 Key 后不该被旧 Key 的证据影响。 */
    fun forgetKeys(providerId: String, validKeys: Set<String>) {
        synchronized(lock) {
            val keys = entries[providerId] ?: return
            keys.keys.retainAll(validKeys)
            if (keys.isEmpty()) entries.remove(providerId)
        }
    }

    private fun entry(providerId: String, key: String): KeyEntry =
        entries.getOrPut(providerId) { mutableMapOf() }.getOrPut(key) { KeyEntry() }

    private fun isFresh(timestamp: Long, now: Long): Boolean = now - timestamp < MODELS_TTL_MILLIS

    companion object {
        /** 模型证据的有效期，同时也是同一 Key 两次拉取之间的最短间隔。 */
        const val MODELS_TTL_MILLIS: Long = 10 * 60 * 1000L
    }
}
