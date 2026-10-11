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
    /**
     * 是否为子代理会话（会话行有 `parentId`）。
     *
     * 判据不在这里推导：由 ViewModel 从会话行算出、随 [com.aicode.feature.agent.domain.model.AgentContext] 传下来。
     * 与「本轮是否有自定义 agent 定义」不是一回事——默认子代理没有定义也仍然是子代理。
     */
    val isSubAgent: Boolean = false,
    /**
     * 子代理定义的名称；没有自定义定义时（默认子代理）为 null。
     * 与 [isSubAgent] 不同义：这个字段带上名字，只供模块在固定提示段里渲染角色行。
     */
    val subAgentName: String? = null,
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

/** 记忆清单按范围的原始分组（`{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` 变量的数据）。 */
data class MemoryListGroups(val global: String?, val project: String?)

/**
 * 引擎模块：一个功能以模块为单位接入引擎，由 [AgentEngine] 统一调度。
 *
 * 所有回调都有默认空实现，模块只覆写自己关心的那几个：
 * - [promptFragment]：本轮要不要往系统提示词里加东西；
 * - [subAgentRules]：会话是子代理时要加的固定纪律段（不受 inject 门禁）；
 * - [tools]：本轮要不要额外提供工具；
 * - [beforeLlmCall]：调模型前对即将发送的消息做处理（压缩、精简、过滤）；
 * - [finalResponseGuard]：模型要收尾时的边界守卫（拦下并回灌一条提醒）；
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

    /**
     * 子代理会话的固定规则片段：本会话是子代理时**必定**注入，不受 `AgentDefinition.inject` 门禁。
     *
     * 与 [promptFragment] 分开的原因：后者在子代理路径上跟着 `inject` 走（如关掉 `MEMORY` 就整段不注入），
     * 而子代理的纪律要求是「能安全干活」的最低条件，不能由定义作者关掉、也不能靠派发的人每次记得写。
     * 需要改内容时改提示词文件（可用 `prompts.custom/agent/` 同名覆盖），不要把规则写回代码字符串。
     */
    fun subAgentRules(ctx: EngineContext): String? = null

    /**
     * 记忆清单的原始分组，供 `{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` 两个变量取值。
     *
     * 与 [promptFragment] 分开的原因：后者是渲染好的整块（含使用规则、按当轮话题召回排序与条数预算），
     * 而这两个变量要的是「每行一项、不排序」的完整清单；合成一份会让变量连规则文本一起拿走。
     * 由 [AgentEngine] 取第一个非空结果（不是拼接），SystemPromptProvider 渲染变量时调用。
     *
     * 实现方必须与 [promptFragment] 共用同一份读取缓存：同一轮里两个入口都会进来，
     * 各读一次盘会把同一批记忆的命中记账重复一遍。
     */
    fun memoryListGroups(ctx: EngineContext): MemoryListGroups? = null

    /** 本轮额外提供的工具；与内置工具合并时同名以内置为准。 */
    fun tools(ctx: EngineContext): List<AgentTool> = emptyList()

    /**
     * 调模型前处理即将发送的上下文，按 [order] 顺序依次应用。
     *
     * 与 [onTurnCompleted] 这类「触发即分发、不等结果」的钩子不同，这个钩子是同步链路：
     * 调用方要用返回值去发请求，所以必须等结果；返回 null 表示本轮不改动。
     */
    suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? = null

    /**
     * 模型要给出本轮最终回复（没有工具调用、没被截断）时的收尾守卫：返回非空文本表示**拦下这一轮**，
     * 调用方把这段文本当用户消息回灌并重新请求模型。
     *
     * 与 [promptFragment] 的区别：那个只能在轮次开始时说话，改的是「倾向」；这个在模型「已经说完」
     * 的边界上拦，改的是「保证」——提示词里提醒一百遍不如边界上一次拦截。
     * 与 [beforeLlmCall] 一样是同步链路（调用方要拿返回值决定是否再发一次请求），
     * 拿不到结论时返回 null：守卫不能把对话拖死。
     */
    suspend fun finalResponseGuard(ctx: EngineContext, finalText: String): String? = null

    /** 一轮对话正常结束（成功、未取消）后调用。 */
    suspend fun onTurnCompleted(ctx: EngineContext) {}

    /** 会话被删除后调用。 */
    suspend fun onSessionDeleted(ctx: EngineContext) {}

    companion object {
        const val DEFAULT_ORDER = 100
    }
}
