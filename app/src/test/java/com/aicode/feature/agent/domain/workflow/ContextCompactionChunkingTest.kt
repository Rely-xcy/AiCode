package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.tool.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块摘要：
 *
 * 1. 纯切分函数——head 正好等于预算时保持单块，超过时按顺序切块，tool 结果不会被切到
 *    新块开头，块数超过 [MAX_SUMMARY_CHUNKS] 时只保留最旧的块；
 * 2. 折叠路径——逐块摘要 + 合并后仍只落一条摘要行；
 * 3. 失败语义——任一块调用失败时整次折叠返回 null，不落库、不归档。
 *
 * 这里只验证「调用次数与落库形状」，不验证模型输出质量；分块本身的成本（N+1 次调用）
 * 只能靠真机日志确认。
 */
class ContextCompactionChunkingTest {

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>()
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private val compactor = ContextCompactor(dao, prompts, records, archive)

    private fun prepare() {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { prompts.resolvePrompt(any()) } returns "提示词 {{INSTRUCTION}}"
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
    }

    private fun rows(vararg ids: String): List<AgentMessageEntity> = ids.map { id ->
        AgentMessageEntity(
            id = id,
            sessionId = "s",
            role = "USER",
            content = "row-$id",
            timestamp = 500L
        )
    }

