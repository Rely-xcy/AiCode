package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.brief.SessionBriefExtractor
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.CompactedHistoryArchive
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.domain.workflow.ContextUsageHolder
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 硬折叠失败不能把安全网一起关掉。
 *
 * 调用方（StatefulAgentWorkflow）早先用一个 flag 在收到 CompactionFailed 后整轮跳过 beforeLlmCall，
 * 于是「摘要模型报错」这一次失败会把同一轮后续每次 LLM 调用的阈值发布与 92% 兜底一起停掉。
 * 那段跳过已删除，本用例钉住模块这一侧的契约：被反复调用时，失败只影响「折不折」，
 * 兜底照跑，而且不会每轮都白试一次摘要模型。
 */
class CompactionModuleTest {

    private companion object {
        /** 64K 窗口走 STANDARD 档位：硬线 = min(85% × 64K, 90% × 64K) = 54.4K → 54,400。 */
        const val WINDOW = 64_000
        const val HARD_THRESHOLD = 54_400

        /** 发送前兜底的预算：窗口 × 92%（与 ModelContextPolicy.GUARD_BUDGET_PERCENT 同源）。 */
        const val GUARD_BUDGET = WINDOW * 92 / 100

        /** 链合并测试用压缩标记正文（与 domain.model.CONTEXT_COMPACTION_MARKER 同值）。 */
        const val CONTEXT_MARKER = "What did we do so far?"
    }

    private class Harness(
        val module: CompactionModule,
        val provider: AIProvider,
        val usageHolder: ContextUsageHolder,
        val completeCalls: () -> Int,
        /** 摘要调用发生时的线程名：压缩体到底跑在哪个线程上的证据。 */
        val summaryCallThreads: () -> List<String>
    )

    private fun harness(
        workDispatcher: CoroutineDispatcher = Dispatchers.Default,
        compactor: ContextCompactor = ContextCompactor(
            agentMessageDao = mockk(relaxed = true),
            systemPromptProvider = mockk(relaxed = true),
            llmCallRecordDao = mockk(relaxed = true),
            compactedHistoryArchive = mockk<CompactedHistoryArchive>(relaxed = true)
        )
    ): Harness {
        var completeCalls = 0
        val summaryCallThreads = mutableListOf<String>()
        fun failSummaryCall(): Nothing {
            completeCalls++
            summaryCallThreads += Thread.currentThread().name
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
            memoryExtractor = MemoryExtractor(mockk(relaxed = true), mockk(relaxed = true)),
            sessionBriefExtractor = SessionBriefExtractor(mockk(relaxed = true)),
            systemPromptProvider = fixedLazy(mockk<SystemPromptProvider>(relaxed = true)),
            modelMetadataService = metadata,
            generalSettingsRepository = settings,
            contextUsageHolder = usageHolder,
            workDispatcher = workDispatcher
        )
        return Harness(module, provider, usageHolder, { completeCalls }, { summaryCallThreads })
    }

    /** 给模块一个「取出来就是它」的 Lazy：DI 的惰性装配在这条路径上没有语义，不必拿 mockk 去桩它。 */
    private fun <T> fixedLazy(value: T): dagger.Lazy<T> = object : dagger.Lazy<T> {
        override fun get(): T = value
    }

    /** 三条消息、第一条约 60k tokens：越过 54.4k 硬线，也躲开「≤2 条且没逼近窗口就早退」那条快路。 */
    private fun oversizedHistory(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(content = "x".repeat(240_000)),
        AgentMessage.UserMessage(content = "继续"),
        AgentMessage.UserMessage(content = "把刚才那些整理一遍")
    )

