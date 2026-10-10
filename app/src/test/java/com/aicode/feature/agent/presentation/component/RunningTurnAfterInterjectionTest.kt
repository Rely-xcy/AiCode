package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话不再终结当前轮。
 *
 * 场景：AI 正在跑一轮（有工具调用链路），用户点队列面板的「立即插入给 AI」。插话落库时会作为一条
 * **普通用户消息**排在本批工具结果之后（见 StatefulAgentWorkflow 的 interjections），而界面「哪一轮
 * 还在跑」原先按「列表里最后一条用户消息」判定（AIChatPanel.activeTurnKey）——插话一落库就把正在跑的
 * 那一轮顶掉：折叠头当场从「执行中」翻成「已完成」、进行中的正文被当成最终回答、还提前挂上操作行。
 *
 * 现在改由运行时真值定位：本轮轮首用户行 id（AIAgentViewModel.currentSessionRunningTurnAnchorId，
 * 与交给 workflow 的 AgentContext.userMessageId 同源）。本用例钉住由此产生的三条判据：
 * 折叠头（[buildChatItems]）、操作行（[actionableResultIds]）、耗时/用量（[computeTaskDurations] /
 * [computeTurnUsage]）——它们都是纯函数，直接给输入断言输出。
 *
 * 消息形状照抄真实落库顺序：助手行（带工具调用）→ 同批工具结果 → 插话行 → 下一批工具结果 → 进行中的助手行。
 */
class RunningTurnAfterInterjectionTest {

    private fun user(id: String, ts: Long) = AgentUIMessage(
        id = id,
        role = MessageRole.USER,
        content = "用户说的话",
        timestamp = ts
    )

    /** 运行中插话：落库时 isInterjection = true（UI 据此不开新轮头）。 */
    private fun interjection(id: String, ts: Long) =
        user(id, ts).copy(isInterjection = true)

