package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.memory.MemoryCurator
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆模块的调度：开关、子代理、**按时间触发治理**（不再按轮）。
 *
 * 治理本身（本地规则 + 模型判定）在 [MemoryCurator]，这里只验证「什么时候叫它」。
 */
class MemoryModuleTest {

    private val history = listOf(AgentMessage.UserMessage(content = "以后回答短一点"))

    private class Harness(
        val module: MemoryModule,
        val curator: MemoryCurator,
        val settings: MemorySettingsRepository
    )

    private fun harness(
        settingsEnabled: Boolean = true,
        lastCuratedAt: Long = 0L,
        intervalHours: Int = 24
    ): Harness {
        val repository = mockk<MemoryRepository>(relaxed = true)
        val curator = mockk<MemoryCurator>(relaxed = true)
        val settings = mockk<MemorySettingsRepository>()
        coEvery { settings.autoDistillEnabled() } returns settingsEnabled
        coEvery { settings.curationIntervalHours() } returns intervalHours
        coEvery { settings.lastCuratedAt() } returns lastCuratedAt
        every { repository.listMemories(any()) } returns emptyList()
        // changes 的类型是 SharedFlow，不能用 emptyFlow（那是 Flow）
        every { repository.changes } returns MutableSharedFlow()
        return Harness(MemoryModule(repository, curator, settings), curator, settings)
    }

    private fun ctx(oneShot: (suspend (String, String) -> String?)?) = EngineContext(
        sessionId = "s1",
        projectRoot = "/ws",
        history = history,
        oneShot = oneShot
    )

    /** [MemoryExtractor.parseEntries] 是实例方法（不读仓库、不调模型），给个 relaxed mock 即可。 */
    private fun parser(): MemoryExtractor = MemoryExtractor(mockk(relaxed = true))

    @Test
    fun onTurnCompleted_curatesOnceIntervalElapsed() = runTest {
        val h = harness(lastCuratedAt = System.currentTimeMillis() - 25L * 60 * 60 * 1000)

        h.module.onTurnCompleted(ctx { _, _ -> "[]" })

        coVerify(exactly = 1) { h.curator.curate(projectRoot = "/ws", complete = any()) }
        // 看过就记时间戳：即使这轮没有改动，也不该每轮重看
        coVerify(exactly = 1) { h.settings.setLastCuratedAt(any()) }
    }

    @Test
    fun onTurnCompleted_skipsBeforeIntervalElapsed() = runTest {
        val h = harness(lastCuratedAt = System.currentTimeMillis() - 60L * 1000)

        repeat(5) { h.module.onTurnCompleted(ctx { _, _ -> "[]" }) }

        coVerify(exactly = 0) { h.curator.curate(any(), any()) }
    }

    @Test
    fun onTurnCompleted_doesNothingWhenCurationOff() = runTest {
        // 治理周期选「关闭」（0 小时）：即使从未治理过也不应该跑
        val h = harness(lastCuratedAt = 0L, intervalHours = 0)

        h.module.onTurnCompleted(ctx { _, _ -> "[]" })

        coVerify(exactly = 0) { h.curator.curate(any(), any()) }
    }

    @Test
    fun onTurnCompleted_curatesWhenNeverCurated() = runTest {
        val h = harness(lastCuratedAt = 0L)

        h.module.onTurnCompleted(ctx { _, _ -> "[]" })

        coVerify(exactly = 1) { h.curator.curate(any(), any()) }
    }

    @Test
    fun onTurnCompleted_doesNothingWhenSwitchOff() = runTest {
        val h = harness(settingsEnabled = false, lastCuratedAt = 0L)

        h.module.onTurnCompleted(ctx { _, _ -> "[]" })

        coVerify(exactly = 0) { h.curator.curate(any(), any()) }
    }

    @Test
    fun onTurnCompleted_doesNothingForSubAgentSession() = runTest {
        val h = harness(lastCuratedAt = 0L)

        h.module.onTurnCompleted(ctx { _, _ -> "[]" }.copy(isSubAgent = true))

        coVerify(exactly = 0) { h.curator.curate(any(), any()) }
    }

