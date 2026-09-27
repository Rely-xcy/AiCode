package com.aicode.core.engine

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode

/**
 * 引擎模块需要的一轮上下文。
 *
 * 刻意只带引擎确实用得上的东西（会话、工作区、模式、历史），不把整个 AgentContext 透出去——
 * 模块不该依赖 agent 循环的内部状态。
 */
data class EngineContext(
    val sessionId: String?,
    val projectRoot: String,
    val mode: AgentMode,
    /** 子代理会话：模块据此决定是否注入/沉淀（子代理不重复写画像、不参与调度编排）。 */
    val isSubAgent: Boolean,
    val history: List<AgentMessage>
)

/**
 * 统一智能引擎的一个模块。
 *
 * 模块之间互不依赖，只通过 [AgentEngine] 拿到 [EngineContext]；任何模块抛错都不影响主流程
 * （引擎逐个调用并吞掉异常），避免一个模块出问题把整轮对话带崩。
 */
interface EngineModule {

    /** 稳定标识，用于日志与去重。 */
    val id: String

    /** 注入顺序（小在前）。也决定片段在提示词里的先后。 */
    val order: Int

    /**
     * 本轮要注入系统提示词的片段；返回 null 表示该模块本轮无可注入内容。
     *
     * 片段统一追加在提示词末尾，避免打断前面的稳定前缀（KV 缓存）。
     */
    suspend fun promptFragment(ctx: EngineContext): String? = null

    /**
     * 一轮对话结束后的沉淀机会（用轻量模型抽取、写库等）。
     *
     * @param transcript 本轮对话文本（用户/助手行拼接），已由调用方截断。
     */
    suspend fun onTurnCompleted(ctx: EngineContext, transcript: String) = Unit

    /** 会话被删除：模块清理自己的会话级状态与数据。 */
    suspend fun onSessionDeleted(sessionId: String) = Unit
}
