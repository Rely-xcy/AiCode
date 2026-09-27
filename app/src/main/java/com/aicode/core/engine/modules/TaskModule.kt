package com.aicode.core.engine.modules

import com.aicode.core.engine.EngineContext
import com.aicode.core.engine.EngineModule
import com.aicode.feature.agent.data.local.dao.SessionGoalDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.MilestoneStatus
import com.aicode.feature.agent.domain.model.SessionGoal
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待办与目标模块：把当前会话的任务清单、目标、计划摘要渲染成一段注入文本。
 *
 * 这些状态存在独立表里而不是消息历史里，所以上下文压缩折叠历史不会让模型忘记进度——
 * 每轮都重新注入，与历史压到多深无关。
 */
@Singleton
class TaskModule @Inject constructor(
    private val todoItemDao: TodoItemDao,
    private val sessionGoalDao: SessionGoalDao
) : EngineModule {

    private companion object {
        /** 单次注入的条目上限：清单很长时优先列未完成的，避免提示词无上限膨胀。 */
        const val MAX_INJECTED_TASKS = 30

        /** 计划模式下重新注入的计划摘要上限。 */
        const val PLAN_SUMMARY_MAX_CHARS = 2_000
    }

    override val id = "task"
    override val order = 10

    override suspend fun promptFragment(ctx: EngineContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val todos = todoItemDao.getBySessionOnce(sessionId).map { it.toDomain() }
        val goal = sessionGoalDao.getBySessionOnce(sessionId)?.toDomain()
        // 计划模式下的计划正文在历史里只是普通 assistant 消息，压得久了就看不到，这里重新注入。
        val plan = if (ctx.mode == AgentMode.PLAN) {
            ctx.history.asReversed()
                .filterIsInstance<AgentMessage.AssistantMessage>()
                .firstOrNull { it.content.isNotBlank() }
                ?.content
                ?.take(PLAN_SUMMARY_MAX_CHARS)
        } else {
            null
        }
        if (todos.isEmpty() && goal == null && plan == null) return null
        return render(todos, goal, plan)
    }

    private fun render(todos: List<TodoItem>, goal: SessionGoal?, plan: String?): String = buildString {
        append("任务与目标 (tasks)（独立于对话历史维护，上下文压缩不会影响它；")
        append("用 `todo` 工具更新清单、`goal` 工具更新目标）：")
        goal?.let { current ->
            append("\n- 目标: ${current.goalText}")
            if (current.milestones.isNotEmpty()) {
                append("\n- 里程碑 (${current.completedCount}/${current.milestones.size}):")
                current.milestones.forEach { milestone ->
                    append("\n  ${markOf(milestone.status.name)} ${milestone.title}")
                    if (milestone.detail.isNotBlank()) append("：${milestone.detail}")
                }
            }
        }
        if (todos.isNotEmpty()) {
            val unfinished = todos.count { it.status != TodoStatus.COMPLETED }
            append("\n- 待办 (已完成 ${todos.size - unfinished}/${todos.size}，未完成 $unfinished):")
            // 未完成的排前面：条目过多被截掉的总是已完成项，进度不会因此看不全。
            val ordered = todos.sortedBy { it.status == TodoStatus.COMPLETED }
            ordered.take(MAX_INJECTED_TASKS).forEach { item ->
                append("\n  ${markOf(item.status.name)} ${item.subject}")
            }
            if (ordered.size > MAX_INJECTED_TASKS) {
                append("\n  …（另有 ${ordered.size - MAX_INJECTED_TASKS} 项未列出）")
            }
        }
        plan?.let { append("\n- 当前计划: $it") }
    }

    /** 待办与里程碑共用一套进度标记：[x] 完成 / [~] 进行中 / [ ] 未开始。 */
    private fun markOf(statusName: String): String = when (statusName) {
        TodoStatus.COMPLETED.name, MilestoneStatus.COMPLETED.name -> "[x]"
        TodoStatus.IN_PROGRESS.name, MilestoneStatus.IN_PROGRESS.name -> "[~]"
        else -> "[ ]"
    }
}
