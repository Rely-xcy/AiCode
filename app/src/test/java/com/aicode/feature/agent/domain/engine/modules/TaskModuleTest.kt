package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务注入块：清单本身 + 新鲜度 + 纪律规则，以及收尾守卫。
 *
 * 新鲜度只在「真要你动手」时才出现——每轮都念同一句，模型会把它当背景音整段无视；
 * 收尾守卫是这次的关键：光靠注入提醒治不住「干完活不更新清单」，边界上得拦一次。
 */
class TaskModuleTest {

    private val dao = FakeTodoItemDao()
    private val module = TaskModule(dao)

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
    fun items_showListAndProgress() {
        val block = module.renderBlock(
            items = listOf(
                todo("分析现有实现", TodoStatus.COMPLETED, order = 0),
                todo("重写 TodoTool", TodoStatus.IN_PROGRESS, order = 1)
            ),
            ctx = ctx()
        )

        assertTrue(block.contains("[x] 分析现有实现"))
        assertTrue(block.contains("[~] 重写 TodoTool"))
        assertTrue(block.contains("进度：已完成 1/2，进行中 1"))
    }

    @Test
    fun freshListWithoutMissedTurns_saysNothingAboutFreshness() {
        // 正常一轮（上一回合更新过清单、本轮清单也刚动过）：整段新鲜度都不注入
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis())),
            ctx = ctx(history = injectionHistory(missedTurns = 0))
        )

        assertFalse(block.contains("清单最后变动"))
        assertFalse(block.contains("刚刚更新过"))
        assertFalse(block.contains("没随工作更新"))
    }

    @Test
    fun missedTurns_escalateInInjection() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.PENDING, updatedAt = minutesAgo(12))),
            ctx = ctx(history = injectionHistory(missedTurns = 2, toolCallsPerTurn = 3))
        )

        assertTrue(block.contains("清单已连续 2 轮没随工作更新"))
        assertTrue(block.contains("这 2 轮共 6 次工具调用"))
        assertTrue(block.contains("约 12 分钟前最后一次变动"))
    }

    @Test
    fun updatedLastTurn_doesNotEscalate() {
        // 上一回合虽然干了 6 次活，但更新过清单：不点它的名
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.PENDING)),
            ctx = ctx(history = injectionHistoryWithLastTurnUpdate())
        )

        assertFalse(block.contains("没随工作更新"))
    }

    @Test
    fun allDoneAndLongQuiet_suggestsClearing() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.COMPLETED, updatedAt = minutesAgo(45))),
            ctx = ctx()
        )

        assertTrue(block.contains("清单已全部完成、约 45 分钟没变动"))
        assertTrue(block.contains("todo(action=\"clear\")"))
    }

    @Test
    fun allDoneJustNow_staysQuiet() {
        val block = module.renderBlock(
            items = listOf(todo("重写 TodoTool", TodoStatus.COMPLETED, updatedAt = System.currentTimeMillis())),
            ctx = ctx()
        )

        assertFalse(block.contains("todo(action=\"clear\")"))
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

    // ------------------------------------------------------------ 收尾守卫

    @Test
    fun guard_firesWhenWorkedButListUntouched() = runTest {
        seed("重写 TodoTool", TodoStatus.IN_PROGRESS)

        val reminder = module.finalResponseGuard(
            ctx(history = guardHistory(currentToolCalls = 3)),
            "已完成主要改动，下一步跑测试。"
        )

        assertNotNull(reminder)
        val text = reminder.orEmpty()
        assertTrue(text.contains("[清单守卫]"))
        assertTrue(text.contains("3 次工具调用、清单更新 0 次"))
        assertTrue(text.contains("[~] 重写 TodoTool"))
        assertTrue(text.contains("先把清单对齐，再给最终回复"))
    }

    @Test
    fun guard_staysQuietOnPureChatTurn() = runTest {
        seed("重写 TodoTool", TodoStatus.IN_PROGRESS)

        val reminder = module.finalResponseGuard(ctx(history = guardHistory(currentToolCalls = 0)), "已完成主要改动。")

        assertNull(reminder)
    }

    @Test
    fun guard_staysQuietWhenListWasUpdated() = runTest {
        seed("重写 TodoTool", TodoStatus.IN_PROGRESS)

        val reminder = module.finalResponseGuard(
            ctx(history = guardHistory(currentToolCalls = 3, todoResults = listOf(mutatedTodo("t1")))),
            "已完成主要改动，下一步跑测试。"
        )

        assertNull(reminder)
    }

    @Test
    fun guard_staysQuietWhenListIsAllDone() = runTest {
        seed("重写 TodoTool", TodoStatus.COMPLETED)

        assertNull(module.finalResponseGuard(ctx(history = guardHistory(currentToolCalls = 3)), "已完成主要改动。"))
    }

    @Test
    fun guard_staysQuietWhenThereIsNoList() = runTest {
        assertNull(module.finalResponseGuard(ctx(history = guardHistory(currentToolCalls = 3)), "已完成主要改动。"))
    }

    @Test
    fun guard_staysQuietWhenReplyIsNotWrappingUp() = runTest {
        seed("重写 TodoTool", TodoStatus.IN_PROGRESS)

        val reminder = module.finalResponseGuard(ctx(history = guardHistory(currentToolCalls = 3)), "我先看看这个文件的内容。")

        assertNull(reminder)
    }

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

    private fun seed(subject: String, status: TodoStatus) {
        dao.rows += TodoItemEntity(
            id = "id-$subject",
            sessionId = SESSION,
            subject = subject,
            status = status.name,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 注入侧的历史：missedTurns 个「干了活没更新清单」的回合 + 本轮用户消息。
     * 本轮还没产生工具结果，所以末尾只挂用户消息（这正是 build 提示词那一刻的样子）。
     */
    private fun injectionHistory(missedTurns: Int, toolCallsPerTurn: Int = 3): List<AgentMessage> =
        missedTurnHistory(missedTurns, toolCallsPerTurn) + AgentMessage.UserMessage(content = "继续")

    /** 上一回合干了 3 次活但更新过清单：连续计数应当停在那里。 */
    private fun injectionHistoryWithLastTurnUpdate(): List<AgentMessage> = buildList {
        add(AgentMessage.UserMessage(content = "上一轮"))
        add(AgentMessage.AssistantMessage(content = "干活"))
        repeat(3) { index -> add(toolResult("past-$index", "readFile")) }
        add(mutatedTodo("last"))
        add(AgentMessage.UserMessage(content = "继续"))
    }

    /** 守卫侧的历史：在注入侧基础上补本轮已产生的工具结果（本轮用户消息 + assistant + 工具结果）。 */
    private fun guardHistory(
        currentToolCalls: Int,
        todoResults: List<AgentMessage.ToolResultMessage> = emptyList()
    ): List<AgentMessage> = buildList {
        add(AgentMessage.UserMessage(content = "继续"))
        add(AgentMessage.AssistantMessage(content = "干活"))
        repeat(currentToolCalls) { index -> add(toolResult("now-$index", "readFile")) }
        addAll(todoResults)
    }

    private fun missedTurnHistory(count: Int, toolCallsPerTurn: Int): List<AgentMessage> = buildList {
        repeat(count) { turn ->
            add(AgentMessage.UserMessage(content = "第 $turn 轮"))
            add(AgentMessage.AssistantMessage(content = "干活"))
            repeat(toolCallsPerTurn) { index -> add(toolResult("past-$turn-$index", "readFile")) }
        }
    }

    private fun toolResult(id: String, name: String): AgentMessage.ToolResultMessage =
        AgentMessage.ToolResultMessage(id = id, toolName = name, result = "{}")

    private fun mutatedTodo(id: String): AgentMessage.ToolResultMessage = AgentMessage.ToolResultMessage(
        id = id,
        toolName = "todo",
        result = """{"status":"success","data":{"message":"已更新「重写 TodoTool」：completed（已完成）；清单全部完成","text":"[x] 重写 TodoTool"}}"""
    )

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
