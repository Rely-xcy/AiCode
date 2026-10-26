package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.data.remote.FetchModelsResult
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按 Key 拉模型列表：成功写入证据、失败与空列表按未知处理、10 分钟内不重复拉。
 *
 * 网络层用 MockK 顶替，协程作用域用测试调度器，保证 `ensureModels` 返回时后台任务已跑完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderKeyModelFetcherTest {

    private val api = mockk<ModelApiService>()
    private val catalog = KeyModelCatalog()

    private fun config() = AIProviderConfig(
        id = "provider-1",
        name = "测试提供商",
        type = ProviderType.OPENAI,
        apiKey = "key-a",
        baseUrl = "https://example.com",
        defaultModel = "m1"
    )

    private fun fetched(vararg models: String) = Result.success(
        FetchModelsResult(
            models = models.toList(),
            debugInfo = ModelTestResult(success = true, latencyMs = 1, message = "ok")
        )
    )

    /** 拉到列表后写成该 Key 的正面证据。 */
    @Test
    fun ensureModels_recordsFetchedList() = runTest {
        coEvery { api.fetchModels(any(), "key-a", any(), any(), any()) } returns fetched("m1", "m2")
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "key-a")

        assertEquals(setOf("m1", "m2"), catalog.modelsOf("provider-1", "key-a"))
        assertEquals(KeyModelSupport.AVAILABLE, catalog.support("provider-1", "key-a", "m2"))
        assertEquals(KeyModelSupport.MISSING, catalog.support("provider-1", "key-a", "m3"))
    }

    /** 拉取失败按「未知」处理：Key 仍可参与选择，不因网络问题把用户卡死。 */
    @Test
    fun ensureModels_failedFetchKeepsKeyUnknown() = runTest {
        coEvery { api.fetchModels(any(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("HTTP 401"))
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "key-a")

        assertNull(catalog.modelsOf("provider-1", "key-a"))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("provider-1", "key-a", "m1"))
    }

    /** 网络层直接抛异常（而不是返回 failure）也不能逃出应用级作用域。 */
    @Test
    fun ensureModels_thrownFetchIsSwallowed() = runTest {
        coEvery { api.fetchModels(any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "key-a")

        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("provider-1", "key-a", "m1"))
    }

    /** 空列表更可能是端点不返回模型，按未知处理而不是「什么都不能用」。 */
    @Test
    fun ensureModels_emptyListIsTreatedAsUnknown() = runTest {
        coEvery { api.fetchModels(any(), any(), any(), any(), any()) } returns fetched()
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "key-a")

        assertNull(catalog.modelsOf("provider-1", "key-a"))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("provider-1", "key-a", "m1"))
    }

    /** 10 分钟内不重复拉：聊天每次选 Key 都会登记，真正发请求的只有第一次。 */
    @Test
    fun ensureModels_fetchesAtMostOnceWithinTtl() = runTest {
        coEvery { api.fetchModels(any(), any(), any(), any(), any()) } returns fetched("m1")
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "key-a")
        fetcher.ensureModels(config(), "key-a")
        fetcher.ensureModels(config(), "another-key")

        coVerify(exactly = 2) { api.fetchModels(any(), any(), any(), any(), any()) }
    }

    /** 空白 Key 不发请求。 */
    @Test
    fun ensureModels_blankKeyDoesNothing() = runTest {
        val fetcher = ProviderKeyModelFetcher(api, catalog, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        fetcher.ensureModels(config(), "   ")

        coVerify(exactly = 0) { api.fetchModels(any(), any(), any(), any(), any()) }
    }
}
