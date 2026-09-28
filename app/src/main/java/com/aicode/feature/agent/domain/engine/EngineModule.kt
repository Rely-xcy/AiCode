package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.workflow.AgentEvent

/**
 * 引擎上下文：模块在一次调度里能看到的全部输入。
 *
 * 只带「模块做判断需要的信息」，不暴露 Workflow/ViewModel 等上层对象，
 * 避免模块反向依赖调用链。
 */
data class EngineContext(
    val sessionId: String?,
    /** 会话被删除等拿不到工作区的场景允许为空串。 */
    val projectRoot: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    val history: List<AgentMessage> = emptyList(),
    /** 是否为子代理会话（按自定义子代理定义运行）。 */
    val isSubAgent: Boolean = false,
    /**
     * 一次性模型调用能力，由会话运行侧注入（不占主对话 provider、不写入会话消息）。
     * 模块拿它做归纳类工作；null 表示当前不可用（如未配置模型）。
     *
     * 用回调而不是让模块注入 workflow：模块 → workflow → SystemPromptProvider →
     * AgentEngine → 模块 会形成 DI 环，所以能力只能从调用方递进来。
     */
    val oneShot: (suspend (promptFile: String, userPrompt: String) -> String?)? = null
)

/**
 * 一次即将发出的模型调用：模块在这里做发送前的上下文处理。
 *
 * [messages] 是要发给模型的消息列表（不是会话历史，可能已被前面的模块改过）；
 * 模块返回处理后的 [LlmCall]，调用方只发最后一次返回的版本。
 *
 * 窗口与模型信息由调用方递进来（引擎不自己解析模型目录）：[windowProvider] 是决定
 * 「上下文快撑满谁」的主聊天模型，[summaryProvider] 是执行摘要生成的压缩专用模型，
 * 两者分离避免小窗口压缩模型导致过早压缩；[summaryProvider] 为 null 表示本轮
 * 只能做不调模型的软精简与兜底。
 */
data class LlmCall(
    val messages: List<AgentMessage>,
    val windowProvider: AIProvider? = null,
    val summaryProvider: AIProvider? = null,
    /** 上一次请求的真实 input tokens（provider 不回传时为 0）。 */
    val lastInputTokens: Int = 0,
    /** system prompt 与工具定义的估算 token：不在 [messages] 里，但实打实占窗口。 */
    val overheadTokens: Int = 0,
    /** 手动强制压缩（用户点「压缩上下文」）。 */
    val force: Boolean = false,
    /** 压缩过程要往 UI 推的事件（开始/结束/失败）。 */
    val onEvent: suspend (AgentEvent) -> Unit = {}
)

/**
 * 引擎模块：一个功能以模块为单位接入引擎，由 [AgentEngine] 统一调度。
 *
 * 所有回调都有默认空实现，模块只覆写自己关心的那几个：
 * - [promptFragment]：本轮要不要往系统提示词里加东西；
 * - [tools]：本轮要不要额外提供工具；
 * - [beforeLlmCall]：调模型前对即将发送的消息做处理（压缩、精简、过滤）；
 * - [onTurnCompleted]：一轮对话正常结束后的沉淀/维护；
 * - [onSessionDeleted]：会话被删除后的清理（模块自持的会话级状态在这里释放）。
 *
 * 模块内抛出的异常由引擎兜住（记日志、不影响其它模块与主流程），
 * 所以模块不必自己写 try/catch。
 */
interface EngineModule {
    /** 模块标识，用于日志与排查。 */
    val id: String

    /** 调度顺序，越小越靠前；同序按 [id] 稳定排序。 */
    val order: Int get() = DEFAULT_ORDER

    /** 本轮注入系统提示词的片段；返回 null 或空白表示本轮不参与。 */
    fun promptFragment(ctx: EngineContext): String? = null

    /** 本轮额外提供的工具；与内置工具合并时同名以内置为准。 */
    fun tools(ctx: EngineContext): List<AgentTool> = emptyList()

    /**
     * 调模型前处理即将发送的上下文，按 [order] 顺序依次应用。
     *
     * 与 [onTurnCompleted] 这类「触发即分发、不等结果」的钩子不同，这个钩子是同步链路：
     * 调用方要用返回值去发请求，所以必须等结果；返回 null 表示本轮不改动。
     */
    suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? = null

    /** 一轮对话正常结束（成功、未取消）后调用。 */
    suspend fun onTurnCompleted(ctx: EngineContext) {}

    /** 会话被删除后调用。 */
    suspend fun onSessionDeleted(ctx: EngineContext) {}

    companion object {
        const val DEFAULT_ORDER = 100
    }
}
