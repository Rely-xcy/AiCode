package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务注入块：清单本身 + 新鲜度 + 纪律规则。
 *
 * 新鲜度是这次重做的关键之一——模型看不到「清单有多旧」，就永远不会有「该更新了」的动机。
 */
class TaskModuleTest {

    private val module = TaskModule(FakeTodoItemDao())

    @Test
    fun rules_alwaysPresent() {
        val block = module.renderBlock(items = null, ctx = ctx())

        assertTrue(block.contains("任务清单（`todo` 工具"))
        assertTrue(block.contains("① 开始多步任务"))
    }

    @Test
    fun snapshotNotReady_claimsNothingAboutTheList() {
        val block = module.renderBlock(items = null, ctx = ctx())

        // 快照还没读到：只能说规则，不能谎报「还没有清单」（那会诱导模型重建一份重复清单）
        assertFalse(block.contains("本会话还没有任务清单"))
    }

    @Test
    fun emptyList_saysSo() {
        val block = module.renderBlock(items = emptyList(), ctx = ctx())

        assertTrue(block.contains("本会话还没有任务清单"))
    }

    @Test
    fun items_showProgressAndFreshness() {
        // 新鲜度按清单里「最后一次变动」算（items 里最新的 updatedAt），所以两条都要是 12 分钟前；
        // 给其中一条默认的「此刻」会把整张清单的新鲜度拉成「刚刚更新过」。
        val twelveMinutesAgo = minutesAgo(12)
        val block = module.renderBlock(
            items = listOf(
                todo("分析现有实现", TodoStatus.COMPLETED, order = 0, updatedAt = twelveMinutesAgo),
                todo("重写 TodoTool", TodoStatus.IN_PROGRESS, order = 1, updatedAt = twelveMinutesAgo)
            ),
            ctx = ctx()
        )

        assertTrue(block.contains("[x] 分析现有实现"))
        assertTrue(block.contains("[~] 重写 TodoTool"))
        assertTrue(block.contains("进度：已完成 1/2，进行中 1"))
        assertTrue(block.contains("清单最后变动：约 12 分钟前"))
    }

    @Test
    fun justUpdated_saysJustNow() {
        val block = module.renderBlock(
            items = listOf(todo("分析现有实现", TodoStatus.COMPLETED, updatedAt = System.currentTimeMillis())),
            ctx = ctx()
        )

        assertTrue(block.contains("清单刚刚更新过"))
    }

    @Test
    fun previousTurnWithManyToolCalls_escalates() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.PENDING)),
            ctx = ctx(history = previousTurn(toolCalls = 6, todoCalls = 0))
        )

        assertTrue(block.contains("上一回合调用工具 6 次，其中更新清单 0 次"))
        assertTrue(block.contains("却没更新过清单"))
    }

    @Test
    fun previousTurnWithTodoCall_doesNotEscalate() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.PENDING)),
            ctx = ctx(history = previousTurn(toolCalls = 6, todoCalls = 1))
        )

        assertTrue(block.contains("其中更新清单 1 次"))
        assertFalse(block.contains("却没更新过清单"))
    }

    @Test
    fun shortPreviousTurn_doesNotEscalate() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.PENDING)),
            ctx = ctx(history = previousTurn(toolCalls = 2, todoCalls = 0))
        )

        assertFalse(block.contains("却没更新过清单"))
    }

    @Test
    fun subAgentRules_onlyForSubAgents() {
        assertNull(module.subAgentRules(ctx(isSubAgent = false, subAgentName = null)))
        assertNotNull(module.subAgentRules(ctx(isSubAgent = true, subAgentName = "Explore")))
    }

    @Test
    fun subAgentFragment_hasNoRules() {
        val block = module.renderBlock(items = emptyList(), ctx = ctx(), withRules = false)

        // 子代理的规则走 subAgentRules（不受 inject 门禁），promptFragment 里不重复
        assertFalse(block.contains("① 开始多步任务"))
        assertTrue(block.contains("本会话还没有任务清单"))
    }

    // ------------------------------------------------------------ 辅助

    private fun ctx(
        history: List<AgentMessage> = emptyList(),
        isSubAgent: Boolean = false,
        subAgentName: String? = null
    ) = EngineContext(
        sessionId = SESSION,
        projectRoot = "/ws",
        history = history,
        isSubAgent = isSubAgent,
        subAgentName = subAgentName
    )

    /** 构造「上一回合」：一条用户消息 + 助手回复 + N 条工具结果 + 本轮用户消息。 */
    private fun previousTurn(toolCalls: Int, todoCalls: Int): List<AgentMessage> = buildList {
        add(AgentMessage.UserMessage(content = "继续"))
        add(AgentMessage.AssistantMessage(content = "开始干活"))
        repeat(toolCalls) { index ->
            add(
                AgentMessage.ToolResultMessage(
                    id = "call-$index",
                    toolName = if (index < todoCalls) "todo" else "readFile",
                    result = "{}"
                )
            )
        }
        add(AgentMessage.UserMessage(content = "继续"))
    }

    private fun todo(
        subject: String,
        status: TodoStatus,
        order: Int = 0,
        updatedAt: Long = System.currentTimeMillis()
    ) = TodoItem(
        id = "id-$subject",
        sessionId = SESSION,
        subject = subject,
        status = status,
        order = order,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun minutesAgo(minutes: Long): Long = System.currentTimeMillis() - minutes * 60_000L

    private companion object {
        const val SESSION = "session-1"
    }
}

/**
 * 最小内存实现：[TodoItemDao] 是 Room 接口，单测里不需要真库。
 * 测试直接读写 [rows]，断言的就是这份内存状态。
 */
private class FakeTodoItemDao : TodoItemDao {
    val rows = mutableListOf<TodoItemEntity>()

    override suspend fun upsert(item: TodoItemEntity) {
        rows.removeAll { it.id == item.id }
        rows.add(item)
    }

    override suspend fun upsertAll(items: List<TodoItemEntity>) {
        items.forEach { upsert(it) }
    }

    override fun getBySession(sessionId: String): Flow<List<TodoItemEntity>> =
        MutableStateFlow(orderedBySession(sessionId))

    override suspend fun getBySessionOnce(sessionId: String): List<TodoItemEntity> = orderedBySession(sessionId)

    override suspend fun getAllOnce(): List<TodoItemEntity> = rows.toList()

    override suspend fun getPageAfter(lastCreatedAt: Long, lastId: String, limit: Int): List<TodoItemEntity> =
        rows.filter { it.createdAt > lastCreatedAt || (it.createdAt == lastCreatedAt && it.id > lastId) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .take(limit)

    override suspend fun getBySessionPageAfter(
        sessionId: String,
        lastCreatedAt: Long,
        lastId: String,
        limit: Int
    ): List<TodoItemEntity> = getPageAfter(lastCreatedAt, lastId, limit).filter { it.sessionId == sessionId }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteBySession(sessionId: String) {
        rows.removeAll { it.sessionId == sessionId }
    }

    override suspend fun getMaxOrder(sessionId: String): Int? =
        rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.order }

    private fun orderedBySession(sessionId: String): List<TodoItemEntity> = rows
        .filter { it.sessionId == sessionId }
        .sortedWith(compareBy({ it.order }, { -it.priority }))
}
