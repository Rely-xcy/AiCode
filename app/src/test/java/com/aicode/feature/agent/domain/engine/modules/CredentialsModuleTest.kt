package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.tool.ToolCall
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据说明只在命中时注入：用户问到要注入，AI 自己访问凭据路径也要注入；
 * 正常对话不能被带偏，同一轮不能重复注入。
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
        sessionId = "s1",
        history = listOf(AgentMessage.UserMessage(id = "u1", content = text))
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
        assertNotNull(module().turnReminder(ctx("供应商的 token 在哪配置")))
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

    @Test
    fun toolCallTouchingCredentialPathInjectsIntoLastToolResult() = runBlocking {
        val messages = listOf(
            AgentMessage.UserMessage(id = "u1", content = "帮我看下 git 为什么推送失败"),
            AgentMessage.AssistantMessage(
                id = "a1",
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "t1",
                        name = "readFile",
                        arguments = mapOf("path" to JsonPrimitive("~/.aicode/git-credentials"))
                    )
                )
            ),
            AgentMessage.ToolResultMessage(id = "r1", toolName = "readFile", result = "YWJj")
        )
        val updated = module().beforeLlmCall(
            EngineContext(sessionId = "s1", history = messages),
            LlmCall(messages = messages)
        )
        assertNotNull(updated)
        val result = (updated!!.messages.last() as AgentMessage.ToolResultMessage).result
        assertTrue(result.contains("刚才的工具访问了凭据相关路径"))
        assertTrue(result.contains("真源只有一份"))
    }

    @Test
    fun toolResultContainingCipherPrefixInjects() = runBlocking {
        val messages = listOf(
            AgentMessage.UserMessage(id = "u1", content = "看看这个文件"),
            AgentMessage.AssistantMessage(id = "a1", content = ""),
            AgentMessage.ToolResultMessage(id = "r1", toolName = "readFile", result = "AICODEENC1:AAAA")
        )
        val updated = module().beforeLlmCall(
            EngineContext(sessionId = "s1", history = messages),
            LlmCall(messages = messages)
        )
        assertNotNull(updated)
    }

    @Test
    fun unrelatedToolCallDoesNotInject() = runBlocking {
        val messages = listOf(
            AgentMessage.UserMessage(id = "u1", content = "读一下说明"),
            AgentMessage.AssistantMessage(
                id = "a1",
                content = "",
                toolCalls = listOf(
                    ToolCall(id = "t1", name = "readFile", arguments = mapOf("path" to JsonPrimitive("README.md")))
                )
            ),
            AgentMessage.ToolResultMessage(id = "r1", toolName = "readFile", result = "# 说明")
        )
        assertNull(
            module().beforeLlmCall(
                EngineContext(sessionId = "s1", history = messages),
                LlmCall(messages = messages)
            )
        )
    }

    @Test
    fun sameTurnIsOnlyInjectedOnce() = runBlocking {
        val messages = listOf(
            AgentMessage.UserMessage(id = "u1", content = "看下凭据"),
            AgentMessage.AssistantMessage(
                id = "a1",
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "t1",
                        name = "readFile",
                        arguments = mapOf("path" to JsonPrimitive("~/.aicode/git-credentials"))
                    )
                )
            ),
            AgentMessage.ToolResultMessage(id = "r1", toolName = "readFile", result = "YWJj")
        )
        val module = module()
        val context = EngineContext(sessionId = "s1", history = messages)
        assertNotNull(module.beforeLlmCall(context, LlmCall(messages = messages)))
        assertNull(module.beforeLlmCall(context, LlmCall(messages = messages)))
    }
}