    private fun history(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "a", content = "汉".repeat(600)),
        AgentMessage.AssistantMessage(id = "b", content = "done"),
        AgentMessage.UserMessage(id = "c", content = "汉".repeat(590)),
        AgentMessage.UserMessage(id = "goal", content = "继续")
    )

    /** 带工具调用的 head：用于验证文件账本进入摘要请求。 */
    private fun historyWithTools(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "a", content = "汉".repeat(600)),
        AgentMessage.AssistantMessage(
            id = "b",
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "call-1",
                    name = "readFile",
                    arguments = mapOf("path" to JsonPrimitive("~/workspace/A.kt"))
                )
            )
        ),
        AgentMessage.UserMessage(id = "c", content = "汉".repeat(590)),
        AgentMessage.UserMessage(id = "goal", content = "继续")
    )

    /**
     * 按顺序返回给定响应，并在第 [failAt] 次调用时抛异常。
     * 3 / 4 / 5 个实参的桩都挂上，匹配 mockk 对默认参数的解析形态。
     */
    private fun stubSequential(
        responses: List<String>,
        failAt: Int = -1,
        onRequest: (List<AgentMessage>) -> Unit = {}
    ): () -> Int {
        var calls = 0
        val answer: (List<AgentMessage>) -> AIResponse = { request ->
            calls++
            onRequest(request)
            if (calls == failAt) throw IllegalStateException("chunk failed")
            AIResponse(responses.getOrElse(calls - 1) { responses.last() }, stopReason = "stop")
        }
        coEvery { provider.complete(any(), any(), any()) } answers {
            answer(secondArg<List<AgentMessage>>())
        }
        coEvery { provider.complete(any(), any(), any(), any()) } answers {
            answer(secondArg<List<AgentMessage>>())
        }
        coEvery { provider.complete(any(), any(), any(), any(), any()) } answers {
            answer(secondArg<List<AgentMessage>>())
        }
        return { calls }
    }

    private suspend fun compact(messages: List<AgentMessage> = history()) = compactor.compact(
        messages = messages,
        summaryProvider = provider,
        sessionId = "s",
        preserveRecentTokens = 1,
        summaryWindowTokens = 400,
        hardThreshold = 40_000,
        contextLimit = 100_000
    )

    // ---------- 纯切分 ----------

    @Test
    fun `head 正好等于预算时保持单块`() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "汉".repeat(40)),
            AgentMessage.UserMessage(content = "汉".repeat(60))
        )
        val budget = TokenEstimator.estimateMessages(messages)

        val chunks = splitIntoSummaryChunks(messages, budget)

        assertEquals(1, chunks.size)
        assertEquals(2, chunks.single().size)
    }

    @Test
    fun `head 超过预算时按顺序切块`() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "汉".repeat(40)),
            AgentMessage.UserMessage(content = "汉".repeat(60))
        )
        val budget = TokenEstimator.estimateMessage(messages[0]) +
            TokenEstimator.estimateMessage(messages[1]) / 2

        val chunks = splitIntoSummaryChunks(messages, budget)

        assertEquals(2, chunks.size)
        assertEquals(
            listOf("汉".repeat(40), "汉".repeat(60)),
            chunks.flatMap { chunk -> chunk.map { it.text() } }
        )
    }

    @Test
    fun `tool 结果不会被切到新块开头`() {
        val call = ToolCall(
            id = "call-1",
            name = "readFile",
            arguments = mapOf("path" to JsonPrimitive("~/workspace/A.kt"))
        )
        val messages = listOf(
            AgentMessage.UserMessage(content = "汉".repeat(50)),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call)),
            AgentMessage.ToolResultMessage(
                id = "call-1",
                toolName = "readFile",
                result = "汉".repeat(50)
            ),
            AgentMessage.UserMessage(content = "汉".repeat(50))
        )
        val budget = TokenEstimator.estimateMessage(messages[0]) +
            TokenEstimator.estimateMessage(messages[1])

        val chunks = splitIntoSummaryChunks(messages, budget)

        assertTrue(chunks.none { it.first() is AgentMessage.ToolResultMessage })
        assertEquals(3, chunks.first().size)
    }

    @Test
    fun `块数超过上限时只保留最旧的块`() {
        val messages = List(20) { AgentMessage.UserMessage(content = "汉".repeat(10)) }
        val budget = TokenEstimator.estimateMessage(messages.first())

        val chunks = splitIntoSummaryChunks(messages, budget, maxChunks = 3)

        assertEquals(3, chunks.size)
        assertEquals(3, chunks.sumOf { it.size })
    }

    // ---------- 折叠路径 ----------

    @Test
    fun `块数超过上限时只折最旧的块，其余原文留在保留区`() = runBlocking {
        prepare()
        val messages = List(10) { index ->
            AgentMessage.UserMessage(id = "m$index", content = "汉".repeat(100))
        } + AgentMessage.UserMessage(id = "goal", content = "继续")
        coEvery { dao.getMessagesBySessionOnce("s") } returns
            rows("m0", "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "goal")
        val callCount = stubSequential(List(9) { "摘要${it + 1}" })

        val result = compactor.compact(
            messages = messages,
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = 1,
            summaryWindowTokens = 200,
            hardThreshold = 40_000,
            contextLimit = 100_000
        )

        assertNotNull(result)
        // 10 条 head 切成 10 块，上限 8：只折最旧 8 条，m8/m9 原样留在 tail。
        assertEquals(9, callCount())
        val tailContents = result!!.drop(2).filterIsInstance<AgentMessage.UserMessage>()
            .map { it.content }
        assertTrue(tailContents.contains("汉".repeat(100)))
        // m8/m9 + goal 三条原文都在 tail，说明切点确实收回到第 8 块末尾。
        assertEquals(3, tailContents.size)
    }

    @Test
    fun `分块摘要最终只落一条摘要行`() = runBlocking {
        prepare()
        val callCount = stubSequential(listOf("块1", "块2", "块3", "合并摘要"))

        val result = compact()

        assertNotNull(result)
        // marker + 摘要 + tail（goal）
        assertEquals(3, result!!.size)
        assertTrue((result[1] as AgentMessage.AssistantMessage).content.contains("合并摘要"))
        // 3 块 + 1 次合并
        assertEquals(4, callCount())
        coVerify(exactly = 1) { dao.insertCompactionPair(any(), any()) }
    }

    @Test
    fun `任一块失败时整次折叠不落库`() = runBlocking {
        prepare()
        val callCount = stubSequential(
            responses = listOf("块1", "块2", "块3", "合并摘要"),
            failAt = 2
        )

        val result = compact()

        assertNull(result)
        assertEquals(2, callCount())
        coVerify(exactly = 0) { dao.insertCompactionPair(any(), any()) }
        verify(exactly = 0) { archive.archive(any(), any(), any()) }
    }

    @Test
    fun `文件账本进入摘要请求并追加到摘要末尾`() = runBlocking {
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        stubSequential(
            responses = listOf("块1", "块2", "块3", "合并摘要"),
            onRequest = { requests += it }
        )

        val result = compact(historyWithTools())

        assertNotNull(result)
        val summary = (result!![1] as AgentMessage.AssistantMessage).content
        assertTrue("摘要末尾应有固定小节", summary.contains(FILE_LEDGER_HEADING))
        assertTrue("账本应带上读取过的文件路径", summary.contains("~/workspace/A.kt"))
        assertTrue(
            "摘要请求里也应带账本（结构化附加输入）",
            requests.any { request ->
                request.lastOrNull()?.let { it.text().contains("~/workspace/A.kt") } == true
            }
        )
    }

    /** 分块折叠一次要跑 N 轮摘要 + 1 次合并：每段开始前上报「第几/共几」，界面才不是干等。 */
    @Test
    fun `分块摘要按块与合并上报进度`() = runBlocking {
        prepare()
        stubSequential(listOf("块1", "块2", "块3", "合并摘要"))
        val events = mutableListOf<AgentEvent>()

        val result = compactor.compact(
            messages = history(),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = 1,
            summaryWindowTokens = 400,
            hardThreshold = 40_000,
            contextLimit = 100_000,
            onEvent = { events += it }
        )

        assertNotNull(result)
        val progress = events.filterIsInstance<AgentEvent.CompactionProgressChanged>()
        // 3 块 + 1 次合并，从 1 数到 4，总步数始终为 4。
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), progress.map { it.step to it.totalSteps })
    }

    /** 只有一次摘要调用时也报一次进度：总步数为 1，界面显示「1/1」。 */
    @Test
    fun `单块折叠上报一步`() = runBlocking {
        prepare()
        stubSequential(listOf("单块摘要"))
        val events = mutableListOf<AgentEvent>()

        val result = compactor.compact(
            messages = listOf(
                AgentMessage.UserMessage(id = "a", content = "汉".repeat(600)),
                AgentMessage.AssistantMessage(id = "b", content = "done"),
                AgentMessage.UserMessage(id = "goal", content = "继续")
            ),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = 1,
            summaryWindowTokens = 100_000,
            hardThreshold = 40_000,
            contextLimit = 100_000,
            onEvent = { events += it }
        )

        assertNotNull(result)
        val progress = events.filterIsInstance<AgentEvent.CompactionProgressChanged>()
        assertEquals(listOf(1 to 1), progress.map { it.step to it.totalSteps })
    }

    private fun AgentMessage.text(): String = when (this) {
        is AgentMessage.UserMessage -> content
        is AgentMessage.AssistantMessage -> content
        is AgentMessage.ToolResultMessage -> result
    }
}
