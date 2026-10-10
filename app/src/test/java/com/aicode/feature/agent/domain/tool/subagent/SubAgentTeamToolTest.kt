package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.workflow.ContextUsage
import com.aicode.feature.agent.domain.workflow.ContextUsageHolder
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `team` 工具：status 的字段形状与阶段派生、collect 的汇总口径与参数解析。
 *
 * 这一层是主代理「收结论」的入口：口径必须与 `task(action="read")` 一致（最后一条有内容的助手回复），
 * 且未给 ids 时只收已完成的子代理——把还在跑的也当成结论汇总，主代理会拿到半截输出。
 */
class SubAgentTeamToolTest {

    private val chatDao = mockk<ChatSessionDao>()
    private val messageDao = mockk<AgentMessageDao>()
    private val bus = SubAgentEventBus()
    private val usageHolder = ContextUsageHolder()
    private val tool = SubAgentTeamTool(chatDao, messageDao, bus, usageHolder)

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/ws",
        language = null,
        sessionId = SESSION
    )

    // ------------------------------------------------------------ status

    @Test
    fun status_reportsStatePhaseAndTokens() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(
            sub("sub-1", "调研 A", input = 100, output = 20),
            sub("sub-2", "实现 B", input = 300, output = 50)
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(
            userMsg("sub-1", "去调研"),
            pendingTool("sub-1", "search")
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-2") } returns listOf(
            userMsg("sub-2", "去实现"),
            assistant("sub-2", "已实现，见 a.kt:12")
        )
        bus.emit(SubAgentEvent("sub-1", SESSION, SubAgentEventType.SPAWNED))
        usageHolder.publish(
            ContextUsage.of(
                "sub-1",
                realTokens = 1234,
                estimatedTokens = 0,
                thresholds = ModelContextPolicy.Thresholds(
                    contextLimit = 128_000,
                    soft = 100_000,
                    hard = 110_000,
                    hardEnabled = true
                )
            )
        )

        val data = dataOf(tool.executeWithContext(args("action" to str("status")), context))
        assertEquals(2, intOf(data, "count"))
        assertEquals(1, intOf(data, "runningCount"))
        assertEquals(SubAgentEventBus.MAX_RUNNING, intOf(data, "maxRunning"))

        val running = subagentOf(data, "sub-1")
        assertEquals("running", running["state"]!!.jsonPrimitive.content)
        assertEquals("正在执行工具：search", running["phase"]!!.jsonPrimitive.content)
        assertEquals(100, intOf(running, "totalInputTokens"))
        assertEquals(20, intOf(running, "totalOutputTokens"))
        assertEquals(1234, intOf(running, "contextTokens"))
        assertEquals(128_000, intOf(running, "contextLimit"))

        val completed = subagentOf(data, "sub-2")
        assertEquals("completed", completed["state"]!!.jsonPrimitive.content)
        assertEquals("已完成", completed["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun status_runningWithoutPendingToolIsGenerating() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(sub("sub-1", "调研 A"))
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(userMsg("sub-1", "去调研"))
        bus.emit(SubAgentEvent("sub-1", SESSION, SubAgentEventType.SPAWNED))

        val data = dataOf(tool.executeWithContext(args("action" to str("status")), context))
        assertEquals("正在生成回复", subagentOf(data, "sub-1")["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun status_finishedWithoutOutput() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(sub("sub-1", "调研 A"))
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(userMsg("sub-1", "去调研"))

        val data = dataOf(tool.executeWithContext(emptyMap(), context))
        assertEquals("completed", subagentOf(data, "sub-1")["state"]!!.jsonPrimitive.content)
        assertEquals("无输出", subagentOf(data, "sub-1")["phase"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ collect

    @Test
    fun collect_defaultsToCompletedSubAgentsOnly() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(
            sub("sub-1", "调研 A"),
            sub("sub-2", "实现 B")
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(
            userMsg("sub-1", "去调研"),
            assistant("sub-1", "还在跑")
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-2") } returns listOf(
            userMsg("sub-2", "去实现"),
            assistant("sub-2", "已实现，见 a.kt:12")
        )
        bus.emit(SubAgentEvent("sub-1", SESSION, SubAgentEventType.SPAWNED))

        val data = dataOf(tool.executeWithContext(args("action" to str("collect")), context))
        assertEquals(1, intOf(data, "count"))
        val collected = subagentOf(data, "sub-2")
        assertEquals("已实现，见 a.kt:12", collected["lastOutput"]!!.jsonPrimitive.content)
        assertEquals("completed", collected["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun collect_withIdsTakesExactlyThoseSubAgents() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(
            sub("sub-1", "调研 A"),
            sub("sub-2", "实现 B")
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(
            userMsg("sub-1", "去调研"),
            assistant("sub-1", "中间结论")
        )
        coEvery { messageDao.getMessagesBySessionOnce("sub-2") } returns listOf(
            userMsg("sub-2", "去实现"),
            assistant("sub-2", "已实现")
        )
        bus.emit(SubAgentEvent("sub-1", SESSION, SubAgentEventType.SPAWNED))

        // ids 支持逗号分隔的字符串（模型偶尔这么传）
        val data = dataOf(
            tool.executeWithContext(args("action" to str("collect"), "ids" to str("sub-1,sub-2")), context)
        )
        assertEquals(2, intOf(data, "count"))
        assertEquals("running", subagentOf(data, "sub-1")["state"]!!.jsonPrimitive.content)
        assertEquals("中间结论", subagentOf(data, "sub-1")["lastOutput"]!!.jsonPrimitive.content)
    }

    @Test
    fun collect_unknownIdsReturnsError() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(sub("sub-1", "调研 A"))

        val result = tool.executeWithContext(
            args("action" to str("collect"), "ids" to JsonArray(listOf(JsonPrimitive("nope")))),
            context
        )
        assertTrue("期望失败结果，实际：$result", result is ToolResult.Error)
        assertEquals("SUBAGENT_NOT_FOUND", (result as ToolResult.Error).code)
    }

    @Test
    fun collect_withoutOutputFallsBackToRequest() = runTest {
        coEvery { chatDao.getSubSessionsByParentOnce(SESSION) } returns listOf(sub("sub-1", "调研 A"))
        coEvery { messageDao.getMessagesBySessionOnce("sub-1") } returns listOf(userMsg("sub-1", "去调研"))

        val data = dataOf(tool.executeWithContext(args("action" to str("collect")), context))
        assertTrue(
            subagentOf(data, "sub-1")["lastOutput"]!!.jsonPrimitive.content.contains("子代理尚未回复")
        )
    }

    // ------------------------------------------------------------ 参数与声明

    @Test
    fun withoutSessionReturnsError() = runTest {
        val result = tool.executeWithContext(
            args("action" to str("status")),
            context.copy(sessionId = null)
        )
        assertEquals("NO_SESSION", (result as ToolResult.Error).code)
    }

    @Test
    fun unknownActionReturnsError() = runTest {
        val result = tool.executeWithContext(args("action" to str("delete")), context)
        assertEquals("INVALID_ARGS", (result as ToolResult.Error).code)
    }

    @Test
    fun declaredAsReadOnlyTeamTool() {
        assertEquals("team", tool.name)
        assertEquals(ToolPermissionPolicy.AUTO_APPROVE, tool.permissionPolicy)
        assertTrue(tool.capabilities.isEmpty())
        assertTrue(tool.parameters["action"]!!.required.not())
    }

    // ------------------------------------------------------------ 辅助

    private fun sub(
        id: String,
        title: String,
        parent: String = SESSION,
        input: Int = 0,
        output: Int = 0
    ): ChatSessionEntity = ChatSessionEntity(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = 100L,
        parentId = parent,
        totalInputTokens = input,
        totalOutputTokens = output
    )

    private fun userMsg(sessionId: String, content: String): AgentMessageEntity = AgentMessageEntity(
        id = "u-$sessionId",
        sessionId = sessionId,
        role = MessageRole.USER.name,
        content = content,
        timestamp = 0L
    )

    private fun assistant(sessionId: String, content: String): AgentMessageEntity = AgentMessageEntity(
        id = "a-$sessionId",
        sessionId = sessionId,
        role = MessageRole.ASSISTANT.name,
        content = content,
        timestamp = 1L
    )

    private fun pendingTool(sessionId: String, toolName: String): AgentMessageEntity = AgentMessageEntity(
        id = "t-$sessionId",
        sessionId = sessionId,
        role = MessageRole.TOOL.name,
        content = "${SessionUseCase.PENDING_TOOL_MARKER} 执行中",
        timestamp = 2L,
        toolName = toolName
    )

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun str(value: String): JsonElement = JsonPrimitive(value)

    private fun dataOf(result: ToolResult): JsonObject {
        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        return (result as ToolResult.Success).data.jsonObject
    }

    private fun subagentOf(data: JsonObject, id: String): JsonObject = data["subagents"]!!
        .jsonArray
        .map { it.jsonObject }
        .first { it["id"]?.jsonPrimitive?.contentOrNull == id }

    private fun intOf(obj: JsonObject, key: String): Int = obj[key]!!.jsonPrimitive.content.toInt()

    private companion object {
        const val SESSION = "s1"
    }
}