    @Test
    fun onTurnCompleted_stillCuratesWithoutModelCapability() = runTest {
        // 拿不到一次性调用能力时仍要跑本地那层零成本规则
        val h = harness(lastCuratedAt = 0L)

        h.module.onTurnCompleted(ctx(oneShot = null))

        coVerify(exactly = 1) { h.curator.curate(projectRoot = "/ws", complete = null) }
    }

    @Test
    fun extract_writesParsedEntriesAsProfileWithSource() = runTest {
        val repository = mockk<MemoryRepository>(relaxed = true)
        every { repository.listMemories(any()) } returns emptyList()
        val extractor = MemoryExtractor(repository)
        val output = """
            name: prefers-brief
            description: Prefers brief answers
            content: No preambles.
        """.trimIndent()

        val written = extractor.extract(
            projectRoot = "/ws",
            history = history,
            source = MemoryExtractor.SOURCE_AUTO_DISTILL,
            complete = { output }
        )

        assertEquals(1, written)
        coVerify(exactly = 1) {
            repository.saveMemory(
                "prefers-brief",
                "Prefers brief answers",
                "No preambles.",
                MemoryScope.GLOBAL,
                "/ws",
                MemoryKind.PROFILE,
                MemoryExtractor.SOURCE_AUTO_DISTILL,
                any()
            )
        }
    }

    @Test
    fun extract_ignoresGarbageAndEmptyResults() = runTest {
        val repository = mockk<MemoryRepository>(relaxed = true)
        every { repository.listMemories(any()) } returns emptyList()
        val extractor = MemoryExtractor(repository)

        val garbage = extractor.extract("/ws", history, MemoryExtractor.SOURCE_AUTO_DISTILL) { "抱歉，这轮没有值得记住的内容。" }
        val empty = extractor.extract("/ws", history, MemoryExtractor.SOURCE_AUTO_DISTILL) { "[]" }
        val nothing = extractor.extract("/ws", history, MemoryExtractor.SOURCE_AUTO_DISTILL) { null }

        assertEquals(0, garbage)
        assertEquals(0, empty)
        assertEquals(0, nothing)
        coVerify(exactly = 0) { repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun parseEntries_acceptsTagFormatWithNoise() {
        // 真实模型输出往往带前言、代码块围栏、项目符号、中文冒号、多行 content
        val raw = """
            ```text
            好的，这是本轮的记忆：
            - name: prefers-brief
              description：偏好简短回答
              content: 不要铺垫，直接给结论。
              第二行细节
            ---
            name: build-env
            content: 本地跑不了 gradle，只能靠 CI
            ```
        """.trimIndent()

        val entries = parser().parseEntries(raw)

        assertEquals(2, entries.size)
        assertEquals("prefers-brief", entries[0].name)
        assertEquals("偏好简短回答", entries[0].description)
        assertTrue(entries[0].content.contains("不要铺垫"))
        assertTrue(entries[0].content.contains("第二行细节"))
        assertEquals("build-env", entries[1].name)
    }

    @Test
    fun parseEntries_readsValueWrittenOnNextLine() {
        // 提示词允许标签占一行、值写到下一行
        val raw = """
            name:
            prefers-brief
            description:
            偏好简短回答
            content:
            不要铺垫，直接给结论。
        """.trimIndent()

        val entries = parser().parseEntries(raw)

        assertEquals(1, entries.size)
        assertEquals("prefers-brief", entries[0].name)
        assertEquals("偏好简短回答", entries[0].description)
        assertTrue(entries[0].content.contains("不要铺垫"))
    }

    @Test
    fun parseEntries_keepsLiteralLabelInsideContent() {
        // 正文里单独一行 "name: xxx" 不是新条目，也不能把上一条正文截断
        val raw = """
            name: yaml-scalar
            content: 第一行
            name: 这是正文里的一行，不是新条目
            第三行
        """.trimIndent()

        val entries = parser().parseEntries(raw)

        assertEquals(1, entries.size)
        assertEquals("yaml-scalar", entries[0].name)
        assertTrue(entries[0].content.contains("不是新条目"))
        assertTrue(entries[0].content.contains("第三行"))
    }

    @Test
    fun parseEntries_fallsBackToJson() {
        val raw = """[{"name":"a","description":"d","content":"c"}]"""

        val entries = parser().parseEntries(raw)

        assertEquals(1, entries.size)
        assertEquals("a", entries[0].name)
    }
}
