package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 整段消息估算缓存：只许省计算，不许改数。
 *
 * 三条底线各有一个用例：
 * 1. 同一份列表复用旧值（省下来的就是重复估算）；
 * 2. 列表变了必须重算（漏重算会让压缩判定拿旧值做决定）；
 * 3. 同一份列表的缓存值与当场估算逐值相等（误差 0）。
 */
class MessageEstimateCacheTest {

    private fun history(): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "u1", content = "先把上下文压缩的现状读一遍"),
        AgentMessage.AssistantMessage(id = "a1", content = "读完了，判定在 CompactionModule"),
        AgentMessage.UserMessage(id = "u2", content = "继续")
    )

    @Test
    fun `同一份列表第二次直接复用缓存`() {
        var calls = 0
        val cache = MessageEstimateCache { messages ->
            calls++
            TokenEstimator.estimateMessages(messages)
        }
        val messages = history()

        val first = cache.estimate("s1", messages)
        val second = cache.estimate("s1", messages)

        assertEquals(1, calls)
        assertEquals(first, second)
    }

    @Test
    fun `追加消息后触发重算且值与直接估算一致`() {
        var calls = 0
        val cache = MessageEstimateCache { messages ->
            calls++
            TokenEstimator.estimateMessages(messages)
        }
        val messages = history()
        cache.estimate("s1", messages)

        val grown = messages + AgentMessage.ToolResultMessage(id = "t1", toolName = "read", result = "汉".repeat(800))
        val value = cache.estimate("s1", grown)

        assertEquals(2, calls)
        assertEquals(TokenEstimator.estimateMessages(grown), value)
    }

    /**
     * 用户编辑历史消息后字数可以一模一样：元素数、长度和、末条 id 三项都不变，
     * 只靠指纹会漏掉这次变化。缓存必须能从元素同一性上认出这是另一份列表。
     */
    @Test
    fun `同长度同末条 id 的改写也不会被漏掉`() {
        var calls = 0
        val cache = MessageEstimateCache { messages ->
            calls++
            TokenEstimator.estimateMessages(messages)
        }
        val first = listOf(
            AgentMessage.UserMessage(id = "u1", content = "aaaa"),
            AgentMessage.AssistantMessage(id = "a1", content = "bbbb"),
            AgentMessage.UserMessage(id = "u2", content = "cccc")
        )
        val edited = listOf(
            first[0],
            AgentMessage.AssistantMessage(id = "a1", content = "zzzz"),
            first[2]
        )
        cache.estimate("s1", first)

        val value = cache.estimate("s1", edited)

        assertEquals(2, calls)
        assertEquals(TokenEstimator.estimateMessages(edited), value)
    }

    /** 判定与日志都读这个数：缓存值必须与直接估算逐值相等，包括图片与工具调用这类特殊字段。 */
    @Test
    fun `各种消息形态的缓存值与直接估算逐值一致`() {
        val cache = MessageEstimateCache()
        val cases = listOf(
            listOf(AgentMessage.UserMessage(content = "汉".repeat(1_200))),
            listOf(
                AgentMessage.UserMessage(
                    content = "看图",
                    images = listOf(
                        AgentImage(mimeType = "image/png", base64Data = "iVBORw0KGgo=", path = "/tmp/a.png")
                    )
                )
            ),
            listOf(
                AgentMessage.AssistantMessage(
                    content = "调工具",
                    reasoning = "先读文件".repeat(50),
                    signature = "sig",
                    thinkingBlocksJson = "[{\"type\":\"thinking\"}]",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call-1",
                            name = "readFile",
                            arguments = mapOf("path" to JsonPrimitive("~/workspace/A.kt"))
                        )
                    )
                )
            ),
            listOf(
                AgentMessage.ToolResultMessage(
                    id = "t1",
                    toolName = "shell",
                    result = "x".repeat(12_000)
                )
            )
        )

        cases.forEachIndexed { index, messages ->
            assertEquals(
                "第 ${index + 1} 组",
                TokenEstimator.estimateMessages(messages),
                cache.estimate("s1", messages)
            )
        }
    }

    @Test
    fun `会话之间互不串用，删除会话后重算`() {
        var calls = 0
        val cache = MessageEstimateCache { messages ->
            calls++
            TokenEstimator.estimateMessages(messages)
        }
        val messages = history()

        cache.estimate("s1", messages)
        cache.estimate("s2", messages)
        assertEquals(2, calls)

        cache.remove("s1")
        cache.estimate("s1", messages)
        assertEquals(3, calls)

        // s2 的条目没被删，仍然命中
        cache.estimate("s2", messages)
        assertEquals(3, calls)
    }

    @Test
    fun `没有会话 id 时不缓存`() {
        var calls = 0
        val cache = MessageEstimateCache { messages ->
            calls++
            TokenEstimator.estimateMessages(messages)
        }
        val messages = history()

        cache.estimate(null, messages)
        cache.estimate(null, messages)

        assertEquals(2, calls)
    }
}
