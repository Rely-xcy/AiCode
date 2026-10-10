package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.subagent.SubAgentTeamTool
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 团队协同模块：把「多个子代理并行干活」里属于引擎职责的部分接进引擎。
 *
 * 四个口子，覆盖一次团队协作的完整生命周期：
 * 1. **主代理策略**（[promptFragment]）——任务复杂到需要拆解时才拆、并行上限、回收纪律、写冲突边界，
 *    内容在 `prompts/agent/subagent-team.md`，可用 `prompts.custom/agent/subagent-team.md` 覆盖；
 * 2. **子代理纪律**（[subAgentRules]）——子代理是团队一员：守角色边界、不嵌套派发、结论只经
 *    最终回复与 `messageParent` 回流，内容在 `prompts/agent/subagent-team-rules.md`；
 * 3. **团队工具**（[tools]）——本轮额外提供 `team` 工具（status/collect），只给主代理。
 *    这是引擎 `tools()` 口子的首个使用者：工具经引擎聚合进主会话工具集，不必注册进 ToolRegistry；
 * 4. **收尾守卫**（[finalResponseGuard]）——本会话还有子代理在跑时，在模型「已经说完」的边界上拦一次，
 *    让它先把结论收回来；每个会话只拦一次，避免模型反复忽略时把对话拖死。
 *
 * 刻意不做的事：不接管并发上限（权威是 [SubAgentEventBus] 的活跃集合）、不接管派发与写范围准入
 * （那是 `task` 工具与 `WriteLeaseRegistry` 的职责）——这里只补「主代理知不知道该等子代理」这一层。
 */
@Singleton
class SubAgentTeamModule @Inject constructor(
    /**
     * 用 [Lazy] 注入断开 DI 环：SystemPromptProvider → AgentEngine → Set<EngineModule> → 本模块。
     * 只在真要解析提示词文件时才取实例（与 [SubAgentModule] 同一处理）。
     */
    private val systemPromptProvider: Lazy<SystemPromptProvider>,
    private val subAgentTeamTool: SubAgentTeamTool,
    private val chatSessionDao: ChatSessionDao,
    private val eventBus: SubAgentEventBus
) : EngineModule {

    override val id = MODULE_ID

    // 排在子代理（40）与记忆（50）之间：先钉住子代理纪律，再补团队协同，最后才是可选的上下文片段。
    override val order = 45

    /** 模板缓存：文件内容在一次进程内不会变（改文件要重启 App 才重新释放）。 */
    @Volatile
    private var strategyCache: String? = null

    @Volatile
    private var rulesCache: String? = null

    /**
     * 已就「还有子代理在跑」提醒过的会话。
     *
     * 用会话级一次性位点而不是轮次级：提醒被忽略时模型可能反复给最终回复，没有位点就会每次收尾都拦一次，
     * 把一轮对话拖成死循环（引擎的收尾守卫是「拦下并回灌、重新请求」）。一次提醒足够把「先收结论」说清楚。
     */
    private val remindedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    override fun promptFragment(ctx: EngineContext): String? {
        if (ctx.isSubAgent) return null
        return strategy()
    }

    override fun subAgentRules(ctx: EngineContext): String? {
        if (!ctx.isSubAgent) return null
        return rules()
    }

    /** 团队工具只给主代理：子代理不参与调度，也不该看到「怎么管子代理」。 */
    override fun tools(ctx: EngineContext): List<AgentTool> =
        if (ctx.isSubAgent) emptyList() else listOf(subAgentTeamTool)

    /**
     * 收尾守卫：本会话仍有运行中的子代理时拦一次，提醒先把结论收回来再收尾。
     *
     * 判定按父会话过滤（[SubAgentEventBus.activeSubSessionIds] 是全局集合，别的会话在跑的子代理
     * 与本会话无关）；命中后记一次位点，同一会话不再重复拦。拿不到结论时返回 null——守卫不能把对话拖死。
     */
    override suspend fun finalResponseGuard(ctx: EngineContext, finalText: String): String? {
        if (ctx.isSubAgent) return null
        val sessionId = ctx.sessionId ?: return null
        val active = eventBus.activeSubSessionIds.value
        if (active.isEmpty()) return null
        val running = chatSessionDao.getSubSessionsByParentOnce(sessionId).filter { it.id in active }
        if (running.isEmpty()) return null
        if (!remindedSessions.add(sessionId)) return null
        FileLogger.w(TAG, "收尾守卫拦下本轮回复：本会话还有 ${running.size} 个子代理在运行")
        return buildString {
            append("还有 ${running.size} 个子代理在运行（")
            append(running.joinToString(", ") { it.title })
            append("）：先把它们的结论收回来再收尾。用 team(action=\"status\") 看谁还在跑、跑到哪一步，")
            append("用 team(action=\"collect\") 汇总已完成子代理的结论；仍在跑的先等它结束，不要让子代理的结果悬空。")
        }
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        val sessionId = ctx.sessionId ?: return
        remindedSessions.remove(sessionId)
    }

    /** 读取并缓存主代理的团队协同策略。文件为空（用户在 `prompts.custom/` 里清空）时返回 null。 */
    private fun strategy(): String? {
        strategyCache?.let { return it.ifEmpty { null } }
        val cleaned = readPrompt(STRATEGY_FILE) ?: return null
        strategyCache = cleaned
        return cleaned.ifEmpty { null }
    }

    /** 读取并缓存子代理的团队纪律。文件为空时返回 null。 */
    private fun rules(): String? {
        rulesCache?.let { return it.ifEmpty { null } }
        val cleaned = readPrompt(RULES_FILE) ?: return null
        rulesCache = cleaned
        return cleaned.ifEmpty { null }
    }

    /**
     * 读取提示词文件并清掉开头的说明性注释。
     * 读不到（文件被删、assets 缺失）时返回 null：对应片段整段不注入，不静默注入半截内容。
     */
    private fun readPrompt(name: String): String? = try {
        systemPromptProvider.get().resolvePrompt(name).replace(LEADING_COMMENT, "").trim()
    } catch (e: Exception) {
        FileLogger.e(TAG, "读取团队协同提示词失败: $name", e)
        null
    }

    private companion object {
        const val MODULE_ID = "subagent-team"
        const val TAG = "SubAgentTeamModule"

        /** 主代理的团队协同策略。可由 `prompts.custom/agent/subagent-team.md` 覆盖。 */
        const val STRATEGY_FILE = "agent/subagent-team.md"

        /** 子代理的团队纪律。可由 `prompts.custom/agent/subagent-team-rules.md` 覆盖。 */
        const val RULES_FILE = "agent/subagent-team-rules.md"

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