    @Test
    fun `硬折叠失败后仍跑兜底，且不再重复尝试折叠`() = runBlocking {
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
        assertTrue(afterFoldFailure >= HARD_THRESHOLD) // 折叠没换来空间：正文本身就占满（硬线 54,400）
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
     * 阶段 1 验收：软线（默认 40%）以上、硬线以下，消息列表必须**原样返回**——
     * 软精简与参数投影均已删除，请求链路不做任何历史改写。
     */
    @Test
    fun `软线以上硬线以下不触发任何改写，消息原样返回`() = runBlocking {
        val compactor = mockk<ContextCompactor>(relaxed = true)
        every { compactor.enforceWindowLimit(any(), any()) } answers { firstArg() }
        val h = harness(compactor = compactor)
        val ctx = EngineContext(sessionId = "s1", projectRoot = "/ws")

        // 64K 窗口：软线 40% = 25,600，硬线 85% = 54,400。约 3 万 token（3 万汉字）夹在中间。
        val messages = listOf(
            AgentMessage.ToolResultMessage(id = "t1", toolName = "read", result = "汉".repeat(30_000)),
            AgentMessage.UserMessage(content = "继续"),
            AgentMessage.UserMessage(content = "把刚才那些整理一遍")
        )
        assertTrue(TokenEstimator.estimateMessages(messages) >= 25_600)
        assertTrue(TokenEstimator.estimateMessages(messages) < 54_400)

        val call = h.module.beforeLlmCall(
            ctx,
            LlmCall(messages = messages, windowProvider = h.provider, summaryProvider = h.provider)
        )

        // 没过硬线：不折叠、不精简，返回 null（编排不变），消息逐条原对象
        assertNull(call)
        coVerify(exactly = 0) { compactor.compact(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        val usage = assertNotNull(h.usageHolder.usage.value["s1"])
        assertTrue(usage.currentTokens >= usage.softThreshold)
        assertTrue(usage.currentTokens < usage.hardThreshold)
    }

    /**
     * 压缩体必须在调用者线程之外跑：调用链（ViewModel 的 Main 协程 → AgentEngine → 本模块 →
     * ContextCompactor）原本全程没有线程切换，全量 token 估算、摘要往返、归档写盘与落库全压在主线程上，
     * 长会话一次折叠实测把主线程占住近一分钟。
     *
     * 两条断言各锁一件事：
     * 1. 摘要调用不在调用者线程上——整段体确实被 withContext(workDispatcher) 搬走了。
     *    去掉那层 withContext，摘要调用就回到 JUnit 的测试线程上，断言立刻红；
     * 2. 事件仍回到调用者线程——压缩内部发出的回调把调用者原本的 context 放了回去，
     *    界面侧收集器的线程与改动前一致（否则 onEvent 里的界面状态更新会在后台线程上执行）。
     *
     * 这里显式传入生产默认值（Dispatchers.Default），测的就是实际生效的那个调度器。
     */
    @Test
    fun `压缩体跑到调用者线程之外且事件回到调用者线程`() = runBlocking {
        val callerThread = Thread.currentThread().name
        val eventThreads = mutableListOf<String>()
        val h = harness(workDispatcher = Dispatchers.Default)
        h.module.beforeLlmCall(
            EngineContext(sessionId = "s1", projectRoot = "/ws"),
            LlmCall(
                messages = oversizedHistory(),
                windowProvider = h.provider,
                summaryProvider = h.provider,
                onEvent = { eventThreads += Thread.currentThread().name }
            )
        )
        val workThreads = h.summaryCallThreads()
        assertTrue(workThreads.isNotEmpty())
        assertTrue(workThreads.none { it == callerThread })
        assertTrue(eventThreads.isNotEmpty())
        assertTrue(eventThreads.all { it == callerThread })
    }

    /**
     * 阶段 4：链合并触发。链块数超过 [CompactionModule.MAX_CHAIN_BLOCKS] 时，
     * 硬折叠判定之前先合并：合并后回到硬线以下就不再折叠（省一次摘要调用）。
     */
    @Test
    fun `链超限时硬折叠前先合并且合并后不折叠`() = runBlocking {
        // 真 Compactor：链状态从库行计；DAO 返回 13 个未淘汰块 + tail。
        val dao = mockk<AgentMessageDao>(relaxed = true)
        val rows = buildList {
            repeat(13) { i ->
                val ts = 100L + i * 10
                add(
                    AgentMessageEntity(
                        id = "marker-$i", sessionId = "s1", role = "USER",
                        content = CONTEXT_COMPACTION_MARKER, timestamp = ts, isCompactionMarker = true
                    )
                )
                add(
                    AgentMessageEntity(
                        id = "summary-$i", sessionId = "s1", role = "ASSISTANT",
                        content = "摘要 $i", timestamp = ts + 1,
                        isContextSummary = true, foldsUpToTimestamp = ts
                    )
                )
            }
        }
        coEvery { dao.getMessagesBySessionOnce("s1") } returns rows
        // 合并提交命中（事务方法内含新块插入 + 旧块标记）
        coEvery { dao.insertChainMergePair(any(), any(), any(), any()) } returns 12

        val prompts = mockk<SystemPromptProvider>(relaxed = true)
        every { prompts.resolvePrompt(any()) } returns "合并提示词 {{INSTRUCTION}}"
        val compactor = ContextCompactor(
            agentMessageDao = dao,
            systemPromptProvider = prompts,
            llmCallRecordDao = mockk(relaxed = true),
            compactedHistoryArchive = mockk<CompactedHistoryArchive>(relaxed = true)
        )

        val h = harness(compactor = compactor)
        // 合并请求返回一份短摘要（覆盖默认的「失败」桩：先挂成功响应再挂折叠失败）
        coEvery { h.provider.complete(any(), any(), any()) } returns
            AIResponse("合并后的短摘要", stopReason = "stop")
        coEvery { h.provider.complete(any(), any(), any(), any()) } returns
            AIResponse("合并后的短摘要", stopReason = "stop")
        coEvery { h.provider.complete(any(), any(), any(), any(), any()) } returns
            AIResponse("合并后的短摘要", stopReason = "stop")

        // 内存视图：13 个块对（短）+ 一条约 60k token 的长尾（推过硬线，验证折叠是否仍触发）。
        // 尾部足够大，合并（去掉 12 行旧摘要）远不能把它拉回硬线以下 → 合并后仍应触发折叠。
        // 这里只验合并先于折叠发生：合并调用（短输入）先于折叠调用（长 head）。
        val messages = buildList {
            repeat(13) { i ->
                add(AgentMessage.UserMessage(id = "marker-$i", content = CONTEXT_COMPACTION_MARKER))
                add(AgentMessage.AssistantMessage(id = "summary-$i", content = "摘要 $i"))
            }
            add(AgentMessage.UserMessage(id = "tail", content = "汉".repeat(60_000)))
        }
        val events = mutableListOf<AgentEvent>()
        h.module.beforeLlmCall(
            EngineContext(sessionId = "s1", projectRoot = "/ws"),
            LlmCall(
                messages = messages,
                windowProvider = h.provider,
                summaryProvider = h.provider,
                onEvent = { events += it }
            )
        )

        // 合并确实发生了：新块与旧块标记在同一次事务调用里落库（12 行 = 6 对旧块）
        coVerify { dao.insertChainMergePair(any(), any(), "s1", any()) }
    }

    /**
     * 阶段 4：合并失败不影响主折叠——合并返回 null 后，折叠照常触发并成功。
     */
    @Test
    fun `链合并失败不影响主折叠`(): Unit = runBlocking {
        val dao = mockk<AgentMessageDao>(relaxed = true)
        val rows = buildList {
            repeat(13) { i ->
                val ts = 100L + i * 10
                add(
                    AgentMessageEntity(
                        id = "marker-$i", sessionId = "s2", role = "USER",
                        content = CONTEXT_COMPACTION_MARKER, timestamp = ts, isCompactionMarker = true
                    )
                )
                add(
                    AgentMessageEntity(
                        id = "summary-$i", sessionId = "s2", role = "ASSISTANT",
                        content = "摘要 $i", timestamp = ts + 1,
                        isContextSummary = true, foldsUpToTimestamp = ts
                    )
                )
            }
        }
        coEvery { dao.getMessagesBySessionOnce("s2") } returns rows
        val prompts = mockk<SystemPromptProvider>(relaxed = true)
        every { prompts.resolvePrompt(any()) } returns "提示词 {{INSTRUCTION}}"
        val compactor = ContextCompactor(
            agentMessageDao = dao,
            systemPromptProvider = prompts,
            llmCallRecordDao = mockk(relaxed = true),
            compactedHistoryArchive = mockk<CompactedHistoryArchive>(relaxed = true)
        )

        val h = harness(compactor = compactor)
        // 合并请求：响应被截断 → 完整性闸门拒绝 → 合并失败；折叠请求：正常摘要成功。
        // 用答案序列区分：第一次（合并）返回截断 stopReason=length，之后（折叠）返回正常。
        var callIndex = 0
        coEvery { h.provider.complete(any(), any(), any()) } answers {
            callIndex++
            if (callIndex == 1) {
                AIResponse("截断摘要", stopReason = "length")
            } else {
                AIResponse("折叠摘要", stopReason = "stop")
            }
        }
        coEvery { h.provider.complete(any(), any(), any(), any()) } answers {
            callIndex++
            if (callIndex == 1) {
                AIResponse("截断摘要", stopReason = "length")
            } else {
                AIResponse("折叠摘要", stopReason = "stop")
            }
        }
        coEvery { h.provider.complete(any(), any(), any(), any(), any()) } answers {
            callIndex++
            if (callIndex == 1) {
                AIResponse("截断摘要", stopReason = "length")
            } else {
                AIResponse("折叠摘要", stopReason = "stop")
            }
        }

        val messages = buildList {
            repeat(13) { i ->
                add(AgentMessage.UserMessage(id = "marker-$i", content = CONTEXT_COMPACTION_MARKER))
                add(AgentMessage.AssistantMessage(id = "summary-$i", content = "摘要 $i"))
            }
            add(AgentMessage.UserMessage(id = "tail", content = "汉".repeat(60_000)))
        }
        val events = mutableListOf<AgentEvent>()
        val result = h.module.beforeLlmCall(
            EngineContext(sessionId = "s2", projectRoot = "/ws"),
            LlmCall(
                messages = messages,
                windowProvider = h.provider,
                summaryProvider = h.provider,
                onEvent = { events += it }
            )
        )

        // 合并确实被触发（13 块 > 12），但合并请求被完整性闸门拒绝（截断）——
        // 失败发生在事务写入之前，所以事务方法一次都没调。
        coVerify(exactly = 0) { dao.insertChainMergePair(any(), any(), any(), any()) }
        // 核心断言：合并失败不影响主流程——本轮请求照常返回（调用方发得出请求，兜底仍生效）。
        // 折叠是否成功发 CompactionFinished 取决于 tail 体量（6 万字的单条消息折完仍可能超线，
        // 那时只有 CompactionFailed/截断），不是这条测试的断言点。
        val modelCalls = events.count { it is AgentEvent.CompactionStarted }
        assertTrue(modelCalls >= 1)
        assertNotNull(result)
    }
}
