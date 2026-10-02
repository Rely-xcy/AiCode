package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.tool.ToolCall
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠提交前的闸门（全部只算已有数据，不发额外模型请求）与提交时写的归属：
 *
 * 1. 摘要响应完整性——空 / 被截断 / 被中止 / 带工具调用，一律不落库（半份摘要换掉整段历史是负收益）；
 * 2. 「真的更小了」——折叠后的估值必须小于折叠前，否则不提交（白花一次调用还换来更差的历史）；
 * 3. 提交时把 `compactedBySummaryId` 写成本次摘要行 id，回退恢复靠它把原文找回来。
 *
 * 每个「不落库」用例都同时断言 `records.insert` 恰好一次：证明摘要调用**成功返回过**、
 * 我们是在拿到完整响应之后主动拒绝的，而不是调用抛异常导致的提前返回。
 */
class ContextCompactionCommitTest {

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>()
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private val compactor = ContextCompactor(dao, prompts, records, archive)

    private val inserted = slot<AgentMessageEntity>()

    private fun prepare() {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { prompts.resolvePrompt(any()) } returns "你是一个上下文压缩引擎。{{INSTRUCTION}}"
        coEvery { dao.insert(capture(inserted)) } just Runs
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
    }

    /**
     * `AIProvider.complete` 有默认参数（tools / reasoningEffort / cacheTail），mockk 按调用点
     * **解析后的实参列表**匹配，所以各实参个数都挂上同一个响应：实参个数对不上时 mockk 会抛
     * 「no answer found」，那就变成「调用直接异常」——正是下面这些用例要排除的假通过路径
     * （每条「不落库」用例都另断言 records.insert 恰好一次，钉住「摘要调用成功返回过」）。
     */
    private fun stubSummary(response: AIResponse) {
        coEvery { provider.complete(any(), any(), any()) } returns response
        coEvery { provider.complete(any(), any(), any(), any()) } returns response
        coEvery { provider.complete(any(), any(), any(), any(), any()) } returns response
    }

    /** head 三条（靠前两条汉字撑出体积）+ 一条尾部用户消息：尾部决定锚点，head 决定被折叠的范围。 */
    private fun history(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "a", content = "汉".repeat(600)),
        AgentMessage.AssistantMessage(id = "b", content = "done"),
        AgentMessage.UserMessage(id = "c", content = "汉".repeat(590)),
        AgentMessage.UserMessage(id = "goal", content = "继续")
    )

    private fun rows(vararg ids: String): List<AgentMessageEntity> = ids.map { id ->
        AgentMessageEntity(
            id = id,
            sessionId = "s",
            role = "USER",
            content = "row-$id",
            timestamp = 500L
        )
    }

    private suspend fun compact(messages: List<AgentMessage>) = compactor.compact(
        messages = messages,
        summaryProvider = provider,
        sessionId = "s",
        preserveRecentTokens = 1,
        summaryWindowTokens = 100_000,
        // 记账日志要用（不参与任何判断），取一组普通值即可
        hardThreshold = 40_000,
        contextLimit = 100_000
    )

    // ---------- 闸门一：响应完整性 ----------

    @Test
    fun `摘要为空时不落库也不标记 head`() = runTest {
        prepare()
        stubSummary(AIResponse(""))

        assertNull(compact(history()))
        coVerify(exactly = 1) { records.insert(any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.markMessagesCompactedBeforeTimestamp(any(), any(), any()) }
    }

    @Test
    fun `摘要被输出上限截断时不落库`() = runTest {
        prepare()
        stubSummary(AIResponse("半份摘要", stopReason = "max_tokens"))

        assertNull(compact(history()))
        coVerify(exactly = 1) { records.insert(any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `摘要被服务端中止时不落库`() = runTest {
        prepare()
        stubSummary(AIResponse("残缺", stopReason = "model_context_window_exceeded"))

        assertNull(compact(history()))
        coVerify(exactly = 1) { records.insert(any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `摘要响应里带工具调用时不落库`() = runTest {
        prepare()
        stubSummary(
            AIResponse(
                content = "想先读个文件",
                toolCalls = listOf(ToolCall(id = "t1", name = "readFile", arguments = emptyMap()))
            )
        )

        assertNull(compact(history()))
        coVerify(exactly = 1) { records.insert(any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    // ---------- 闸门二：必须真的更小 ----------

    @Test
    fun `摘要比被折叠的历史还长时不提交`() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "goal")
        stubSummary(AIResponse("汉".repeat(5_000)))
        val tinyHead = listOf(
            AgentMessage.UserMessage(id = "a", content = "hi"),
            AgentMessage.UserMessage(id = "goal", content = "继续")
        )

        assertNull(compact(tinyHead))
        coVerify(exactly = 1) { records.insert(any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    // ---------- 闸门三：提交时写归属 ----------

    @Test
    fun `提交时把摘要行 id 写成被折叠行的归属`() = runTest {
        prepare()
        stubSummary(AIResponse("Concise handoff", stopReason = "stop"))

        val result = compact(history())

        assertNotNull(result)
        // marker + 摘要 + 尾部
        assertEquals(3, result!!.size)
        assertTrue(result[1] is AgentMessage.AssistantMessage)
        assertTrue((result[1] as AgentMessage.AssistantMessage).content.contains("Concise handoff"))
        coVerify(exactly = 2) { dao.insert(any()) }
        // 最后一次插入的是摘要行；被折叠的 head 必须把它的 id 写成归属，回退恢复才有据可依
        coVerify { dao.markMessagesCompactedBeforeTimestamp("s", any(), inserted.captured.id) }
    }
}
