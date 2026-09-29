package com.aicode.feature.agent.domain.tool.todo

import com.aicode.feature.agent.data.local.dao.FakeTodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `todo` 工具的增量语义：按 subject 定位、只改一项，整表替换只作兜底。
 *
 * 这一层是「AI 不记得更新待办」的第一道修复——每完成一步都要重发整张清单的话，
 * 模型会本能回避更新；增量接口把一次更新压到一次廉价调用。
 */
class TodoToolTest {

    private val dao = FakeTodoItemDao()
    private val tool = TodoTool(dao)
    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/ws",
        language = null,
        sessionId = SESSION
    )

    // ------------------------------------------------------------ add

    @Test
    fun add_newItem_defaultsToPending() = runTest {
        val result = tool.executeWithContext(
            args("action" to str("add"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals(1, dao.rows.size)
        assertEquals(TodoStatus.PENDING.name, dao.rows.single().status)
        assertEquals("pending", statusOf(result, "分析现有实现"))
        assertTrue(messageOf(result).contains("已新增"))
        assertTrue(messageOf(result).contains("下一个未完成项"))
    }

    @Test
    fun add_existingSubject_updatesInsteadOfDuplicating() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("分析现有实现"),
                "status" to str("in_progress")
            ),
            context
        )

        assertEquals(1, dao.rows.size)
        assertEquals(TodoStatus.IN_PROGRESS.name, dao.rows.single().status)
        assertTrue(messageOf(result).contains("按更新处理"))
    }

    @Test
    fun add_beyondCap_returnsError() = runTest {
        repeat(50) { index -> seed("待办 $index", order = index, id = "id-$index") }

        val result = tool.executeWithContext(
            args("action" to str("add"), "subject" to str("第 51 项")),
            context
        )

        assertEquals("TOO_MANY_ITEMS", errorCode(result))
        assertEquals(50, dao.rows.size)
    }

    // ------------------------------------------------------------ update

    @Test
    fun update_fuzzySubject_marksCompletedAndKeepsId() = runTest {
        val seeded = seed("重做任务待办引擎")

        val result = tool.executeWithContext(
            args(
                "action" to str("update"),
                "subject" to str("待办引擎"),
                "status" to str("completed")
            ),
            context
        )

        assertEquals("completed", statusOf(result, "重做任务待办引擎"))
        assertEquals(seeded.id, dao.rows.single().id)
        assertTrue(messageOf(result).contains("已完成"))
    }

    @Test
    fun update_ambiguousSubject_returnsErrorWithoutWriting() = runTest {
        seed("重做待办引擎")
        seed("待办引擎的注入", order = 1, id = "id-second")

        val result = tool.executeWithContext(
            args(
                "action" to str("update"),
                "subject" to str("待办引擎"),
                "status" to str("completed")
            ),
            context
        )

        assertEquals("AMBIGUOUS_SUBJECT", errorCode(result))
        assertTrue(dao.rows.all { it.status == TodoStatus.PENDING.name })
    }

    @Test
    fun update_unknownSubject_returnsErrorWithCurrentList() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("不存在的项"), "status" to str("completed")),
            context
        )

        assertEquals("SUBJECT_NOT_FOUND", errorCode(result))
        assertTrue((result as ToolResult.Error).message.contains("分析现有实现"))
    }

    @Test
    fun update_withoutAnyField_returnsError() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals("MISSING_FIELDS", errorCode(result))
    }

    @Test
    fun update_invalidStatus_returnsError() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("分析现有实现"), "status" to str("halfway")),
            context
        )

        assertEquals("INVALID_STATUS", errorCode(result))
    }

    // ------------------------------------------------------------ remove / replace / list

    @Test
    fun remove_deletesItem() = runTest {
        seed("分析现有实现")
        seed("重写工具", order = 1, id = "id-second")

        val result = tool.executeWithContext(
            args("action" to str("remove"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals(listOf("重写工具"), dao.rows.map { it.subject })
        assertTrue(messageOf(result).contains("已删除"))
    }

    @Test
    fun replace_keepsIdAndCreatedAtBySubject() = runTest {
        val seeded = seed("分析现有实现")
        val createdAt = seeded.createdAt

        val result = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("分析现有实现"), "status" to str("completed")),
                    mapOf("subject" to str("重写工具"))
                )
            ),
            context
        )

        assertEquals(2, dao.rows.size)
        val kept = dao.rows.first { it.subject == "分析现有实现" }
        assertEquals(seeded.id, kept.id)
        assertEquals(createdAt, kept.createdAt)
        assertEquals(TodoStatus.COMPLETED.name, kept.status)
        assertEquals(1, dao.rows.count { it.subject == "重写工具" })
        assertEquals("completed", statusOf(result, "分析现有实现"))
    }

    @Test
    fun list_returnsCurrentItemsWithoutWriting() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(args("action" to str("list")), context)

        assertEquals("分析现有实现", subjectOfFirstItem(result))
        assertEquals(1, dao.rows.size)
    }

    // ------------------------------------------------------------ action 推断

    @Test
    fun inferAction_fromParameters() = runTest {
        seed("分析现有实现")

        // 只给 items：整表替换
        tool.executeWithContext(args("items" to items(mapOf("subject" to str("只留这一项")))), context)
        assertEquals(listOf("只留这一项"), dao.rows.map { it.subject })

        // 给 status：改某项
        tool.executeWithContext(
            args("subject" to str("只留这一项"), "status" to str("completed")),
            context
        )
        assertEquals(TodoStatus.COMPLETED.name, dao.rows.single().status)

        // 只给 subject：加一项
        tool.executeWithContext(args("subject" to str("再加一项")), context)
        assertEquals(2, dao.rows.size)

        // 什么都不给：只读
        val result = tool.executeWithContext(emptyMap(), context)
        assertEquals(2, dao.rows.size)
        assertNull(errorCodeOrNull(result))
    }

    // ------------------------------------------------------------ 辅助

    private fun seed(
        subject: String,
        status: TodoStatus = TodoStatus.PENDING,
        order: Int = 0,
        id: String = "id-$subject"
    ): TodoItemEntity {
        val now = System.currentTimeMillis()
        val entity = TodoItemEntity(
            id = id,
            sessionId = SESSION,
            subject = subject,
            status = status.name,
            order = order,
            createdAt = now,
            updatedAt = now
        )
        dao.rows.add(entity)
        return entity
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun str(value: String): JsonElement = JsonPrimitive(value)

    private fun items(vararg entries: Map<String, JsonElement>): JsonElement =
        kotlinx.serialization.json.JsonArray(entries.map { JsonObject(it) })

    private fun dataOf(result: ToolResult): JsonObject {
        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        return (result as ToolResult.Success).data.jsonObject
    }

    private fun messageOf(result: ToolResult): String =
        dataOf(result)["message"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun subjectsOf(result: ToolResult): List<String> = dataOf(result)["items"]!!
        .jsonArray
        .map { it.jsonObject["subject"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    private fun subjectOfFirstItem(result: ToolResult): String? = subjectsOf(result).firstOrNull()

    private fun statusOf(result: ToolResult, subject: String): String? = dataOf(result)["items"]!!
        .jsonArray
        .map { it.jsonObject }
        .firstOrNull { it["subject"]?.jsonPrimitive?.contentOrNull == subject }
        ?.get("status")
        ?.jsonPrimitive
        ?.contentOrNull

    private fun errorCode(result: ToolResult): String {
        assertTrue("期望失败结果，实际：$result", result is ToolResult.Error)
        return (result as ToolResult.Error).code
    }

    private fun errorCodeOrNull(result: ToolResult): String? = (result as? ToolResult.Error)?.code

    private companion object {
        const val SESSION = "session-1"
    }
}
