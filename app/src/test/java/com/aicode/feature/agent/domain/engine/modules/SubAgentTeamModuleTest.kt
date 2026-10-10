package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.subagent.SubAgentTeamTool
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 团队协同模块：片段与工具的注入门禁、收尾守卫的一次性位点。
 *
 * 重点在「主代理与子代理拿到的东西完全相反」：策略段与团队工具只给主代理，纪律段只给子代理；
 * 守卫则必须只在本会话确实有子代理在跑时拦，且拦一次就收手（否则模型每次收尾都被拦，对话拖死）。
 */
class SubAgentTeamModuleTest {

    private val strategy = "## 团队协同（多子代理）\n- 任务复杂时才拆解。"
    private val rules = "## 你是团队的一员\n- 只做派发给你的那件事。"

    private fun promptProvider(strategyText: String, rulesText: String): Lazy<SystemPromptProvider> {
        val provider = mockk<SystemPromptProvider>()
        every { provider.resolvePrompt("agent/subagent-team.md") } returns strategyText
        every { provider.resolvePrompt("agent/subagent-team-rules.md") } returns rulesText
        val lazyProvider = mockk<Lazy<SystemPromptProvider>>()
        every { lazyProvider.get() } returns provider
        return lazyProvider
    }

    private fun module(
        strategyText: String = strategy,
        rulesText: String = rules,
        tool: SubAgentTeamTool = mockk(relaxed = true),
        dao: ChatSessionDao = mockk(relaxed = true),
        bus: SubAgentEventBus = SubAgentEventBus()
    ) = SubAgentTeamModule(promptProvider(strategyText, rulesText), tool, dao, bus)

    private fun subSession(id: String, parentId: String, title: String = "子代理 $id"): ChatSessionEntity =
        ChatSessionEntity(id = id, title = title, createdAt = 0L, updatedAt = 0L, parentId = parentId)

    private val mainCtx = EngineContext(sessionId = "s1")
    private val subCtx = EngineContext(sessionId = "sub-1", isSubAgent = true, subAgentName = "Explore")

    // ------------------------------------------------------------ 注入门禁

    @Test
    fun promptFragment_onlyForMainAgent() {
        val module = module()
        assertNull("子代理不该拿到主代理的团队策略", module.promptFragment(subCtx))
        val text = module.promptFragment(mainCtx)
        assertTrue("主代理必须拿到团队策略", text != null && text.contains("任务复杂时才拆解"))
    }

    @Test
    fun subAgentRules_onlyForSubAgent() {
        val module = module()
        assertNull("主代理不该拿到子代理的团队纪律", module.subAgentRules(mainCtx))
        val text = module.subAgentRules(subCtx)
        assertTrue("子代理必须拿到团队纪律", text != null && text.contains("只做派发给你的那件事"))
    }

    @Test
    fun tools_onlyForMainAgent() {
        val tool = mockk<SubAgentTeamTool>(relaxed = true)
        val module = module(tool = tool)

        val tools = module.tools(mainCtx)
        assertEquals(1, tools.size)
        assertTrue("主代理的工具必须是注入的那个 team 工具", tools.single() === tool)
        assertTrue("子代理不该拿到团队工具", module.tools(subCtx).isEmpty())
    }

    @Test
    fun emptyTemplateMeansOptOut() {
        // 用户在 prompts.custom/ 里清空文件 = 明确不要这段，此时不能注入空壳
        val module = module(strategyText = "   \n")
        assertNull(module.promptFragment(mainCtx))
    }

    @Test
    fun moduleIdAndOrderAreStable() {
        val module = module()
        assertEquals("subagent-team", module.id)
        // 排在子代理（40）与记忆（50）之间
        assertEquals(45, module.order)
    }

    // ------------------------------------------------------------ 收尾守卫

    @Test
    fun guard_warnsOncePerSessionWhileSubAgentRunning() = runTest {
        val bus = SubAgentEventBus()
        val dao = mockk<ChatSessionDao>()
        coEvery { dao.getSubSessionsByParentOnce("s1") } returns listOf(subSession("sub-1", "s1", "调研 A"))
        bus.emit(SubAgentEvent("sub-1", "s1", SubAgentEventType.SPAWNED))
        val module = module(dao = dao, bus = bus)

        val first = module.finalResponseGuard(mainCtx, "做完了")
        assertTrue("有子代理在跑时必须拦一次", first != null && first!!.contains("1 个子代理在运行"))
        assertNull("同一会话不重复拦", module.finalResponseGuard(mainCtx, "做完了"))
    }

    @Test
    fun guard_silentWithoutActiveSubAgent() = runTest {
        val dao = mockk<ChatSessionDao>()
        coEvery { dao.getSubSessionsByParentOnce(any()) } returns listOf(subSession("sub-1", "s1"))
        // 活跃集合为空：子代理都已结束
        val module = module(dao = dao, bus = SubAgentEventBus())
        assertNull(module.finalResponseGuard(mainCtx, "做完了"))
    }

    @Test
    fun guard_ignoresOtherSessionsRunningSubAgents() = runTest {
        val bus = SubAgentEventBus()
        val dao = mockk<ChatSessionDao>()
        // 本会话没有子会话；活跃的是另一个会话的子代理
        coEvery { dao.getSubSessionsByParentOnce("s1") } returns emptyList()
        bus.emit(SubAgentEvent("sub-9", "s2", SubAgentEventType.SPAWNED))
        val module = module(dao = dao, bus = bus)

        assertNull("别的会话在跑子代理不该拦本会话", module.finalResponseGuard(mainCtx, "做完了"))
    }

    @Test
    fun guard_silentForSubAgentSession() = runTest {
        val bus = SubAgentEventBus()
        bus.emit(SubAgentEvent("sub-1", "s1", SubAgentEventType.SPAWNED))
        val module = module(bus = bus)
        assertNull(module.finalResponseGuard(subCtx, "做完了"))
    }

    @Test
    fun onSessionDeleted_clearsReminderLatch() = runTest {
        val bus = SubAgentEventBus()
        val dao = mockk<ChatSessionDao>()
        coEvery { dao.getSubSessionsByParentOnce("s1") } returns listOf(subSession("sub-1", "s1"))
        bus.emit(SubAgentEvent("sub-1", "s1", SubAgentEventType.SPAWNED))
        val module = module(dao = dao, bus = bus)

        assertTrue(module.finalResponseGuard(mainCtx, "x") != null)
        module.onSessionDeleted(mainCtx)
        assertTrue("位点已清，重建的会话仍能拦", module.finalResponseGuard(mainCtx, "x") != null)
    }
}
