package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aicode.feature.agent.domain.model.GoalMilestone
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.SessionGoal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "session_goals")
data class SessionGoalEntity(
    @PrimaryKey val sessionId: String,
    val goalText: String,
    val milestonesJson: String = "[]",
    val status: String = "ACTIVE",
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): SessionGoal = SessionGoal(
        sessionId = sessionId,
        goalText = goalText,
        milestones = decodeMilestones(milestonesJson),
        status = runCatching { GoalStatus.valueOf(status) }.getOrDefault(GoalStatus.ACTIVE),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromDomain(goal: SessionGoal): SessionGoalEntity = SessionGoalEntity(
            sessionId = goal.sessionId,
            goalText = goal.goalText,
            milestonesJson = json.encodeToString(goal.milestones),
            status = goal.status.name,
            createdAt = goal.createdAt,
            updatedAt = goal.updatedAt
        )

        /** 里程碑 JSON 解析失败时按「无里程碑」处理，不让一行坏数据把整个目标读崩。 */
        private fun decodeMilestones(raw: String): List<GoalMilestone> =
            runCatching { json.decodeFromString<List<GoalMilestone>>(raw) }.getOrDefault(emptyList())
    }
}
