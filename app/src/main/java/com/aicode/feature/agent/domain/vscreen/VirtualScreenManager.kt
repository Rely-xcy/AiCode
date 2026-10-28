package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import com.aicode.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏生命周期：创建 / 查询 / 销毁，并持有「当前屏」这一份全局状态。
 *
 * 同一时刻只允许一块屏（设计 §4.3）：单屏足够，多屏只会让焦点与资源管理复杂化。
 * 因此建屏走 [lock] 串行：并发进来两次只会有一块屏被真正建出来，后来者如实被告知「已有屏」。
 *
 * 存活上限由**定时器主动收尾**，不靠下一次工具调用时的惰性检查：AI 不再调用工具时，
 * 惰性检查永远不会发生，那块屏会一直合成、目标 app 一直跑，用户看不见也在耗电。
 *
 * 设备侧动作（建屏、取帧、释放）在 [VirtualScreenBackend] 里，本类只管状态与时限。
 */
@Singleton
class VirtualScreenManager @Inject constructor(
    private val backend: VirtualScreenBackend,
    private val guard: VirtualScreenGuard,
    private val clock: VscreenClock,
    @param:ApplicationScope private val scope: CoroutineScope
) {

    /** 一块存活的虚拟屏。 */
    data class Screen(
        val displayId: Int,
        val sessionId: String,
        val packageName: String,
        /** 建屏时刻（来自 [VscreenClock] 的单调时钟），只用于日志与寿命换算，不是墙钟日期。 */
        val createdAtMs: Long,
        /** 存活上限到期时间戳（含）：到点由定时器自动销毁。 */
        val expiresAtMs: Long
    )

    /**
     * 建屏 / 销毁的互斥闸。
     *
     * 「有没有屏」的判断与「把屏记下来」必须落在同一把锁里，否则并发两次 start 会各建一块、
     * 后写者覆盖前者，被覆盖的那块屏从此没人能释放（持续合成直到进程退出）。
     */
    private val lock = Mutex()

    @Volatile
    var current: Screen? = null
        private set

    @Volatile
    private var surface: VirtualScreenSurface? = null

    /** 到点自动销毁的定时任务；销毁或换屏时必须取消，否则旧计时器会把新屏收掉。 */
    @Volatile
    private var expiryJob: Job? = null

    /** 一条控件树快照记录：屏对象是身份凭据，换了屏就不认。 */
    private class TreeSnapshotEntry(val screen: Screen, val snapshot: UiTreeSnapshot)

    /**
     * 最近一次读屏留下的指纹，只存一份、跟着当前这块屏。
     *
     * 有界：新的一份直接覆盖旧的，不做历史；换屏或被注入改动后由 [forgetTreeSnapshot] 清空。
     */
    @Volatile
    private var treeSnapshot: TreeSnapshotEntry? = null

    /** 是否存在存活中的屏（工具侧做会话授权与并发判定用）。 */
    val hasScreen: Boolean get() = current != null

    /**
     * 创建虚拟屏并在其上启动目标 app。
     *
     * 参数全部显式传入（阶段 0 demo 也走同一条路径，不硬编码包名/尺寸）。
     * 已有屏但已过期或被系统回收时不报「已有屏」，而是先把它收掉再建新的——那种屏留着没有意义。
     */
    suspend fun start(
        sessionId: String,
        packageName: String,
        activity: String?,
        width: Int,
        height: Int,
        density: Int,
        lifetimeMs: Long = DEFAULT_LIFETIME_MS
    ): Result<Screen> {
        VscreenScreenSpec.violation(width, height, density)?.let { reason ->
            return Result.failure(IllegalArgumentException(reason))
        }

        // 锁要跨挂起点持有（建屏过程里有 root 启动命令）：只有整段串行，stop 才一定看到真实状态，
        // 建屏窗口内的 stop 才不会被静默忽略。
        lock.lock()
        try {
            val existing = current
            if (existing != null) {
                if (!isStale(existing)) {
                    return Result.failure(
                        IllegalStateException("已有虚拟屏在运行（displayId=${existing.displayId}），先 vscreen_stop 再开新的。")
                    )
                }
                destroyLocked("建屏前发现已过期或已被系统回收", revokeSession = true)
            }

            val opened = backend.open(width, height, density, packageName, activity).getOrElse { return Result.failure(it) }
            val now = clock.nowMs()
            val screen = Screen(
                displayId = opened.displayId,
                sessionId = sessionId,
                packageName = packageName,
                createdAtMs = now,
                expiresAtMs = now + lifetimeMs.coerceAtLeast(MIN_LIFETIME_MS)
            )
            surface = opened
            current = screen
            armExpiry(screen)
            FileLogger.i(
                TAG,
                "虚拟屏已就绪：displayId=${opened.displayId}，目标=$packageName，${width}x$height @${density}dpi，" +
                    "存活上限 ${lifetimeMinutes(screen)} 分钟"
            )
            return Result.success(screen)
        } finally {
            lock.unlock()
        }
    }

    /** 销毁当前屏。没有屏时返回失败（调用方据此提示「本来就没开」）。 */
    suspend fun stop(): Result<Unit> {
        lock.lock()
        try {
            if (current == null) return Result.failure(IllegalStateException("当前没有存活的虚拟屏。"))
            destroyLocked("调用方请求销毁", revokeSession = true)
            return Result.success(Unit)
        } finally {
            lock.unlock()
        }
    }

    /** 从屏的 Surface 取一帧，编码成 PNG 落到 [target]。 */
    fun captureFrame(target: File, maxWaitMs: Long = CAPTURE_WAIT_MS): Result<Unit> {
        val active = surface
            ?: return Result.failure(IllegalStateException("当前没有存活的虚拟屏，先调用 vscreen_start 创建。"))
        return active.captureFrame(target, maxWaitMs)
    }

    /**
     * 取当前屏并做存活检查：到点或已被系统回收就地销毁。
     *
     * 定时器已经负责到点销毁，这里是第二道闸：进程被冻结时 delay 不推进、屏被系统回收时
     * 定时器也不会知道，真正要用屏（注入、取帧、读控件树）前仍要确认一次。
     */
    suspend fun ensureAlive(): Result<Screen> {
        lock.lock()
        try {
            val screen = current ?: return Result.failure(IllegalStateException("当前没有存活的虚拟屏。"))
            if (clock.nowMs() >= screen.expiresAtMs) {
                val minutes = lifetimeMinutes(screen)
                destroyLocked("已超过存活上限", revokeSession = true)
                return Result.failure(
                    IllegalStateException("虚拟屏已超过存活上限（${minutes}分钟）被自动销毁，需要时重新 vscreen_start。")
                )
            }
            // 屏可能被系统回收（内存紧张）：display 对象还活着但 displayId 已无效时，视为不存在。
            if (surface?.isValid != true) {
                destroyLocked("display 已被系统回收", revokeSession = true)
                return Result.failure(IllegalStateException("虚拟屏已被系统回收（display 不再有效），需要时重新 vscreen_start。"))
            }
            return Result.success(screen)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 记下这次读到的界面指纹，供下一次注入当对照。
     *
     * 只有「读的就是当前这块屏」时才记账：并发里可能有旧屏的读树结果晚到，
     * 记下来会让下一次注入拿上一块屏的界面当对照。
     */
    fun rememberTreeSnapshot(screen: Screen, snapshot: UiTreeSnapshot) {
        if (current !== screen) return
        treeSnapshot = TreeSnapshotEntry(screen, snapshot)
    }

    /**
     * 取最近一次读屏的指纹；屏已经换了、或覆盖深度不够的都不算数。
     *
     * 比的是屏对象身份而不是 displayId：displayId 在旧屏释放后可能被系统重新分配，
     * 用编号比会把旧屏的树当成新屏的对照。
     */
    fun recentTreeSnapshot(screen: Screen): UiTreeSnapshot? =
        treeSnapshot
            ?.takeIf { it.screen === screen }
            ?.snapshot
            ?.takeIf { it.coversComparePrefix }

    /** 界面已被改动（注入成功）：缓存的指纹不再描述当前界面，丢弃。 */
    fun forgetTreeSnapshot(screen: Screen) {
        if (treeSnapshot?.screen === screen) treeSnapshot = null
    }

    /** 取当前屏，没有时返回失败并给出可照做的提示。 */
    fun requireScreen(): Result<Screen> =
        current?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("当前没有存活的虚拟屏，先调用 vscreen_start 创建。"))

    /** 到点自动销毁：屏的生命周期必须自己收尾，不能指望下一次工具调用替它检查。 */
    private fun armExpiry(screen: Screen) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            // 用时钟算剩余时间再 delay：进程被冻结时 delay 不推进（没人跑协程），醒来后循环会把
            // 剩余时间重算一遍，而不是按最初那个时长直接销毁。
            while (true) {
                val remaining = screen.expiresAtMs - clock.nowMs()
                if (remaining <= 0) break
                delay(remaining)
            }
            expire(screen)
        }
    }

    private suspend fun expire(screen: Screen) {
        lock.lock()
        try {
            // 期间可能已被 stop、或已被换成了另一块屏：只收拾当时记下来的那一块。
            if (current !== screen) return
            destroyLocked("已到存活上限", revokeSession = true)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 释放屏并把状态清空。调用方必须已持有 [lock]；释放是同步操作，这里不会挂起。
     *
     * revokeSession：屏没了必须同时收回它的会话授权。否则授权会活过屏，下一次 vscreen_start
     * 就绕过了那道唯一的 ASK 点。
     */
    private fun destroyLocked(reason: String, revokeSession: Boolean) {
        // 屏一消失，屏上读到的控件树就不再是「当前界面」，必须跟屏一起失效。
        treeSnapshot = null
        val dying = current ?: return
        expiryJob?.cancel()
        expiryJob = null
        current = null
        val releasing = surface
        surface = null
        // 释放失败也要把状态清干净：留着 current 会让上层以为屏还在，之后每次都报「已有屏」。
        runCatching { releasing?.release() }.onFailure { FileLogger.w(TAG, "释放虚拟屏资源失败：${it.message}", it) }
        if (revokeSession) guard.revoke(dying.sessionId)
        FileLogger.i(TAG, "虚拟屏已销毁（$reason）：displayId=${dying.displayId}")
    }

    private fun isStale(screen: Screen): Boolean =
        clock.nowMs() >= screen.expiresAtMs || surface?.isValid != true

    private fun lifetimeMinutes(screen: Screen): Long =
        (screen.expiresAtMs - screen.createdAtMs) / 60_000L

    private companion object {
        const val TAG = "VirtualScreenManager"

        const val CAPTURE_WAIT_MS = 3_000L

        /** 单屏默认存活上限（30 分钟）：忘 stop 的兜底，可由任务参数放宽。 */
        const val DEFAULT_LIFETIME_MS = 30 * 60 * 1000L
        const val MIN_LIFETIME_MS = 60 * 1000L
    }
}
