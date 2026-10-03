package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送前兜底（ContextCompactor.enforceWindowLimit）与硬压缩请求的输入形态。
 *
 * 兜底这一条是「单条消息本身就超窗」的唯一防线：压缩只能把老消息收进摘要，动不了单条消息本身，
 * 而 tail 至少要留一条——那一条常常就是一次巨大的工具输出或超长粘贴。ContextCompactor 的 KDoc
 * 对此写得很直白：「不兜底就只能让请求硬撞窗口上限」。本文件把它钉住：一条两万字符的消息喂进去，
 * 出来的必须还在预算量级上，并且只改喂模型的那一份投影（落库/UI 的原文一字不动）。
 *
 * 关于余量：[ContextCompactor.headTailTrim] 追加的「已省略中间 N 字符」标记本身也要算 token，
 * 但它是截断之后才拼上去的、没有计入 target，所以收尾时可能略超预算。同口径的容忍在
 * CompactionModuleTest 里已经用过（GUARD_BUDGET + 1_000）。断言按「预算 + 标记开销」写，
 * 若把兜底改成不动原消息（或整段返回），估算会停在两万量级，断言必红。
 *
 * 摘要请求那一条锁的是硬压缩自己的输入：head 原始消息 + 末尾一条压缩指令，且不带任何工具定义
 * （压缩请求不发送 tools）。把 tools 传成真实工具集、或把 tail 一起发出去，断言即红。
 */
class ContextCompactorWindowLimitTest {

    private companion object {
        /** 兜底预算：与 CompactionModule 的 92% 档位同一量级（真实值远大于它）。 */
        const val BUDGET = 1_000

        /**
         * 头尾截断标记的估算开销：`\n...[内容过长，已省略中间 N 字符]...\n` 约 30 个字符，
         * 与正文的 token 一起进估算，但没算进 target。留一倍余量防格式微调。
         */
        const val MARKER_OVERHEAD_TOKENS = 32

        /**
         * 大块参数换成摘录时的开销：摘录前言（哨兵 + 「原文共 N 字符…」+ 回读指针 + 禁令）
         * 约 150 字符，同样是拼接后才有的，与 target 无关。
         */
        const val EXCERPT_OVERHEAD_TOKENS = 256
    }

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private val compactor = ContextCompactor(
        agentMessageDao = dao,
        systemPromptProvider = prompts,
        llmCallRecordDao = records,
        compactedHistoryArchive = archive
    )

    // ---------- 兜底截断 ----------

    @Test
    fun `单条消息本身就超预算时仍被压回预算量级`() {
        val huge = AgentMessage.UserMessage(id = "u1", content = "汉".repeat(20_000))
        val before = TokenEstimator.estimateMessage(huge)
        assertTrue("用例前提：这条消息本身就远超预算", before > BUDGET)

        val result = compactor.enforceWindowLimit(listOf(huge), BUDGET)

        assertEquals(1, result.size)
        assertNotSame("超窗消息必须被改写，不能原样返回", huge, result[0])
        val after = TokenEstimator.estimateMessages(result)
        assertTrue("兜底后仍有 $after tokens（预算 $BUDGET）", after <= BUDGET + MARKER_OVERHEAD_TOKENS)

        // 截断是「保头保尾」而不是清空：两端逐字内容要还在，省略量要写明
        val text = (result[0] as AgentMessage.UserMessage).content
        assertTrue(text.startsWith("汉"))
        assertTrue(text.endsWith("汉"))
        assertTrue(text.contains("已省略中间"))
    }

    @Test
    fun `超预算的工具结果只压模型可见的那一份`() {
        val huge = AgentMessage.ToolResultMessage(
            id = "t1",
            toolName = "readFile",
            result = "汉".repeat(20_000)
        )
        assertTrue(TokenEstimator.estimateMessage(huge) > BUDGET)

        val result = compactor.enforceWindowLimit(listOf(huge), BUDGET)

        val out = result[0] as AgentMessage.ToolResultMessage
        // 落库 / UI / 工具执行读的都是 result，必须一字不动
        assertEquals(huge.result, out.result)
        val modelFacing = out.modelResult
        assertNotNull("喂模型的那一份应当被截断后写进 modelResult", modelFacing)
        assertTrue((modelFacing as String).length < huge.result.length)
        assertTrue(TokenEstimator.estimateMessages(result) <= BUDGET + MARKER_OVERHEAD_TOKENS)
    }

