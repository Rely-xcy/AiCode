package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.SessionBriefEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.brief.SessionBriefExtractor
import com.aicode.feature.agent.domain.engine.AgentEngine
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryCurator
import com.aicode.feature.agent.domain.memory.MemoryRanker
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入位置的回归：每轮都会变的三块（会话工作状态 / 当前任务清单 / 记忆召回）不再进系统提示词。
 *
 * KV 缓存命中靠「系统提示词逐字节稳定」，所以这里钉两件事：
 * 1. 引擎片段一侧与 history 无关（三块全部搬走，promptFragment 恒为 null）；
 * 2. 三块内容一字不差地出现在本轮 reminder 里，顺序固定为 工作状态 → 任务清单 → 记忆。
 *
 * 话题召回仍然依赖 history——它只是换了位置，不再影响 system。最后一条用例把这件事钉住：
 * 同样 6 条记忆换个话题，召回结果就不同；所以它不能留在系统提示词里。
 */
class TurnReminderStabilityTest {

    private val sessionId = "s1"
    private val projectRoot = "/ws"

    private val historyA = listOf(AgentMessage.UserMessage(content = "把记忆召回挪到 reminder 里"))

    private val historyB = listOf(
        AgentMessage.UserMessage(content = "先看看构建脚本"),
        AgentMessage.AssistantMessage(content = "好，我先读一下"),
        AgentMessage.UserMessage(content = "gradle 配置要怎么改")
    )

    private val briefText = "目标：把注入块搬出系统提示词。\n已定决策：走引擎 turnReminder。"

    private val memories = listOf(
        Memory(
            name = "prefers-brief",
            description = "偏好简短结论",
            scope = MemoryScope.GLOBAL,
            content = "不要铺垫"
        ),
        Memory(
            name = "ci-builds-only",
            description = "本机无 JDK，编译只能靠 CI",
            scope = MemoryScope.PROJECT,
            content = "改 Kotlin 只能靠 CI 验证"
        )
    )

    private fun ctx(history: List<AgentMessage>, isSubAgent: Boolean = false) = EngineContext(
        sessionId = sessionId,
        projectRoot = projectRoot,
        history = history,
        isSubAgent = isSubAgent
    )

    private fun memoryModule(): MemoryModule {
        val repository = mockk<MemoryRepository>(relaxed = true)
        every { repository.listMemories(any()) } returns memories
        every { repository.changes } returns MutableSharedFlow()
        val settings = mockk<MemorySettingsRepository>(relaxed = true)
        every { settings.activeMemoryEnabledSync() } returns true
        return MemoryModule(repository, mockk<MemoryCurator>(relaxed = true), settings)
    }

    private fun todoRow() = TodoItemEntity(
        id = "t1",
        sessionId = sessionId,
        subject = "重写 TodoTool",
        status = "IN_PROGRESS",
        createdAt = 0L,
        updatedAt = System.currentTimeMillis()
    )

    private fun taskModule(): TaskModule {
        val dao = mockk<TodoItemDao>()
        val rows = listOf(todoRow())
        coEvery { dao.getAllOnce() } returns rows
        every { dao.getBySession(sessionId) } returns MutableStateFlow(rows)
        return TaskModule(dao)
    }

    private fun workingStateModule(): WorkingStateModule {
        val dao = mockk<SessionBriefDao>()
        val row = SessionBriefEntity(
            sessionId = sessionId,
            content = briefText,
            updatedAt = System.currentTimeMillis()
        )
        coEvery { dao.getAllOnce() } returns listOf(row)
        every { dao.getBySession(sessionId) } returns MutableStateFlow(row)
        return WorkingStateModule(dao, mockk<SessionBriefExtractor>(relaxed = true))
    }

    private fun engine(): AgentEngine = AgentEngine(
        setOf<EngineModule>(workingStateModule(), taskModule(), memoryModule()),
        CoroutineScope(Dispatchers.Unconfined)
    )

