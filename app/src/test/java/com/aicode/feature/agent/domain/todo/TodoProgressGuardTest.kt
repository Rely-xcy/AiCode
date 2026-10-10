package com.aicode.feature.agent.domain.todo

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾守卫的判据：什么情况下拦、拦下来该说什么、以及最重要的——什么情况下**不**拦。
 *
 * 拦错的代价是白花一次模型往返，漏判的代价就是「清单半天不动」这次投诉，所以两边都要有测试钉住。
 */
class TodoProgressGuardTest {

    // ------------------------------------------------------------ 收尾表述

    @Test
    fun claimsWrapUp_recognizesChineseAndEnglish() {
        listOf(
            "已完成主要改动，下一步跑测试。",
            "两处都改好了，接下来我再看看编译。",
            "这两件事都完成了，剩余工作如下。",
            "Done. Next steps: run the tests.",
            "I've finished the refactor."
        ).forEach { text ->
            assertTrue("应当认作收尾: $text", TodoProgressGuard.claimsWrapUp(text))
        }
    }

    /**
     * 只描述时间顺序或剩余量的词不再算收尾：它们在正常叙述里到处都是，认了就等着白拦。
     * 实测口径：本机 721 个干活回合里，原词表命中 37.4%，删掉这六个词后 19.6%。
     */
    @Test
    fun claimsWrapUp_ignoresForwardLookingAndRemainingQuantity() {
        listOf(
            "下一步我打算先看看这个文件。",
            "接下来要处理的是压缩阈值。",
            "后续再看这一块。",
            "剩下的两项先放着。",
            "还剩两个文件没读，剩余时间不够。",
            "Next steps: run the tests.",
            "Three items remaining on the list."
        ).forEach { text ->
            assertFalse("不该认作收尾: $text", TodoProgressGuard.claimsWrapUp(text))
        }
    }

    /** 收窄的是「只提下一步」那一类；只要同一段里有完成声明，仍然要拦。 */
    @Test
    fun claimsWrapUp_stillFiresWhenCompletionClaimIsPresent() {
        listOf(
            "已完成 A，下一步 B。",
            "接口改好了，剩下的下次再说。"
        ).forEach { text ->
            assertTrue("应当认作收尾: $text", TodoProgressGuard.claimsWrapUp(text))
        }
    }

    @Test
    fun claimsWrapUp_ignoresPlainTalk() {
        listOf(
            "",
            "这个报错是什么意思？",
            "我先看一下这个文件的内容。",
            "Could you clarify what you mean?"
        ).forEach { text ->
            assertFalse("不该认作收尾: $text", TodoProgressGuard.claimsWrapUp(text))
        }
    }

    // ------------------------------------------------------------ 回合统计

    @Test
    fun currentTurnStats_countsToolResultsAndRealMutations() {
        val history = turnHistory(
            currentToolCalls = 3,
            currentTodoResults = listOf(todoListed("l1"), todoMutated("t1"))
        )

        val stats = TodoProgressGuard.currentTurnStats(history)

        assertEquals(5, stats.toolCalls)
        assertEquals(1, stats.todoMutations)
    }

    @Test
    fun todoCallsThatOnlyLookOrFail_areNotMutations() {
        val history = turnHistory(
            currentToolCalls = 2,
            currentTodoResults = listOf(todoListed("l1"), todoFailed("t1"))
        )

        assertEquals(0, TodoProgressGuard.currentTurnStats(history).todoMutations)
    }

    @Test
    fun listingAnEmptyList_isStillNotAMutation() {
        // list 的成功消息后面会拼「；下一个未完成项：…」或「；清单现在是空的」，所以只能比前缀
        val history = turnHistory(
            currentToolCalls = 2,
            currentTodoResults = listOf(todoListed("l1", "当前清单；清单现在是空的"))
        )

        assertEquals(0, TodoProgressGuard.currentTurnStats(history).todoMutations)
    }

    @Test
    fun unreadableTodoResult_countsAsMutation() {
        // 传输格式变了就按「改过清单」处理：宁可漏拦，也不能在模型真更新过时把它拦下来
        val history = turnHistory(
            currentToolCalls = 2,
            currentTodoResults = listOf(rawTodo("w", """{"weird":true}"""))
        )

        assertEquals(1, TodoProgressGuard.currentTurnStats(history).todoMutations)
    }

