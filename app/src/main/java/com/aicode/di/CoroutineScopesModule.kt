package com.aicode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 应用级长生命周期作用域：用于「不跟随调用方生命周期」的后台分发。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * 上下文压缩这类重活的调度器：由压缩模块在调模型前把整段判定与压缩体切到它上面跑。
 *
 * 与 [ApplicationScope] 一样提供为可注入项而不是模块内部直接取 `Dispatchers.Default`：
 * 单测要能换成自定义调度器，断言「压缩体确实没跑在调用者线程上」。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CompactionWork

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopesModule {

    /**
     * SupervisorJob：单个子任务失败不连坐其它子任务（引擎分发钩子依赖这一点）。
     * 提供为可注入项而非引擎内部 new，是为了单测能换成 TestScope 做确定性验证。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 压缩重活取 Default 而不是 IO：这活以 CPU 为主（逐字符 token 估算、消息投影、摘要文本处理），
     * Default 的线程数按核数有界；里面少数几次 I/O（Room 读写、归档写盘、FileLogger）各自在自己的
     * 线程池上跑，不受这里影响。
     */
    @Provides
    @CompactionWork
    fun provideCompactionWorkDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
