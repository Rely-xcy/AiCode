package com.aicode.feature.agent.domain.model

import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.BULK_ARG_KEYS
import com.aicode.feature.agent.domain.workflow.bulkArgumentExcerptOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 历史工具参数的**写入时**摘录阈值（字符）。
 *
 * 为什么放在写入时：请求组装前按当前上下文重算摘录，会让同一条历史消息在不同轮次得到不同形态，
 * 发给服务商的历史前缀字节随之变化，前缀缓存整段失效。参数一旦进入历史就应当定型，
 * 因此这里只做一次，写进库的内存历史与落库快照共用同一结果。
 *
 * 取 8_000：与旧入口投影的阈值一致，也和 [com.aicode.feature.agent.domain.tool.ToolOutputStore]
 * 的 8_000 头部保留量同量级——超过这个量级的正文，模型当下用得上的就是头尾，中段可以回读。
 */
internal const val HISTORY_TOOL_ARG_THRESHOLD_CHARS = 8_000

/** 非正文类参数（命令、脚本等）撑破落库上限时的截断标记；保持 JSON 合法，绝不截整串 JSON。 */
internal const val HISTORY_ARG_TRUNCATED_MARKER = "…[参数过长，写入时已截断]"

/** 参数里的目标文件路径字段，与 [BULK_ARG_KEYS] 同样保持 write/edit 系列工具的别名。 */
private val HISTORY_PATH_ARG_KEYS = setOf("path", "file_path", "filepath", "file")

/** 低于这个长度的参数不再继续二分截断：再切下去只剩标记，没有阅读价值。 */
private const val HISTORY_ARG_MIN_SHRINK_CHARS = 512

/**
 * 把一条工具调用的参数摘录成历史形态（纯函数）。
 *
 * 只改 [BULK_ARG_KEYS] 里超阈值的大字段：留头留尾、写清省略量与回读路径；
 * 其余字段原样保留。返回值在第二次应用时与输入相同（摘录后的长度已低于阈值）。
 */
internal fun excerptToolCallForHistory(toolCall: ToolCall): ToolCall {
    val path = toolCall.arguments.entries
        .firstOrNull { it.key.lowercase() in HISTORY_PATH_ARG_KEYS }
        ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() }
    var changed = false
    val excerpted = toolCall.arguments.mapValues { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapValues value
        if (key.lowercase() !in BULK_ARG_KEYS) return@mapValues value
        if (text.length <= HISTORY_TOOL_ARG_THRESHOLD_CHARS) return@mapValues value
        val excerpt = bulkArgumentExcerptOf(text, path, HISTORY_TOOL_ARG_THRESHOLD_CHARS)
            ?: return@mapValues value
        changed = true
        JsonPrimitive(excerpt)
    }
    return if (changed) toolCall.copy(arguments = excerpted) else toolCall
}

/** 批量版本；整批都不需要摘录时返回原列表，便于调用方按引用判断是否改动。 */
internal fun excerptToolCallsForHistory(toolCalls: List<ToolCall>): List<ToolCall> {
    if (toolCalls.isEmpty()) return toolCalls
    var changed = false
    val excerpted = toolCalls.map { call ->
        val next = excerptToolCallForHistory(call)
        if (next !== call) changed = true
        next
    }
    return if (changed) excerpted else toolCalls
}

/**
 * 把工具调用序列化成落库快照，并保证结果**始终是合法 JSON**。
 *
 * 先按 [excerptToolCallsForHistory] 换成摘录；若仍超过 [maxBytes]，不再截断整串 JSON（那会让下一轮
 * 解码失败、整对工具消息从历史里消失），而是找出最长的字符串值对半截断并追加
 * [HISTORY_ARG_TRUNCATED_MARKER]，逐轮收敛。极端情况下所有字符串都短到不值得再切时允许超限：
 * 宁可单行偏大（仍远低于 CursorWindow），也不能让历史丢消息。
 *
 * @param json 与落库使用同一个序列化配置，避免两处产出不同字节。
 */
internal fun toolCallsJsonForStorage(
    toolCalls: List<ToolCall>,
    maxBytes: Int,
    json: Json = Json { ignoreUnknownKeys = true }
): String {
    var calls = excerptToolCallsForHistory(toolCalls)
    var encoded = json.encodeToString(calls)
    while (!fitsUtf8(encoded, maxBytes)) {
        val shrunk = shrinkLongestArgumentString(calls) ?: return encoded
        calls = shrunk
        encoded = json.encodeToString(calls)
    }
    return encoded
}

private fun fitsUtf8(text: String, maxBytes: Int): Boolean =
    text.length.toLong() * 3 <= maxBytes || text.toByteArray(Charsets.UTF_8).size <= maxBytes

/**
 * 把参数树里最长的字符串值对半截断一次。返回 null 表示已没有值得截断的值。
 *
 * 每次只动一个值并重新序列化，收敛路径确定：同一输入必然得到同一输出，落库后可安全重放。
 */
private fun shrinkLongestArgumentString(calls: List<ToolCall>): List<ToolCall>? {
    var bestCallIndex = -1
    var bestKey: String? = null
    var bestLength = 0
    calls.forEachIndexed { index, call ->
        call.arguments.forEach { (key, value) ->
            val length = value.longestStringLength()
            if (length > bestLength) {
                bestLength = length
                bestCallIndex = index
                bestKey = key
            }
        }
    }
    if (bestLength <= HISTORY_ARG_MIN_SHRINK_CHARS * 2) return null
    val call = calls.getOrNull(bestCallIndex) ?: return null
    val key = bestKey ?: return null
    val value = call.arguments[key] ?: return null
    val rawCut = bestLength / 2
    var replaced = false
    val shortened = value.capLongestString(bestLength) { text ->
        replaced = true
        // 切点落在代理对中间会留下半个字符，序列化后变成非法 UTF-8。
        val cut = if (rawCut in 1 until text.length && text[rawCut - 1].isHighSurrogate()) rawCut - 1 else rawCut
        text.substring(0, cut) + HISTORY_ARG_TRUNCATED_MARKER
    }
    if (!replaced) return null
    val updatedArguments = call.arguments.toMutableMap()
    updatedArguments[key] = shortened
    val updated = calls.toMutableList()
    updated[bestCallIndex] = call.copy(arguments = updatedArguments)
    return updated
}

private fun JsonElement.longestStringLength(): Int = when (this) {
    is JsonPrimitive -> if (isString) content.length else 0
    is JsonObject -> values.maxOfOrNull { it.longestStringLength() } ?: 0
    is JsonArray -> maxOfOrNull { it.longestStringLength() } ?: 0
}

private fun JsonElement.capLongestString(targetLength: Int, cap: (String) -> String): JsonElement =
    when (this) {
        is JsonPrimitive -> if (isString && content.length == targetLength) {
            JsonPrimitive(cap(content))
        } else {
            this
        }
        // mapValues 保留键，lambda 只需返回新值（返回 键 to 值 会得到 Pair<String, JsonElement>）。
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.capLongestString(targetLength, cap) })
        is JsonArray -> JsonArray(map { it.capLongestString(targetLength, cap) })
    }
