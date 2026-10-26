package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定 + 整轮任务折叠。
 *
 * 分组展开与否 = **上层持久化的手动选择**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取）：
 * 没有手动记录就一律收起——工具调用统一默认不展开。
 *
 * 整轮折叠（最外层）只认 [AIAgentViewModel.turnExpansionOverrides]；没记录时「末轮运行中默认展开、
 * 其余默认收起」，任务收工由 activeTurnKey 归 null 自动收起。
 *
 * 构建时过程项归入 [ChatRenderItem.turnProcess]，随后展开为独立列表项，保持追加与分页前后的布局结构稳定。
 */
class ToolGroupExpansionTest {

    private fun user(id: String) =
        AgentUIMessage(id = id, role = MessageRole.USER, content = "hi")

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
        turnOverrides: Map<String, Boolean> = emptyMap(),
        activeTurnKey: String? = null,
    ) = buildChatItems(messages, overrides, turnOverrides, activeTurnKey)

    /** 顶层 item 里唯一的轮头 item。 */
    private fun List<ChatRenderItem>.header() = first { it.turnHeader != null }

    /** 所有轮头携带的工具分组项（按轮头顺序展开）。 */
    private fun List<ChatRenderItem>.groupItems(): List<ChatRenderItem> =
        flatMap { it.turnProcess }.filter { it.toolGroup != null }

    @Test
    fun compactionMarkerDoesNotStartNewTurn() {
        val marker = user("marker").copy(isCompactionMarker = true)
        val summary = assistant("summary").copy(isContextSummary = true)
        val rendered = items(
            listOf(user("u0"), tool("t1"), marker, summary, assistant("answer")),
            activeTurnKey = "turn:u0"
        )
        // marker / summary 是过程性卡片：随整轮折叠进同一个轮头，不新开轮、也不常显为顶层 item。
        val headers = rendered.filter { it.turnHeader != null }
        assertEquals(1, headers.size)
        assertEquals("turn:u0", headers.single().turnHeader!!.key)
        assertFalse(rendered.any { it.key == "marker" || it.key == "summary" })
        assertTrue("压缩标记折进轮头过程项", headers.single().turnProcess.any { it.key == "marker" })
        assertTrue("上下文摘要折进轮头过程项", headers.single().turnProcess.any { it.key == "summary" })
    }

    @Test
    fun attachmentTool_keepsTimelinePosition_notPushedToTurnEnd() {
        // 带附件的工具（sendFile / generateImage 产出的文件行）是常显项：停在自己的时间线位置，
        // 不推到该轮最末；晚于它的过程项另起一段轮头。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val send = tool("send").copy(toolName = "sendFile", attachments = listOf(file))
        val rendered = items(
            listOf(user("u0"), tool("t1"), tool("t2"), send, tool("t3"), tool("t4")),
            turnOverrides = mapOf("turn:u0" to true),
        )
        val headers = rendered.filter { it.turnHeader != null }
        assertEquals(
            listOf("turn:u0#header", "turn:u0#header#1"),
            headers.map { it.key },
        )
        assertEquals(
            listOf("toolgroup:t1", "toolgroup:t3"),
            headers.map { it.turnProcess.single().key },
        )
        val sendIndex = rendered.indexOfFirst { it.key == "send" }
        assertTrue("常显项停在自己的位置：两段过程之间", sendIndex in 1 until rendered.lastIndex)
        assertTrue(
            "常显项不折进任何一段过程项",
            headers.none { head -> head.turnProcess.any { it.key == "send" } },
        )
    }

    // ---- 连续工具调用分组 ----

    @Test
    fun expandedTurn_groupCollapsesByDefault() {
        // 整轮展开（末轮运行中）时，连续工具分组仍默认收起
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val groups = items.groupItems()
        assertEquals(1, groups.size)
        assertFalse(groups.first().groupExpanded)
    }

    @Test
    fun singleTool_isNotGrouped() {
        // 只有一条工具时不折成「1 次工具调用」，直接当普通工具行显示
        val items = items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0")
        assertEquals(0, items.groupItems().size)
        assertTrue(items.header().turnProcess.any { it.key == "t1" })
    }

    @Test
    fun noGroupAutoExpands() {
        // 回归：曾经「组内还在跑」或「本轮仍在进行且这是最后一个分组」会自动弹开整组。
        // 现在一律默认收起，历史分组与最新分组一视同仁。
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, activeTurnKey = "turn:u0").groupItems()
        assertEquals(2, groups.size)
        assertTrue("默认全部收起", groups.none { it.groupExpanded })
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(user("u0"), tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(expanded.groupItems().first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(rebuilt.groupItems().first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
            activeTurnKey = "turn:u0",
        )
        assertFalse("手动收起过：保持收起", items.groupItems().first().groupExpanded)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            user("u1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, turnOverrides = mapOf("turn:u0" to true, "turn:u1" to true)).groupItems()
        assertEquals(2, groups.size)
        assertEquals("toolgroup:t1", groups[0].key)
        assertEquals("toolgroup:t3", groups[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // sendFile / generateImage 产出的文件行就是结果本身：不参与「N 次工具调用」折叠，
        // 也不参与整轮折叠（常显）。它自己是一级 item，同时把左右两批工具切开。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, activeTurnKey = "turn:u0")
        // send 是常显项：顶层独立 item
        assertTrue("带附件的工具是顶层常显项", items.any { it.key == "send" })
        val groups = items.groupItems()
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), groups.map { it.key })
        assertTrue("分组默认收起", groups.none { it.groupExpanded })
        // send 不是任何分组的成员
        assertFalse(groups.any { group -> group.toolGroup.orEmpty().any { it.id == "send" } })
    }

    // ---- 整轮任务折叠 ----

    @Test
    fun activeTurn_expandsByDefault() {
        // 末轮且 agent 忙：折叠头显示「执行中 · N 步」且**默认展开**——运行中的过程实时演示
        // 必须可见；且展开态不能在运行中从「几十个过程项」缩成一个折叠头，否则 item 数骤减会把视口顶到上面。
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val header = items.header().turnHeader!!
        assertTrue(header.running)
        assertTrue("运行中默认展开", header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun activeTurn_keepsAssistantBeforeFollowingTools() {
        val items = items(
            listOf(user("u0"), assistant("a1"), tool("t1"), tool("t2")),
            activeTurnKey = "turn:u0",
        )
        assertEquals(listOf("a1", "toolgroup:t1"), items.header().turnProcess.map { it.key })
        assertFalse(items.any { it.key == "a1" })
    }

    @Test
    fun activeTurn_keepsSuccessiveAssistantMessagesInOrder() {
        val messages = listOf(user("u0"), assistant("a1"), tool("t1"), assistant("a2"))
        val running = items(messages, activeTurnKey = "turn:u0")
        assertEquals(listOf("a1", "t1", "a2"), running.header().turnProcess.map { it.key })
        assertFalse(running.any { it.key == "a2" })

        val finished = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertEquals(listOf("a1", "t1"), finished.header().turnProcess.map { it.key })
        assertEquals(1, finished.count { it.key == "a2" })
    }

    @Test
    fun finishedTurn_autoCollapses() {
        // 收工（activeTurnKey 归 null）：折叠头转为「已完成」并默认收起；过程数据仍在，由动画收起
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = null)
        val header = items.header().turnHeader!!
        assertFalse(header.running)
        assertFalse(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun manualTurnExpand_survivesFinish() {
        // 用户手动展开过：任务收工也不自动收起
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            turnOverrides = mapOf("turn:u0" to true),
            activeTurnKey = null,
        )
        val header = items.header().turnHeader!!
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    // ---- 窗口按条数截断：最前面那一截半轮 ----

    @Test
    fun truncatedWindow_foldsLeadingHalfTurn() {
        // 窗口从某一轮中间切开时，最前面那段缺的是轮首用户消息、不是折叠头：
        // 过程项同样要折进折叠头，不能摊在历史最前面
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "答"),
        )
        val rendered = items(messages)
        val heads = rendered.filter { it.turnHeader != null }
        assertEquals("只有截断那一截需要补折叠头", 1, heads.size)
        val leading = heads.first().turnHeader!!
        assertEquals("turn:head:t2", leading.key)
        assertFalse(leading.running)
        assertFalse("默认收起", leading.expanded)
        assertEquals(listOf("a1", "toolgroup:t1"), heads.first().turnProcess.map { it.key })
        assertFalse("过程项不再占顶层 item", rendered.any { it.key == "a1" || it.key == "toolgroup:t1" })
    }

    @Test
    fun truncatedWindow_keepsTrailingAnswerVisible() {
        // 这一截的结尾通常就是上一轮的最终回复：它照旧常显，不跟着过程一起折起来
        val messages = listOf(
            tool("t1"), tool("t2"),
            AgentUIMessage(id = "a9", role = MessageRole.ASSISTANT, content = "上一轮的答案"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "本轮答案"),
        )
        val rendered = items(messages)
        val leadingHead = rendered.first { it.turnHeader?.key == "turn:head:a9" }
        assertEquals(listOf("toolgroup:t1"), leadingHead.turnProcess.map { it.key })
        assertTrue("上一轮答案常显", rendered.any { it.key == "a9" })
        assertFalse(rendered.first { it.key == "a9" }.reasoningVisible)
    }

    @Test
    fun truncatedWindow_headKeySurvivesOlderMessagesPrepended() {
        // 窗口继续向前补加载（新消息插在块首）时块尾不变、key 不变，手动展开态跟着保住
        val older = listOf(assistant("m0"), tool("m1"))
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"), user("u0"))
        val before = items(tail, turnOverrides = mapOf("turn:head:t2" to true))
        val after = items(older + tail, turnOverrides = mapOf("turn:head:t2" to true))
        assertTrue(before.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
        assertTrue(after.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
    }

    @Test
    fun truncatedWindow_manualExpansionSurvivesToolUpdatesAndWindowShift() {
        // turnKeys 还没有交接记录时（首帧 / 消息未就绪），块首半轮的折叠头 key 退化为「按会话」：
        // 分页窗口两端都可能随工具输出移动，只有所属会话是稳定的，手动展开态不会被块尾变化带走。
        val overrides = mapOf("turn:head:session:s1" to true)
        val shifted = buildChatItems(
            listOf(tool("t2"), tool("t3"), tool("t4")), emptyMap(), overrides, sessionId = "s1"
        ).header().turnHeader!!
        assertEquals("turn:head:session:s1", shifted.key)
        assertTrue(shifted.expanded)
        val prepended = buildChatItems(
            listOf(assistant("older"), tool("t2"), tool("t3"), tool("t4")),
            emptyMap(), overrides, sessionId = "s1",
        ).header().turnHeader!!
        assertEquals("turn:head:session:s1", prepended.key)
        assertTrue("窗口向前补加载，手动展开态保住", prepended.expanded)
        val grown = buildChatItems(
            listOf(assistant("a1"), tool("t1"), tool("t2"), tool("t3")),
            emptyMap(), overrides, sessionId = "s1",
        ).header().turnHeader!!
        assertEquals("块尾变了，key 仍是会话级的", "turn:head:session:s1", grown.key)
        assertTrue(grown.expanded)
        val collapsed = buildChatItems(
            listOf(assistant("older"), tool("t2"), tool("t3"), tool("t4")),
            emptyMap(), mapOf("turn:head:session:s1" to false), sessionId = "s1",
        ).header().turnHeader!!
        assertFalse("手动收起同样按会话 key 生效", collapsed.expanded)
    }

    @Test
    fun backgroundNotification_foldsIntoProcessItems() {
        // 后台通知是过程性提示，随整轮折叠（上游语义）：不占顶层 item，也不另起折叠头。
        val notice = AgentUIMessage(id = "n1", role = MessageRole.USER, content = "done")
            .copy(isBackgroundNotification = true)
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            notice,
        )
        val keys = reconcileTurnKeys(messages, emptyMap(), "s1")
        val rendered = buildChatItems(messages, emptyMap(), emptyMap(), sessionId = "s1", turnKeys = keys)
        val head = rendered.first { it.turnHeader != null }
        assertEquals("turn:head:session:s1", head.turnHeader!!.key)
        assertFalse("通知不再常显为顶层 item", rendered.any { it.key == "n1" })
        assertTrue("通知折进轮头过程项", head.turnProcess.any { it.key == "n1" })
        assertEquals("通知不新开轮", 1, rendered.count { it.turnHeader != null })
    }

    @Test
    fun longProcess_expandedToolsBecomeIndependentLazyItems() {
        val messages = listOf(user("u0")) + (1..20).map { tool("t$it") }
        val expanded = flattenChatProcesses(items(
            messages,
            overrides = mapOf(groupKey to true),
            turnOverrides = mapOf("turn:u0" to true),
        ))
        assertTrue(expanded.header().turnProcess.isEmpty())
        assertEquals(20, expanded.count { it.groupMember })
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
        val collapsed = flattenChatProcesses(items(messages))
        assertEquals(2, collapsed.size)
        assertFalse(collapsed.any { it.groupMember })
        val groupCollapsed = flattenChatProcesses(items(
            messages, turnOverrides = mapOf("turn:u0" to true)
        ))
        assertEquals(3, groupCollapsed.size)
        assertFalse(groupCollapsed.any { it.groupMember })
    }

    @Test
    fun shortProcess_usesIndependentItems() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0"
        ))
        assertTrue(rendered.header().turnProcess.isEmpty())
        assertTrue(rendered.any { it.key == "t1" })
    }

    @Test
    fun newGroup_preservesExpandedSingleTool() {
        val rendered = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf("t1" to true),
            activeTurnKey = "turn:u0",
        )
        assertTrue(rendered.groupItems().single().groupExpanded)
        val collapsed = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf("t1" to true, groupKey to false),
            activeTurnKey = "turn:u0",
        )
        assertFalse(collapsed.groupItems().single().groupExpanded)
    }

    @Test
    fun prependedTools_preserveExpandedGroup() {
        val rendered = items(
            listOf(user("u0"), tool("older"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to true),
            activeTurnKey = "turn:u0",
        )
        assertTrue(rendered.groupItems().single().groupExpanded)
        val collapsed = items(
            listOf(user("u0"), tool("older"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false, "t1" to true),
            activeTurnKey = "turn:u0",
        )
        assertFalse(collapsed.groupItems().single().groupExpanded)
    }

    @Test
    fun processGrowth_keepsExistingLazyItemKeys() {
        fun render(count: Int) = flattenChatProcesses(items(
            listOf(user("u0")) + (1..count).map { tool("t$it") },
            overrides = mapOf(groupKey to true),
            activeTurnKey = "turn:u0",
        ))
        val before = render(8)
        val after = render(9)
        assertTrue(before.header().turnProcess.isEmpty())
        assertEquals(before.map { it.key }, after.take(before.size).map { it.key })
    }

    @Test
    fun loadingTurnStart_keepsHeadKeyFromTurnKeys() {
        // 块首半轮的折叠头 key 走 turnKeys 查表（reconcileTurnKeys 的产出），不再按块尾消息 id 现拼成
        // turn:head:<尾 id>：轮首补进窗口时 reconcile 沿用半轮时期那一套 key，渲染层的头 key 因此不变。
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"))
        val previousKeys = reconcileTurnKeys(tail, emptyMap(), "s1")
        val headKey = previousKeys.getValue("a1")
        assertEquals("turn:head:session:s1", headKey)
        val half = buildChatItems(
            tail, emptyMap(), mapOf(headKey to true), sessionId = "s1", turnKeys = previousKeys,
        )
        assertEquals(headKey, half.header().turnHeader!!.key)
        assertTrue("手动展开按 turnKeys 给出的 key 命中", half.header().turnHeader!!.expanded)
        val full = listOf(user("u0")) + tail
        val nextKeys = reconcileTurnKeys(full, previousKeys, "s1")
        assertEquals("轮首补进来仍沿用半轮时期的 key", headKey, nextKeys.getValue("u0"))
        val rendered = buildChatItems(
            full, emptyMap(), mapOf(headKey to true), sessionId = "s1", turnKeys = nextKeys,
        )
        assertEquals(headKey, rendered.header().turnHeader?.key)
        assertTrue(rendered.header().turnHeader!!.expanded)
        val collapsed = buildChatItems(
            full, emptyMap(), mapOf(headKey to false), sessionId = "s1", turnKeys = nextKeys,
        )
        assertFalse(collapsed.header().turnHeader!!.expanded)
    }

    @Test
    fun prependingTurnHead_keepsManualExpansion() {
        // 半轮先出现（轮首被分页翻出去）、用户点开、轮首随后补进窗口：渲染按 turnKeys 交接的 key 走，
        // 手动展开态因此保留。本地旧实现半轮取 turn:head:<尾 id>、整轮取 turn:<轮首 id>，两个 key
        // 对不上，展开态在轮首补进来的那一刻丢失。
        val tail = listOf(assistant("a1"), tool("t1"))
        val previousKeys = mapOf("a1" to "turn:u0", "t1" to "turn:u0")
        val half = buildChatItems(
            tail, emptyMap(), mapOf("turn:u0" to true), sessionId = "s1", turnKeys = previousKeys,
        )
        assertEquals("turn:u0", half.header().turnHeader!!.key)
        assertTrue(half.header().turnHeader!!.expanded)
        val full = listOf(user("u0")) + tail
        val nextKeys = reconcileTurnKeys(full, previousKeys, "s1")
        assertEquals("turn:u0", nextKeys.getValue("u0"))
        val restored = buildChatItems(
            full, emptyMap(), mapOf("turn:u0" to true), sessionId = "s1", turnKeys = nextKeys,
        )
        assertEquals("turn:u0", restored.header().turnHeader!!.key)
        assertTrue("轮首补进窗口后手动展开态保留", restored.header().turnHeader!!.expanded)
    }

    @Test
    fun slidingWindow_truncatedRunningTurnStaysRunning() {
        val full = listOf(user("u0"), assistant("a1"), tool("t1"))
        val previousKeys = reconcileTurnKeys(full, emptyMap(), "s1")
        val window = full.drop(1) + tool("t2")
        val nextKeys = reconcileTurnKeys(window, previousKeys, "s1")
        // 窗口滑掉轮首用户行后，reconcile 仍把这一截认在滑动前的那一轮上。
        assertEquals("turn:u0", nextKeys.getValue("t2"))
        val rendered = buildChatItems(
            window, emptyMap(), activeTurnKey = "turn:u0", sessionId = "s1", turnKeys = nextKeys,
        )
        // 头 key 来自 turnKeys 查表（不是按块尾 id 现拼的 turn:head:t2），所以这一截仍认得出
        // 自己就是正在跑的那一轮：running 与默认展开都跟着成立。
        val head = rendered.header().turnHeader!!
        assertEquals("turn:u0", head.key)
        assertTrue(head.running)
        assertTrue("运行中默认展开", head.expanded)
        // 进行中的半轮结尾不是「结果」，不折成顶层常显项。
        assertFalse(rendered.any { it.key == "t2" })
        // 收工后（activeTurnKey 归 null）同一截转成已完成、默认收起。
        val idle = buildChatItems(
            window, emptyMap(), activeTurnKey = null, sessionId = "s1", turnKeys = nextKeys,
        ).header().turnHeader!!
        assertFalse(idle.running)
        assertFalse(idle.expanded)
    }

    @Test
    fun prependingOtherTurns_keepsTurnIdentitiesSeparate() {
        val tail = listOf(assistant("a1"), tool("t1"), user("u2"), tool("t2"))
        val previousKeys = reconcileTurnKeys(tail, emptyMap(), "s1")
        val full = listOf(user("u0"), tool("t0"), user("u1")) + tail
        val nextKeys = reconcileTurnKeys(full, previousKeys, "s1")
        assertEquals("turn:u0", nextKeys.getValue("u0"))
        assertEquals(previousKeys.getValue("a1"), nextKeys.getValue("u1"))
        assertEquals("turn:u2", nextKeys.getValue("u2"))
        val rendered = flattenChatProcesses(buildChatItems(
            full, emptyMap(), turnKeys = nextKeys,
        ))
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
    }

    @Test
    fun reloadingEvictedTurn_preservesItsHeaderAndExpansion() {
        val history = mutableMapOf<String, String>()
        val tail = listOf(assistant("a1"), tool("t1"))
        history.putAll(reconcileTurnKeys(tail, history, "s1"))
        val halfKey = history.getValue("a1")
        val full = listOf(user("u1")) + tail
        history.putAll(reconcileTurnKeys(full, history, "s1"))
        val newer = listOf(user("u2"), tool("t2"))
        history.putAll(reconcileTurnKeys(newer, history, "s1"))
        val reloaded = full + newer
        val keys = reconcileTurnKeys(reloaded, history, "s1")
        // reconcile 的交接不变：轮首补进窗口后仍沿用半轮时期那一套 key。
        assertEquals(halfKey, keys.getValue("u1"))
        // 渲染层的轮头 key 取 turnKeys[u1]（= 半轮时期那一套），不再换成 turn:<轮首 id>：
        // 被窗口翻出去再装回来时，手动展开态仍按同一个 key 命中。
        val rendered = flattenChatProcesses(buildChatItems(
            reloaded, emptyMap(), mapOf(halfKey to true), turnKeys = keys,
        ))
        assertTrue(rendered.first { it.turnHeader?.key == halfKey }.turnHeader!!.expanded)
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
    }

    @Test
    fun searchResult_targetsAnswerInsteadOfItsReasoning() {
        val answer = assistant("a1").copy(reasoning = "thinking")
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), answer), turnOverrides = mapOf("turn:u0" to true),
        ))
        assertEquals("a1", rendered[findChatMessageIndex(rendered, "a1")].key)
    }

    @Test
    fun searchResult_targetsFirstContentChunkInsteadOfReasoning() {
        val answer = assistant("a1").copy(
            content = (1..100).joinToString("\n\n") { "paragraph $it " + "content ".repeat(20) },
            reasoning = "thinking",
        )
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), answer), turnOverrides = mapOf("turn:u0" to true),
        ))
        assertEquals("a1#chunk0", rendered[findChatMessageIndex(rendered, "a1")].key)
        assertEquals(-1, findChatMessageIndex(rendered, "missing"))
    }

    @Test
    fun plainTextTurn_hasNoHeader() {
        // 无过程（无思考、无工具）的纯文本轮：退化为现状，不加折叠头
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "直接回答"),
        )
        val items = items(messages, activeTurnKey = null)
        assertTrue(items.none { it.turnHeader != null })
        assertTrue(items.any { it.key == "a1" })
    }

    @Test
    fun interjectionDoesNotStartNewTurn() {
        // 运行中插话（isInterjection = true）落库但 UI 不渲染：splitChatTurns 剔除后，
        // 轮次切分与无插话时完全一致，且插话行不出现在任何 item 上。
        val interjection = user("i1").copy(isInterjection = true)
        val messages = listOf(user("u0"), tool("t1"), interjection, tool("t2"), assistant("a1"))
        val items = items(messages, activeTurnKey = "turn:u0")
        val headers = items.mapNotNull { it.turnHeader }
        assertTrue("插话不开新轮头，所有轮头都属于 turn:u0", headers.isNotEmpty() && headers.all { it.key == "turn:u0" })
        assertFalse("插话不渲染为独立气泡", items.any { it.key == "i1" })
    }

    @Test
    fun resultReasoning_becomesProcessItem() {
        // 轮末助手的思考抽为过程项；结果正文块不再重复渲染思考
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "最终答案", reasoning = "想一下"),
        )
        val items = items(messages, turnOverrides = mapOf("turn:u0" to true), activeTurnKey = null)
        assertTrue("思考成为轮头过程项", items.header().turnProcess.any { it.key == "a1#reasoning" })
        assertFalse("结果正文块不重复渲染思考", items.first { it.key == "a1" }.reasoningVisible)
    }
}
