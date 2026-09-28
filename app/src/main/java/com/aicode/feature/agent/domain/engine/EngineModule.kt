package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool

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
 * 引擎模块：一个功能以模块为单位接入引擎，由 [AgentEngine] 统一调度。
 *
 * 所有回调都有默认空实现，模块只覆写自己关心的那几个：
 * - [promptFragment]：本轮要不要往系统提示词里加东西；
 * - [tools]：本轮要不要额外提供工具；
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

    /** 一轮对话正常结束（成功、未取消）后调用。 */
    suspend fun onTurnCompleted(ctx: EngineContext) {}

    /** 会话被删除后调用。 */
    suspend fun onSessionDeleted(ctx: EngineContext) {}

    companion object {
        const val DEFAULT_ORDER = 100
    }
}
