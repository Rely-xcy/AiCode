package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.SessionBriefDao
import com.aicode.feature.agent.domain.brief.SessionBriefExtractor
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作状态模块：把「本会话已确定的任务理解」每轮注入系统提示词。
 *
 * 与 [TaskModule] 的分工：任务清单记「要做哪些事」，这里记「为什么做、做到哪、定过什么」。
 * 两者都存独立表、都每轮注入，所以上下文压缩到多深都不会丢——这正是「越聊越傻」的根治：
 * 模型缺的从来不是逐字历史，而是「当前任务是什么」的稳定理解。
 *
 * 注入是同步的（[EngineModule.promptFragment] 不是 suspend），沿用 [TaskModule]/[MemoryModule]
 * 的做法：进程启动预热全部会话，之后每个会话挂一个 Flow 收集器持续刷新内存快照。
 */
@Singleton
class WorkingStateModule @Inject constructor(
    private val sessionBriefDao: SessionBriefDao,
    private val sessionBriefExtractor: SessionBriefExtractor
) : EngineModule {

    override val id = MODULE_ID

    // 排在压缩（10）之后：先让上下文定型，再补上不随压缩丢失的任务理解。
    override val order = 15

    /** 会话 → 工作状态正文；键不存在表示还没读到或确实没有。 */
    private val snapshotBySession = ConcurrentHashMap<String, String>()

    /** 会话 → 状态变更监听；同一会话只挂一次。 */
    private val watchers = ConcurrentHashMap<String, Job>()

    /** 距上次收尾更新的轮数（只在达到阈值时刷新一次，避免每轮多一次模型调用）。 */
    private val turnsSinceUpdate = ConcurrentHashMap<String, Int>()

    // 声明在 init 之前：Kotlin 按声明顺序初始化
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            runCatching { sessionBriefDao.getAllOnce() }
                .onSuccess { rows ->
                    rows.forEach { snapshotBySession.putIfAbsent(it.sessionId, it.content) }
                }
                .onFailure { FileLogger.w(TAG, "预热会话工作状态失败: ${it.message}", it) }
        }
    }

    override fun promptFragment(ctx: EngineContext): String? {
        // 子代理任务短、窗口小，注入工作状态只会增加噪音。
        if (ctx.isSubAgent) return null
        val sessionId = ctx.sessionId ?: return null
        watch(sessionId)
        val content = snapshotBySession[sessionId] ?: return null
        if (content.isBlank()) return null
        return buildString {
            append("会话工作状态（每轮重新注入，压缩上下文不会丢；这是本会话已确定的任务理解）：\n")
            append("硬约束优先于本会话其它一切安排，任何一条都不得违反。\n\n")
            append(content)
            append("\n\n若其中某条已被后续工作推翻，按实际情况继续，不必强行遵守过时结论。")
        }
    }

    override suspend fun onTurnCompleted(ctx: EngineContext) {
        // 阶段 0：只在有实质工作的轮次里定期刷新；一次性对话不值得多花一次模型调用。
        if (ctx.isSubAgent) return
        val sessionId = ctx.sessionId ?: return
        val oneShot = ctx.oneShot ?: return
        val count = (turnsSinceUpdate[sessionId] ?: 0) + 1
        if (count < UPDATE_EVERY_TURNS) {
            turnsSinceUpdate[sessionId] = count
            return
        }
        turnsSinceUpdate[sessionId] = 0
        runCatching {
            sessionBriefExtractor.extract(
                sessionId = sessionId,
                history = ctx.history,
                complete = { prompt -> oneShot(SessionBriefExtractor.PROMPT_FILE, prompt) }
            )
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "收尾更新会话工作状态失败，不影响主流程", e)
        }
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        val sessionId = ctx.sessionId ?: return
        watchers.remove(sessionId)?.cancel()
        snapshotBySession.remove(sessionId)
        turnsSinceUpdate.remove(sessionId)
    }

    private fun watch(sessionId: String) {
        if (watchers.size >= MAX_WATCHED_SESSIONS) {
            // 只防长期累积；被清掉的会话下一轮 promptFragment 会重新挂上。
            watchers.values.forEach { it.cancel() }
            watchers.clear()
        }
        watchers.computeIfAbsent(sessionId) { id ->
            scope.launch {
                sessionBriefDao.getBySession(id).collect { row ->
                    if (row == null) snapshotBySession.remove(id) else snapshotBySession[id] = row.content
                }
            }
        }
    }

    private companion object {
        const val MODULE_ID = "working-state"
        const val TAG = "WorkingStateModule"

        /** 同时跟踪的会话数上限：只防长期累积。 */
        const val MAX_WATCHED_SESSIONS = 64

        /** 每多少轮收尾刷新一次工作状态：太频繁等于给每轮加一次模型调用。 */
        const val UPDATE_EVERY_TURNS = 5
    }
}
