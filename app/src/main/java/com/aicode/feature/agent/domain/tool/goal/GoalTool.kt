package com.aicode.feature.agent.domain.tool.goal

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.SessionGoalDao
import com.aicode.feature.agent.data.local.entity.SessionGoalEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.GoalMilestone
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.MilestoneStatus
import com.aicode.feature.agent.domain.model.SessionGoal
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * 维护当前会话的目标（总目标 + 里程碑）。
 *
 * 与 [com.aicode.feature.agent.domain.tool.todo.TodoTool] 同为快照式：每次提交完整的目标与里程碑列表，
 * 工具整体替换。目标独立存表、每轮注入系统提示词，因此上下文压缩折叠历史不会让模型忘记目标进度。
 */
class GoalTool @Inject constructor(
    private val sessionGoalDao: SessionGoalDao
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "GoalTool"
        const val MAX_GOAL_CHARS = 2_000
        const val MAX_MILESTONE_CHARS = 300
        const val MAX_MILESTONES = 30
    }

    override val name = "goal"

    override val description = "设置或更新本会话的目标与里程碑。提交完整的目标文本与里程碑列表即可，" +
        "无需区分新增/修改；里程碑按 title 匹配复用原 id 与状态。传空里程碑数组表示只保留目标；" +
        "action=clear 清空目标。目标独立于对话历史保存，压缩上下文不会丢失。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)

    private val milestoneSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "里程碑标题（简短祈使句）"),
            "detail" to mapOf("type" to "string", "description" to "补充说明（可选）"),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("pending", "in_progress", "completed"),
                "description" to "状态，默认 pending"
            )
        ),
        "required" to listOf("title")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "set（默认，设置/更新目标）/ clear（清空目标与里程碑）",
            required = false
        ),
        "goal" to ToolParameter(
            name = "goal",
            type = ParameterType.STRING,
            description = "总体目标（一句话说清最终要达成什么）。action=set 时必填",
            required = false
        ),
        "milestones" to ToolParameter(
            name = "milestones",
            type = ParameterType.ARRAY,
            description = "里程碑列表；传空数组表示只保留目标",
            required = false,
            itemsSchema = milestoneSchema
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        return try {
            if (action == "clear") clear(sessionId) else setGoal(args, sessionId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "goal 工具执行失败: ${e.message}", e)
            ToolResult.Error("目标操作失败: ${e.message}")
        }
    }

    private suspend fun clear(sessionId: String): ToolResult {
        sessionGoalDao.deleteBySession(sessionId)
        return ToolResult.Success(JsonObject(mapOf("goal" to JsonPrimitive(""), "milestones" to JsonArray(emptyList()))))
    }

    private suspend fun setGoal(args: Map<String, JsonElement>, sessionId: String): ToolResult {
        val goalText = (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (goalText.isBlank()) return ToolResult.Error("需要 goal 文本", "MISSING_GOAL")

        val existing = sessionGoalDao.getBySessionOnce(sessionId)?.toDomain()
        val previousByTitle = existing?.milestones.orEmpty().associateBy { it.title.trim().lowercase() }
        val now = System.currentTimeMillis()

        val milestoneElements = args["milestones"] as? JsonArray ?: JsonArray(emptyList())
        val milestones = milestoneElements.take(MAX_MILESTONES).mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val previous = previousByTitle[title.lowercase()]
            GoalMilestone(
                id = previous?.id ?: UUID.randomUUID().toString(),
                title = title.take(MAX_MILESTONE_CHARS),
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                status = parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull)
            )
        }

        val goal = SessionGoal(
            sessionId = sessionId,
            goalText = goalText.take(MAX_GOAL_CHARS),
            milestones = milestones,
            status = if (milestones.isNotEmpty() && milestones.all { it.status == MilestoneStatus.COMPLETED }) {
                GoalStatus.COMPLETED
            } else {
                GoalStatus.ACTIVE
            },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        sessionGoalDao.upsert(SessionGoalEntity.fromDomain(goal))
        FileLogger.d(TAG, "goal set: ${milestones.size} 个里程碑，会话 $sessionId")
        return ToolResult.Success(render(goal))
    }

    private fun render(goal: SessionGoal): JsonObject = JsonObject(mapOf(
        "goal" to JsonPrimitive(goal.goalText),
        "status" to JsonPrimitive(goal.status.name.lowercase()),
        "completed" to JsonPrimitive(goal.completedCount),
        "total" to JsonPrimitive(goal.milestones.size),
        "milestones" to JsonArray(goal.milestones.map { milestone ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(milestone.id),
                "title" to JsonPrimitive(milestone.title),
                "detail" to JsonPrimitive(milestone.detail),
                "status" to JsonPrimitive(milestone.status.name.lowercase())
            ))
        })
    ))

    private fun parseStatus(raw: String?): MilestoneStatus {
        val normalized = raw?.trim()?.replace("-", "_")?.replace(" ", "_")?.uppercase()
        return runCatching { MilestoneStatus.valueOf(normalized.orEmpty()) }
            .getOrDefault(MilestoneStatus.PENDING)
    }
}
