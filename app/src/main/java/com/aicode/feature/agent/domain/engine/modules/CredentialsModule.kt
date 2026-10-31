package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据说明按需注入：只有本轮提到凭据、密钥、密码或认证失败时，才把
 * `agent/credentials.md` 的完整内容随用户消息下发。
 *
 * 为什么不常驻系统提示词：这份内容接近 4KB（位置表 + 解码方式 + 纪律），
 * 大多数任务根本用不到，放 system 里等于每轮白付一次窗口。
 *
 * 为什么不干脆删掉：模型不知道凭据在哪时会去翻 `~/.aicode/` 或猜路径，
 * 既慢又容易碰到敏感文件。保留「提到才给」的注入，既省窗口又不让它瞎找。
 *
 * 走 [turnReminder] 而不是 [promptFragment]：是否命中随本轮对话变化，
 * 放进 system 会让 KV 缓存前缀每轮失效。
 */
@Singleton
class CredentialsModule @Inject constructor(
    private val systemPromptProvider: Lazy<SystemPromptProvider>
) : EngineModule {

    override val id = MODULE_ID

    /** 排在其它上下文模块之后：它是补丁型信息，不参与记忆/任务的排序语义。 */
    override val order = 70

    @Volatile
    private var contentCache: String? = null

    override fun promptFragment(ctx: EngineContext): String? = null

    override fun turnReminder(ctx: EngineContext): String? {
        if (!mentionsCredentials(ctx)) return null
        val body = content() ?: return null
        return "$REMINDER_HEADING\n$body"
    }

    /**
     * 命中判据：最近几条消息里出现凭据类词，或出现「token」且同时在问位置/配置/认证。
     *
     * 单独一个 `token` 不算：Token 统计页、上下文用量这类正常对话会频繁提到它，
     * 一律命中就退回「每轮都注入」了。
     */
    private fun mentionsCredentials(ctx: EngineContext): Boolean {
        val text = recentText(ctx)
        if (text.isBlank()) return false
        if (KEYWORDS.containsMatchIn(text)) return true
        return TOKEN_WORD.containsMatchIn(text) && TOKEN_CONTEXT.containsMatchIn(text)
    }

    private fun recentText(ctx: EngineContext): String = buildString {
        ctx.history.takeLast(SCAN_MESSAGES).forEach { message ->
            val chunk = when (message) {
                is AgentMessage.UserMessage -> message.content
                is AgentMessage.AssistantMessage -> message.content
                is AgentMessage.ToolResultMessage -> message.result
            }
            if (chunk.isNotBlank()) append(chunk).append('\n')
        }
    }.take(MAX_SCAN_CHARS)

    private fun content(): String? {
        contentCache?.let { return it.ifEmpty { null } }
        val cleaned = try {
            systemPromptProvider.get()
                .resolvePrompt(PROMPT_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取凭据说明失败: $PROMPT_FILE", e)
            return null
        }
        contentCache = cleaned
        return cleaned.ifEmpty { null }
    }

    private companion object {
        const val MODULE_ID = "credentials-ref"
        const val TAG = "CredentialsModule"
        const val PROMPT_FILE = "agent/credentials.md"
        const val REMINDER_HEADING = "## 凭据与密钥（本轮涉及，自动注入）"

        /** 只扫最近几条：凭据问题通常在当轮提出，扫全史会误伤。 */
        const val SCAN_MESSAGES = 4

        /** 扫描文本上限：工具结果可能很长，没必要整段跑正则。 */
        const val MAX_SCAN_CHARS = 4000

        val KEYWORDS = Regex(
            "凭据|凭证|密钥|私钥|密码|认证失败|鉴权失败|授权失败|publickey|permission denied|" +
                "credential|api[ _-]?key|access[ _-]?key|secret[ _-]?key|\\b401\\b|\\b403\\b",
            RegexOption.IGNORE_CASE
        )

        val TOKEN_WORD = Regex("token", RegexOption.IGNORE_CASE)

        val TOKEN_CONTEXT = Regex(
            "在哪|位置|怎么|配置|获取|拿|找|凭据|密钥|认证|apikey|api key",
            RegexOption.IGNORE_CASE
        )

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