    @Test
    fun missedStreak_countsBackwardsAndStopsAtAnUpdatedTurn() {
        val twoMissed = turnHistory(missedTurns = 2, toolCallsPerTurn = 3, currentToolCalls = 3)
        assertEquals(2, TodoProgressGuard.missedStreak(twoMissed).missedTurns)
        assertEquals(6, TodoProgressGuard.missedStreak(twoMissed).toolCalls)

        // 最近的那回合更新过清单 → 连续计数归零，不再往前数
        val lastTurnUpdated = historyOf(
            listOf(listOf(tool("r1", "readFile"), tool("r2", "readFile")), listOf(todoMutated("t1"))),
            currentToolCalls = 3
        )
        assertEquals(0, TodoProgressGuard.missedStreak(lastTurnUpdated).missedTurns)
    }

    @Test
    fun lightTurns_breakTheStreak() {
        // 中间夹了一轮只调 1 次工具的问答轮：那轮不算「干活没更新」，连续计数到此为止
        val history = historyOf(
            listOf(listOf(tool("r1", "readFile"), tool("r2", "readFile"), tool("r3", "readFile"))),
            trivialTurn = listOf(tool("q1", "readFile")),
            currentToolCalls = 3
        )

        assertEquals(0, TodoProgressGuard.missedStreak(history).missedTurns)
    }

    // ------------------------------------------------------------ 拦不拦

    @Test
    fun needsReminder_firesWhenWorkedButListStayedUntouched() {
        val items = listOf(item("重写 TodoTool", TodoStatus.IN_PROGRESS), item("跑测试", TodoStatus.PENDING))

        assertTrue(TodoProgressGuard.needsReminder(items, turnHistory(currentToolCalls = 3), "已完成 A，下一步 B。"))
    }

    @Test
    fun needsReminder_staysQuietWhenAnyConditionFails() {
        val items = listOf(item("重写 TodoTool", TodoStatus.IN_PROGRESS))

        // 纯对话轮：没有工具调用
        assertFalse(TodoProgressGuard.needsReminder(items, turnHistory(currentToolCalls = 0), "已完成 A。"))
        // 只调了一次工具（看一眼的问答轮）
        assertFalse(TodoProgressGuard.needsReminder(items, turnHistory(currentToolCalls = 1), "已完成 A。"))
        // 本轮真的更新过清单
        assertFalse(
            TodoProgressGuard.needsReminder(
                items,
                turnHistory(currentToolCalls = 3, currentTodoResults = listOf(todoMutated("t1"))),
                "已完成 A。"
            )
        )
        // 回复没在收尾
        assertFalse(TodoProgressGuard.needsReminder(items, turnHistory(currentToolCalls = 3), "我先看看这个文件。"))
        // 清单全完成：没什么可更新的
        assertFalse(
            TodoProgressGuard.needsReminder(
                listOf(item("重写 TodoTool", TodoStatus.COMPLETED)),
                turnHistory(currentToolCalls = 3),
                "已完成 A。"
            )
        )
        // 快照还没读到：不猜
        assertFalse(TodoProgressGuard.needsReminder(null, turnHistory(currentToolCalls = 3), "已完成 A。"))
    }

