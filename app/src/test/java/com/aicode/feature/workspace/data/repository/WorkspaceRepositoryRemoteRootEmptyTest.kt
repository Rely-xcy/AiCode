package com.aicode.feature.workspace.data.repository

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * 远程工作区根展开后为空（remoteWorkspacePath 配成 `/` 之类，结尾斜杠被 `trimEnd('/')` 吃光）
 * 时的两个症状，各钉一条：
 *
 * ① 提示文案 —— 默认工作区没建出来时 `initialize()` 原来一律给「请检查服务器权限与磁盘空间」，
 * 把用户指向服务器；真正要改的是「容器与镜像」里的远程工作区路径。
 * ② 工作区列表 —— `refreshRemoteWorkspaces()` 拿空串去拼命令，拼出来的是列远端根目录的那条 ls，
 * 远端根下的 `/home`、`/tmp` 这些子目录会被当成工作区列出来，与 `initialize()` 在同一份配置下
 * 给出空列表的结果分叉（打开工作区面板时 `refreshAvailability()` 走的就是这条链）。
 *
 * 这里构造**真实**的 [WorkspaceRepository]（只 mock 依赖），被测的就是仓库自己的取根与分派逻辑；
 * 纯 JVM：Context 用 mockk，DataStore/projectsRoot 落到临时目录，exec 会话用 mockk 喂回放好的 stdout。
 */
class WorkspaceRepositoryRemoteRootEmptyTest {

    private val context = mockk<Context>()
    private val executionModeHolder = mockk<ExecutionModeHolder>()
    private val remoteSshConnection = mockk<RemoteSshConnection>()
    private val pathHomeResolver = mockk<PathHomeResolver>()
    private val generalSettingsRepository = mockk<GeneralSettingsRepository>()

    /** 每次 exec 用的命令，按调用顺序记录 —— 用它断言「那条命令有没有被发出去」。 */
    private val executedCommands = mutableListOf<String>()

    /**
     * DataStore 与 projectsRoot 共用的真实临时目录。`preferencesDataStore` 委托在 JVM 内是单例、
     * 缓存首次传入的 Context，所以这个目录只创建不删除（删掉后续用例的读写会失败）。
     */
    private val filesDir: File by lazy { Files.createTempDirectory("workspace-remote-root-test").toFile() }

    private fun config(remoteWorkspacePath: String) = RemoteConnectionConfig(
        host = "example.test",
        port = 22,
        username = "deploy",
        auth = RemoteAuth.Password("secret"),
        remoteWorkspacePath = remoteWorkspacePath
    )

