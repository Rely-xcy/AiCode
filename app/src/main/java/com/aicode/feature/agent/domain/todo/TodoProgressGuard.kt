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
 * - 收尾表述只认「把活干到什么程度」的声明，不认只描述时间顺序或剩余量的词（见 [CHINESE_WRAP_UP]）。
 *   代价是只交代「下一步做什么」而不声称任何完成度的收尾不再触发本守卫（实测约占干活回合的 1/6）。
 *   这不是盲区：跨轮的慢性落后由 `TaskModule.freshness` 兜着，它只看「连续几轮干活没更新清单」，
 *   与本词表无关；本守卫负责的是当轮立刻纠正，不是唯一的兜底。
 *
 * 第二条判据（用户新口径）：规矩不是「完成一项就清」，而是**整张清单全部完成后一次性清空**
 * （完成项先留在清单里，以便看整体进度）。所以除了上面「还有未完成项、清单却没更新」，还要补一支
 * 「清单已全部完成、收尾时却没收尾清空」——见 [needsAllDoneCleanup]。两支互斥：一支要求有未完成项，
 * 一支要求一项不剩。
 */
object TodoProgressGuard {

    /** `todo` 工具的注册名（协议字段，不随文案变）。 */
    const val TOOL_NAME = "todo"

    /** 工具结果传输文本里的失败标记（见 `ToolResult` 的类判别字段）。 */
    private const val STATUS_ERROR = "error"

    /**
     * `clear` 动作成功文案的开头（见 `TodoTool.clearAll` 的 `buildSuccess("已清空清单", …)`，
     * 后面还拼「；清单现在是空的」）。TodoTool 不归本守卫维护，故这里独立钉一份前缀；
     * 改 TodoTool 的这条文案要同步过来。
     */
    private const val CLEAR_ACTION_MESSAGE = "已清空清单"

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

    /**
     * 收尾性表述：中文。只认「这一轮把活干到什么程度」的声明（已完成 / 改好了 / 做完了…），
     * 不认只描述时间顺序（下一步 / 接下来 / 后续）或剩余量（剩下的 / 还剩 / 剩余）的词——
     * 那几个词在正常叙述里到处都是，认下来等于每个干过活的回合都白拦一次。
     *
     * 口径取自本机 17 份历史会话转录里「本轮 ≥2 次工具调用」的 721 个回合：原词表命中 37.4%，
     * 删掉那六个词后 19.6%；「下一步」一个词独占 14.6 个百分点，而命中它的回合里只有 26.7%
     * 另有完成声明——留下的正是「说了干完了、清单却可能没动」这一类。
     * 完成项 / 待办项 留着：实测命中率 0.4% / 0.0%，误报成本可忽略，而它们指向的就是清单本身。
     */
    private val CHINESE_WRAP_UP = Regex(
        "已完成|完成了|已做完|做完了|已搞定|搞定了|已改好|改好了|已修好|修好了|已实现|已加上|已对齐|" +
            "全部完成|都完成|完成项|待办项"
    )

    /**
     * 收尾性表述：英文（用户用英文提问时模型也用英文收尾，同样要认出来）。
     * 与中文同一口径：只认「做完了」的声明，`next steps` / `remaining` / `follow-ups`
     * 这类也只描述时间顺序或剩余量，同样删掉。本机语料全是中文，这一条是按中文口径推的，没有实测。
     */
    private val ENGLISH_WRAP_UP: List<Regex> = listOf(
        Regex("""\b(done|completed|finished|all set)\b""", RegexOption.IGNORE_CASE),
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
     * 第二条判据：清单里的任务已经全部完成、模型又在收尾，但还没把清单清空。
     *
     * 与 [needsReminder] 互补且互斥——那一支要求清单里还有未完成项，这一支要求一项不剩；
     * 两支合起来覆盖「收尾时清单该动却没动」的两种情形。判据：
     * 1. 清单存在且非空（空清单或快照未就绪都没什么可清的）；
     * 2. 清单里每一项都是 COMPLETED；
     * 3. 回复在收尾（没在收尾就还会继续干，清早了下一件事又得重建清单）；
     * 4. **本轮真的更新过清单**（`todoMutations > 0`）——要拦的是「刚把最后几项打完勾、
     *    扭头就要收尾、却忘了清空」的那个回合。刻意**不**做成「本轮没动过清单」：那样会把
     *    上一轮就完成、这一轮只是顺带收尾的陈旧清单也反复拦（该情形归 TaskModule 的 freshness
     *    静默期管，30 分钟后提示「该清空或建新清单」），而在模型刚打勾的回合反而不命中——
     *    而那正是要治的场景。代价：纯对话轮（本轮没碰清单）里一张全完成的旧清单不会被这里拦下，
     *    这是有意的收窄。
     * 5. 本轮没有已经 `clear` 过。快照是异步刷新的，本轮刚清空时快照可能还停在「全完成」，
     *    不排掉这一条会把「刚清空」误判成「全完成却没清」而白拦一次。
     */
    fun needsAllDoneCleanup(
        items: List<TodoItem>?,
        history: List<AgentMessage>,
        finalText: String
    ): Boolean {
        if (items == null || items.isEmpty()) return false
        if (items.any { it.status != TodoStatus.COMPLETED }) return false
        if (!claimsWrapUp(finalText)) return false
        if (currentTurnStats(history).todoMutations == 0) return false
        return !clearedListThisTurn(history)
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

    /**
     * 「清单全完成却没清空」那一支的提醒正文。调用方必须先过 [needsAllDoneCleanup]。
     * 与 [buildReminder] 分开写：那一支讲「还有几项没完成」，这一支只讲「全完成、收尾前清空」。
     */
    fun buildAllDoneReminder(items: List<TodoItem>): String {
        val listed = items.take(MAX_ITEMS_IN_REMINDER).joinToString("\n") { item -> TodoListText.line(item) }
        val hidden = items.size - MAX_ITEMS_IN_REMINDER
        return buildString {
            append("[清单守卫] 清单里 ${items.size} 项都完成了，收尾前用 todo(action=\"clear\") 清空整张清单、不留残余条目：\n")
            append(listed)
            if (hidden > 0) append("\n…（另有 $hidden 项未列出）")
            append("\n完成项留在清单里是为了看整体进度，全部完成后就该整张清掉；清空后重新给出收尾回复。")
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

    /** 本轮是否已经执行过 `clear` 把清单清空（快照异步刷新，刚清空的回合要能识别出来）。 */
    private fun clearedListThisTurn(history: List<AgentMessage>): Boolean =
        currentTurnSegment(history).any {
            it is AgentMessage.ToolResultMessage && it.toolName == TOOL_NAME && clearedList(it.result)
        }

    /**
     * 这次 todo 调用的结果是不是 `clear` 成功。认的是成功文案前缀（[CLEAR_ACTION_MESSAGE]）；
     * 结果读不懂时按「没清空」处理——与 [mutatedList] 方向相反：这里宁可多拦一次（提醒里已点名 clear），
     * 也不要把真正全完成却没清的回合放过去。
     */
    private fun clearedList(result: String): Boolean {
        val obj = runCatching { guardJson.parseToJsonElement(result) as? JsonObject }.getOrNull() ?: return false
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        if (status == STATUS_ERROR) return false
        val data = obj["data"] as? JsonObject ?: return false
        val message = (data["message"] as? JsonPrimitive)?.contentOrNull ?: return false
        return message.startsWith(CLEAR_ACTION_MESSAGE)
    }
}
