package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.ToolCall

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = "",
        val content: String,
        val images: List<AgentImage> = emptyList(),
        /**
         * 模式变化时注入的模式提醒。**不拼进 [content]**：界面渲染、落库、编辑都只认 content（用户原话），
         * 提醒只在组装请求时由 [modelFacingContent] 拼回模型侧文本。与 [ToolCall.modelArguments] 同一套对称。
         * null 表示这条消息没有提醒（绝大多数消息如此）。
         */
        val modelReminder: String? = null
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = "",
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** 本轮模型的思考过程（对应 OpenAI/DeepSeek 的 reasoning_content）。回传上下文时需要原样发回，否则 DeepSeek 思考模式会报 400 错误。 */
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名。与 [reasoning] 一起原样回传（工具循环必须），否则 400。其他 provider 为空串。 */
        val signature: String = "",
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），回传时需保持原样与原序。其他 provider 为空串。 */
        val thinkingBlocksJson: String = "",
        /**
         * 本轮模型直接生成的图片（Gemini 图像模型）。内存态下 base64Data 可为空、path 指向容器文件，
         * 回放时按 path 重建 base64 喂模型；落库只存附件路径不存 base64（见 [MessagePersistenceUseCase]）。
         */
        val images: List<AgentImage> = emptyList()
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = "",
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** 仅喂模型的精简结果文本；null 时回退用 [result]。UI 与持久化仍用 result。 */
        val modelResult: String? = null
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "What did we do so far?"
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> id
    }

/**
 * 喂模型的用户消息文本：提醒（[AgentMessage.UserMessage.modelReminder]）在末尾拼回。
 * 所有 provider 的请求组装与 [com.aicode.feature.agent.domain.workflow.TokenEstimator] 都必须走本入口，
 * 否则界面干净了、模型却收不到提醒（估算也会与实际请求不一致）。
 * 与工具参数的 `effectiveArguments`（见 `domain/tool/AgentTool.kt`）对称：取「模型可见的那份」只此一处。
 */
val AgentMessage.UserMessage.modelFacingContent: String
    get() {
        val reminder = modelReminder
        if (reminder.isNullOrBlank()) return content
        return if (content.isBlank()) reminder else "$content\n\n$reminder"
    }

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    val inputImages: List<AgentImage> = emptyList(),
    /** 当前会话 id：用于把本轮所有 AI 请求/响应落到该会话的日志文件（[com.aicode.core.util.AILogger]）。 */
    val sessionId: String? = null,
    /**
     * 本轮用户消息在库里的行 id。workflow 把模式提醒写回这一行的 modelReminder 列，
     * content 仍只存用户原话。null 表示本轮没有对应的用户行（自动触发的 /init、/skill 轮次），
     * 此时提醒只在本轮请求里生效。
     */
    val userMessageId: String? = null,
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）：退出 PLAN 时恢复到它，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    /** 思考强度（"low"/"medium"/"high"），随每次 LLM 请求传给支持的 provider。 */
    val reasoningEffort: String? = null,
    /**
     * 本会话绑定的自定义子代理定义；非空表示这是一个按定义运行的子代理会话，
     * 系统提示词与工具集都按其配置组装（见 [com.aicode.feature.agent.domain.prompt.SystemPromptProvider]）。
     */
    val agentDefinition: AgentDefinition? = null
)
