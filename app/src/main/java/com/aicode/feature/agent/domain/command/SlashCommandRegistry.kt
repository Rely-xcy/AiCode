package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillTriggerHit
import com.aicode.feature.agent.domain.skill.SkillTriggerMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 斜杠命令注册表：聚合内置命令（[ActionCommandHandler]）与已启用技能，
 * 统一供 `/` 菜单展示与发送时解析。
 *
 * - 菜单：[commands] 为合并后的 [SlashCommand] 列表——内置命令优先，同名技能被跳过，按名排序。
 * - 解析：[resolve] 把 `/name args` 拆成命令名与参数，命中内置命令或技能；纯内存，无 IO。
 * - 刷新：[refresh] 重新扫描技能（文件 IO），调用方负责触发时机。
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    actionHandlers: Set<@JvmSuppressWildcards ActionCommandHandler>,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context
) {
    private val actions: List<ActionCommandHandler> = actionHandlers.sortedBy { it.name.lowercase() }
    private val actionsByName: Map<String, ActionCommandHandler> =
        actions.associateBy { it.name.lowercase() }

    /** 技能快照：由 [refresh] 在 IO 线程更新，[resolve] 只读。 */
    @Volatile
    private var skills: List<Skill> = emptyList()

    /**
     * 会话级去重表：同一技能在同一会话内只自动注入一次完整正文。
     * 内存态，App 重启清空（正文已沉在历史里，重启后重新注入一次属可接受，见设计 §2.4）。
     */
    private val triggeredSessions = HashMap<String, MutableSet<String>>()

    private val triggerMutex = Any()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())

    /** `/` 菜单展示的命令列表。 */
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    /** 重新扫描技能并重建菜单。涉及文件 IO，调用方应在 IO 上下文调用。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        skills = skillRepository.listSkills()
        _commands.value = buildCommands()
    }

    private fun buildCommands(): List<SlashCommand> = buildList {
        actions.forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = context.getString(it.descriptionRes),
                    kind = SlashCommandKind.ACTION,
                    acceptsArgs = it.acceptsArgs
                )
            )
        }
        skills.filterNot { it.name.lowercase() in actionsByName }
            .sortedBy { it.name.lowercase() }
            .forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = it.description,
                    kind = SlashCommandKind.SKILL,
                    acceptsArgs = true
                )
            )
        }
    }

    /**
     * 解析发送文本：仅当输入以 `/` 开头且命中内置命令或技能时返回非空。
     * 内置命令不接受参数时，带参数视为未命中（回落为普通消息）。
     */
    fun resolve(input: String): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()

        actionsByName[name.lowercase()]?.let { handler ->
            if (!handler.acceptsArgs && args.isNotEmpty()) return null
            return ResolvedCommand.Action(handler, args)
        }
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ResolvedCommand.SkillCommand(skill, args)
    }

    /**
     * 自然语言触发：用户消息中把某个技能的触发词作为**空格分隔的独立词**说出时命中。
     *
     * 返回有序命中列表（句首优先 > 触发词更长优先，见 [SkillTriggerMatcher]），并处理会话级去重：
     * - 首次命中：原样返回，由调用方注入完整正文；
     * - 同会话再次命中同一技能：结果里 [SkillTriggerHit.deduped] = true，调用方只附一行提示。
     *
     * 匹配用的是 [skills] 快照（refresh 时已过滤禁用技能），零额外 IO。纯内存。
     */
    fun findTriggered(request: String, sessionId: String): List<SkillTriggerHit> {
        val hits = SkillTriggerMatcher.match(request, skills)
        if (hits.isEmpty()) return hits
        synchronized(triggerMutex) {
            val seen = triggeredSessions.getOrPut(sessionId) { mutableSetOf() }
            return hits.map { hit ->
                if (seen.add(hit.skill.name.lowercase())) hit else hit.copy(deduped = true)
            }
        }
    }

    /** 解析结果：内置动作或技能。 */
    sealed interface ResolvedCommand {
        val name: String
        val args: String

        data class Action(val handler: ActionCommandHandler, override val args: String) : ResolvedCommand {
            override val name: String get() = handler.name
        }

        data class SkillCommand(val skill: Skill, override val args: String) : ResolvedCommand {
            override val name: String get() = skill.name
        }
    }

    companion object {
        /**
         * 构造技能本轮指令：把技能正文里的 `$ARGUMENTS` 替换为 [args]；
         * 无占位且 [args] 非空时追加一行，保证用户输入不丢失。
         */
        fun buildSkillPrompt(skill: Skill, args: String): String =
            substituteArguments(skill.instructions, args)
    }
}
