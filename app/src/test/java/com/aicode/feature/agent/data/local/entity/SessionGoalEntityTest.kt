package com.aicode.feature.agent.data.local.entity

import com.aicode.feature.agent.domain.model.GoalMilestone
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.MilestoneStatus
import com.aicode.feature.agent.domain.model.SessionGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGoalEntityTest {

    @Test
    fun roundTrip_preservesMilestonesAndStatus() {
        val goal = SessionGoal(
            sessionId = "s1",
            goalText = "重构压缩机制",
            milestones = listOf(
                GoalMilestone(id = "m1", title = "对齐阈值", detail = "45/70", status = MilestoneStatus.COMPLETED),
                GoalMilestone(id = "m2", title = "缓存清理", status = MilestoneStatus.IN_PROGRESS)
            ),
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 2L
        )

        val restored = SessionGoalEntity.fromDomain(goal).toDomain()

        assertEquals(goal, restored)
        assertEquals(1, restored.completedCount)
    }

    @Test
    fun toDomain_brokenJson_fallsBackToEmptyMilestones() {
        val entity = SessionGoalEntity(
            sessionId = "s1",
            goalText = "目标",
            milestonesJson = "{ 不是数组 }",
            status = "ACTIVE",
            createdAt = 1L,
            updatedAt = 2L
        )

        assertTrue(entity.toDomain().milestones.isEmpty())
    }

    @Test
    fun toDomain_unknownStatus_fallsBackToActive() {
        val entity = SessionGoalEntity(
            sessionId = "s1",
            goalText = "目标",
            milestonesJson = "[]",
            status = "SOMETHING_NEW",
            createdAt = 1L,
            updatedAt = 2L
        )

        assertEquals(GoalStatus.ACTIVE, entity.toDomain().status)
    }
}
