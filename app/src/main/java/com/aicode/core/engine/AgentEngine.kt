package com.aicode.core.engine

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一智能引擎：持有全部 [EngineModule]，对外提供三件事——每轮注入片段、轮次结束沉淀、会话删除清理。
 *
 * 它不是 agent 循环的替代品：[com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * 仍然是循环本体，引擎只作为它的「状态与模块层」被调用（提示词注入 + 生命周期回调）。
 */
@Singleton
class AgentEngine @Inject constructor(
    modules: Set<@JvmSuppressWildcards EngineModule>
) {
    private companion object {
        const val TAG = "AgentEngine"
    }

    val orderedModules: List<EngineModule> = modules.sortedBy { it.order }

    /**
     * 汇总各模块本轮要注入的片段。
     *
     * 模块串行取数（都很快，且顺序决定片段顺序）；单个模块失败只记日志并跳过。
     */
    suspend fun promptFragment(ctx: EngineContext): String? {
        if (orderedModules.isEmpty()) return null
        val pieces = orderedModules.mapNotNull { module ->
            runCatching { module.promptFragment(ctx) }
                .onFailure { FileLogger.w(TAG, "模块 ${module.id} 注入片段失败: ${it.message}", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        return pieces.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * 轮次结束：并发通知各模块沉淀。
     *
     * 并发是为了不把用户的下一次交互卡在沉淀上；各模块内部再自行决定是否节流。
     */
    suspend fun onTurnCompleted(ctx: EngineContext, transcript: String) {
        if (orderedModules.isEmpty() || transcript.isBlank()) return
        coroutineScope {
            orderedModules
                .map { module ->
                    async {
                        runCatching { module.onTurnCompleted(ctx, transcript) }
                            .onFailure { FileLogger.w(TAG, "模块 ${module.id} 轮次沉淀失败: ${it.message}", it) }
                    }
                }
                .awaitAll()
        }
    }

    /** 会话删除：通知各模块清理自己的会话级数据。 */
    suspend fun onSessionDeleted(sessionId: String) {
        orderedModules.forEach { module ->
            runCatching { module.onSessionDeleted(sessionId) }
                .onFailure { FileLogger.w(TAG, "模块 ${module.id} 清理会话失败: ${it.message}", it) }
        }
    }
}
