package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.workflow.ContextUsageHolder
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 团队工具 `team`：只读地查看与汇总本会话派出的子代理，供主代理在收尾前把并行子代理的结论收齐。
 *
 * 通过 `action` 参数区分操作类型：
 * - `status`（默认）：列出当前会话的全部子代理及其状态、当前阶段与 token 消耗；
 * - `collect`：汇总已完成子代理的最终结论（可用 `ids` 指定，省略则取本会话下全部已完成子代理）。
 *
 * 与 `task` 的分工：`task` 负责派发/发消息/读取单个/停止/删除（会改会话状态），`team` 只做只读的
 * 团队视角查看与汇总。因此本工具声明为只读、免问级别，不参与授权弹窗。
 * 只在主代理会话提供（由 [com.aicode.feature.agent.domain.engine.modules.SubAgentTeamModule.tools] 聚合）。
 */
class SubAgentTeamTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val contextUsageHolder: ContextUsageHolder
) : AbstractContextualTool() {

    override val name = "team"

    /** 两个 action 都是只读：不弹授权窗。 */
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val description = "团队协同：查看与汇总本会话派出的子代理。status：列出全部子代理的 id、标题、状态（running/completed）、当前阶段与 token 消耗；collect：汇总已完成子代理的最终结论（可选 ids 指定，省略则取本会话下全部已完成子代理）。" +
        "只读，不改变子代理状态；派发、发消息、停止、删除仍用 task 工具。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：status（默认，列出本会话全部子代理的状态、阶段与 token 消耗）/ collect（汇总已完成子代理的最终结论）",
            required = false
        ),
        "ids" to ToolParameter(
            name = "ids",
            type = ParameterType.ARRAY,
            description = "要汇总的子代理 id 列表（collect 可选，取自 team(action=\"status\") 或 task 返回的 id）；省略则汇总本会话下全部已完成子代理。也可传逗号分隔的字符串。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "status"
        return when (action) {
            "status" -> status(parentSessionId)
            "collect" -> collect(parentSessionId, args)
            else -> ToolResult.Error("未知的 action：$action，支持：status / collect", "INVALID_ARGS")
        }
    }

    /** 列出本会话的全部子代理：id、标题、状态、阶段、token 消耗。 */
    private suspend fun status(parentSessionId: String): ToolResult {
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val active = eventBus.activeSubSessionIds.value
        val usage = contextUsageHolder.usage.value

        val subagents = buildJsonArray {
            subs.forEach { entity ->
                val running = entity.id in active
                val messages = agentMessageDao.getMessagesBySessionOnce(entity.id)
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (running) "running" else "completed")
                    put("phase", phaseOf(running, messages))
                    put("totalInputTokens", entity.totalInputTokens)
                    put("totalOutputTokens", entity.totalOutputTokens)
                    usage[entity.id]?.let { snapshot ->
                        put("contextTokens", snapshot.currentTokens)
                        put("contextLimit", snapshot.contextLimit)
                    }
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", subagents)
                put("count", subs.size)
                put("runningCount", subs.count { it.id in active })
                put("maxRunning", SubAgentEventBus.MAX_RUNNING)
            }
        )
    }

    /** 汇总已完成子代理的最终结论；`ids` 给定时按给定集合取（含仍在跑的，用 state 标出）。 */
    private suspend fun collect(parentSessionId: String, args: Map<String, JsonElement>): ToolResult {
        val requested = parseIds(args["ids"])
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val active = eventBus.activeSubSessionIds.value
        val byId = subs.associateBy { it.id }

        val selected: List<ChatSessionEntity>
        val missing: List<String>
        if (requested.isEmpty()) {
            selected = subs.filter { it.id !in active }
            missing = emptyList()
        } else {
            selected = requested.mapNotNull { byId[it] }
            missing = requested.filter { it !in byId }
        }
        if (requested.isNotEmpty() && selected.isEmpty()) {
            return ToolResult.Error(
                "指定的子代理不存在或不属于当前会话：${missing.joinToString(", ")}",
                "SUBAGENT_NOT_FOUND"
            )
        }

        val subagents = buildJsonArray {
            selected.forEach { entity ->
                val messages = agentMessageDao.getMessagesBySessionOnce(entity.id)
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in active) "running" else "completed")
                    put("lastOutput", lastOutputOf(messages))
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", subagents)
                put("count", selected.size)
                if (missing.isNotEmpty()) {
                    put("missingIds", JsonArray(missing.map { JsonPrimitive(it) }))
                }
            }
        )
    }

    /**
     * 子代理当前阶段的最近似描述。只按领域层可得的信息派生，不臆造：
     * - 运行中且最后一条是「执行中」的工具占位行 → 正在执行该工具；
     * - 运行中其余情况 → 正在生成回复；
     * - 已结束且有助手正文 → 已完成；否则 → 无输出。
     */
    private fun phaseOf(running: Boolean, messages: List<AgentMessageEntity>): String {
        if (!running) {
            val hasOutput = messages.any { it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank() }
            return if (hasOutput) "已完成" else "无输出"
        }
        val last = messages.lastOrNull()
        val pendingTool = last?.takeIf {
            it.role == MessageRole.TOOL.name && it.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER)
        }
        return if (pendingTool != null) {
            "正在执行工具：${pendingTool.toolName ?: "未知工具"}"
        } else {
            "正在生成回复"
        }
    }

    /** 取子代理最后一条有内容的助手回复；口径与 `task(action="read")` 一致（跳过 reasoning-only 中间消息）。 */
    private fun lastOutputOf(messages: List<AgentMessageEntity>): String {
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        return when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（子代理尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（子代理会话为空）"
        }
    }

    /** 解析 `ids`：标准写法是字符串数组；模型偶尔会传逗号分隔的字符串，那时必须照样认出来。 */
    private fun parseIds(raw: JsonElement?): List<String> {
        val items = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            null -> return emptyList()
            else -> listOfNotNull((raw as? JsonPrimitive)?.contentOrNull)
        }
        return items.flatMap { it.split(',', '\n') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
