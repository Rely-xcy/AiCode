package com.aicode.feature.agent.domain.vscreen

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 虚拟屏的设备侧装配。
 *
 * 接口与实现分开绑（[VirtualScreenBackend] 的实现在 Android 侧，单测里换成假实现）；
 * 时钟单独提供一个可注入项，而不是让 [VirtualScreenManager] 直接读 `SystemClock`：
 * 单测要精确摆出「时钟已越过存活上限、但定时器还没跑」这种只在进程被冻结时才会出现的状态，
 * 必须能替换时钟。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VscreenModule {

    @Binds
    @Singleton
    abstract fun bindVirtualScreenBackend(impl: AndroidVirtualScreenBackend): VirtualScreenBackend
}

@Module
@InstallIn(SingletonComponent::class)
object VscreenClockModule {

    /**
     * 用单调时钟而不是墙钟：存活上限表达的是一段时长，用户改系统时间不该让屏提前销毁或永不销毁；
     * elapsedRealtime 计入深睡，设备睡醒后定时循环会按真实剩余时间重算（delay 在冻结期间不推进）。
     */
    @Provides
    @Singleton
    fun provideVscreenClock(): VscreenClock = VscreenClock { SystemClock.elapsedRealtime() }
}
