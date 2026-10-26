package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.di.ApplicationScope
import com.aicode.feature.agent.domain.provider.resolveCustomHeaders
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按 Key 懒加载模型列表，填进 [KeyModelCatalog]。
 *
 * 列表请求带的是**这个 Key 自己的** Authorization：同一供应商不同分组的 Key 会拿到不同的模型
 * 集合，这正是选 Key 的依据。请求在应用级作用域里异步执行，调用方只登记、不等待——拉不到就把
 * 这个 Key 当「未知」，不能因此卡住聊天请求。同一 Key 10 分钟内最多拉一次（见
 * [KeyModelCatalog.shouldFetch]），失败的尝试也占住窗口，避免对拉不到列表的 Key 反复发请求。
 */
@Singleton
class ProviderKeyModelFetcher @Inject constructor(
    private val modelApiService: ModelApiService,
    private val catalog: KeyModelCatalog,
    @param:ApplicationScope private val scope: CoroutineScope
) {

    private val lock = Any()

    /** 正在拉取的 providerId + Key，避免并发请求重复拉同一份列表。 */
    private val inFlight = mutableSetOf<String>()

    /** 该 Key 的模型列表若从未拉过、或上次尝试已过 10 分钟，就异步拉一次。 */
    fun ensureModels(config: AIProviderConfig, apiKey: String, sessionId: String? = null) {
        if (apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val id = "${config.id}\u0000$apiKey"
        synchronized(lock) {
            if (!catalog.shouldFetch(config.id, apiKey, now)) return
            if (!inFlight.add(id)) return
            catalog.markFetchStarted(config.id, apiKey, now)
        }
        scope.launch {
            try {
                // fetchModels 自己把失败包成 Result；这里再兜一层，避免任何意外异常从应用级作用域逃出去。
                val outcome = try {
                    modelApiService.fetchModels(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        type = config.type,
                        useFullUrl = config.useFullUrl,
                        customHeaders = resolveCustomHeaders(config.customHeaders, sessionId, apiKey)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    FileLogger.w(TAG, "拉取 Key ${apiKey.masked()} 的模型列表异常，按未知处理：${e.message}")
                    null
                }
                val fetched = outcome?.getOrNull()
                when {
                    fetched == null -> FileLogger.w(
                        TAG,
                        "拉取 Key ${apiKey.masked()} 的模型列表失败，按未知处理：${outcome?.exceptionOrNull()?.message}"
                    )
                    // 空列表意味着「什么都访问不了」，更可能是端点不返回模型（自建网关常见），按未知处理。
                    fetched.models.isEmpty() -> FileLogger.w(
                        TAG,
                        "Key ${apiKey.masked()} 的模型列表为空，按未知处理"
                    )
                    else -> catalog.recordModels(config.id, apiKey, fetched.models.toSet())
                }
            } finally {
                synchronized(lock) { inFlight.remove(id) }
            }
        }
    }

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyModelFetcher"
    }
}
