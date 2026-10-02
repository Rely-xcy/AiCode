package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * 折叠的两半：材料投影（纯函数，直接测）与 [ContextCompactor.compact] 的落库契约。
 *
 * 投影那半边钉住「喂给摘要模型的到底是什么」：媒体与思考快照必须剥掉（占地方又不能被摘要成
 * 文字），工具输出取喂模型的那份（modelResult），超长只留头尾并写明省略。落库那半边钉住两条
 * 路径的选择与失败语义：head 有稳定 id 时一次事务提交（标 head + 插 marker/摘要 + 清旧 usage），
 * 缺 id 时退回时间戳路径；摘要模型给了不完整响应、事务失败一律不落库并保留原历史。
 */
class ContextCompactorTest {

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>()
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private val compactor = ContextCompactor(dao, prompts, records, persistence, archive)

    private companion object {
        /** 摘要模型窗口：够小，好让材料必然被切成多块（块间要融合上一块的摘要）。 */
        const val SUMMARY_WINDOW = 1_000

        /** 折叠前的真正文预算：给 1 都行，只要它小于最后一条消息的体积。 */
        const val PRESERVE_RECENT = 1
    }

    private fun prepare() {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        coEvery { provider.complete(any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
    }

    /** head 里的三条 + 一条尾部用户消息：尾部决定锚点，head 决定被折叠的范围。 */
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

    // ---------- 材料投影：纯函数 ----------

    @Test
    fun `工具结果投影取 modelResult 并保留头尾`() {
        val message = AgentMessage.ToolResultMessage(
            id = "call",
            toolName = "read",
            result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END"
        )

        val text = CompactionText.project(message)

        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        // 喂模型的只有 modelResult：result 是落库/界面那份，不该出现在摘要材料里
        assertFalse(text.contains("not sent to model"))
        // 中间省略了多少要写出来，否则模型会以为文件真的短了一截
        assertTrue(text.contains("characters"))
    }

    @Test
    fun `投影剥掉媒体与思考快照`() {
        val image = AgentImage("image/png", "secret-media")
        val messages = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(
                content = "answer",
                reasoning = "private-thinking",
                signature = "signature",
                thinkingBlocksJson = "snapshot",
                images = listOf(image)
            ),
            AgentMessage.ToolResultMessage(
                toolName = "image",
                result = """{"images":[{"base64Data":"secret-media"}],"value":"kept"}""",
                images = listOf(image)
            )
        )

        val text = messages.joinToString { CompactionText.project(it) }

        assertFalse(text.contains("secret-media"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("private-thinking"))
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("snapshot"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun `用户消息投影带上模式提醒`() {
        val message = AgentMessage.UserMessage(id = "u", content = "继续", modelReminder = "【模式提醒】别问直接做")

        val text = CompactionText.project(message)

        assertTrue(text.contains("继续"))
        // 提醒是模型真正看到的那部分输入，接手摘要必须能覆盖它
        assertTrue(text.contains("别问直接做"))
    }

    @Test
    fun `一个单元包含紧跟其后的工具结果`() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(
                content = "",
                toolCalls = listOf(ToolCall("one", "read", emptyMap()))
            ),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one")
        )

        val units = CompactionText.units(messages)

        // 助手消息与它的工具结果同属一个单元，分开切会让模型看到一段无主的输出
        assertEquals(2, units.size)
        assertTrue(units[1].contains("one"))
        assertTrue(units[1].contains("tool-result"))
    }

    @Test
    fun `游标不丢内容且不超预算`() {
        val source = "汉".repeat(1_000)
        val cursor = CompactionText.Cursor(listOf(source, "LAST"))

        val chunks = mutableListOf<String>()
        repeat(64) { if (!cursor.finished) chunks.add(cursor.next(100)) }

        assertTrue(cursor.finished)
        // 任何字符都只被摘一次：切块的偏移标记不能变成「重复计数」或「丢失」
        assertEquals(1_000, chunks.sumOf { chunk -> chunk.count { it == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
    }

    // ---------- compact()：落库契约 ----------

    @Test
    fun `head 有稳定 id 时按事务提交并失效历史缓存`() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
        val requests = mutableListOf<List<AgentMessage>>()
        coEvery { provider.complete(any(), capture(requests), any()) } returns AIResponse("Concise handoff", stopReason = "stop")

        val original = history()
        val result = assertNotNull(
            compactor.compact(
                messages = original,
                summaryProvider = provider,
                sessionId = "s",
                preserveRecentTokens = PRESERVE_RECENT,
                summaryWindowTokens = SUMMARY_WINDOW
            )
        )

        assertEquals(CONTEXT_COMPACTION_MARKER, (result[0] as AgentMessage.UserMessage).content)
        assertTrue((result[1] as AgentMessage.AssistantMessage).content.contains("Concise handoff"))
        assertSame(original.last(), result.last())
        // 一次事务里标 head（带摘要归属）+ 插 marker/摘要 + 清旧 usage
        coVerify(exactly = 1) { dao.commitCompaction("s", listOf("a", "b", "c"), any(), any()) }
        coVerify(exactly = 1) { persistence.invalidateHistory("s") }
        // 需要摘要多块时，后一块会把前一块的摘要带回去，跨块事实才合得起来
        assertTrue(requests.size >= 2)
        assertTrue(requests.all { it.size == 1 && it[0] is AgentMessage.UserMessage })
        assertTrue(requests.all { (it[0] as AgentMessage.UserMessage).content.contains("<history-material") })
        assertTrue((requests.last()[0] as AgentMessage.UserMessage).content.contains("<previous-summary>"))
    }

    @Test
    fun `head 缺稳定 id 时退回时间戳路径`() = runTest {
        prepare()
        // 库里的行 id 与内存态对不上：只能靠内容匹配找到锚点
        coEvery { dao.getMessagesBySessionOnce("s") } returns listOf(
            AgentMessageEntity(id = "row-x", sessionId = "s", role = "USER", content = "继续", timestamp = 500L)
        )

        val result = compactor.compact(
            messages = history(),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = PRESERVE_RECENT,
            summaryWindowTokens = SUMMARY_WINDOW
        )

        assertNotNull(result)
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        coVerify(exactly = 2) { dao.insert(any()) }
        coVerify(exactly = 1) { dao.markMessagesCompactedBeforeTimestamp("s", any()) }
        coVerify(exactly = 1) { persistence.invalidateHistory("s") }
    }

    @Test
    fun `摘要响应不完整时不落库且记失败调用`() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
        for (response in listOf(AIResponse(""), AIResponse("partial", stopReason = "length"))) {
            coEvery { provider.complete(any(), any(), any()) } returns response
            val original = history()

            val result = compactor.compact(
                messages = original,
                summaryProvider = provider,
                sessionId = "s",
                preserveRecentTokens = PRESERVE_RECENT,
                summaryWindowTokens = SUMMARY_WINDOW
            )

            assertNull(result)
        }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(atLeast = 1) { records.insert(match { it.status == "error" }) }
    }

    @Test
    fun `事务失败时保留原历史`() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")

        val result = compactor.compact(
            messages = history(),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = PRESERVE_RECENT,
            summaryWindowTokens = SUMMARY_WINDOW
        )

        assertNull(result)
        // 事务没落地就谈不上「历史已变」，不能顺手把缓存清了
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun `找不到锚点时不落库`() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("s") } returns emptyList()

        val result = compactor.compact(
            messages = history(),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = PRESERVE_RECENT,
            summaryWindowTokens = SUMMARY_WINDOW
        )

        assertNull(result)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun `取消会向外传播且留下失败调用记录`() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any()) } throws CancellationException("cancelled")

        try {
            compactor.compact(
                messages = history(),
                summaryProvider = provider,
                sessionId = "s",
                preserveRecentTokens = PRESERVE_RECENT,
                summaryWindowTokens = SUMMARY_WINDOW
            )
            throw AssertionError("取消必须向外传播")
        } catch (_: CancellationException) {
            coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
        }
    }
}
