package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据说明按需注入：用户问到凭据，或 AI 自己去读凭据相关文件时，才把
 * `agent/credentials.md` 的完整内容带进上下文。
 *
 * 两条触发路径：
 * - [turnReminder]：本轮用户消息里出现凭据、密钥、密码或认证失败等词——在开轮时就告诉它；
 * - [beforeLlmCall]：工具调用参数或工具结果里出现 `git-credentials`、`AICODEENC1` 这类路径/前缀
 *   ——在工具执行完、下一次模型调用前补上。AI 自己翻文件时也能立刻看到纪律，而不是读完才踩坑。
 *
 * 两条路径共用「每轮只注入一次」的位点，避免重复。
 *
 * 为什么不常驻系统提示词：这份内容约 4KB，大多数任务用不到，放 system 等于每轮白付窗口。
 * 为什么走 [LlmCall] 的消息而不是 system：是否命中随对话变化，改 system 会让 KV 缓存前缀失效。
 *
 * 中途注入不新增消息、只把说明拼到最后一条工具结果末尾：新增一条 user/assistant 消息会打乱
 * 各 provider 对 tool 角色顺序的要求，拼在 tool 结果里对所有协议都安全（且不会被落库）。
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

    /** 已经提醒过的轮次；键是「会话 + 本轮用户消息」，超过上限整表清空。 */
    private val notifiedTurns: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    override fun promptFragment(ctx: EngineContext): String? = null

    override fun turnReminder(ctx: EngineContext): String? {
        if (!mentionsInText(ctx.history)) return null
        val body = content() ?: return null
        markNotified(ctx.sessionId, ctx.history)
        return "$REMINDER_HEADING\n$body"
    }

    /**
     * 工具执行后、下一次模型调用前补提示。
     *
     * 只改最后一条工具结果的内容：不新增消息，避免破坏 OpenAI/Anthropic/Gemini 对
     * assistant-tool 顺序的约束。返回值不会落库，下一轮重建历史时自然消失。
     */
    override suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? {
        if (!mentionsInToolActivity(call.messages)) return null
        val body = content() ?: return null
        val key = turnKey(ctx.sessionId, call.messages)
        if (!notifiedTurns.add(key)) return null
        trimNotifiedIfNeeded()
        val lastToolIndex = call.messages.indexOfLast { it is AgentMessage.ToolResultMessage }
        if (lastToolIndex < 0) return null
        val notice = "\n\n$MID_LOOP_HEADING\n$body"
        val updated = call.messages.toMutableList()
        val last = updated[lastToolIndex] as AgentMessage.ToolResultMessage
        updated[lastToolIndex] = last.copy(result = last.result + notice)
        return call.copy(messages = updated)
    }

    /** 用户消息命中凭据类词（或是问 token 在哪/怎么配）。 */
    private fun mentionsInText(messages: List<AgentMessage>): Boolean {
        val text = messages.takeLast(SCAN_MESSAGES).filterIsInstance<AgentMessage.UserMessage>()
            .joinToString("\n") { it.content }
            .take(MAX_SCAN_CHARS)
        if (text.isBlank()) return false
        if (KEYWORDS.containsMatchIn(text)) return true
        return TOKEN_WORD.containsMatchIn(text) && TOKEN_CONTEXT.containsMatchIn(text)
    }

    /**
     * 工具活动命中：调用参数里出现凭据路径，或工具结果里出现凭据路径 / 密文前缀。
     *
     * 只看最近几条：工具调用与结果紧邻，扫全史既慢又会命中很久以前读过的无关内容。
     */
    private fun mentionsInToolActivity(messages: List<AgentMessage>): Boolean =
        messages.takeLast(SCAN_MESSAGES).any { message ->
            when (message) {
                is AgentMessage.AssistantMessage ->
                    message.toolCalls.any { CREDENTIAL_PATHS.containsMatchIn(it.arguments.toString()) }

                is AgentMessage.ToolResultMessage ->
                    CREDENTIAL_PATHS.containsMatchIn(message.result) ||
                        message.toolName.contains("credential", ignoreCase = true)

                is AgentMessage.UserMessage -> false
            }
        }

    private fun markNotified(sessionId: String?, messages: List<AgentMessage>) {
        notifiedTurns.add(turnKey(sessionId, messages))
        trimNotifiedIfNeeded()
    }

    private fun trimNotifiedIfNeeded() {
        if (notifiedTurns.size <= MAX_NOTIFIED_TURNS) return
        notifiedTurns.clear()
    }

    /**
     * 本轮标识：优先用最后一条真实用户消息的 id；没有 id（测试或内部轮次）时退回消息条数。
     *
     * 不能只用 sessionId：否则同一会话里只有第一轮会被提醒。
     */
    private fun turnKey(sessionId: String?, messages: List<AgentMessage>): String {
        val anchor = messages.asReversed()
            .filterIsInstance<AgentMessage.UserMessage>()
            .firstOrNull { !it.content.startsWith(MID_LOOP_HEADING) }
        val id = anchor?.id?.takeIf { it.isNotBlank() } ?: messages.size.toString()
        return "${sessionId ?: "-"}#$id"
    }

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
        const val MID_LOOP_HEADING = "## 凭据与密钥（刚才的工具访问了凭据相关路径，以下是位置与纪律）"

        /** 只扫最近几条：凭据问题通常在当轮提出，扫全史会误伤。 */
        const val SCAN_MESSAGES = 4

        /** 扫描文本上限：工具结果可能很长，没必要整段跑正则。 */
        const val MAX_SCAN_CHARS = 4000

        /** 位点上限：只防长期累积。 */
        const val MAX_NOTIFIED_TURNS = 64

        val KEYWORDS = Regex(
            "凭据|凭证|密钥|私钥|密码|认证失败|鉴权失败|授权失败|publickey|permission denied|" +
                "credential|api[ _-]?key|access[ _-]?key|secret[ _-]?key|\\b401\\b|\\b403\\b",
            RegexOption.IGNORE_CASE
        )

        val TOKEN_WORD = Regex("token", RegexOption.IGNORE_CASE)

        val TOKEN_CONTEXT = Regex(
            "在哪|位置|配置|获取|拿|找|凭据|密钥|认证|apikey|api key",
            RegexOption.IGNORE_CASE
        )

        /** 凭据相关路径、密文前缀与表名：工具参数或结果命中任一项就补提示。 */
        val CREDENTIAL_PATHS = Regex(
            "git-credentials|git-credential-aicode|mcp-oauth\\.json|mcp\\.json|ssh_login_keys|" +
                "ssh_keys|ssh_host_keys|remote_connections|ai_providers|proxy_settings|" +
                "ftp_server_prefs|backup_targets|AICODEENC1|aicode_secret_v1",
            RegexOption.IGNORE_CASE
        )

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
