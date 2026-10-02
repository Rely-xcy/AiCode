package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val projectionJson = Json { ignoreUnknownKeys = true }

/**
 * 文件类工具喂给模型的精简结果文本，对齐 opencode 的 edit / write 语义：
 * - editFile：一句话确认 + 替换数 + 增删行数 + diff 预览（截断）；
 * - writeFile：一句话确认 + 行数，不回显内容。
 *
 * 只投影成功结果，其它工具 / 失败返回 null，由调用方回退用完整 result。
 * 带插话通知（顶层 `notifications`）的结果同样返回 null：那份通知只能靠完整结果才能到模型。
 * 注意：此文本仅喂模型，UI 与持久化仍走 result 的完整 diff。
 */
fun modelToolResultText(toolName: String, transportJson: String): String? {
    // 先按工具名短路：token 估算会对每条工具结果调用本函数，非文件类工具（readFile 的结果可能上百 KB）
    // 不该为了拿一个 null 去解析整份 JSON。名字清单必须与下面的 when 分支一致。
    if (toolName != "editFile" && toolName != "writeFile" && toolName != "todo") return null
    val raw = transportJson.trim()
    if (raw.isEmpty()) return null
    val obj = runCatching { projectionJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    // 带通知时必须回退完整结果：通知挂在 transport JSON 顶层，投影会把整段吃掉；
    // 而通知在注入后就被 ack，模型这一份丢了就真丢了（用户插话到不了模型，且不会重发）。
    if (obj.containsKey("notifications")) return null
    if ((obj["status"] as? JsonPrimitive)?.contentOrNull != "success") return null
    val data = obj["data"] as? JsonObject ?: return null
    // 兜底：调用方（TokenEstimator）没有 try/catch，投影报错会顺估算链路把 app 带崩；
    // 投影失败只损失这一份精简文本，返回 null 即回退完整 result（调用方 `?: message.result`）。
    return runCatching {
        when (toolName) {
            "editFile" -> editProjection(data)
            "writeFile" -> writeProjection(data)
            "todo" -> todoProjection(data)
            else -> null
        }
    }.getOrNull()
}

/**
 * todo 工具：只给「这一步改了什么 + 当前清单」，不回显 items 的完整 JSON。
 *
 * 清单本来就每轮注入系统提示词，工具结果只需确认这次改动后的状态——
 * 增量更新的意义就在这里：完成一步只花几十个 token。
 */
private fun todoProjection(data: JsonObject): String? {
    val message = (data["message"] as? JsonPrimitive)?.contentOrNull
    val text = (data["text"] as? JsonPrimitive)?.contentOrNull
    if (message.isNullOrBlank() && text.isNullOrBlank()) return null
    return listOf(message.orEmpty(), text.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
}

private fun editProjection(data: JsonObject): String? {
    val path = (data["path"] as? JsonPrimitive)?.contentOrNull ?: return null
    val replacements = (data["replacements"] as? JsonPrimitive)?.intOrNull ?: 0
    val added = (data["added_lines"] as? JsonPrimitive)?.intOrNull ?: 0
    val removed = (data["removed_lines"] as? JsonPrimitive)?.intOrNull ?: 0
    val lines = buildList {
        add("Edited file successfully: $path")
        add("Replacements: $replacements")
        add("Changed lines: +$added -$removed")
        diffPreview(data)?.let { add("```diff"); addAll(it); add("```") }
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

private fun writeProjection(data: JsonObject): String? {
    val path = (data["path"] as? JsonPrimitive)?.contentOrNull ?: return null
    val created = (data["created"] as? JsonPrimitive)?.contentOrNull == "true"
    val added = (data["added_lines"] as? JsonPrimitive)?.intOrNull ?: 0
    val removed = (data["removed_lines"] as? JsonPrimitive)?.intOrNull ?: 0
    val total = (data["lines_written"] as? JsonPrimitive)?.intOrNull
        ?: (data["total_lines"] as? JsonPrimitive)?.intOrNull
    val verb = if (created) "Created" else "Wrote"
    val lines = buildList {
        add("$verb file successfully: $path (lines: ${total ?: "?"}, +$added -$removed)")
    }
    return lines.joinToString("\n")
}

/** 从 hunks 里取 diff 的前几行做预览，每行超长截断。hunks 为空时返回 null。 */
private fun diffPreview(data: JsonObject): List<String>? {
    val hunks = (data["hunks"] as? JsonArray) ?: return null
    val all = buildList {
        hunks.forEach { el ->
            val h = (el as? JsonObject) ?: return@forEach
            ((h["diff"] as? JsonPrimitive)?.contentOrNull)?.let { this += it }
        }
    }
    if (all.isEmpty()) return null
    val lines = all
        .flatMap { it.split("\n") }
        .map { if (it.length > 240) it.take(240) + "..." else it }
    return lines.take(6)
}