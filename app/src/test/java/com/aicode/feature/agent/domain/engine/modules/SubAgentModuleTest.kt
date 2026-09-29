package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.schedule.WriteLeaseRegistry
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理模块：固定纪律段的注入条件与角色名渲染、会话删除时的写范围释放。
 *
 * 重点在「不受 inject 门禁」：纪律段缺失意味着子代理会凭记忆写 API，所以这里必须钉住
 * 「是子代理就一定注入、不是子代理一定不注入」。
 */
class SubAgentModuleTest {

    private val rules = "当前角色 (subagent: {{SUBAGENT_NAME}})：你是子代理。\n\n# 子代理硬规则\n- 不凭记忆写 API。"

    private fun promptProvider(text: String): Lazy<SystemPromptProvider> {
        val provider = mockk<SystemPromptProvider>()
        every { provider.resolvePrompt(any()) } returns text
        val lazyProvider = mockk<Lazy<SystemPromptProvider>>()
        every { lazyProvider.get() } returns provider
        return lazyProvider
    }

    private fun module(
        template: String = rules,
        leases: WriteLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true)
    ) = SubAgentModule(promptProvider(template), leases)

    private val subAgentCtx = EngineContext(sessionId = "sub-1", isSubAgent = true, subAgentName = "Explore")

    @Test
    fun subAgentRules_injectedForSubAgentAndNameRendered() {
        val text = module().subAgentRules(subAgentCtx)
        assertTrue("纪律段必须注入", text != null)
        assertTrue(text!!.contains("subagent: Explore"))
        assertFalse(text.contains("{{SUBAGENT_NAME}}"))
        assertTrue(text.contains("不凭记忆写 API"))
    }

    @Test
    fun subAgentRules_notInjectedForMainAgent() {
        assertNull(module().subAgentRules(EngineContext(sessionId = "s1")))
    }

    @Test
    fun subAgentRules_fallsBackWhenNameMissing() {
        val ctx = EngineContext(sessionId = "sub-1", isSubAgent = true)
        val text = module().subAgentRules(ctx)
        assertTrue(text != null && text.contains("未命名"))
    }

    @Test
    fun subAgentRules_emptyTemplateMeansOptOut() {
        // 用户在 prompts.custom/ 里清空文件 = 明确不要这段规则，此时不能注入空壳
        assertNull(module(template = "   \n").subAgentRules(subAgentCtx))
    }

    @Test
    fun onSessionDeleted_releasesWriteLease() = runTest {
        val leases = mockk<WriteLeaseRegistry>(relaxed = true)
        module(leases = leases).onSessionDeleted(EngineContext(sessionId = "sub-1"))
        verify { leases.release("sub-1") }
    }

    @Test
    fun onSessionDeleted_withoutSessionIdDoesNothing() = runTest {
        val leases = mockk<WriteLeaseRegistry>(relaxed = true)
        module(leases = leases).onSessionDeleted(EngineContext(sessionId = null))
        verify(exactly = 0) { leases.release(any()) }
    }

    @Test
    fun moduleIdsAndOrderAreStable() {
        assertEquals("subagent", module().id)
        // 排在记忆（50）之前：行为约束比可选上下文更靠前
        assertTrue(module().order < 50)
    }
}
