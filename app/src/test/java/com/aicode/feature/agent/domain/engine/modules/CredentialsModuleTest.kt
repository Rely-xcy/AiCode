package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据说明只在命中时注入：命中要给出完整正文，未命中的正常对话不能被带偏。
 */
class CredentialsModuleTest {

    private val body = "## 凭据与密钥\n- 真源只有一份：~/.aicode/git-credentials"

    private fun module(): CredentialsModule {
        val provider = mockk<SystemPromptProvider>(relaxed = true)
        every { provider.resolvePrompt("agent/credentials.md") } returns "<!-- 注释 -->\n$body"
        val lazyProvider = mockk<Lazy<SystemPromptProvider>>(relaxed = true)
        every { lazyProvider.get() } returns provider
        return CredentialsModule(lazyProvider)
    }

    private fun ctx(text: String) = EngineContext(
        history = listOf(AgentMessage.UserMessage(content = text))
    )

    @Test
    fun authenticationFailureInjectsFullReference() {
        val reminder = module().turnReminder(ctx("SSH 认证失败，提示 Permission denied (publickey)，怎么办"))
        assertNotNull(reminder)
        assertTrue(reminder!!.contains("## 凭据与密钥（本轮涉及，自动注入）"))
        assertTrue(reminder.contains("真源只有一份"))
    }

    @Test
    fun tokenStatsQuestionDoesNotInject() {
        // Token 统计是正常功能，不该被当成「找凭据」
        assertNull(module().turnReminder(ctx("token 统计页的数据怎么清零")))
    }

    @Test
    fun askingWhereTokenIsConfiguredInjects() {
        val reminder = module().turnReminder(ctx("供应商的 token 在哪配置"))
        assertNotNull(reminder)
    }

    @Test
    fun unrelatedQuestionDoesNotInject() {
        assertNull(module().turnReminder(ctx("把设置页的列表改成懒加载")))
    }

    @Test
    fun promptFragmentNeverTouchesSystemPrompt() {
        // 命中与否都不该改 system：否则 KV 缓存前缀每轮失效
        assertNull(module().promptFragment(ctx("密钥在哪")))
    }
}
