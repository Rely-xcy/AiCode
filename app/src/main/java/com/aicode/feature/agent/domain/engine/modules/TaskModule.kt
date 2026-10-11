package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.agent.domain.todo.TodoListText
import com.aicode.feature.agent.domain.todo.TodoProgressGuard
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
 * 两个口子，一前一后：
 * 1. **注入**——每轮把清单连同新鲜度放进系统提示词，让进度在上下文压到多深时都看得见
 *    （清单存在独立表里而不是消息历史里，就是为了这个）；
 * 2. **收尾守卫**——模型要给出本轮最终回复时在边界上拦一次：这一轮在推进任务、清单里还有
 *    未完成项、清单却整轮没被动过，或清单已全部完成、收尾时却没清空，就把「清单该动却没动」
 *    这件事当场告诉它（判据见 [TodoProgressGuard]）。
 *    以前只有第一件事，靠提示词里的「提醒 + 统计」推动更新，实测治不住——提醒是描述，边界是动作。
 *
 * 为什么需要同步快照：系统提示词是同步拼接的（[EngineModule.promptFragment] 不是 suspend），
 * 读不了 Room 的 suspend 查询。所以这里沿用记忆开关（`MemorySettingsRepository`）的做法——
 * 进程启动时先把各会话的清单铺进内存，之后每个会话挂一个 Flow 收集器持续刷新。
 * 快照还没就绪时**什么都不说**（不谎报「没有清单」），只注入纪律规则；收尾守卫是 suspend 的，
 * 快照没就绪就补读一次库——提示词可以等下一轮，收尾拦截错过就没了。
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

    /**
     * 收尾守卫：模型要给出本轮最终回复时，拦一次「清单该动却没动」——两支判据（
     * 还有未完成项却没更新、或已全部完成却没清空，见 [TodoProgressGuard]），命中就给对应的点名提醒。
     *
     * 「一轮最多拦一次」由调用方 `StatefulAgentWorkflow` 的状态位 `todoReminderSent` 保证：它只在
     * 「本轮最终回复、且本轮还没拦过」时调用本方法（见 shouldAskTodoGuard），命中后置位、本轮不再问。
     * 两支共用这同一个状态位——本方法每次调用最多返回一条提醒，不存在同轮两拦。
     *
     * 快照没就绪就补读一次库；读失败直接抛给 [AgentEngine] 兜（记日志、本轮放行）——守卫不能把对话弄挂。
     */
    override suspend fun finalResponseGuard(ctx: EngineContext, finalText: String): String? {
        val sessionId = ctx.sessionId ?: return null
        val items = snapshotOf(sessionId) ?: todoItemDao.getBySessionOnce(sessionId).map { it.toDomain() }
        if (TodoProgressGuard.needsReminder(items, ctx.history, finalText)) {
            val streak = TodoProgressGuard.missedStreak(ctx.history).missedTurns + 1
            FileLogger.w(TAG, "收尾守卫拦下本轮回复：清单未随本轮工作更新（连续第 $streak 轮）")
            return TodoProgressGuard.buildReminder(items, ctx.history)
        }
        if (TodoProgressGuard.needsAllDoneCleanup(items, ctx.history, finalText)) {
            FileLogger.w(TAG, "收尾守卫拦下本轮回复：清单已全部完成但未清空")
            return TodoProgressGuard.buildAllDoneReminder(items)
        }
        return null
    }

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
     * 新鲜度：只说「要你动手」的那种，其余一律不开口。
     *
     * 上一版每轮都念「清单最后变动：约 N 分钟前；上一回合调用工具 N 次，其中更新清单 0 次」——
     * 正常也念、落后也念，很快就变成背景音被整段无视。现在只在两种情况注入：
     * 清单已连续若干轮没跟着工作更新（跨轮升级；收尾守卫没兜住时才会走到这里），
     * 或清单全部完成且久未变动（该清空、该建新清单）。
     */
    private fun freshness(items: List<TodoItem>, ctx: EngineContext): String? {
        val minutes = ((System.currentTimeMillis() - items.maxOf { it.updatedAt }) / 60_000L).coerceAtLeast(0)
        if (items.none { it.status != TodoStatus.COMPLETED }) {
            if (minutes < ALL_DONE_QUIET_MINUTES) return null
            return "清单已全部完成、约 $minutes 分钟没变动：没有待办时 todo(action=\"clear\") 清空，" +
                "或把下一件事建成新清单。"
        }
        val missed = TodoProgressGuard.missedStreak(ctx.history)
        if (missed.missedTurns == 0) return null
        return "清单已连续 ${missed.missedTurns} 轮没随工作更新（这 ${missed.missedTurns} 轮共 ${missed.toolCalls} 次工具调用、" +
            "约 $minutes 分钟前最后一次变动）——上面未完成的项若已做完或正在做，先 todo(action=\"update\") 对齐，再开始新工作。"
    }

    private companion object {
        const val MODULE_ID = "task"
        const val TAG = "TaskModule"

        /** 同时跟踪的会话数上限：只防长期累积。 */
        const val MAX_WATCHED_SESSIONS = 64

        /** 清单全部完成、且这么久没再动过，才提一句「该清空或建新清单」：刚做完那一轮不念。 */
        const val ALL_DONE_QUIET_MINUTES = 30L

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
            ⑥ 清单里的任务全部完成后，用 todo(action="clear") 把整张清单一次清空、不留残余条目（完成项先留着，看得出整体进度）。
            只改一项就只发那一项，不要重发整张清单；先调用工具、再写回复。
        """.trimIndent()
    }
}