    private fun assistant(id: String, ts: Long) = AgentUIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "助手的正文",
        timestamp = ts
    )

    private fun tool(id: String, ts: Long) = AgentUIMessage(
        id = id,
        role = MessageRole.TOOL,
        content = "工具结果",
        timestamp = ts,
        toolName = "Bash"
    )

    /** 本轮轮首用户行 id（运行时真值，界面拿它算 activeTurnKey）。 */
    private val anchorId = "u0"

    /** 插话行的 id（它是一条普通用户消息，不是轮首）。 */
    private val interjectionId = "i1"

    /**
     * 一轮进行中、中途插过话的消息列表：
     * u0 本轮开始 → a1 助手（发起工具调用）→ t1 同批工具结果 → i1 插话落库 → t2 下一批工具结果 → a2 进行中。
     *
     * 注意 i1 的落库位置在工具行之后（workflow 先发 ToolCallFinished 再发 UserMessageAdded），
     * 这是真实顺序，不是构造出来的方便形状。
     * 插话不再开新轮：i1 现在带 isInterjection = true，归入 turn:u0 的正文，时间线原位显示。
     */
    private fun messagesWithInterjection() = listOf(
        user(anchorId, 1_000),
        assistant("a1", 2_000),
        tool("t1", 2_500),
        interjection(interjectionId, 3_000),
        tool("t2", 3_500),
        assistant("a2", 4_000)
    )

    /**
     * 插话紧跟助手行的形状（LLM 流式结束后的检查点送达时正是这个形状）。
     * 用于耗时/用量那两条用例。
     */
    private fun messagesWithInterjectionRightAfterAssistant() = listOf(
        user(anchorId, 1_000),
        assistant("a1", 2_000),
        interjection(interjectionId, 3_000),
        assistant("a2", 4_000)
    )

    // ---- 折叠头：正在跑的那一轮不翻「已完成」 ----

    /**
     * 插话带 isInterjection = true 时不开新轮头：整个运行只有 turn:u0 一轮，插话与后续工具、
     * 正文都归入该轮。旧实现（插话开新轮）下 items 会出现第二个轮头，第一条断言即红。
     */
    @Test
    fun `插话不开新轮头，仍归入当前轮`() {
        val items = buildChatItems(
            messages = messagesWithInterjection(),
            groupOverrides = emptyMap(),
            turnOverrides = emptyMap(),
            activeTurnKey = turnKeyOf(anchorId)
        )
        val headers = items.mapNotNull { it.turnHeader }
        assertEquals("插话不开新轮，整个运行只有本轮一个轮头", listOf("turn:u0"), headers.map { it.key })
        assertTrue("本轮必须是进行中", headers.single().running)
        // 插话在时间线原位：不是轮首用户行（turn.userMessage），而是轮内一条普通用户气泡。
        val bubble = items.first { it.message.id == interjectionId }
        assertEquals(interjectionId, bubble.key)
        assertTrue("插话仍是普通用户气泡", bubble.message.role == MessageRole.USER)
    }

    /** 普通用户消息（isInterjection = false）仍开新轮：这是插话豁免的对照组。 */
    @Test
    fun `普通用户消息仍开新轮`() {
        val items = buildChatItems(
            messages = listOf(
                user(anchorId, 1_000),
                assistant("a1", 2_000),
                user("u2", 3_000)
            ),
            groupOverrides = emptyMap(),
            turnOverrides = emptyMap(),
            activeTurnKey = turnKeyOf(anchorId)
        )
        assertEquals("普通用户消息开新轮", listOf("turn:u0", "turn:u2"), items.mapNotNull { it.turnHeader?.key })
    }

    /**
     * 旧实现下必红：旧的 running 判据是「turn.key == activeTurnKey」，只认相等。把它用在
     * turn:i1（插话开的那一轮）上，running 恒为 false，折叠头渲染成「已完成 Xs」（MessageBubbles 的
     * TurnCollapseHeader），断言即红。
     *
     * 另一条同样必红的路：旧调用方传进来的 activeTurnKey 是「最后一条用户消息」的 key（turn:i1），
     * 那么本轮轮首那一轮（turn:u0）反而是 false——第一条断言在旧调用链下就红。两条合起来说明：
     * 只要插话在中途落库，旧实现必然把正在跑的这一轮判成已完成。
     *
     * 插话不再开新轮后，本用例主要钉「唯一那轮的 running 判定」。
     */
    @Test
    fun `插话落库后正在跑的那一轮仍显示执行中`() {
        val items = buildChatItems(
            messages = messagesWithInterjection(),
            groupOverrides = emptyMap(),
            turnOverrides = emptyMap(),
            activeTurnKey = turnKeyOf(anchorId)
        )
        val headers = items.mapNotNull { it.turnHeader }.associateBy { it.key }
        assertTrue("本轮轮首那一轮必须是进行中", headers.getValue("turn:u0").running)
    }

    // ---- 操作行：进行中的正文不挂「复制 / 更多」 ----

    /**
     * 旧实现下必红：这条判据当时内联在组合里、拿不到 activeTurnKey，只按「末轮 + isBusy」算
     * （turnFinished = !isLastTurn || !isBusy）。同一份 messages、同样的忙碌状态下，turn:u0 不是末轮，
     * 于是被判为已收尾 → 它最后一条可挂消息 a1（进行中的正文）拿到操作行，集合非空，断言即红。
     */
    @Test
    fun `插话落库后进行中的正文不挂操作行`() {
        val ids = actionableResultIds(messagesWithInterjection(), activeTurnKey = turnKeyOf(anchorId), busy = true)
        assertTrue("本轮还没收工，任何一条都不该挂复制 / 更多", ids.isEmpty())
    }

    /** 收工后（空闲、无锚点）历史照旧：每一轮的末条正文都能挂操作行。此为「没退回」钉子，两版实现都绿。 */
    @Test
    fun `收工后历史上每轮的末条正文照常能挂操作行`() {
        val ids = actionableResultIds(messagesWithInterjection(), activeTurnKey = null, busy = false)
        assertEquals(setOf("a1", "a2"), ids)
    }

    // ---- 耗时 / 用量：正在跑的轮不结算 ----

    /**
     * 旧实现下必红：splitTurns 只在「助手行的下一条用户消息」处结一轮。u0 那一轮在 a1 处被 i1（插话）
     * 判定为轮末 → 结算出耗时 durations = {a1 -> 1000}（且 lastTurnFinished=false 也救不了它：它只
     * 管列表末条），断言即红。
     */
    @Test
    fun `插话紧跟助手行时也不给正在跑的轮结算耗时`() {
        val durations = computeTaskDurations(
            messagesWithInterjectionRightAfterAssistant(),
            lastTurnFinished = false,
            runningTurnStartId = anchorId
        )
        assertTrue("正在跑的轮不该出现「本轮总耗时」", durations.isEmpty())
    }

    /** 同上：旧实现下必红（a1 会拿到这一轮的 token 合计），新实现不给正在跑的轮结算用量。 */
    @Test
    fun `插话紧跟助手行时也不给正在跑的轮结算 token 合计`() {
        val usage = computeTurnUsage(
            messagesWithInterjectionRightAfterAssistant(),
            lastTurnFinished = false,
            runningTurnStartId = anchorId
        )
        assertTrue("正在跑的轮不该出现 token 合计行", usage.isEmpty())
    }

    /** 收工后耗时口径不变：整轮从轮首用户消息算到轮末助手行。此为「没退回」钉子，两版实现都绿。 */
    @Test
    fun `收工后耗时口径不变`() {
        val durations = computeTaskDurations(
            messagesWithInterjectionRightAfterAssistant(),
            lastTurnFinished = true,
            runningTurnStartId = null
        )
        // 插话不再断轮：整轮从 u0 算到 a2（4000-1000），只有 a2 一条轮末助手行。
        assertEquals(3_000L, durations["a2"])
    }

    // ---- 插话本身：仍是普通用户消息，不降级 ----

    /** 此为「没退回」钉子（两版实现都绿）：插话照常作为一条普通用户消息渲染，不是后台通知条。 */
    @Test
    fun `插话照常作为普通用户消息存在`() {
        val items = buildChatItems(
            messages = messagesWithInterjection(),
            groupOverrides = emptyMap(),
            turnOverrides = emptyMap(),
            activeTurnKey = turnKeyOf(anchorId)
        )
        val bubble = items.first { it.message.id == interjectionId }
        assertEquals(MessageRole.USER, bubble.message.role)
        assertFalse("插话不是后台通知条", bubble.message.isBackgroundNotification)
        assertTrue("插话带 isInterjection 标记", bubble.message.isInterjection)
    }

    /** 收工后（空闲）带插话的历史：本轮从 u0 到 a2 只结一次，插话不产生第二次结算。 */
    @Test
    fun `收工后用量不因插话多结算一轮`() {
        val usage = computeTurnUsage(
            messagesWithInterjectionRightAfterAssistant(),
            lastTurnFinished = true,
            runningTurnStartId = null
        )
        assertEquals("插话不开新轮，只有 a2 一条轮末助手行", setOf("a2"), usage.keys)
    }
}
