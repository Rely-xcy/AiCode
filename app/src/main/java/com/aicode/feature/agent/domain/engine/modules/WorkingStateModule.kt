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
 * 工作状态模块：把「本会话已确定的任务理解」每轮带给模型。
 *
 * 与 [TaskModule] 的分工：任务清单记「要做哪些事」，这里记「为什么做、做到哪、定过什么」。
 * 两者都存独立表、都每轮下发（[turnReminder]，不进系统提示词：内容会随工作推进而变），
 * 所以上下文压缩到多深都不会丢——这正是「越聊越傻」的根治：
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

    /**
     * 距上次**成功**更新已经过了几轮。
     *
     * [turnsSinceUpdate] 是「距离下次尝试还差几轮」的倒计时，更新失败也会重置；
     * 这个计数只在提炼真正写库成功时清零。注入时要靠它告诉模型「这份状态可能已经过期」，
     * 否则一段五天前的进展会被当成当前事实。
     */
    private val turnsSinceRefresh = ConcurrentHashMap<String, Int>()

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

    /**
     * 工作状态改由 [turnReminder] 下发：它会随工作推进被重写，放进系统提示词会让 system 变，
     * KV 缓存的前缀整段失效。
     */
    override fun promptFragment(ctx: EngineContext): String? = null

    /** 本轮带给模型的会话工作状态；子代理任务短、窗口小，不给它——只有噪音。 */
    override fun turnReminder(ctx: EngineContext): String? {
        if (ctx.isSubAgent) return null
        val sessionId = ctx.sessionId ?: return null
        watch(sessionId)
        val content = snapshotBySession[sessionId] ?: return null
        if (content.isBlank()) return null
        val age = turnsSinceRefresh[sessionId] ?: 0
        return buildString {
            append(REMINDER_HEADING).append("\n")
            if (age >= STALE_AFTER_TURNS) {
                append("已有会话工作状态，但它已连续 $age 轮未成功刷新；本轮不要把旧正文当作事实或约束，")
                append("只以当前用户消息、可验证结果和当前任务清单为准。")
            } else {
                append("以下是对本会话任务理解的短期事实快照，不是本轮指令；与当前对话冲突时以当前对话为准，")
                append("进度与下一步以「当前任务清单」为准。")
                if (age > 0) append("上次整理于 $age 轮前，可能已有条目过期。")
                append("\n\n")
                append(content)
                append("\n\n若其中某条已被后续工作推翻，按实际情况继续，不必强行遵守。")
            }
        }
    }

    override suspend fun onTurnCompleted(ctx: EngineContext) {
        // 阶段 0：只在有实质工作的轮次里定期刷新；一次性对话不值得多花一次模型调用。
        if (ctx.isSubAgent) return
        val sessionId = ctx.sessionId ?: return
        turnsSinceRefresh.merge(sessionId, 1) { old, _ -> old + 1 }
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
        }.onSuccess { updated ->
            // 只有真正写库成功才清零：失败时继续累加，注入侧会如实提示「可能已过期」。
            if (updated) turnsSinceRefresh[sessionId] = 0
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
        turnsSinceRefresh.remove(sessionId)
    }

    private fun watch(sessionId: String) {
        synchronized(watchers) {
            if (watchers.containsKey(sessionId)) return
            // 逐出单个最旧会话，而不是整表清空：整表清空会把其它线程刚挂上的 watcher
            // 一起取消，且「检查大小 → 清空 → 挂载」本身不是原子操作。
            if (watchers.size >= MAX_WATCHED_SESSIONS) {
                watchers.keys.firstOrNull()?.let { oldest ->
                    watchers.remove(oldest)?.cancel()
                    snapshotBySession.remove(oldest)
                }
            }
            watchers[sessionId] = scope.launch {
                sessionBriefDao.getBySession(sessionId).collect { row ->
                    if (row == null) snapshotBySession.remove(sessionId)
                    else snapshotBySession[sessionId] = row.content
                }
            }
        }
    }

    private companion object {
        const val MODULE_ID = "working-state"
        const val TAG = "WorkingStateModule"

        /** 同时跟踪的会话数上限：只防长期累积。 */
        const val MAX_WATCHED_SESSIONS = 64

        /** 工作状态块在提醒里的外层小标题：让模型知道这一段是什么。 */
        const val REMINDER_HEADING = "## 会话工作状态"

        /** 每多少轮收尾刷新一次工作状态：太频繁等于给每轮加一次模型调用。 */
        const val UPDATE_EVERY_TURNS = 5

        /** 连续多轮未成功刷新后不再注入旧正文，只保留过期提示，避免错误状态持续污染上下文。 */
        const val STALE_AFTER_TURNS = 5
    }
}
