package com.aicode.feature.agent.domain.vscreen

import java.io.File

/**
 * 虚拟屏的「设备侧」能力：建屏、取帧、释放。
 *
 * 单独成接口是为了让 [VirtualScreenManager] 的生命周期状态机能被 JVM 单测覆盖——
 * 建屏要 DisplayManager 与 ImageReader，两者在普通单测里都构造不出来；
 * Android 侧实现见 [AndroidVirtualScreenBackend]。
 */
interface VirtualScreenBackend {

    /**
     * 建一块屏并在其上启动目标 app。
     *
     * 失败时实现方必须把建到一半的 display 与取帧器都收干净：调用方只看到 [Result]，
     * 拿不到任何句柄去补释放。
     */
    suspend fun open(
        width: Int,
        height: Int,
        density: Int,
        packageName: String,
        activity: String?
    ): Result<VirtualScreenSurface>
}

/** 一块已建成的虚拟屏句柄：屏与取帧器由它自己负责释放。 */
interface VirtualScreenSurface {

    val displayId: Int

    /** 屏是否仍被系统认可（系统回收内存时会把它置为无效）。 */
    val isValid: Boolean

    /** 取一帧 PNG 落到 [target]。 */
    fun captureFrame(target: File, maxWaitMs: Long): Result<Unit>

    /** 释放屏与取帧器；重复调用无副作用。 */
    fun release()
}

/**
 * 时钟缝：生产取单调时钟（`SystemClock.elapsedRealtime`），单测取虚拟时间。
 *
 * 到期判定与定时器共用同一个时钟，单测才能既真跑定时器、又精确制造「时钟已越过存活上限、
 * 但定时器还没跑」这种（进程被冻结时才会出现的）状态。
 */
fun interface VscreenClock {
    fun nowMs(): Long
}
