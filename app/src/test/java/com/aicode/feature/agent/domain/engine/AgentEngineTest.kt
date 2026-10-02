package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 引擎调度：片段聚合顺序、异常隔离、工具去重、钩子分发。 */
class AgentEngineTest {

    private val ctx = EngineContext(sessionId = "s1", projectRoot = "/ws")

    private class FakeTool(override val name: String) : AgentTool() {
        override val description = "fake"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Error("unused")
    }

    private class FakeModule(
        override val id: String,
        override val order: Int = EngineModule.DEFAULT_ORDER,
        private val fragment: String? = null,
        private val providedTools: List<AgentTool> = emptyList(),
        private val fragmentThrows: Boolean = false,
        private val hookThrows: Boolean = false,
        private val subAgentFragment: String? = null,
        private val guardReminder: String? = null,
        private val guardThrows: Boolean = false
    ) : EngineModule {
        var turnCompleted = 0
        var sessionDeleted = 0

        override fun promptFragment(ctx: EngineContext): String? {
            if (fragmentThrows) error("fragment boom")
            return fragment
        }

        override fun subAgentRules(ctx: EngineContext): String? = subAgentFragment

        override fun tools(ctx: EngineContext): List<AgentTool> = providedTools

        override suspend fun finalResponseGuard(ctx: EngineContext, finalText: String): String? {
            if (guardThrows) error("guard boom")
            return guardReminder
        }

        override suspend fun onTurnCompleted(ctx: EngineContext) {
            turnCompleted++
            if (hookThrows) error("hook boom")
        }

        override suspend fun onSessionDeleted(ctx: EngineContext) {
            sessionDeleted++
        }
    }

    @Test
    fun promptFragment_joinsByOrderAndSkipsBlank() {
        val late = FakeModule(id = "late", order = 200, fragment = "B")
        val early = FakeModule(id = "early", order = 10, fragment = "A")
        val blank = FakeModule(id = "blank", order = 20, fragment = "   ")
        val silent = FakeModule(id = "silent", order = 30)

        val engine = AgentEngine(setOf(late, early, blank, silent), TestScope())

        assertEquals("A\n\nB", engine.promptFragment(ctx))
    }

    @Test
    fun promptFragment_returnsNullWhenNoModuleContributes() {
        val engine = AgentEngine(setOf(FakeModule(id = "silent")), TestScope())
        assertNull(engine.promptFragment(ctx))
    }

    @Test
    fun promptFragment_oneModuleThrowingDoesNotBreakOthers() {
        val broken = FakeModule(id = "broken", order = 1, fragmentThrows = true)
        val healthy = FakeModule(id = "healthy", order = 2, fragment = "OK")

        val engine = AgentEngine(setOf(broken, healthy), TestScope())

        assertEquals("OK", engine.promptFragment(ctx))
    }

    @Test
    fun tools_keepsFirstOccurrenceByName() {
        val first = FakeModule(id = "a", order = 1, providedTools = listOf(FakeTool("dup"), FakeTool("onlyA")))
        val second = FakeModule(id = "b", order = 2, providedTools = listOf(FakeTool("dup"), FakeTool("onlyB")))

        val engine = AgentEngine(setOf(first, second), TestScope())

        assertEquals(listOf("dup", "onlyA", "onlyB"), engine.tools(ctx).map { it.name })
    }

    @Test
    fun onTurnCompleted_dispatchesToEveryModuleAndIsolatesFailures() = runTest {
        val scope = TestScope(testScheduler)
        val a = FakeModule(id = "a")
        val b = FakeModule(id = "b", hookThrows = true)
        val c = FakeModule(id = "c")

        val engine = AgentEngine(setOf(a, b, c), scope)
        engine.onTurnCompleted(ctx)
        scope.advanceUntilIdle()

        assertEquals(1, a.turnCompleted)
        assertEquals(1, b.turnCompleted)
        assertEquals(1, c.turnCompleted)
    }

    @Test
    fun onSessionDeleted_dispatchesToEveryModule() = runTest {
        val scope = TestScope(testScheduler)
        val module = FakeModule(id = "a")

        val engine = AgentEngine(setOf(module), scope)
        engine.onSessionDeleted(ctx)
        scope.advanceUntilIdle()

        assertEquals(1, module.sessionDeleted)
    }

    @Test
    fun finalResponseGuard_returnsFirstNonBlankAndIsolatesFailures() = runTest {
        val broken = FakeModule(id = "a", order = 1, guardThrows = true)
        val silent = FakeModule(id = "b", order = 2)
        val blank = FakeModule(id = "c", order = 3, guardReminder = "   ")
        val hit = FakeModule(id = "d", order = 4, guardReminder = "清单落后了")
        val later = FakeModule(id = "e", order = 5, guardReminder = "另一个模块的提醒")

        val engine = AgentEngine(setOf(broken, silent, blank, hit, later), TestScope(testScheduler))

        // 抛异常的跳过、空白不算命中、只取第一个非空（拦一次就多一次模型往返，不能叠加）
        assertEquals("清单落后了", engine.finalResponseGuard(ctx, "已完成"))
        assertNull(AgentEngine(setOf(silent), TestScope(testScheduler)).finalResponseGuard(ctx, "已完成"))
    }

    @Test
    fun subAgentRules_joinsByOrderAndSkipsBlank() {
        val late = FakeModule(id = "late", order = 200, subAgentFragment = "B")
        val early = FakeModule(id = "early", order = 10, subAgentFragment = "A")
        val blank = FakeModule(id = "blank", order = 20, subAgentFragment = "  ")

        val engine = AgentEngine(setOf(late, early, blank), TestScope())

        assertEquals("A\n\nB", engine.subAgentRules(ctx))
        assertNull(AgentEngine(setOf(blank), TestScope()).subAgentRules(ctx))
    }

    @Test
    fun subAgentRules_areIndependentOfPromptFragment() {
        // 纪律段不受 inject 门禁：模块没提供 promptFragment 时它照样得出来
        val module = FakeModule(id = "rules", subAgentFragment = "R")
        val engine = AgentEngine(setOf(module), TestScope())

        assertNull(engine.promptFragment(ctx))
        assertEquals("R", engine.subAgentRules(ctx))
    }
}
