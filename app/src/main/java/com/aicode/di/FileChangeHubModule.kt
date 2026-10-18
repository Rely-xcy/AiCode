package com.aicode.di

import com.aicode.core.watch.FileWatchAicodeDir
import com.aicode.core.watch.FileWatchModeGate
import com.aicode.core.watch.FileWatchPathMapper
import com.aicode.core.watch.FileWatchRemoteConnection
import com.aicode.core.watch.FileWatchWorkspaceSource
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 把 `FileChangeHub` 需要的窄接口绑到 feature 侧实现：core 只声明需求，具体实现与依赖方向在这里收口。
 *
 * 五个实现都是对现有单例的薄适配，不改变调用路径——工作区、执行模式、SSH 连接、配置目录、路径映射
 * 都转发到原来那一个实例，因此总线的订阅建立时机、发射顺序与线程上下文保持原样。
 */
@Module
@InstallIn(SingletonComponent::class)
object FileChangeHubModule {

    @Provides
    @Singleton
    fun provideFileWatchWorkspaceSource(repository: WorkspaceRepository): FileWatchWorkspaceSource =
        FileWatchWorkspaceSource { repository.current.map { repository.currentPathOrNull() } }

    @Provides
    @Singleton
    fun provideFileWatchModeGate(holder: ExecutionModeHolder): FileWatchModeGate =
        object : FileWatchModeGate {
            override fun isRemoteSsh(): Boolean =
                holder.currentMode() == ExecutionMode.REMOTE_SSH

            // 用 getter 而非一次性赋值：读属性仍发生在每次订阅建立时，与原实现同频。
            override val remoteSshChanges: Flow<Boolean>
                get() = holder.mode.map { it == ExecutionMode.REMOTE_SSH }
        }

    @Provides
    @Singleton
    fun provideFileWatchRemoteConnection(
        connection: RemoteSshConnection
    ): FileWatchRemoteConnection = object : FileWatchRemoteConnection {
        // 值本身不被消费，只当「连接状态变了」的信号用；映射不改变发射次数与时机。
        // 用 getter 的理由同 [FileWatchModeGate.remoteSshChanges]。
        override val connectionChanges: Flow<Unit>
            get() = connection.connectionState.map { Unit }

        override val remoteHome: String?
            get() = connection.remoteHome
    }

    @Provides
    @Singleton
    fun provideFileWatchAicodeDir(installer: ContainerInstaller): FileWatchAicodeDir =
        FileWatchAicodeDir { installer.aicodeDir }

    @Provides
    @Singleton
    fun provideFileWatchPathMapper(mapper: WorkspacePathMapper): FileWatchPathMapper =
        object : FileWatchPathMapper {
            override fun toHostFile(path: String): File = mapper.toHostFile(path)

            override fun toContainerPath(hostPath: String): String = mapper.toContainerPath(hostPath)
        }
}
