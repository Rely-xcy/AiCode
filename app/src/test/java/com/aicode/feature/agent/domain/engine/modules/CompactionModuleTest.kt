package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.CompactedHistoryArchive
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.domain.workflow.ContextUsageHolder
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 硬折叠失败不能把安全网一起关掉。
 *
 * 调用方（StatefulAgentWorkflow）早先用一个 flag 在收到 CompactionFailed 后整轮跳过 beforeLlmCall，
 * 于是「摘要模型报错」这一次失败会把同一轮后续每次 LLM 调用的软精简、阈值发布与 92% 兜底一起停掉。
 * 那段跳过已删除，本用例钉住模块这一侧的契约：被反复调用时，失败只影响「折不折」，
 * 兜底照跑，而且不会每轮都白试一次摘要模型。
 */
class CompactionModuleTest {

    private companion object {
        /** 64K 窗口走 STANDARD 档位：硬线被档位上限压到 54K（85% 算出来是 54.4K）。 */
        const val WINDOW = 64_000
        const val HARD_THRESHOLD = 54_000

        /** 发送前兜底的预算：窗口 × 92%（与 ModelContextPolicy.GUARD_BUDGET_PERCENT 同源）。 */
        const val GUARD_BUDGET = WINDOW * 92 / 100
    }

    private class Harness(
        val module: CompactionModule,
        val provider: AIProvider,
        val usageHolder: ContextUsageHolder,
        val completeCalls: () -> Int
    )

    private fun harness(): Harness {
        val compactor = ContextCompactor(
            agentMessageDao = mockk(relaxed = true),
            systemPromptProvider = mockk(relaxed = true),
            llmCallRecordDao = mockk(relaxed = true),
            compactedHistoryArchive = mockk<CompactedHistoryArchive>(relaxed = true)
        )
        var completeCalls = 0
        fun failSummaryCall(): Nothing {
            completeCalls++
            throw RuntimeException("摘要模型挂了")
        }
        val provider = mockk<AIProvider>(relaxed = true)
        // 压缩请求现在多带一个 cacheTail 形参（一次性调用不打尾部缓存断点）。
        // mockk 按「调用点解析后的实参列表」匹配（默认参数也会参与），所以把 4 个与 5 个实参
        // 的形态都挂上：否则一次都匹配不上，协程会抛「no answer found」——那走的就不是
        // 「摘要模型挂了」这条被测路径了。
        coEvery { provider.complete(any(), any(), any(), any()) } answers { failSummaryCall() }
        coEvery { provider.complete(any(), any(), any(), any(), any()) } answers { failSummaryCall() }
        val metadata = mockk<ModelMetadataService>()
        coEvery { metadata.resolve(any(), any(), any()) } returns
            ModelMetadata(id = "test-model", contextTokens = WINDOW)
        val settings = mockk<GeneralSettingsRepository>()
        coEvery { settings.softCompactionThresholdPercent() } returns 40
        coEvery { settings.compactionThresholdPercent() } returns 85

        val usageHolder = ContextUsageHolder()
        val module = CompactionModule(
            compactor = fixedLazy(compactor),
            memoryExtractor = MemoryExtractor(mockk(relaxed = true)),
            systemPromptProvider = fixedLazy(mockk<SystemPromptProvider>(relaxed = true)),
            modelMetadataService = metadata,
            generalSettingsRepository = settings,
            contextUsageHolder = usageHolder
        )
        return Harness(module, provider, usageHolder) { completeCalls }
    }

    /** 给模块一个「取出来就是它」的 Lazy：DI 的惰性装配在这条路径上没有语义，不必拿 mockk 去桩它。 */
    private fun <T> fixedLazy(value: T): dagger.Lazy<T> = object : dagger.Lazy<T> {
        override fun get(): T = value
    }

    /** 三条消息、第一条约 60k tokens：越过 54k 硬线，也躲开「≤2 条且没逼近窗口就早退」那条快路。 */
    private fun oversizedHistory(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(content = "x".repeat(240_000)),
        AgentMessage.UserMessage(content = "继续"),
        AgentMessage.UserMessage(content = "把刚才那些整理一遍")
    )

