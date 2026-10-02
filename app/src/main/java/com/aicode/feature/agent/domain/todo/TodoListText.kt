package com.aicode.feature.agent.domain.todo

import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus

/**
 * 待办清单的文本渲染。
 *
 * 注入系统提示词（[com.aicode.feature.agent.domain.engine.modules.TaskModule]）与工具结果
 * （[com.aicode.feature.agent.domain.tool.todo.TodoTool]）共用同一份渲染，避免两处格式漂移：
 * 模型在提示词里看到的形态，就是它在工具结果里看到的形态。
 */
object TodoListText {

    /** 单次渲染的条目上限：清单很长时优先列未完成的，避免提示词无上限膨胀。 */
    const val MAX_LINES = 30

    /**
     * `list` 动作的成功消息开头（后面还会拼「；下一个未完成项：…」这类后缀，所以只比对前缀）。
     *
     * 工具结果里不带调用参数，收尾守卫只能认这条文本，用来区分「只是看了一眼清单」与「真的改了清单」；
     * [TodoProgressGuard] 依赖它，改这里要同步。
     */
    const val LIST_ACTION_MESSAGE = "当前清单"

    /** 待办状态标记：[x] 完成 / [~] 进行中 / [ ] 未开始。 */
    fun mark(status: TodoStatus): String = when (status) {
        TodoStatus.COMPLETED -> "[x]"
        TodoStatus.IN_PROGRESS -> "[~]"
        TodoStatus.PENDING -> "[ ]"
    }

    /** 一行待办：标记 + 标题（不含描述——描述留在工具结果里，注入时只给标题省窗口）。 */
    fun line(item: TodoItem): String = "${mark(item.status)} ${item.subject}"

    fun summary(items: List<TodoItem>): String {
        val completed = items.count { it.status == TodoStatus.COMPLETED }
        val inProgress = items.count { it.status == TodoStatus.IN_PROGRESS }
        return if (inProgress > 0) {
            "已完成 $completed/${items.size}，进行中 $inProgress"
        } else {
            "已完成 $completed/${items.size}"
        }
    }

    /**
     * 渲染清单正文。
     *
     * [unfinishedFirst] 为 true 时未完成的排前面：条目过多被截掉的总是已完成项，进度不会因此看不全。
     */
    fun render(
        items: List<TodoItem>,
        limit: Int = MAX_LINES,
        unfinishedFirst: Boolean = false
    ): String {
        if (items.isEmpty()) return ""
        val ordered = if (unfinishedFirst) {
            items.sortedBy { it.status == TodoStatus.COMPLETED }
        } else {
            items
        }
        val body = ordered.take(limit).joinToString("\n") { line(it) }
        val rest = ordered.size - limit
        return if (rest > 0) "$body\n…（另有 $rest 项未列出）" else body
    }

    /** 下一个未完成项：先取进行中的，再取未开始的；都完成时返回 null。 */
    fun nextItem(items: List<TodoItem>): TodoItem? =
        items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
            ?: items.firstOrNull { it.status == TodoStatus.PENDING }
}
