package com.aicode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus

@Entity(tableName = "todo_items")
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: String = "PENDING",
    val priority: Int = 0,
    val order: Int = 0,
    /**
     * 前置任务的 subject，逗号分隔（空串表示无依赖）。
     *
     * 用 subject 而不是 id：每次 `todo` 工具都是整表替换，id 只在 subject 同名时才会沿用，
     * 而 subject 是模型能稳定引用的身份。
     */
    @ColumnInfo(name = "blocked_by") val blockedBy: String = "",
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): TodoItem = TodoItem(
        id = id,
        sessionId = sessionId,
        subject = subject,
        description = description,
        status = try { TodoStatus.valueOf(status) } catch (_: Exception) { TodoStatus.PENDING },
        priority = priority,
        order = order,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(item: TodoItem): TodoItemEntity = TodoItemEntity(
            id = item.id,
            sessionId = item.sessionId,
            subject = item.subject,
            description = item.description,
            status = item.status.name,
            priority = item.priority,
            order = item.order,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }
}
