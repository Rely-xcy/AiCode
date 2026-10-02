package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.di.ApplicationScope
import com.aicode.feature.agent.domain.tool.AgentTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一智能调度：所有 [EngineModule] 由这里调度，调用方只跟引擎打交道。
 *
 * 三件事：
 * 1. 片段聚合——按 [EngineModule.order] 取各模块本轮的系统提示词片段，拼成一段
 *    （[subAgentRules] 同理，但它只给子代理会话、且不受 `inject` 门禁；
 *    [memoryListGroups] 是同一段里的另一个取数口子，取第一个非空结果而不是拼接）；
 * 2. 工具聚合——收集各模块额外提供的工具（同名以内置/先注册者为准，由调用方去重）；
 * 3. 钩子分发——轮次结束、会话删除这类「一次触发、多个模块响应」的动作，并发分发。
 *
 * 隔离策略：单个模块抛异常只记日志，绝不影响其它模块，也不向上抛——
 * 一个模块出问题不能拖垮整轮对话。
 */
@Singleton
class AgentEngine @Inject constructor(
    private val modules: Set<@JvmSuppressWildcards EngineModule>,
    @param:ApplicationScope private val dispatchScope: CoroutineScope
) {

    /** 各模块本轮片段按 order 拼接；全部为空时返回 null。 */
    fun promptFragment(ctx: EngineContext): String? {
        val pieces = sortedModules().mapNotNull { module ->
            runModule(module, "promptFragment") { it.promptFragment(ctx) }
                ?.takeIf { it.isNotBlank() }
        }
        return pieces.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** 各模块本轮提供的工具，按模块顺序拼接，同名只保留第一个。 */
    fun tools(ctx: EngineContext): List<AgentTool> =
        sortedModules()
            .flatMap { module -> runModule(module, "tools") { it.tools(ctx) } ?: emptyList() }
            .distinctBy { it.name }

    /**
     * 子代理会话的固定纪律段，按 [EngineModule.order] 拼接；全部为空时返回 null。
     *
     * 与 [promptFragment] 分开：调用方（子代理提示词组装）无条件调它，不再叠加 `inject` 门禁。
     */
    fun subAgentRules(ctx: EngineContext): String? {
        val pieces = sortedModules().mapNotNull { module ->
            runModule(module, "subAgentRules") { it.subAgentRules(ctx) }
                ?.takeIf { it.isNotBlank() }
        }
        return pieces.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * 记忆清单的按范围分组，取第一个非空结果；无模块提供时返回 null。
     *
     * 不拼接的原因：记忆只有一个来源（MemoryModule），拼接会把同一份清单展开多遍；
     * 取第一个与 [promptFragment] 的拼接不同，是因为这里要的是一份纯数据，不是多个片段的和。
     */
    fun memoryListGroups(ctx: EngineContext): MemoryListGroups? {
        sortedModules().forEach { module ->
            runModule(module, "memoryListGroups") { it.memoryListGroups(ctx) }?.let { return it }
        }
        return null
    }

    /** 一轮对话正常结束：并发分发给所有模块，调用方不等待。 */
    fun onTurnCompleted(ctx: EngineContext) {
        dispatch("onTurnCompleted") { it.onTurnCompleted(ctx) }
    }

    /**
     * 调模型前：按 [EngineModule.order] 顺序依次让模块处理即将发送的上下文。
     *
     * 与 [onTurnCompleted] 不同，这里是同步链路（调用方要拿返回值去发请求），
     * 所以逐个 await。某个模块失败只记日志并保留上一版 [LlmCall]，不影响后续模块与主流程。
     */
    suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall {
        var current = call
        sortedModules().forEach { module ->
            val next = try {
                module.beforeLlmCall(ctx, current)
            } catch (e: Exception) {
                FileLogger.w(TAG, "模块 ${module.id} 的 beforeLlmCall 失败，已跳过", e)
                null
            }
            if (next != null) current = next
        }
        return current
    }

    /**
     * 收尾守卫：依次问各模块「这次收尾要不要拦」，返回第一个非空提醒。
     *
     * 取第一个而不是拼接：拦一次就多一次模型往返，两个模块同时拦就是两次；
     * 先拦下的那个把话说完，后面那个等下一轮（各模块自己的「本轮已补过」门禁负责收敛）。
     * 模块抛异常只记日志并跳过——守卫失效可以退回提示词层，但不能把整轮对话弄挂。
     */
    suspend fun finalResponseGuard(ctx: EngineContext, finalText: String): String? {
        sortedModules().forEach { module ->
            val reminder = try {
                module.finalResponseGuard(ctx, finalText)
            } catch (e: Exception) {
                FileLogger.w(TAG, "模块 ${module.id} 的 finalResponseGuard 失败，已跳过", e)
                null
            }
            if (!reminder.isNullOrBlank()) return reminder
        }
        return null
    }

    /** 会话被删除：并发分发给所有模块，调用方不等待。 */
    fun onSessionDeleted(ctx: EngineContext) {
        dispatch("onSessionDeleted") { it.onSessionDeleted(ctx) }
    }

    private fun dispatch(action: String, block: suspend (EngineModule) -> Unit) {
        sortedModules().forEach { module ->
            dispatchScope.launch {
                runSuspendModule(module, action) { block(it) }
            }
        }
    }

    private fun sortedModules(): List<EngineModule> =
        modules.sortedWith(compareBy({ it.order }, { it.id }))

    private inline fun <T> runModule(
        module: EngineModule,
        action: String,
        block: (EngineModule) -> T
    ): T? = try {
        block(module)
    } catch (e: Exception) {
        FileLogger.w(TAG, "模块 ${module.id} 的 $action 失败，已跳过", e)
        null
    }

    private suspend inline fun runSuspendModule(
        module: EngineModule,
        action: String,
        block: suspend (EngineModule) -> Unit
    ) {
        try {
            block(module)
        } catch (e: Exception) {
            FileLogger.w(TAG, "模块 ${module.id} 的 $action 失败，已跳过", e)
        }
    }

    private companion object {
        const val TAG = "AgentEngine"
    }
}
