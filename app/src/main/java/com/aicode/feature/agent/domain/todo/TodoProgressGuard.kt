package com.aicode.feature.agent.domain.todo

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 「活干完了、清单却没动」的边界判据。
 *
 * 背景（用户投诉，已实测发生）：只在系统提示词里塞「提醒 + 统计」治不住——规则写成描述，
 * 模型会把它当背景音；同一句每轮都念，更会被整段无视。这里换成可核验的拦截：模型要给出
 * 本轮最终回复时，若这一轮确实在推进任务、清单里还有未完成项、而清单整轮没被动过，就在
 * 边界上把回复拦下来，回灌一条点名到项级的提醒，让它先把清单对齐再收尾。
 *
 * 代价与边界（刻意收窄）：
 * - 命中才多一次模型往返，不命中零成本；每轮最多拦一次（由调用方的状态位兜住）。
 * - 判据只认「可观察的事实」——本轮工具结果条数、清单快照、回复正文里有没有收尾表述，
 *   不猜意图。纯对话轮、清单全完成、本轮更新过清单、回复没在收尾，四条任一不成立即放行。
 * - 「更新过」认的是本轮真的改了清单，不是「调用过 todo」：只 todo(action="list") 看一眼不算。
 */
object TodoProgressGuard {

    /** `todo` 工具的注册名（协议字段，不随文案变）。 */
    const val TOOL_NAME = "todo"

    /** 工具结果传输文本里的失败标记（见 `ToolResult` 的类判别字段）。 */
    private const val STATUS_ERROR = "error"

    /** 本轮至少这么多次工具调用才算「在推进任务」：1 次的轮次多是看文件、查一下的问答。 */
    const val MIN_TURN_TOOL_CALLS = 2

    /**
     * 连续未更新的回合数到这么多，提醒从「先补上」升级为「本轮必须更新」。
     *
     * 取 2 而不是 3：第一次命中可能只是一次误判（回复恰好写得像收尾），第二次仍不更新就说明
     * 「先补上」这种语气确实被无视了，再多等一轮只是把同一次漏更新再演一遍；
     * 而升级本身不花额外往返，只是在提醒里多一句「连续第几轮」。
     */
    const val ESCALATE_AFTER_TURNS = 2

    /**
     * 往前回看的回合数上限。更早的历史可能已被压缩，数出来的「连续」也不可信；
     * 升级只用来说清严重程度，不需要精确到底忘了多少轮。
     */
    const val MAX_LOOKBACK_TURNS = 5

    /** 提醒里最多点名几项：清单很长时只给最该动的那些。 */
    const val MAX_ITEMS_IN_REMINDER = 8

    /** 超过这么久没动过的未完成项，在提醒里带上时长，便于模型判断「这项是不是早该收了」。 */
    const val TOUCHED_MINUTES = 20

    /** 收尾性表述：中文。与规则里的「回复里说已被完成 / 下一步」同源，这里认的是模型真写出来的话。 */
    private val CHINESE_WRAP_UP = Regex(
        "已完成|完成了|已做完|做完了|已搞定|搞定了|已改好|改好了|已修好|修好了|已实现|已加上|已对齐|" +
            "全部完成|都完成|下一步|接下来|后续|剩下的|还剩|剩余|完成项|待办项"
    )

    /** 收尾性表述：英文（用户用英文提问时模型也用英文收尾，同样要认出来）。 */
    private val ENGLISH_WRAP_UP: List<Regex> = listOf(
        Regex("""\b(done|completed|finished|all set)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnext steps?\b|\bremaining\b|\bfollow-?ups?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bI('ve| have)\s+(finished|completed|implemented|fixed|done)\b""", RegexOption.IGNORE_CASE)
    )

    private val guardJson = Json { ignoreUnknownKeys = true }

    /** 一轮（一条用户消息之后到下一轮之前的全部消息）的工具调用统计。 */
    data class TurnStats(val toolCalls: Int, val todoMutations: Int)

    /** 连续未更新的回合统计：missedTurns 轮里一共 toolCalls 次工具调用、0 次清单更新。 */
    data class MissedStreak(val missedTurns: Int, val toolCalls: Int)

    /** 这段正文是不是在收尾（汇报完成、交代下一步）。 */
    fun claimsWrapUp(text: String): Boolean {
        if (text.isBlank()) return false
        if (CHINESE_WRAP_UP.containsMatchIn(text)) return true
        return ENGLISH_WRAP_UP.any { it.containsMatchIn(text) }
    }

    /** 未完成项，进行中的排前面。 */
    fun unfinishedItems(items: List<TodoItem>): List<TodoItem> =
        items.filter { it.status != TodoStatus.COMPLETED }
            .sortedBy { if (it.status == TodoStatus.IN_PROGRESS) 0 else 1 }

    /** 本轮（最后一条用户消息之后）的工具统计。 */
    fun currentTurnStats(history: List<AgentMessage>): TurnStats = statsOf(currentTurnSegment(history))

    /**
     * 当前这次收尾之前，已经连续多少轮「干了活却没更新清单」（不含本轮），以及这些轮次一共调了多少次工具。
     * 从最近一回合往前数，遇到第一次正常更新的回合就停——提醒只需要说「连续第几轮」。
     */
    fun missedStreak(history: List<AgentMessage>): MissedStreak {
        val previous = turnSegments(history).dropLast(1)
        var missedTurns = 0
        var toolCalls = 0
        for (index in previous.indices.reversed()) {
            if (missedTurns >= MAX_LOOKBACK_TURNS) break
            val stats = statsOf(previous[index])
            if (stats.toolCalls < MIN_TURN_TOOL_CALLS || stats.todoMutations > 0) break
            missedTurns++
            toolCalls += stats.toolCalls
        }
        return MissedStreak(missedTurns, toolCalls)
    }

