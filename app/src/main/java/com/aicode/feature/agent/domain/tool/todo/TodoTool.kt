package com.aicode.feature.agent.domain.tool.todo

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.agent.domain.todo.TodoListText
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * 管理 AI Agent 当前会话的任务清单（待办列表）。
 *
 * 接口以**增量**为主：加一项、改一项、删一项，按 subject 定位。快照式整表替换（`action=replace`）
 * 只在清单大改时用——要求模型每完成一小步就重发整张清单，成本高且容易改坏，模型会本能回避，
 * 于是进度只留在回复正文里，清单停在几十分钟前的旧状态。
 */
class TodoTool @Inject constructor(
    private val todoItemDao: TodoItemDao
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TodoTool"

        /** 清单条目上限：再多既撑窗口又没人看，超了先报错让模型收敛清单。 */
        const val MAX_ITEMS = 50
    }

    override val name = "todo"

    override val description = "维护本会话的任务清单（待办）。默认用增量操作、按 subject 定位，不必重发整张清单：" +
        "add=加一项（也支持 items 数组一次加多项；标题已存在时按更新处理）；" +
        "update=改一项（subject + status/description/priority 至少一个）；" +
        "remove=删一项；replace=用 items 整表替换（兜底）；clear=清空；list=查看当前清单。" +
        "【先调用、再写回复——不要只在回复里说「已完成」】" +
        "① 开始多步任务（3 步以上或需要多次工具调用）时先 add 建清单，再动手；" +
        "② 每完成一项立刻 update 成 completed，不要攒到最后一次性补；" +
        "③ 开始下一项时 update 成 in_progress；" +
        "④ 计划或需求变了（用户改主意、发现新问题、放弃某项）当场同步清单；" +
        "⑤ 一轮收尾前对照清单：回复里说「已完成 / 下一步」的每一项，清单里必须已经对上；" +
        "⑥ 已完成的项及时清掉（remove；一个阶段做完就 clear 一次），别让清单攒成一长串 completed——" +
        "清单要反映「还剩什么」，不是「干过什么」。" +
        "status 取值 pending / in_progress / completed。清单每轮都会重新注入系统提示词，压缩上下文不会丢。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)

    /** items 数组中单个待办项的 JSON Schema */
    private val todoItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "subject" to mapOf(
                "type" to "string",
                "description" to "简短的待办标题（祈使句，如「分析项目结构」）"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "详细说明（可选）"
            ),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("pending", "in_progress", "completed"),
                "description" to "状态，默认 pending"
            ),
            "priority" to mapOf(
                "type" to "integer",
                "description" to "优先级，0=普通，越大越优先"
            )
        ),
        "required" to listOf("subject")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "add=加一项（默认）| update=改一项 | remove=删一项 | replace=整表替换 | clear=清空 | list=查看。" +
                "省略时按参数推断：给了 items 视为 replace，给了 status 视为 update，只给 subject 视为 add。",
            required = false,
            enum = listOf("add", "update", "remove", "replace", "clear", "list")
        ),
        "subject" to ToolParameter(
            name = "subject",
            type = ParameterType.STRING,
            description = "待办标题。add/update/remove 用它定位，按内容匹配（不必与上次一字不差）。",
            required = false
        ),
        "status" to ToolParameter(
            name = "status",
            type = ParameterType.STRING,
            description = "状态：pending（未开始）/ in_progress（进行中）/ completed（已完成）",
            required = false,
            enum = listOf("pending", "in_progress", "completed")
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "补充说明（可选）",
            required = false
        ),
        "priority" to ToolParameter(
            name = "priority",
            type = ParameterType.INTEGER,
            description = "优先级，0=普通，越大越优先",
            required = false
        ),
        "items" to ToolParameter(
            name = "items",
            type = ParameterType.ARRAY,
            description = "add/replace 用：每项为含 subject 的对象。replace 时它是「当前完整清单」。",
            required = false,
            itemsSchema = todoItemSchema
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")

        val action = resolveAction(args)
            ?: return ToolResult.Error(
                "action 无效：${args["action"]?.jsonPrimitive?.contentOrNull}（可用 add / update / remove / replace / clear / list）",
                "INVALID_ACTION"
            )

        val rawStatus = args["status"]?.jsonPrimitive?.contentOrNull
        if (!rawStatus.isNullOrBlank() && parseStatus(rawStatus) == null) {
            return ToolResult.Error(
                "status 无效：$rawStatus（可用 pending / in_progress / completed）",
                "INVALID_STATUS"
            )
        }

        return try {
            val existing = todoItemDao.getBySessionOnce(sessionId)
            when (action) {
                Action.LIST -> buildSuccess(TodoListText.LIST_ACTION_MESSAGE, existing.map { it.toDomain() })
                Action.CLEAR -> clearAll(sessionId)
                Action.REPLACE -> replaceAll(args, sessionId, existing)
                Action.ADD -> addItems(args, sessionId, existing)
                Action.UPDATE -> updateItem(args, sessionId, existing)
                Action.REMOVE -> removeItem(args, sessionId, existing)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "todo 工具执行失败: ${e.message}", e)
            ToolResult.Error("待办操作失败：${e.message}")
        }
    }

    // ---------------------------------------------------------------- 各动作

    private suspend fun clearAll(sessionId: String): ToolResult {
        todoItemDao.deleteBySession(sessionId)
        FileLogger.d(TAG, "todo clear: 已清空 $sessionId 的清单")
        return buildSuccess("已清空清单", emptyList())
    }

    /** 整表替换（兜底）：按 subject 复用原有 id/createdAt，避免每轮替换都把历史清一遍。 */
    private suspend fun replaceAll(
        args: Map<String, JsonElement>,
        sessionId: String,
        existing: List<TodoItemEntity>
    ): ToolResult {
        val itemElements = args["items"] as? JsonArray
            ?: return ToolResult.Error("缺少必需参数：items（当前完整清单）", "MISSING_ITEMS")
        if (itemElements.size > MAX_ITEMS) {
            return ToolResult.Error("清单最多 $MAX_ITEMS 项，当前提交了 ${itemElements.size} 项", "TOO_MANY_ITEMS")
        }

        val existingBySubject = existing
            .groupBy { normalize(it.subject) }
            .mapValues { (_, items) -> items.toMutableList() }
        val now = System.currentTimeMillis()
        val entities = itemElements.mapIndexed { idx, element ->
            val draft = parseDraft(element, idx)
            val previous = existingBySubject[normalize(draft.subject)]?.removeFirstOrNull()
            TodoItemEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                subject = draft.subject,
                description = draft.description.orEmpty(),
                status = (draft.status ?: TodoStatus.PENDING).name,
                priority = draft.priority ?: 0,
                order = idx,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now
            )
        }

        todoItemDao.deleteBySession(sessionId)
        if (entities.isNotEmpty()) todoItemDao.upsertAll(entities)
        FileLogger.d(TAG, "todo replace: 替换为 ${entities.size} 项")
        return buildSuccess("已用 ${entities.size} 项替换清单", entities.map { it.toDomain() })
    }

    private suspend fun addItems(
        args: Map<String, JsonElement>,
        sessionId: String,
        existing: List<TodoItemEntity>
    ): ToolResult {
        val drafts = parseDrafts(args)
        if (drafts.isEmpty()) {
            return ToolResult.Error("add 需要 subject（或用 items 数组一次加多项）", "MISSING_ITEM")
        }

        val now = System.currentTimeMillis()
        val rows = existing.toMutableList()
        var nextOrder = (rows.maxOfOrNull { it.order } ?: -1) + 1
        val touched = mutableListOf<TodoItemEntity>()
        val created = mutableListOf<String>()
        val merged = mutableListOf<String>()

        for (draft in drafts) {
            val hitIndex = rows.indexOfFirst { normalize(it.subject) == normalize(draft.subject) }
            if (hitIndex >= 0) {
                // 标题已存在就按更新处理：清单里出现两条一样的待办比少一条更难收拾
                val hit = rows[hitIndex]
                val updated = hit.copy(
                    description = draft.description ?: hit.description,
                    status = (draft.status ?: parseStatus(hit.status) ?: TodoStatus.PENDING).name,
                    priority = draft.priority ?: hit.priority,
                    updatedAt = now
                )
                rows[hitIndex] = updated
                touched += updated
                merged += updated.subject
                continue
            }
            if (rows.size >= MAX_ITEMS) {
                return ToolResult.Error("清单已有 $MAX_ITEMS 项，先 remove 掉不要的再加", "TOO_MANY_ITEMS")
            }
            val entity = TodoItemEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                subject = draft.subject,
                description = draft.description.orEmpty(),
                status = (draft.status ?: TodoStatus.PENDING).name,
                priority = draft.priority ?: 0,
                order = nextOrder++,
                createdAt = now,
                updatedAt = now
            )
            rows += entity
            touched += entity
            created += entity.subject
        }

        todoItemDao.upsertAll(touched)
        val message = buildString {
            if (created.isNotEmpty()) append("已新增 ${created.size} 项：${created.joinToString("、")}")
            if (merged.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("标题已存在，按更新处理：${merged.joinToString("、")}")
            }
        }
        FileLogger.d(TAG, "todo add: 新增 ${created.size} 项，合并 ${merged.size} 项")
        return successResult(sessionId, message)
    }

    private suspend fun updateItem(
        args: Map<String, JsonElement>,
        sessionId: String,
        existing: List<TodoItemEntity>
    ): ToolResult {
        val subject = subjectOf(args)
            ?: return ToolResult.Error("update 需要 subject 指定改哪一项", "MISSING_SUBJECT")
        val status = parseStatus(args["status"]?.jsonPrimitive?.contentOrNull)
        val description = if (args.containsKey("description")) {
            args["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            null
        }
        val priority = args["priority"]?.jsonPrimitive?.intOrNull
        if (status == null && description == null && priority == null) {
            return ToolResult.Error(
                "update 至少要给一个要改的字段：status / description / priority",
                "MISSING_FIELDS"
            )
        }

        val target = when (val located = locate(existing, subject)) {
            is Locate.Hit -> located.entity
            else -> return locateError(located, subject, existing)
        }
        val updated = target.copy(
            status = (status ?: parseStatus(target.status) ?: TodoStatus.PENDING).name,
            description = description ?: target.description,
            priority = priority ?: target.priority,
            updatedAt = System.currentTimeMillis()
        )
        todoItemDao.upsert(updated)
        FileLogger.d(TAG, "todo update: 「${updated.subject}」-> ${updated.status}")

        val message = buildString {
            append("已更新「${updated.subject}」")
            status?.let { append("：${statusLabel(it)}") }
        }
        return successResult(sessionId, message)
    }

    private suspend fun removeItem(
        args: Map<String, JsonElement>,
        sessionId: String,
        existing: List<TodoItemEntity>
    ): ToolResult {
        val subject = subjectOf(args)
            ?: return ToolResult.Error("remove 需要 subject 指定删哪一项", "MISSING_SUBJECT")
        val target = when (val located = locate(existing, subject)) {
            is Locate.Hit -> located.entity
            else -> return locateError(located, subject, existing)
        }
        todoItemDao.delete(target.id)
        FileLogger.d(TAG, "todo remove: 已删除「${target.subject}」")
        return successResult(sessionId, "已删除「${target.subject}」")
    }

    // ---------------------------------------------------------------- 结果组装

    private suspend fun successResult(sessionId: String, message: String): ToolResult =
        buildSuccess(message, todoItemDao.getBySessionOnce(sessionId).map { it.toDomain() })

    /**
     * 工具结果：UI 卡片依赖 total/completed/items[{id,subject,description,status,priority,order}]
     * 这套字段（见 [com.aicode.feature.agent.presentation.component.parseTodoResult]），不要改。
     * 另外给模型两个更省 token 的字段：message（这一步做了什么）与 text（渲染后的清单）。
     */
    private fun buildSuccess(message: String, items: List<TodoItem>): ToolResult {
        val next = TodoListText.nextItem(items)
        val fullMessage = buildString {
            append(message)
            if (items.isEmpty()) {
                append("；清单现在是空的")
            } else if (next != null) {
                append("；下一个未完成项：${next.subject}")
            } else {
                append("；清单全部完成")
            }
        }
        val data = JsonObject(
            mapOf(
                "message" to JsonPrimitive(fullMessage),
                "total" to JsonPrimitive(items.size),
                "completed" to JsonPrimitive(items.count { it.status == TodoStatus.COMPLETED }),
                "next" to JsonPrimitive(next?.subject.orEmpty()),
                "text" to JsonPrimitive(TodoListText.render(items)),
                "items" to JsonArray(
                    items.map { item ->
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(item.id),
                                "subject" to JsonPrimitive(item.subject),
                                "description" to JsonPrimitive(item.description),
                                "status" to JsonPrimitive(item.status.name.lowercase()),
                                "priority" to JsonPrimitive(item.priority),
                                "order" to JsonPrimitive(item.order)
                            )
                        )
                    }
                )
            )
        )
        return ToolResult.Success(data)
    }

    // ---------------------------------------------------------------- 参数解析

    private enum class Action { ADD, UPDATE, REMOVE, REPLACE, CLEAR, LIST }

    private fun resolveAction(args: Map<String, JsonElement>): Action? {
        val raw = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        if (!raw.isNullOrBlank()) {
            return when (raw) {
                "add", "create" -> Action.ADD
                "update", "set", "complete", "done" -> Action.UPDATE
                "remove", "delete" -> Action.REMOVE
                "replace" -> Action.REPLACE
                "clear", "reset" -> Action.CLEAR
                "list", "get" -> Action.LIST
                else -> null
            }
        }
        // 省略 action 时按参数推断，容错模型少写一个字段
        return when {
            args["items"] is JsonArray -> Action.REPLACE
            args["status"] != null -> Action.UPDATE
            subjectOf(args) != null -> Action.ADD
            else -> Action.LIST
        }
    }

    private fun parseDrafts(args: Map<String, JsonElement>): List<Draft> {
        val array = args["items"] as? JsonArray
        if (array != null) return array.mapIndexed { idx, element -> parseDraft(element, idx) }
        val subject = subjectOf(args) ?: return emptyList()
        return listOf(
            Draft(
                subject = subject,
                description = args["description"]?.jsonPrimitive?.contentOrNull,
                status = parseStatus(args["status"]?.jsonPrimitive?.contentOrNull),
                priority = args["priority"]?.jsonPrimitive?.intOrNull
            )
        )
    }

    /** 解析数组里的单项；格式不对直接抛异常，由外层统一转成 ToolResult.Error。 */
    private fun parseDraft(element: JsonElement, index: Int): Draft {
        if (element is JsonPrimitive) {
            val subject = element.contentOrNull?.trim().orEmpty()
            if (subject.isBlank()) throw IllegalArgumentException("第 ${index + 1} 项标题为空")
            return Draft(subject = subject)
        }
        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("第 ${index + 1} 项需要字符串标题或含 subject 的对象")
        val subject = obj["subject"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (subject.isBlank()) throw IllegalArgumentException("第 ${index + 1} 项缺少 subject")
        val rawStatus = obj["status"]?.jsonPrimitive?.contentOrNull
        val status = parseStatus(rawStatus)
        if (!rawStatus.isNullOrBlank() && status == null) {
            throw IllegalArgumentException("第 ${index + 1} 项 status 无效：$rawStatus")
        }
        return Draft(
            subject = subject,
            description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim(),
            status = status,
            priority = obj["priority"]?.jsonPrimitive?.intOrNull
        )
    }

    private fun subjectOf(args: Map<String, JsonElement>): String? =
        args["subject"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseStatus(raw: String?): TodoStatus? {
        val normalized = raw
            ?.trim()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return when (normalized) {
            "PENDING", "TODO", "OPEN", "NOT_STARTED" -> TodoStatus.PENDING
            "IN_PROGRESS", "INPROGRESS", "DOING", "ACTIVE", "STARTED" -> TodoStatus.IN_PROGRESS
            "COMPLETED", "COMPLETE", "DONE", "FINISHED", "CLOSED" -> TodoStatus.COMPLETED
            else -> null
        }
    }

    // ---------------------------------------------------------------- 定位

    private sealed interface Locate {
        data class Hit(val entity: TodoItemEntity) : Locate

        /** 多个近似候选：不猜，让模型用完整标题重试。 */
        data class Ambiguous(val candidates: List<String>) : Locate

        object None : Locate
    }

    /**
     * 按标题定位：先精确匹配（忽略大小写与空白），再退到包含/前缀匹配。
     * 退一步匹配命中多项时宁可报错——删错项比多一次调用糟得多。
     */
    private fun locate(items: List<TodoItemEntity>, subject: String): Locate {
        val key = normalize(subject)
        items.firstOrNull { normalize(it.subject) == key }?.let { return Locate.Hit(it) }
        val loose = items.filter {
            val name = normalize(it.subject)
            name.startsWith(key) || key.startsWith(name) || name.contains(key)
        }
        return when (loose.size) {
            0 -> Locate.None
            1 -> Locate.Hit(loose.first())
            else -> Locate.Ambiguous(loose.map { it.subject })
        }
    }

    private fun locateError(located: Locate, subject: String, items: List<TodoItemEntity>): ToolResult.Error {
        val current = TodoListText.render(items.map { it.toDomain() })
        return when (located) {
            is Locate.Ambiguous -> ToolResult.Error(
                "「$subject」匹配到多项：${located.candidates.joinToString("、")}——请用完整标题重试。\n当前清单：\n$current",
                "AMBIGUOUS_SUBJECT"
            )
            else -> ToolResult.Error(
                "清单里没有「$subject」。当前清单：\n${current.ifBlank { "（空）" }}",
                "SUBJECT_NOT_FOUND"
            )
        }
    }

    private fun statusLabel(status: TodoStatus): String = when (status) {
        TodoStatus.COMPLETED -> "completed（已完成）"
        TodoStatus.IN_PROGRESS -> "in_progress（进行中）"
        TodoStatus.PENDING -> "pending（未开始）"
    }

    private fun normalize(subject: String): String =
        subject.trim().lowercase().replace(Regex("\\s+"), "")
}

private data class Draft(
    val subject: String,
    val description: String? = null,
    val status: TodoStatus? = null,
    val priority: Int? = null
)
