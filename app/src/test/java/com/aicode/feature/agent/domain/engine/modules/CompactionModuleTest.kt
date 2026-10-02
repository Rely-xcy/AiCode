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
        // 摘要模型一直失败：折叠拿不到结果，模块必须退化为「软精简 + 兜底」而不是就此罢手。
        val provider = mockk<AIProvider>(relaxed = true)
        coEvery { provider.complete(any(), any(), any()) } answers {
            completeCalls++
            throw RuntimeException("摘要模型挂了")
        }
        val metadata = mockk<ModelMetadataService>()
        coEvery { metadata.resolve(any(), any(), any()) } returns
            ModelMetadata(id = "test-model", contextTokens = WINDOW)
        val settings = mockk<GeneralSettingsRepository>()
        coEvery { settings.softCompactionThresholdPercent() } returns 40
        coEvery { settings.compactionThresholdPercent() } returns 85

        val module = CompactionModule(
            compactor = fixedLazy(compactor),
            memoryExtractor = MemoryExtractor(mockk(relaxed = true)),
            systemPromptProvider = fixedLazy(mockk<SystemPromptProvider>(relaxed = true)),
            modelMetadataService = metadata,
            generalSettingsRepository = settings,
            contextUsageHolder = ContextUsageHolder()
        )
        return Harness(module, provider) { completeCalls }
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
}
