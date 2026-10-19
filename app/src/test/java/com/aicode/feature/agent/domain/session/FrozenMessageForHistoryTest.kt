package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.toolCallsFrozenForHistory
import com.aicode.feature.agent.domain.model.toolCallsJsonForStorage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落库形态与发送形态必须是同一串字节。
 *
 * 这组用例锁两件事：
 * 1. 工具参数超限时截断的是 JSON 里的字符串值，结构本身仍可解析——直接截 JSON 文本会让下一轮
 *    解码失败、整对工具消息从历史里消失；
 * 2. 冻结是幂等的——第二次处理不会再改字节，所以同一条消息在任何一轮请求里都长一样。
 */
class FrozenMessageForHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oversizedToolArgumentsStayValidJsonAndMatchStoredBytes() {
        val raw = listOf(
            ToolCall(
                id = "call-1",
                name = "writeFile",
                arguments = mapOf(
                    "path" to JsonPrimitive("a.txt"),
                    "content" to JsonPrimitive("汉".repeat(60_000))
                )
            )
        )

        val stored = toolCallsJsonForStorage(
            raw,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES,
            json
        )
        val frozen = toolCallsFrozenForHistory(
            raw,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES,
            json
        )

        // 截断后仍是合法 JSON：下一轮 buildHistory 能解出来
        val decoded = json.decodeFromString<List<ToolCall>>(stored)
        assertEquals(frozen, decoded)
        // 发送侧序列化出来的就是落库的那串字节
        assertEquals(stored, json.encodeToString(frozen))
        assertTrue(
            "路径字段必须原样保留，截断只动大块正文",
            (frozen.single().arguments["path"] as JsonPrimitive).content == "a.txt"
        )
        assertTrue(
            "大块正文应当被收敛到快照上限下",
            stored.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )
    }

    @Test
    fun frozenMessageIsIdempotent() {
        val assistant = AgentMessage.AssistantMessage(
            id = "a1",
            content = "a".repeat(200_000),
            toolCalls = listOf(
                ToolCall(
                    id = "call-1",
                    name = "writeFile",
                    arguments = mapOf("content" to JsonPrimitive("x".repeat(60_000)))
                )
            ),
            reasoning = "r".repeat(200_000),
            signature = "s".repeat(120_000),
            thinkingBlocksJson = "t".repeat(120_000)
        )
        val user = AgentMessage.UserMessage(
            id = "u1",
            content = "汉".repeat(80_000),
            modelReminder = "提".repeat(60_000)
        )
        val tool = AgentMessage.ToolResultMessage(
            id = "t1",
            toolName = "readFile",
            result = "汉".repeat(80_000)
        )

        listOf<AgentMessage>(assistant, user, tool).forEach { original ->
            val once = MessagePersistenceUseCase.frozenForHistory(original, json)
            val twice = MessagePersistenceUseCase.frozenForHistory(once, json)
            assertEquals("冻结结果必须幂等", once, twice)
        }
    }
}