    @Test
    fun `硬折叠失败后仍跑兜底，且不再重复尝试折叠`() = runTest {
        val h = harness()
        val messages = oversizedHistory()
        val events = mutableListOf<AgentEvent>()

        suspend fun call(force: Boolean = false): LlmCall? = h.module.beforeLlmCall(
            EngineContext(sessionId = "s1", projectRoot = "/ws"),
            LlmCall(
                messages = messages,
                windowProvider = h.provider,
                summaryProvider = h.provider,
                force = force,
                onEvent = { events += it }
            )
        )

        // 第一轮：越过硬线 → 试硬折叠 → 摘要模型抛错。失败照常上报，但兜底必须仍然把消息压回预算。
        val first = assertNotNull(call())
        assertEquals(1, events.count { it is AgentEvent.CompactionFailed })
        val afterFoldFailure = TokenEstimator.estimateMessages(first.messages)
        assertTrue(afterFoldFailure >= HARD_THRESHOLD) // 折叠没换来空间：正文本身就占满
        assertTrue(afterFoldFailure <= GUARD_BUDGET + 1_000) // 但发送前兜底照跑了
        val callsAfterFirst = h.completeCalls()
        assertTrue(callsAfterFirst > 0)

        // 第二轮（同一轮内的下一次 LLM 调用）：不再白试一次摘要模型，兜底依旧生效
        val second = assertNotNull(call())
        assertEquals(callsAfterFirst, h.completeCalls())
        assertTrue(TokenEstimator.estimateMessages(second.messages) <= GUARD_BUDGET + 1_000)
        assertEquals(1, events.count { it is AgentEvent.CompactionFailed })

        // 手动强制压缩：用户点了就该真去再试一次，抑制不该把 call.force 一起挡住
        call(force = true)
        assertTrue(h.completeCalls() > callsAfterFirst)
        assertEquals(2, events.count { it is AgentEvent.CompactionFailed })
    }

    /**
     * 约 3 万 token 的历史（3 万汉字）：越过软线 25,600，但正文本就是用户消息、
     * softTrim 没有可削的目标 —— 所以「没触发」与「触发了但没东西可削」在这里靠发布值区分。
     */
    private fun softLineHistory(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(content = "汉".repeat(30_000)),
        AgentMessage.UserMessage(content = "继续"),
        AgentMessage.UserMessage(content = "把刚才那些整理一遍")
    )

    /**
     * 端到端验收：上一轮高估（估 3 万 / 真实 1 万）之后，本轮同样的历史必须按**校准后的 1 万**判定。
     *
     * 修复前：判定 = max(10,000, 约 3 万) = 约 3 万 ≥ 软线 25,600 → 白跑软精简（真机同型：真实
     * 352,982 被估成 523,798，52.4% 触发而真实只有 35.3%）。修复后 10,000 < 25,600 → 什么都不做。
     * 断言直接读模块发布的快照 —— 那是它自己用来决策的同一个数（判定、界面、日志同源）。
     */
    @Test
    fun `上一轮的高估被校准掉后不再触发软精简`() = runTest {
        val h = harness()
        val messages = softLineHistory()
        val ctx = EngineContext(sessionId = "s1", projectRoot = "/ws")

        suspend fun call(lastInputTokens: Int): LlmCall? = h.module.beforeLlmCall(
            ctx,
            LlmCall(
                messages = messages,
                windowProvider = h.provider,
                summaryProvider = h.provider,
                lastInputTokens = lastInputTokens
            )
        )

        assertTrue(TokenEstimator.estimateMessages(messages) >= 25_600)

        // 第一轮：还没拿到真实 usage，只把原始估算记成基线，不改动编排
        assertNull(call(lastInputTokens = 0))
        // 第二轮：provider 回传真实 1 万（比估算少 2 万）→ 校准减掉这 2 万 → 判定 1 万，低于软线
        assertNull(call(lastInputTokens = 10_000))

        val usage = assertNotNull(h.usageHolder.usage.value["s1"])
        assertEquals(10_000, usage.currentTokens)
        assertEquals(10_000, usage.reportedTokens)
        assertTrue(usage.currentTokens < usage.softThreshold)
    }
}