    /**
     * 要不要在收尾边界上拦下这次回复。[items] 为 null 表示清单快照还没读到——此时不猜、放行。
     *
     * 四条判据缺一不可，也正因为四条同时成立才谈得上「漏更新」：
     * 1. 清单里还有未完成项（否则没什么可更新）；
     * 2. 回复在收尾（否则后面还要继续干，现在更新也是白更新）；
     * 3. 本轮至少 [MIN_TURN_TOOL_CALLS] 次工具调用（否则是纯对话轮）；
     * 4. 本轮真的没改动过清单。
     */
    fun needsReminder(items: List<TodoItem>?, history: List<AgentMessage>, finalText: String): Boolean {
        if (items == null) return false
        if (unfinishedItems(items).isEmpty()) return false
        if (!claimsWrapUp(finalText)) return false
        val turn = currentTurnStats(history)
        if (turn.toolCalls < MIN_TURN_TOOL_CALLS) return false
        return turn.todoMutations == 0
    }

    /**
     * 收尾守卫的提醒正文：先把「本轮干了多少活、清单里还剩什么」摆出来，再给逐个动作。
     * 调用方必须先过 [needsReminder]（清单里没有未完成项时这段没有意义）。
     */
    fun buildReminder(
        items: List<TodoItem>,
        history: List<AgentMessage>,
        now: Long = System.currentTimeMillis()
    ): String {
        val unfinished = unfinishedItems(items)
        val turn = currentTurnStats(history)
        val streak = missedStreak(history).missedTurns + 1
        val listed = unfinished.take(MAX_ITEMS_IN_REMINDER).joinToString("\n") { item ->
            val minutes = ((now - item.updatedAt) / 60_000L).coerceAtLeast(0)
            val hint = if (minutes >= TOUCHED_MINUTES) "（约 $minutes 分钟没动）" else ""
            "${TodoListText.line(item)}$hint"
        }
        val hidden = unfinished.size - MAX_ITEMS_IN_REMINDER
        return buildString {
            append("[清单守卫] 你要给本轮最终回复了，但这一轮 ${turn.toolCalls} 次工具调用、清单更新 0 次")
            if (streak > 1) append("——这已经是连续第 $streak 轮这样了")
            append("。\n清单里还有 ${unfinished.size} 项没完成：\n")
            append(listed)
            if (hidden > 0) append("\n…（另有 $hidden 项未列出）")
            append("\n")
            if (streak >= ESCALATE_AFTER_TURNS) {
                append("本轮必须更新清单，这不是可选项：\n")
            } else {
                append("先把清单对齐，再给最终回复：\n")
            }
            append("① 这一轮已经做完的：todo(action=\"update\", subject=\"…\", status=\"completed\")；\n")
            append("② 正在做的置 in_progress；本轮放弃的置回 pending 或 todo(action=\"remove\")。\n")
            append("③ 清单确实不用动（例如刚才那些调用只是查看、没产生新进度）：不要编造状态，只回一句「清单已对齐」。\n")
            append("然后重新给出收尾回复：只写结论与下一步，不要重复上一段。")
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 按用户消息切回合：每条用户消息起一段，段内是这次请求到下次请求之间的助手消息与工具结果。
     * 守卫依赖的「本轮」就是这个意义上的最后一段。
     */
    private fun turnSegments(history: List<AgentMessage>): List<List<AgentMessage>> {
        val segments = mutableListOf<MutableList<AgentMessage>>()
        var current: MutableList<AgentMessage>? = null
        history.forEach { message ->
            if (message is AgentMessage.UserMessage) {
                val segment = mutableListOf<AgentMessage>()
                segments += segment
                current = segment
            } else {
                current?.add(message)
            }
        }
        return segments
    }

    private fun currentTurnSegment(history: List<AgentMessage>): List<AgentMessage> =
        turnSegments(history).lastOrNull().orEmpty()

    private fun statsOf(segment: List<AgentMessage>): TurnStats {
        val toolResults = segment.filterIsInstance<AgentMessage.ToolResultMessage>()
        return TurnStats(
            toolCalls = toolResults.size,
            todoMutations = toolResults.count { it.toolName == TOOL_NAME && mutatedList(it.result) }
        )
    }

    /**
     * 这次 todo 调用的结果算不算「真的改了清单」。
     * 只 list 看一眼不算（成功消息以清单本身开头），执行失败也没改动；结果读不懂时按「改过」处理——
     * 守卫宁可漏拦，也不要在模型其实更新了清单时把它拦下来。
     */
    private fun mutatedList(result: String): Boolean {
        val obj = runCatching { guardJson.parseToJsonElement(result) as? JsonObject }.getOrNull() ?: return true
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        if (status == STATUS_ERROR) return false
        val data = obj["data"] as? JsonObject ?: return true
        val message = (data["message"] as? JsonPrimitive)?.contentOrNull ?: return true
        return !message.startsWith(TodoListText.LIST_ACTION_MESSAGE)
    }
}