    @Test
    fun `超预算的工具参数换成摘录且 arguments 保持原文`() {
        val content = JsonPrimitive("汉".repeat(20_000))
        val call = ToolCall(
            id = "call-1",
            name = "writeFile",
            arguments = mapOf("path" to JsonPrimitive("a.txt"), "content" to content)
        )
        val huge = AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call))
        assertTrue(TokenEstimator.estimateMessage(huge) > BUDGET)

        val result = compactor.enforceWindowLimit(listOf(huge), BUDGET)

        val out = result[0] as AgentMessage.AssistantMessage
        assertEquals("参数原文必须一字不动", call.arguments, out.toolCalls[0].arguments)
        val projection = out.toolCalls[0].modelArguments
        assertNotNull("喂模型的那一份应当换成摘录", projection)
        assertTrue((projection as Map<*, *>).isNotEmpty())
        val after = TokenEstimator.estimateMessages(result)
        assertTrue("兜底后仍有 $after tokens（预算 $BUDGET）", after <= BUDGET + EXCERPT_OVERHEAD_TOKENS)
    }

    @Test
    fun `未超预算时原样返回同一个列表`() {
        val messages = listOf(AgentMessage.UserMessage(id = "u1", content = "汉".repeat(10)))
        assertTrue(TokenEstimator.estimateMessages(messages) <= BUDGET)
        assertSame(messages, compactor.enforceWindowLimit(messages, BUDGET))
    }

    @Test
    fun `预算非正或列表为空时原样返回`() {
        val messages = listOf(AgentMessage.UserMessage(id = "u1", content = "汉".repeat(20_000)))
        assertSame(messages, compactor.enforceWindowLimit(messages, 0))
        val empty = emptyList<AgentMessage>()
        assertSame(empty, compactor.enforceWindowLimit(empty, BUDGET))
    }

    @Test
    fun `低于单条下限的消息不削，宁可让它超窗`() {
        // MIN_MESSAGE_TOKENS = 64：再短就没法干活，KDoc 明确写了「宁可让它超窗」
        val messages = listOf(AgentMessage.UserMessage(id = "u1", content = "汉".repeat(50)))
        assertTrue(TokenEstimator.estimateMessages(messages) > 10)
        assertSame(messages, compactor.enforceWindowLimit(messages, 10))
    }

    // ---------- 摘要请求的输入形态 ----------

    private val sentSystem = slot<String>()
    private val sentMessages = slot<List<AgentMessage>>()
    private val sentTools = slot<List<AgentTool>>()

    /**
     * 摘要调用的桩：三个实参个数都挂上同一个响应。
     *
     * AIProvider.complete 有默认参数，mockk 按调用点解析后的实参列表匹配（CompactionModuleTest
     * 里记了这条），所以 3 / 4 / 5 个实参的形态都注册；捕获用的是同一组 slot，无论命中哪种形态都能拿到入参。
     */
    private fun stubSummary(response: AIResponse) {
        coEvery { provider.complete(capture(sentSystem), capture(sentMessages), capture(sentTools), any(), any()) } returns response
        coEvery { provider.complete(capture(sentSystem), capture(sentMessages), capture(sentTools), any()) } returns response
        coEvery { provider.complete(capture(sentSystem), capture(sentMessages), capture(sentTools)) } returns response
    }

    /** 被折叠的 head（两条撑体积的汉字消息 + 一条助手回复）与保留区里的一条用户消息。 */
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

    @Test
    fun `摘要请求的输入是 head 加一条指令且不带工具`() = runTest {
        val template = "你是一个上下文压缩引擎。{{INSTRUCTION}}"
        every { prompts.resolvePrompt(any()) } returns template
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        coEvery { dao.insert(any()) } just Runs
        coEvery { dao.getMessagesBySessionOnce("s") } returns rows("a", "b", "c", "goal")
        stubSummary(AIResponse("接手摘要", stopReason = "stop"))

        val result = compactor.compact(
            messages = history(),
            summaryProvider = provider,
            sessionId = "s",
            preserveRecentTokens = 1,
            summaryWindowTokens = 100_000,
            hardThreshold = 40_000,
            contextLimit = 100_000
        )

        // 压缩本身成功：marker + 摘要 + 保留区
        assertNotNull(result)
        assertEquals(3, result!!.size)

        val sent = sentMessages.captured
        // head 三条原文 + 末尾一条压缩指令；保留区的「继续」不在这份请求里
        assertEquals(4, sent.size)
        assertEquals(
            listOf("汉".repeat(600), "done", "汉".repeat(590)),
            sent.dropLast(1).map { it.text() }
        )
        val instruction = sent.last()
        assertTrue("末尾必须是一条用户消息形态的压缩指令", instruction is AgentMessage.UserMessage)
        assertEquals(
            template.replace("{{INSTRUCTION}}", "请根据下面的对话历史创建一个新的锚定摘要。"),
            (instruction as AgentMessage.UserMessage).content
        )
        // 压缩请求不带任何工具定义
        assertTrue(sentTools.captured.isEmpty())
        assertTrue(sentSystem.captured.isNotBlank())
    }

    private fun AgentMessage.text(): String = when (this) {
        is AgentMessage.UserMessage -> content
        is AgentMessage.AssistantMessage -> content
        is AgentMessage.ToolResultMessage -> result
    }
}