    @Test
    fun needsReminder_firesWhenTodoCallFailed() {
        // 更新失败 = 清单没变，正需要提醒
        assertTrue(
            TodoProgressGuard.needsReminder(
                listOf(item("重写 TodoTool", TodoStatus.PENDING)),
                turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoFailed("t1"))),
                "已完成 A。"
            )
        )
    }

    // ------------------------------------------------------------ 全完成清空判据

    @Test
    fun needsAllDoneCleanup_firesWhenAllCompletedThisTurnAndWrappingUp() {
        val items = listOf(
            item("重写 TodoTool", TodoStatus.COMPLETED),
            item("跑测试", TodoStatus.COMPLETED)
        )
        // 本轮刚把最后一项打完勾（todoMutations > 0），收尾却没清空 → 拦
        val history = turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoMutated("t1")))

        assertTrue(TodoProgressGuard.needsAllDoneCleanup(items, history, "两件事都完成了。"))
    }

    @Test
    fun needsAllDoneCleanup_staysQuietWhileIncompleteItemsRemain() {
        val items = listOf(
            item("重写 TodoTool", TodoStatus.COMPLETED),
            item("跑测试", TodoStatus.PENDING)
        )
        val history = turnHistory(currentToolCalls = 3)

        // 还有未完成项：新支不触发
        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, history, "都完成了。"))
        // 同一份清单、同一段收尾：归旧支（还有未完成项 + 本轮没动过清单）——两支互斥
        assertTrue(TodoProgressGuard.needsReminder(items, history, "都完成了。"))
    }

    @Test
    fun needsAllDoneCleanup_ignoresEmptyOrMissingList() {
        val history = turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoMutated("t1")))

        assertFalse(TodoProgressGuard.needsAllDoneCleanup(emptyList(), history, "都完成了。"))
        assertFalse(TodoProgressGuard.needsAllDoneCleanup(null, history, "都完成了。"))
    }

    @Test
    fun needsAllDoneCleanup_staysQuietWhenReplyIsNotWrappingUp() {
        val items = listOf(item("重写 TodoTool", TodoStatus.COMPLETED))
        val history = turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoMutated("t1")))

        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, history, "我先看看这个文件。"))
    }

    @Test
    fun needsAllDoneCleanup_staysQuietForAllDoneListWithPlainTalk() {
        val items = listOf(item("重写 TodoTool", TodoStatus.COMPLETED), item("跑测试", TodoStatus.COMPLETED))
        val history = turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoMutated("t1")))

        // 清单非空且全完成、本轮也动过，但正文没有任何收尾表述 → 不拦
        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, history, ""))
        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, history, "这个报错是什么意思？"))
    }

    @Test
    fun needsAllDoneCleanup_staysQuietWhenListUntouchedThisTurn() {
        // 本轮没碰清单（全完成是上一轮遗留）：不在本支处理，交给 TaskModule 的 freshness 静默期
        val items = listOf(item("重写 TodoTool", TodoStatus.COMPLETED))

        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, turnHistory(currentToolCalls = 3), "都完成了。"))
    }

    @Test
    fun needsAllDoneCleanup_staysQuietWhenAlreadyClearedThisTurn() {
        // 快照异步刷新：本轮刚 clear，快照可能还停在「全完成」，不能因此白拦一次
        val items = listOf(item("重写 TodoTool", TodoStatus.COMPLETED))
        val history = turnHistory(currentToolCalls = 2, currentTodoResults = listOf(todoCleared("c1")))

        assertFalse(TodoProgressGuard.needsAllDoneCleanup(items, history, "都完成了。"))
    }

    // ------------------------------------------------------------ 提醒正文

    @Test
    fun reminder_listsUnfinishedItemsAndAsksToUpdate() {
        val items = listOf(
            item("重写 TodoTool", TodoStatus.IN_PROGRESS, minutesAgo = 25),
            item("跑测试", TodoStatus.PENDING),
            item("分析现有实现", TodoStatus.COMPLETED)
        )
        val history = turnHistory(missedTurns = 0, currentToolCalls = 3)

        val reminder = TodoProgressGuard.buildReminder(items, history, now = System.currentTimeMillis())

        assertTrue(reminder.contains("[清单守卫]"))
        assertTrue(reminder.contains("3 次工具调用、清单更新 0 次"))
        assertTrue(reminder.contains("[~] 重写 TodoTool（约 25 分钟没动）"))
        assertTrue(reminder.contains("[ ] 跑测试"))
        assertFalse(reminder.contains("分析现有实现"))
        assertTrue(reminder.contains("先把清单对齐，再给最终回复"))
        assertTrue(reminder.contains("status=\"completed\""))
    }

    @Test
    fun reminder_escalatesAfterRepeatedMisses() {
        val items = listOf(item("重写 TodoTool", TodoStatus.IN_PROGRESS))

        val first = TodoProgressGuard.buildReminder(items, turnHistory(missedTurns = 0, currentToolCalls = 3))
        val second = TodoProgressGuard.buildReminder(items, turnHistory(missedTurns = 1, currentToolCalls = 3))

        assertFalse(first.contains("连续第"))
        assertTrue(second.contains("这已经是连续第 2 轮这样了"))
        assertTrue(second.contains("本轮必须更新清单，这不是可选项"))
    }

    @Test
    fun reminder_truncatesLongListsButSaysHowManyAreHidden() {
        val items = (1..12).map { item("待办 $it", TodoStatus.PENDING) }

        val reminder = TodoProgressGuard.buildReminder(items, turnHistory(currentToolCalls = 3))

        assertTrue(reminder.contains("还有 12 项没完成"))
        assertTrue(reminder.contains("另有 4 项未列出"))
    }

    @Test
    fun allDoneReminder_namesCountAndAsksToClear() {
        val items = listOf(
            item("重写 TodoTool", TodoStatus.COMPLETED),
            item("跑测试", TodoStatus.COMPLETED)
        )

        val reminder = TodoProgressGuard.buildAllDoneReminder(items)

        assertTrue(reminder.contains("[清单守卫]"))
        assertTrue(reminder.contains("2 项都完成了"))
        assertTrue(reminder.contains("todo(action=\"clear\")"))
        assertTrue(reminder.contains("[x] 重写 TodoTool"))
        assertTrue(reminder.contains("[x] 跑测试"))
    }

    // ------------------------------------------------------------ 辅助

    private fun item(
        subject: String,
        status: TodoStatus,
        minutesAgo: Long = 0
    ) = TodoItem(
        id = "id-$subject",
        sessionId = SESSION,
        subject = subject,
        status = status,
        createdAt = System.currentTimeMillis() - minutesAgo * 60_000L,
        updatedAt = System.currentTimeMillis() - minutesAgo * 60_000L
    )

    private fun tool(id: String, name: String) = AgentMessage.ToolResultMessage(id = id, toolName = name, result = "{}")

    private fun rawTodo(id: String, result: String) =
        AgentMessage.ToolResultMessage(id = id, toolName = TODO, result = result)

    private fun todoListed(id: String, message: String = "当前清单；下一个未完成项：跑测试") =
        AgentMessage.ToolResultMessage(
            id = id,
            toolName = TODO,
            result = """{"status":"success","data":{"message":"$message","text":"[~] 重写 TodoTool"}}"""
        )

    private fun todoMutated(id: String) = AgentMessage.ToolResultMessage(
        id = id,
        toolName = TODO,
        result = """{"status":"success","data":{"message":"已更新「重写 TodoTool」：completed（已完成）；清单全部完成","text":"[x] 重写 TodoTool"}}"""
    )

    private fun todoCleared(id: String) = AgentMessage.ToolResultMessage(
        id = id,
        toolName = TODO,
        result = """{"status":"success","data":{"message":"已清空清单；清单现在是空的","text":""}}"""
    )

    private fun todoFailed(id: String) = AgentMessage.ToolResultMessage(
        id = id,
        toolName = TODO,
        result = """{"status":"error","message":"清单里没有「跑测试」。当前清单：\n（空）","code":"SUBJECT_NOT_FOUND"}"""
    )

    /** 当前轮：一条用户消息 + assistant + N 条 readFile 结果 + 若干 todo 结果。 */
    private fun turnHistory(
        missedTurns: Int = 0,
        toolCallsPerTurn: Int = 3,
        currentToolCalls: Int = 3,
        currentTodoResults: List<AgentMessage.ToolResultMessage> = emptyList()
    ): List<AgentMessage> = historyOf(
        previousTurns = List(missedTurns) { turn -> List(toolCallsPerTurn) { index -> tool("past-$turn-$index", "readFile") } },
        currentToolCalls = currentToolCalls,
        currentTodoResults = currentTodoResults
    )

    /**
     * previousTurns 里每一段 = 一个历史回合的工具结果；trivialTurn 给「连续计数里的打断」用。
     * 末尾固定补一条本轮用户消息，本轮的工具结果挂在它后面。
     */
    private fun historyOf(
        previousTurns: List<List<AgentMessage.ToolResultMessage>>,
        trivialTurn: List<AgentMessage.ToolResultMessage>? = null,
        currentToolCalls: Int = 0,
        currentTodoResults: List<AgentMessage.ToolResultMessage> = emptyList()
    ): List<AgentMessage> = buildList {
        previousTurns.forEachIndexed { turn, results ->
            add(AgentMessage.UserMessage(content = "第 $turn 轮"))
            add(AgentMessage.AssistantMessage(content = "干活"))
            addAll(results)
        }
        if (trivialTurn != null) {
            add(AgentMessage.UserMessage(content = "随手问一句"))
            add(AgentMessage.AssistantMessage(content = "答一下"))
            addAll(trivialTurn)
        }
        add(AgentMessage.UserMessage(content = "继续"))
        add(AgentMessage.AssistantMessage(content = "干活"))
        repeat(currentToolCalls) { index -> add(tool("now-$index", "readFile")) }
        addAll(currentTodoResults)
    }

    private companion object {
        const val SESSION = "session-1"
        const val TODO = "todo"
    }
}
