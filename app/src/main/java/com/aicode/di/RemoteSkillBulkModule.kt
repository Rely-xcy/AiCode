package com.aicode.di

import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.RemoteBulkCommandRunner
import com.aicode.feature.agent.domain.container.RemoteSkillBulkContext
import com.aicode.feature.agent.domain.container.RemoteSkillBulkContextSource
import com.aicode.feature.agent.domain.container.RemoteSkillBulkReader
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 技能 / 子代理批量读取的接线：把「当前远端上下文」与「执行一条命令」两个窄接口绑到现有单例上，
 * 读取器本身只依赖这两个接口，单测不需要 SSH 连接、也不需要 DataStore。
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteSkillBulkModule {

    /**
     * 只有「远程模式 + 工作区已落定 + 已连上服务器」才给出上下文，其余一律 null（调用方走逐文件回退）。
     *
     * `remoteHome` 必须是连接之后拿到的真实值：拿 `/root` 兜底会在非 root 用户上算错四个根的路径。
     */
    @Provides
    @Singleton
    fun provideRemoteSkillBulkContextSource(
        holder: ExecutionModeHolder,
        workspaceRepository: WorkspaceRepository,
        connection: RemoteSshConnection
    ): RemoteSkillBulkContextSource = RemoteSkillBulkContextSource {
        val mode = holder.currentMode()
        val workspaceRoot = workspaceRepository.currentPathOrNull()
        val home = connection.remoteHome
        if (mode != ExecutionMode.REMOTE_SSH || workspaceRoot == null || home == null) {
            null
        } else {
            RemoteSkillBulkContext(
                cacheKey = "$mode|$workspaceRoot|$home",
                workspaceRoot = workspaceRoot,
                home = home
            )
        }
    }

    /**
     * 用不限幅通道：4 万字符的默认限幅会把批量输出挖空（头尾各留一段），解析出来的内容对不上文件大小，
     * 白白多花一次往返。连接未就绪时直接返回 null，不在这里触发建连。
     */
    @Provides
    @Singleton
    fun provideRemoteBulkCommandRunner(engine: CommandEngine): RemoteBulkCommandRunner =
        object : RemoteBulkCommandRunner {
            override suspend fun runUnbounded(command: String, timeoutMs: Long): CommandResult? {
                if (!engine.isContainerInstalled()) return null
                return runCatching {
                    engine.runCommandSyncUnbounded(command, projectPath = null, timeoutMs = timeoutMs)
                }.getOrNull()
            }
        }

    @Provides
    @Singleton
    fun provideBulkFileSource(reader: RemoteSkillBulkReader): BulkFileSource = reader
}