    private fun createRepository(
        config: RemoteConnectionConfig?,
        listing: String = "",
        connectionState: ConnectionState = ConnectionState.CONNECTED
    ): WorkspaceRepository {
        every { executionModeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH
        every { remoteSshConnection.connectionState } returns MutableStateFlow(connectionState)
        every { remoteSshConnection.config } returns config
        every { remoteSshConnection.isConnected() } returns (connectionState == ConnectionState.CONNECTED)
        // 构造期与 DataStore 委托都会碰到这两处（strict mock 下未 stub 即抛）
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { generalSettingsRepository.deleteExternalWorkspaceSessionsFlow } returns MutableStateFlow(false)
        // 仓库只用到 expandHome；按 WorkspacePathMapperTest 的做法保持真实展开语义（home 取 /root）
        every { pathHomeResolver.expandHome(any()) } answers {
            val p = firstArg<String>()
            when {
                p == "~" -> REMOTE_HOME
                p.startsWith("~/") -> REMOTE_HOME.trimEnd('/') + p.removePrefix("~")
                else -> p
            }
        }
        // 三个文案键各返回一个不同的哨兵串：断言拿到哪个串，就等于钉住「落到了哪个原因上」
        every { context.getString(R.string.workspace_remote_target_dir_empty) } returns TARGET_DIR_EMPTY_MESSAGE
        every { context.getString(R.string.workspace_remote_not_configured) } returns NOT_CONFIGURED_MESSAGE
        every { context.getString(R.string.workspace_remote_default_create_failed) } returns PERMISSION_DISK_MESSAGE
        every { remoteSshConnection.startExecSession(any()) } answers {
            executedCommands += firstArg<String>()
            execSessionReturning(listing)
        }
        return WorkspaceRepository(
            context = context,
            executionModeHolder = executionModeHolder,
            remoteSshConnection = remoteSshConnection,
            pathHomeResolver = pathHomeResolver,
            sessionUseCase = mockk<SessionUseCase>(),
            generalSettingsRepository = generalSettingsRepository
        )
    }

    /** 一个把 [output] 当作命令 stdout 的 exec 会话。 */
    private fun execSessionReturning(output: String): Session.Command {
        val command = mockk<Session.Command>()
        every { command.inputStream } returns ByteArrayInputStream(output.toByteArray())
        every { command.close() } returns Unit
        return command
    }

    // ── ② 刷新：目标目录展开后为空时，不许把列远端根目录的那条命令发出去 ──────────

    @Test
    fun refreshAvailability_targetDirIsSlash_issuesNoCommandAndKeepsListEmpty() = runTest {
        // 远端根目录下的子目录名，就是旧实现会列成「工作区」的那几个
        val repo = createRepository(config = config("/"), listing = "home\ntmp\netc\n")

        repo.refreshAvailability()

        // 旧实现下必红：wsRoot 展开成空串后照样拼命令并 exec 一次，executedCommands 里会留下 1 条
        assertTrue(executedCommands.isEmpty())
        // 旧实现下必红：那条命令的 stdout 会被解析成 home / tmp / etc 三个假工作区
        assertTrue(repo.workspaces.value.isEmpty())
    }

    @Test
    fun refreshAvailability_configuredTargetDir_listsSubdirsOfThatDir() = runTest {
        val repo = createRepository(config = config("/srv/ai/code"), listing = "beta\nalpha\n")

        repo.refreshAvailability()

        // 护栏（新旧实现都绿，不是本次修复的红/绿证据）：正常配置下这条链一点没变 —— 仍然只发一条
        // 命令，仍然只列该目录的子目录。它防的是「把空路径的短路写宽了，顺手把正常路径也短路掉」。
        assertEquals(1, executedCommands.size)
        assertTrue(executedCommands.single().contains("ls -d /srv/ai/code/*/"))
        assertFalse(executedCommands.single().contains("ls -d /*/"))
        assertEquals(listOf("alpha", "beta"), repo.workspaces.value.map { it.name })
        assertEquals("/srv/ai/code/alpha", repo.workspaces.value.first().path)
    }

    // ── ① 文案：默认工作区没建出来时，Toast 要说清是路径没配，而不是服务器权限/磁盘 ────

    @Test
    fun initialize_targetDirIsSlash_reportsMissingPathInsteadOfServerPermissions() = runTest {
        val repo = createRepository(config = config("/"))

        repo.initialize()

        // 旧实现下必红：这一支原来不论来因都取 workspace_remote_default_create_failed 的文案，
        // 用户会拿着「请检查服务器权限与磁盘空间」去查服务器，而真正要改的是工作区路径。
        assertEquals(TARGET_DIR_EMPTY_MESSAGE, repo.initError.value)
    }

    @Test
    fun initialize_withoutRemoteConnection_reportsMissingConnection() = runTest {
        // 没有已保存的远程连接时连接状态停在 FAILED（监督协程不会去连），这里与之保持一致，
        // 免得 waitForConnection 撞上 5s 等待上限。
        val repo = createRepository(config = null, connectionState = ConnectionState.FAILED)

        repo.initialize()

        // 旧实现下必红：isConnected() 为 false 时这一支什么都不设，initError 留在 null ——
        // 工作区是空的，用户却看不到任何解释。
        assertEquals(NOT_CONFIGURED_MESSAGE, repo.initError.value)
    }

    @Test
    fun remoteScanAbort_separatesMissingConfigFromEmptyTargetDir() {
        // 这份分类是 ① 的文案与上一轮的日志共用的判据：根路径取不到时只有「没配连接」与
        // 「目标目录展开后为空」两种，混成一种，Toast 就又会指错方向。
        // 旧实现下没有这个符号（判据还埋在 remoteScanAbortReason 的 if 链里），本用例编译不过 ——
        // 它的红不是断言失败，而是符号不存在，故只作为判据本身的钉桩，不当作行为回归的证据。
        assertEquals(RemoteScanAbort.NotConfigured, WorkspaceRepository.remoteScanAbort(cfg = null, root = null))
        assertEquals(RemoteScanAbort.TargetDirEmpty, WorkspaceRepository.remoteScanAbort(cfg = config("/"), root = null))
        assertEquals(RemoteScanAbort.TargetDirEmpty, WorkspaceRepository.remoteScanAbort(cfg = config(""), root = null))
        assertEquals(
            RemoteScanAbort.ScanAborted,
            WorkspaceRepository.remoteScanAbort(cfg = config("/srv/ai/code"), root = "/srv/ai/code")
        )
    }

    private companion object {
        /** 远程连接成功后会缓存到的 home，用它复刻 expandHome 的语义。 */
        const val REMOTE_HOME = "/root"
        const val TARGET_DIR_EMPTY_MESSAGE = "远程工作区路径为空或无效（测试文案）"
        const val NOT_CONFIGURED_MESSAGE = "尚未配置远程连接（测试文案）"
        const val PERMISSION_DISK_MESSAGE = "远程默认工作区创建失败，请检查服务器权限与磁盘空间（测试文案）"
    }
}
