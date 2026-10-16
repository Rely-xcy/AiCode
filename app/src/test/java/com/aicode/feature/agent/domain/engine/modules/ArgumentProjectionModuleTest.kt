package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数指针化模块（上下文压缩重设计 §9 阶段 0）的行为钉子。
 *
 * 核心契约：纯函数投影——只改本轮请求的内存副本，`arguments` 原文不落库、不持久化，
 * 同一输入永远得到同一输出（确定性 → 缓存友好）。
 */
class ArgumentProjectionModuleTest {

    private val module = ArgumentProjectionModule()
    private val ctx = EngineContext(sessionId = "s1", projectRoot = "/ws")

    private suspend fun apply(messages: List<AgentMessage>): LlmCall? =
        module.beforeLlmCall(ctx, LlmCall(messages = messages))

    @Test
    fun `超阈值大字段被换成摘录，原文不动`() = runBlockingTest {
        val original = "a".repeat(20_000)
        val call = ToolCall(id = "t1", name = "writeFile", arguments = mapOf("path" to JsonPrimitive("src/A.kt"), "content" to JsonPrimitive(original)))
        val message = AgentMessage.AssistantMessage(id = "m1", content = "", toolCalls = listOf(call))

        val result = apply(listOf(message))

        assertTrue(result != null)
        val projectedCall = (result!!.messages[0] as AgentMessage.AssistantMessage).toolCalls[0]
        // 摘录形态：首行哨兵 + 头尾逐字 + 省略标记；显著短于原文
        val excerpt = (projectedCall.arguments["content"] as JsonPrimitive).content
        assertTrue(excerpt.startsWith("[AiCode 上下文摘录·非完整正文]"))
        assertTrue(excerpt.contains("…[AiCode 摘录省略"))
        assertTrue(excerpt.contains("readFile 读回 src/A.kt"))
        assertTrue(excerpt.length < original.length / 2)
        // 小字段（路径）原样保留
        assertEquals("src/A.kt", (projectedCall.arguments["path"] as JsonPrimitive).content)
        // 原文对象未被改写：投影只存在于返回的消息副本里
        assertEquals(original, (message.toolCalls[0].arguments["content"] as JsonPrimitive).content)
    }

    @Test
    fun `阈值以下与无工具调用的消息不动，未改动时返回 null`() = runBlockingTest {
        val small = AgentMessage.AssistantMessage(
            id = "m1", content = "",
            toolCalls = listOf(ToolCall(id = "t1", name = "writeFile", arguments = mapOf("content" to JsonPrimitive("x".repeat(8_000)))))
        )
        val plain = AgentMessage.UserMessage(content = "你好")
        assertNull(apply(listOf(small, plain)))
        assertNull(apply(listOf(plain)))
    }

    @Test
    fun `投影确定性：同一输入两次结果逐字节相同`() = runBlockingTest {
        val original = "b".repeat(30_000)
        fun message() = AgentMessage.AssistantMessage(
            id = "m1", content = "",
            toolCalls = listOf(ToolCall(id = "t1", name = "writeFile", arguments = mapOf("path" to JsonPrimitive("a.txt"), "new_string" to JsonPrimitive(original))))
        )
        val first = apply(listOf(message()))!!
        val second = apply(listOf(message()))!!
        assertEquals(first.messages, second.messages)
    }

    @Test
    fun `刚过阈值的单行长文也能摘录且显著变短`() = runBlockingTest {
        // 8_001 字符单行长文：摘录预算 1_600（max(400, len/4) 与 EXCERPT_MAX_CHARS 取小），
        // 摘录约 1.7K 字符、显著短于原文 → 正常产出摘录，不会退回原值也不会出占位说明。
        val marginal = "行".repeat(8_001)
        val message = AgentMessage.AssistantMessage(
            id = "m1", content = "",
            toolCalls = listOf(ToolCall(id = "t1", name = "writeFile", arguments = mapOf("content" to JsonPrimitive(marginal))))
        )
        val result = apply(listOf(message))
        assertTrue(result != null)
        val value = ((result!!.messages[0] as AgentMessage.AssistantMessage).toolCalls[0].arguments["content"] as JsonPrimitive).content
        assertTrue(value.startsWith("[AiCode 上下文摘录·非完整正文]"))
        assertTrue(value.length < marginal.length / 2)
        assertFalse(value.contains("[已省略"))
    }

    @Test
    fun `记账日志口径：节省 token 等于原消息与投影消息的估算差`() = runBlockingTest {
        val original = "c".repeat(20_000)
        val message = AgentMessage.AssistantMessage(
            id = "m1", content = "",
            toolCalls = listOf(ToolCall(id = "t1", name = "writeFile", arguments = mapOf("content" to JsonPrimitive(original))))
        )
        val result = apply(listOf(message))!!
        val projected = result.messages[0] as AgentMessage.AssistantMessage
        val saved = TokenEstimator.estimateMessage(message) - TokenEstimator.estimateMessage(projected)
        assertTrue(saved > 0)
        assertEquals(projected.toolCalls[0].arguments.size, message.toolCalls[0].arguments.size)
    }

    private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