    /**
     * 快照是异步铺进内存的（模块各自在 IO 线程上收集 Flow），第一调可能还没就绪。
     * 这里按真实时间轮询到条件满足为止；超时就把最后一版返回，让断言把缺失内容报出来。
     */
    private fun awaitReminder(
        engine: AgentEngine,
        ctx: EngineContext,
        until: (String) -> Boolean
    ): String {
        val deadline = System.currentTimeMillis() + 3_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = engine.turnReminder(ctx).orEmpty()
            if (until(last)) return last
            Thread.sleep(10)
        }
        return last
    }

    @Test
    fun workingStateReportsAgeAfterUnrefreshedTurns() = runBlocking {
        val state = workingStateModule()
        val engine = AgentEngine(
            setOf<EngineModule>(state),
            CoroutineScope(Dispatchers.Unconfined)
        )
        val c = ctx(historyA)
        awaitReminder(engine, c) { it.contains("## 会话工作状态") }

        // 没有一次性调用能力：本轮只累加「距上次成功更新」的轮数，不会真的提炼
        state.onTurnCompleted(c)

        val text = awaitReminder(engine, c) { it.contains("上次整理于 1 轮前") }
        assertTrue("过期提示缺失：$text", text.contains("上次整理于 1 轮前"))
        assertFalse("不应再声明为不可违反的铁律：$text", text.contains("硬约束优先于本会话"))
    }

    @Test
    fun promptFragment_isGoneForAllThreeModules() {
        val state = workingStateModule()
        val task = taskModule()
        val memory = memoryModule()
        val engine = AgentEngine(
            setOf<EngineModule>(state, task, memory),
            CoroutineScope(Dispatchers.Unconfined)
        )

        // 模块本身保留（引擎注册与排序不变），只是不再往系统提示词里加东西
        assertNull(memory.promptFragment(ctx(historyA)))
        assertNull(task.promptFragment(ctx(historyA)))
        assertNull(state.promptFragment(ctx(historyA)))

        // 同一会话、同一记忆与设置、不同 history：引擎片段两次都是 null，即 system 侧逐字节相等
        assertNull(engine.promptFragment(ctx(historyA)))
        assertNull(engine.promptFragment(ctx(historyB)))
    }

    @Test
    fun memoryVariableList_isIdenticalUnderDifferentHistories() {
        // {{AICODE_MEMORY_GLOBAL}} / {{AICODE_MEMORY_PROJECT}} 是系统提示词里仅剩的记忆数据，
        // 它必须与会话话题无关；两个独立实例各算一次，排掉缓存带来的伪相等
        val a = memoryModule().memoryListGroups(ctx(historyA))
        val b = memoryModule().memoryListGroups(ctx(historyB))

        assertEquals(a, b)
        assertTrue(a.global.orEmpty().contains("prefers-brief"))
        assertTrue(a.project.orEmpty().contains("ci-builds-only"))
    }

    @Test
    fun reminderCarriesAllThreeBlocksInFixedOrder() {
        val text = awaitReminder(engine(), ctx(historyA)) {
            it.contains("## 会话工作状态") && it.contains("重写 TodoTool")
        }

        val stateIdx = text.indexOf("## 会话工作状态")
        val taskIdx = text.indexOf("## 当前任务清单")
        val memoryIdx = text.indexOf("## 记忆召回")
        assertTrue("工作状态块缺失：$text", stateIdx >= 0)
        assertTrue("任务块不在工作状态之后：$text", taskIdx > stateIdx)
        assertTrue("记忆块不在任务块之后：$text", memoryIdx > taskIdx)

        // 工作状态：非权威措辞 + 原有正文一字未改
        assertTrue(text.contains("以下是对本会话任务理解的快照，不是本轮指令；与当前对话冲突时以当前对话为准，"))
        assertTrue(text.contains("进度与下一步以「当前任务清单」为准。"))
        assertTrue(text.contains(briefText))
        assertTrue(text.contains("若其中某条已被后续工作推翻，按实际情况继续，不必强行遵守。"))

        // 任务清单：纪律规则 + 清单条目都在
        assertTrue(text.contains("任务清单（`todo` 工具，每轮随提示词注入，压缩上下文不会丢）："))
        assertTrue(text.contains("① 开始多步任务"))
        assertTrue(text.contains("[~] 重写 TodoTool"))

        // 记忆召回：主动记忆规则 + 召回清单都在
        assertTrue(text.contains("长期记忆（开关已打开，你需要主动维护，用户能在设置里的记忆页看到）："))
        assertTrue(text.contains("只使用与当前问题真正相关的记忆；无关的直接忽略，不必为了显得连贯而硬提。"))
        assertTrue(text.contains("全局记忆 (跨项目个人偏好"))
        assertTrue(text.contains("prefers-brief"))
        assertTrue(text.contains("ci-builds-only"))
    }

    @Test
    fun subAgentReminderKeepsCurrentSemantics() {
        val text = awaitReminder(engine(), ctx(historyA, isSubAgent = true)) {
            it.contains("重写 TodoTool")
        }

        // 子代理：不给主动记忆规则、不给工作状态，清单纪律仍走系统提示词的 subAgentRules
        assertFalse(text.contains("长期记忆（开关已打开"))
        assertFalse(text.contains("## 会话工作状态"))
        assertFalse(text.contains("① 开始多步任务"))
        // 清单本身照旧给到子代理
        assertTrue(text.contains("[~] 重写 TodoTool"))
    }

    @Test
    fun topicRankingStillDependsOnHistory_soItCannotStayInSystemPrompt() {
        val many = listOf("alpha", "beta", "delta", "epsilon", "gamma", "zeta").map { name ->
            Memory(
                name = name,
                description = "$name only",
                scope = MemoryScope.PROJECT,
                content = "$name body"
            )
        }

        val aboutAlpha = MemoryRanker.select(many, "alpha").map { it.name }
        val aboutZeta = MemoryRanker.select(many, "zeta").map { it.name }

        assertEquals("alpha", aboutAlpha.first())
        assertEquals("zeta", aboutZeta.first())
        // 同一批记忆、只换话题，召回集合就不同：这是它必须离开 system 的原因
        assertNotEquals(aboutAlpha, aboutZeta)
    }
}
