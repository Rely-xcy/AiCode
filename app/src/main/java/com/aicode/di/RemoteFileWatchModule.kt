package com.aicode.di

import com.aicode.core.watch.RemoteWatchCommandOutput
import com.aicode.core.watch.RemoteWatchCommandRunner
import com.aicode.core.watch.RemoteWatchModeGate
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 把远端轮询器需要的窄接口绑到 feature 侧实现：core 只声明需求，具体实现与依赖方向由这里收口。
 *
 * 两个实现都是对现有单例的薄适配，不改变调用路径——转发到同一个 [CommandEngine]（即
 * `DelegatingCommandEngine`）与同一个 [ExecutionModeHolder]，因此模式判断与命令执行的时序保持原样。
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteFileWatchModule {

    @Provides
    @Singleton
    fun provideRemoteWatchCommandRunner(engine: CommandEngine): RemoteWatchCommandRunner =
        object : RemoteWatchCommandRunner {
            override suspend fun runIfReady(command: String, timeoutMs: Long): RemoteWatchCommandOutput? =
                engine.runCommandSyncIfReady(
                    command = command,
                    projectPath = null,
                    timeoutMs = timeoutMs
                )?.let { result ->
                    RemoteWatchCommandOutput(result.output, result.outputTruncated)
                }
        }

    @Provides
    @Singleton
    fun provideRemoteWatchModeGate(holder: ExecutionModeHolder): RemoteWatchModeGate =
        RemoteWatchModeGate { holder.currentMode() == ExecutionMode.REMOTE_SSH }
}
