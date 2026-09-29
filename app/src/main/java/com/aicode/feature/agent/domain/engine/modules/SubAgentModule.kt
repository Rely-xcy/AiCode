package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.schedule.WriteLeaseRegistry
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理模块：把「子代理怎么跑」里属于引擎职责的部分接进引擎。
 *
 * 两件事：
 * 1. 提示词固定段——子代理会话必定注入一段纪律（角色行 + 硬规则：核对 API、没验证要说、
 *    结论带依据、报告固定结构、自检清单、文件边界、写范围），内容放在
 *    `prompts/agent/subagent-rules.md`，可用 `prompts.custom/agent/subagent-rules.md` 覆盖；
 * 2. 会话级清理——会话被删除时释放它持有的写范围租约。
 *
 * 刻意不做的事（详见各处的注释）：
 * - 不接管并发上限：权威是 [com.aicode.feature.agent.domain.subagent.SubAgentEventBus] 的活跃集合，
 *   在这里再存一份就是两套并行状态；
 * - 不接管写前准入：那是每次工具调用的即时判定，不是轮次级钩子，硬塞进钩子会变成没人按语义调用的假钩子，
 *   所以执行路径直连 [WriteLeaseRegistry]；
 * - 不接管可用子代理清单的注入：它是主代理提示词里的一个独立来源，`{{AICODE_SUBAGENTS}}` 变量与片段
 *   顺序都依赖这一点，搬进通用 [promptFragment] 会和记忆清单合并成一个 blob，既破坏变量替换也打断 KV 缓存。
 */
@Singleton
class SubAgentModule @Inject constructor(
    /**
     * 用 [Lazy] 注入断开 DI 环：SystemPromptProvider → AgentEngine → Set<EngineModule> → 本模块。
     * 只在真要解析提示词文件时才取实例；[SystemPromptProvider.resolvePrompt] 本身不再回调引擎，
     * 所以这里不会递归。
     */
    private val systemPromptProvider: Lazy<SystemPromptProvider>,
    private val writeLeaseRegistry: WriteLeaseRegistry
) : EngineModule {

    override val id = MODULE_ID

    // 排在记忆（50）之前：纪律段是行为约束，应该比可选的上下文片段更靠前、更稳定。
    override val order = 40

    /** 模板缓存：文件内容在一次进程内不会变（改文件要重启 App 才重新释放）。 */
    @Volatile
    private var templateCache: String? = null

    override fun subAgentRules(ctx: EngineContext): String? {
        if (!ctx.isSubAgent) return null
        val template = template() ?: return null
        val name = ctx.subAgentName?.takeIf { it.isNotBlank() } ?: "未命名"
        return template.replace(NAME_PLACEHOLDER, name).trim().ifEmpty { null }
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        val sessionId = ctx.sessionId ?: return
        writeLeaseRegistry.release(sessionId)
    }

    /**
     * 读取并缓存纪律段模板。文件为空（用户在 `prompts.custom/` 里清空）时返回 null，
     * 这是「我不要这段固定规则」的唯一出口——其它情况下它无条件注入。
     */
    private fun template(): String? {
        templateCache?.let { return it.ifEmpty { null } }
        val text = try {
            systemPromptProvider.get().resolvePrompt(RULES_FILE)
        } catch (e: Exception) {
            // 读不到（文件被删、assets 缺失）时不能静默降级：纪律段缺失意味着子代理会凭记忆写 API。
            FileLogger.e(TAG, "读取子代理纪律段失败: $RULES_FILE", e)
            return null
        }
        val cleaned = text.replace(LEADING_COMMENT, "").trim()
        templateCache = cleaned
        return cleaned.ifEmpty { null }
    }

    private companion object {
        const val MODULE_ID = "subagent"
        const val TAG = "SubAgentModule"

        /** 子代理固定纪律段。可由 `prompts.custom/agent/subagent-rules.md` 覆盖。 */
        const val RULES_FILE = "agent/subagent-rules.md"

        /** 模板里的角色名占位符；与 `{{AICODE_*}}` 变量区分开，避免被 SystemPromptProvider 的变量展开误处理。 */
        const val NAME_PLACEHOLDER = "{{SUBAGENT_NAME}}"

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
