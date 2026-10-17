package com.aicode.feature.agent.domain.model

enum class TodoStatus { PENDING, IN_PROGRESS, IMPLEMENTED, VERIFYING, COMPLETED, BLOCKED, FAILED, CANCELLED }

data class TodoItem(
    val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: Int = 0,
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    /** 父任务 id；顶层任务为 null。 */
    val parentId: String? = null
)
