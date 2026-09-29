package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.todo.TodoListText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务模块：把「当前会话的任务清单」接进引擎。
 *
 * 只做一件事——每轮把清单连同**新鲜度**注入系统提示词，让「该更新了」变成看得见的事实：
 * 清单存在独立表里而不是消息历史里，所以上下文压到多深都能看到进度；而「上一回合调了多少次工具、
 * 有没有更新过清单」这类信号，是模型判断自己是否落后的唯一依据（以前它连清单都看不到）。
 *
 * 为什么需要同步快照：系统提示词是同步拼接的（[EngineModule.promptFragment] 不是 suspend），
 * 读不了 Room 的 suspend 查询。所以这里沿用记忆开关（`MemorySettingsRepository`）的做法——
 * 进程启动时先把各会话的清单铺进内存，之后每个会话挂一个 Flow 收集器持续刷新。
 * 快照还没就绪时**什么都不说**（不谎报「没有清单」），只注入纪律规则。
 *
 * 这里只读不写：清单的增删改都归 `todo` 工具，语义上「目标（goal）」是另一层，不混进来。
 */
@Singleton
class TaskModule @Inject constructor(
    private val todoItemDao: TodoItemDao
) : EngineModule {

    override val id = MODULE_ID

    // 排在压缩（10）之后、记忆（50）之前：先让上下文定型，再注入任务状态。
    override val order = 20

    /** 会话 → 清单快照；null 与空列表语义不同：没有条目表示「还没读到」，空列表表示「确实没有清单」。 */
    private val snapshotBySession = ConcurrentHashMap<String, List<TodoItem>>()

    /** 会话 → 清单变更监听；Room 的 Flow 在同一会话内一直活着，直到会话被删或超出跟踪上限。 */
    private val watchers = ConcurrentHashMap<String, Job>()

    // 声明在 init 之前：Kotlin 按声明顺序初始化
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 冷启动先铺一遍所有会话：恢复旧会话时第一轮就要能看到清单，不能等收集器首帧。
        scope.launch {
            runCatching { todoItemDao.getAllOnce() }
                .onSuccess { rows ->
                    rows.groupBy { it.sessionId }.forEach { (sessionId, items) ->
                        snapshotBySession.putIfAbsent(sessionId, items.map { it.toDomain() })
                    }
                }
                .onFailure { FileLogger.w(TAG, "预热任务清单快照失败: ${it.message}", it) }
        }
    }

    override fun promptFragment(ctx: EngineContext): String? {
        val sessionId = ctx.sessionId
        val items = sessionId?.let { snapshotOf(it) }
        // 子代理的纪律段走 [subAgentRules]（不受 inject 门禁），这里不再重复一遍
        return renderBlock(items, ctx, withRules = !ctx.isSubAgent)
    }

    /**
     * 子代理会话的固定纪律段：与 [SubAgentModule] 同理——清单纪律是「能安全干活」的一部分，
     * 不能因为定义作者没勾 MEMORY 片段就消失。
     */
    override fun subAgentRules(ctx: EngineContext): String? =
        DISCIPLINE_RULE.takeIf { ctx.isSubAgent }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        val sessionId = ctx.sessionId ?: return
        watchers.remove(sessionId)?.cancel()
        snapshotBySession.remove(sessionId)
    }

    // ---------------------------------------------------------------- 快照

    /** 取会话的清单快照；顺带挂上变更监听（同一会话只挂一次）。 */
    private fun snapshotOf(sessionId: String): List<TodoItem>? {
        watch(sessionId)
        return snapshotBySession[sessionId]
    }

    private fun watch(sessionId: String) {
        if (watchers.size >= MAX_WATCHED_SESSIONS) {
            // 只防长期累积；被清掉的会话下一轮 promptFragment 会重新挂上
            watchers.values.forEach { it.cancel() }
            watchers.clear()
        }
        watchers.computeIfAbsent(sessionId) { id ->
            scope.launch {
                todoItemDao.getBySession(id).collect { rows ->
                    snapshotBySession[id] = rows.map { it.toDomain() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 渲染注入块。[items] 为 null 表示快照尚未就绪——此时只给规则，不给任何关于清单的断言。
     */
    internal fun renderBlock(
        items: List<TodoItem>?,
        ctx: EngineContext,
        withRules: Boolean = true
    ): String = buildString {
        if (withRules) append(DISCIPLINE_RULE)
        if (items == null) return@buildString
        if (items.isEmpty()) {
            append("\n\n本会话还没有任务清单。")
            return@buildString
        }

        append("\n\n任务清单（每轮重新注入，压缩上下文不会丢）：\n")
        append(TodoListText.render(items, unfinishedFirst = true))
        append("\n进度：").append(TodoListText.summary(items))
        freshness(items, ctx)?.let { append("\n").append(it) }
    }

    /**
     * 新鲜度：清单多久没动过 + 上一回合干了多少活。两行都只陈述事实，不做猜测——
     * 模型要的是「我是不是落后了」，而不是被念一遍。
     */
    private fun freshness(items: List<TodoItem>, ctx: EngineContext): String? {
        val parts = mutableListOf<String>()
        val lastTouchedAt = items.maxOf { it.updatedAt }
        val minutes = ((System.currentTimeMillis() - lastTouchedAt) / 60_000L).coerceAtLeast(0)
        parts += if (minutes < 1) "清单刚刚更新过" else "清单最后变动：约 $minutes 分钟前"

        val stats = previousTurnStats(ctx.history)
        if (stats != null && stats.toolCalls > 0) {
            parts += "上一回合调用工具 ${stats.toolCalls} 次，其中更新清单 ${stats.todoCalls} 次"
        }

        val stale = stats != null && stats.toolCalls >= STALE_TOOL_CALLS && stats.todoCalls == 0
        val staleCalls = if (stale) stats?.toolCalls ?: 0 else 0
        return buildString {
            append(parts.joinToString("；"))
            if (stale) {
                append("\n上一回合做了 $staleCalls 次工具调用却没更新过清单——")
                append("如果那里面有已经完成的步骤，先 todo(action=\"update\") 把状态补上，再开始新工作。")
            }
        }
    }

    /** 上一回合（最后一条用户消息之前的那一段）的工具调用统计。看不到就返回 null。 */
    private fun previousTurnStats(history: List<AgentMessage>): TurnStats? {
        val lastUser = history.indexOfLast { it is AgentMessage.UserMessage }
        if (lastUser <= 0) return null
        val previousUser = history.subList(0, lastUser).indexOfLast { it is AgentMessage.UserMessage }
        val segment = history.subList(if (previousUser < 0) 0 else previousUser + 1, lastUser)
        val toolResults = segment.filterIsInstance<AgentMessage.ToolResultMessage>()
        if (toolResults.isEmpty()) return null
        return TurnStats(
            toolCalls = toolResults.size,
            todoCalls = toolResults.count { it.toolName == TOOL_NAME }
        )
    }

    private data class TurnStats(val toolCalls: Int, val todoCalls: Int)

    private companion object {
        const val MODULE_ID = "task"
        const val TAG = "TaskModule"

        /** `todo` 工具的注册名（协议字段，不随文案变）。 */
        const val TOOL_NAME = "todo"

        /** 同时跟踪的会话数上限：只防长期累积。 */
        const val MAX_WATCHED_SESSIONS = 64

        /** 上一回合达到这么多次工具调用、却一次都没更新清单，就在注入里点出来。 */
        const val STALE_TOOL_CALLS = 6

        /**
         * 清单纪律：钉死「哪些时刻必须先调用工具」。规则写成动作，不写成态度——
         * 「记得维护清单」这种话模型会当成描述，写进回复里就算交差（记忆引擎上已经踩过一次）。
         */
        val DISCIPLINE_RULE = """
            任务清单（`todo` 工具，每轮随提示词注入，压缩上下文不会丢）：
            ① 开始多步任务（3 步以上或要多次工具调用）时先 todo(action="add") 建清单，再动手；一次性小任务不要建。
            ② 每完成一项立刻 todo(action="update", subject="…", status="completed")，不要攒到最后一次性补。
            ③ 开始下一项时把它置为 in_progress。
            ④ 计划或需求变了（用户改主意、发现新问题、放弃某项）当场同步清单。
            ⑤ 一轮收尾前对照清单：回复里说「已完成 / 下一步」的每一项，清单里必须已经对上。
            只改一项就只发那一项，不要重发整张清单；先调用工具、再写回复。
        """.trimIndent()
    }
}
