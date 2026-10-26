package com.aicode.feature.agent.domain.brief

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.data.local.entity.SessionBriefEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话工作状态提炼：把一段即将离开上下文的历史，归纳成「当前任务理解」存进 `session_briefs`。
 *
 * 与 [com.aicode.feature.agent.domain.memory.MemoryExtractor] 的分工：
 * - 记忆抽「关于用户的稳定结论」（用户是谁、偏好、环境），跨会话通用；
 * - 这里抽「当前任务本身」（目标、已定决策、进展、约束），只属于这个会话。
 *
 * 为什么必须独立存在：长期记忆的提示词明确排除任务细节（skip task details / transient state），
 * 而「越聊越傻」丢的恰恰是任务理解。压缩把历史折成摘要后，摘要是有损的；这份状态每轮注入，
 * 不随压缩丢失，模型因此始终知道「我在做什么、做到哪、定过什么」。
 */
@Singleton
class SessionBriefExtractor @Inject constructor(
    private val sessionBriefDao: SessionBriefDao
) {

    /**
     * @param complete 一次性模型调用能力：system prompt 由调用方按 [PROMPT_FILE] 解析好，
     *   这里只给 user prompt（历史 + 现有状态）。
     * @return 是否写入了新状态；历史为空、模型返空、解析失败都返回 false。
     */
    suspend fun extract(
        sessionId: String,
        history: List<AgentMessage>,
        complete: suspend (userPrompt: String) -> String?
    ): Boolean {
        if (history.isEmpty()) return false
        val existing = runCatching { sessionBriefDao.getBySessionOnce(sessionId)?.content }
            .getOrNull()
        val raw = complete(buildUserPrompt(history, existing)) ?: return false
        val content = raw.trim().take(MAX_BRIEF_CHARS)
        if (content.isBlank()) return false
        sessionBriefDao.upsert(
            SessionBriefEntity(
                sessionId = sessionId,
                content = content,
                updatedAt = System.currentTimeMillis()
            )
        )
        FileLogger.i(TAG, "更新会话工作状态，会话 $sessionId，长度 ${content.length}")
        return true
    }

    /** 交给模型的输入：现有状态（合并而不是覆盖）+ 即将离开上下文的历史。 */
    private fun buildUserPrompt(history: List<AgentMessage>, existing: String?): String =
        buildString {
            appendLine("Existing working state (merge into it; keep what is still true):")
            if (existing.isNullOrBlank()) appendLine("(none)") else appendLine(existing)
            appendLine()
            appendLine("Transcript (the part about to leave the context window):")
            history.takeLast(TRANSCRIPT_MESSAGES).forEach { appendLine(renderMessage(it)) }
        }

    private fun renderMessage(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> "user: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.AssistantMessage -> "assistant: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.ToolResultMessage ->
            "tool(${message.toolName}): ${message.result.take(MAX_TOOL_CHARS)}"
    }

    companion object {
        const val TAG = "SessionBriefExtractor"

        /** 提炼提示词：与其它一次性调用一样放 assets/prompts 下。 */
        const val PROMPT_FILE = "agent/session-brief.md"

        /** 状态上限：每轮都注入，太长会挤占窗口，也说明提炼没收敛。 */
        const val MAX_BRIEF_CHARS = 4000

        private const val TRANSCRIPT_MESSAGES = 30
        private const val MAX_MESSAGE_CHARS = 1200
        private const val MAX_TOOL_CHARS = 400
    }
}
