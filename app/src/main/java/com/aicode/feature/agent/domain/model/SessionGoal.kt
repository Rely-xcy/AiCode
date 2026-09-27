package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable

/** 会话级目标状态。 */
@Serializable
enum class GoalStatus { ACTIVE, COMPLETED }

/** 里程碑状态。 */
@Serializable
enum class MilestoneStatus { PENDING, IN_PROGRESS, COMPLETED }

/** 目标下的一个里程碑。 */
@Serializable
data class GoalMilestone(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: MilestoneStatus = MilestoneStatus.PENDING
)

/**
 * 会话级目标：一个总目标 + 若干里程碑。
 *
 * 独立于消息历史存储（`session_goals` 表），每轮注入系统提示词——
 * 这样上下文压缩折叠历史时不会把「目标做到哪一步了」一起压掉。
 */
@Serializable
data class SessionGoal(
    val sessionId: String,
    val goalText: String,
    val milestones: List<GoalMilestone> = emptyList(),
    val status: GoalStatus = GoalStatus.ACTIVE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val completedCount: Int get() = milestones.count { it.status == MilestoneStatus.COMPLETED }
}
