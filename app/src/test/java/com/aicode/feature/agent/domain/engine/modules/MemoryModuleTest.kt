package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 记忆模块的沉淀行为：开关、子代理、解析容错。 */
class MemoryModuleTest {

    private val history = listOf(AgentMessage.UserMessage(content = "以后回答短一点"))

    private fun module(settingsEnabled: Boolean): Pair<MemoryModule, MemoryRepository> {
        val repository = mockk<MemoryRepository>(relaxed = true)
        val settings = mockk<MemorySettingsRepository>()
        coEvery { settings.autoDistillEnabled() } returns settingsEnabled
        every { repository.listMemories(any()) } returns emptyList()
        return MemoryModule(repository, settings) to repository
    }

    private fun ctx(oneShot: (suspend (String, String) -> String?)?) = EngineContext(
        sessionId = "s1",
        projectRoot = "/ws",
        history = history,
        oneShot = oneShot
    )

    @Test
    fun onTurnCompleted_writesDistilledEntriesAsProfile() = runTest {
        val (module, repository) = module(settingsEnabled = true)
        val json = """[{"name":"prefers-brief","description":"Prefers brief answers","content":"No preambles."}]"""

        // 归约是攒够 DISTILL_EVERY_TURNS 轮才跑一次
        repeat(5) { module.onTurnCompleted(ctx { _, _ -> json }) }

        coVerify(exactly = 1) {
            repository.saveMemory(
                "prefers-brief",
                "Prefers brief answers",
                "No preambles.",
                MemoryScope.GLOBAL,
                "/ws",
                MemoryKind.PROFILE
            )
        }
    }

    @Test
    fun onTurnCompleted_waitsUntilEnoughTurnsBeforeDistilling() = runTest {
        val (module, repository) = module(settingsEnabled = true)

        repeat(4) { module.onTurnCompleted(ctx { _, _ -> """[{"name":"a","content":"b"}]""" }) }

        coVerify(exactly = 0) { repository.saveMemory(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun onTurnCompleted_doesNothingWhenSwitchOff() = runTest {
        val (module, repository) = module(settingsEnabled = false)

        // 攒够轮数也不写（开关关着直接在计数前返回）
        repeat(5) { module.onTurnCompleted(ctx { _, _ -> """[{"name":"a","content":"b"}]""" }) }

        coVerify(exactly = 0) { repository.saveMemory(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun onTurnCompleted_doesNothingForSubAgentSession() = runTest {
        val (module, repository) = module(settingsEnabled = true)
        val subCtx = ctx { _, _ -> """[{"name":"a","content":"b"}]""" }.copy(isSubAgent = true)

        module.onTurnCompleted(subCtx)

        coVerify(exactly = 0) { repository.saveMemory(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun onTurnCompleted_ignoresGarbageAndEmptyResults() = runTest {
        val (module, repository) = module(settingsEnabled = true)

        repeat(5) { module.onTurnCompleted(ctx { _, _ -> "抱歉，这轮没有值得记住的内容。" }) }
        repeat(5) { module.onTurnCompleted(ctx { _, _ -> "[]" }) }
        repeat(5) { module.onTurnCompleted(ctx { _, _ -> null }) }

        coVerify(exactly = 0) { repository.saveMemory(any(), any(), any(), any(), any(), any()) }
    }
}
