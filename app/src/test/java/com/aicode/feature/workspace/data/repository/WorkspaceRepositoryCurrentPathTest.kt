package com.aicode.feature.workspace.data.repository

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工作区**未落定**（本地初始化未完成 / 远程未连接 / 远程已连但未加载完）时的路径语义：
 * [WorkspaceRepository.currentPathOrNull] 返回 null、[WorkspaceRepository.currentPath] 抛
 * [WorkspaceNotReadyException] 且文案按三种原因区分；[WorkspaceRepository.awaitCurrentPathOrNull]
 * 等到上限仍无工作区时返回 null，不无限等待；落定后 [WorkspaceRepository.currentPath] 与
 * [WorkspaceRepository.currentPathOrNull] 一致，且绝不回退到工作区父目录。
 *
 * 这里构造**真实**的 [WorkspaceRepository]（只 mock 依赖）：被测的就是仓库自身的落定状态判断，
 * 把仓库整个 mock 掉就锁不住「未落定不猜兜底目录」这条语义。纯 JVM：Context 用 mockk，
 * DataStore/projectsRoot 落到临时目录。
 */
class WorkspaceRepositoryCurrentPathTest {

    private val context = mockk<Context>()
    private val executionModeHolder = mockk<ExecutionModeHolder>()
    private val remoteSshConnection = mockk<RemoteSshConnection>()
    private val generalSettingsRepository = mockk<GeneralSettingsRepository>()

    /**
     * DataStore 与 projectsRoot 共用同一真实临时目录：`preferencesDataStore` 委托在 JVM 内是单例，
     * 缓存首次传入的 Context，故整个测试类共用一个目录并且不删除（删掉后续用例的读写会失败）。
     */
    private val filesDir: File by lazy { Files.createTempDirectory("workspace-repo-test").toFile() }

    private fun createRepository(
        mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        connectionState: ConnectionState = ConnectionState.DISCONNECTED
    ): WorkspaceRepository {
        every { executionModeHolder.currentMode() } returns mode
        every { remoteSshConnection.connectionState } returns MutableStateFlow(connectionState)
        // 构造期就会访问这两处：DataStore 委托取 applicationContext，deleteExternalWorkspaceSessionsFlow
        // 是属性初始化（strict mock 下未 stub 即抛 MockKException，构造直接失败）。
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { generalSettingsRepository.deleteExternalWorkspaceSessionsFlow } returns MutableStateFlow(false)
        // 三个文案键各自返回不同值：断言文案即可钉住「落到哪个原因的键上」。
        every { context.getString(R.string.workspace_not_ready_local) } returns LOCAL_MESSAGE
        every { context.getString(R.string.workspace_not_ready_remote_disconnected) } returns REMOTE_DISCONNECTED_MESSAGE
        every { context.getString(R.string.workspace_not_ready_remote_connected) } returns REMOTE_CONNECTED_MESSAGE
        return WorkspaceRepository(
            context = context,
            executionModeHolder = executionModeHolder,
            remoteSshConnection = remoteSshConnection,
            pathHomeResolver = mockk<PathHomeResolver>(),
            sessionUseCase = mockk<SessionUseCase>(),
            generalSettingsRepository = generalSettingsRepository
        )
    }

    private fun currentPathFailure(repo: WorkspaceRepository): Throwable? =
        runCatching { repo.currentPath() }.exceptionOrNull()

    // ── currentPathOrNull()：未落定就是 null，不猜兜底目录 ──────────────

    @Test
    fun currentPathOrNull_localNotSettled_isNull() {
        assertNull(createRepository().currentPathOrNull())
    }

    @Test
    fun currentPathOrNull_remoteNotConnected_isNull() {
        val repo = createRepository(mode = ExecutionMode.REMOTE_SSH, connectionState = ConnectionState.DISCONNECTED)
        assertNull(repo.currentPathOrNull())
    }

    @Test
    fun currentPathOrNull_remoteConnectedButNotLoaded_isNull() {
        val repo = createRepository(mode = ExecutionMode.REMOTE_SSH, connectionState = ConnectionState.CONNECTED)
        assertNull(repo.currentPathOrNull())
    }

    // ── currentPath()：未落定抛 WorkspaceNotReadyException，文案区分三种原因 ──

    @Test
    fun currentPath_localNotSettled_throwsWithLocalMessage() {
        val e = currentPathFailure(createRepository())
        assertTrue(e is WorkspaceNotReadyException)
        // 子类关系是既有调用方（按 IllegalStateException 捕获）的兼容前提，一并钉住
        assertTrue(e is IllegalStateException)
        assertEquals(LOCAL_MESSAGE, e?.message)
    }

    @Test
    fun currentPath_remoteNotConnected_throwsWithRemoteDisconnectedMessage() {
        val repo = createRepository(mode = ExecutionMode.REMOTE_SSH, connectionState = ConnectionState.DISCONNECTED)
        val e = currentPathFailure(repo)
        assertTrue(e is WorkspaceNotReadyException)
        assertEquals(REMOTE_DISCONNECTED_MESSAGE, e?.message)
    }

    @Test
    fun currentPath_remoteConnectedButNotLoaded_throwsWithRemoteConnectedMessage() {
        val repo = createRepository(mode = ExecutionMode.REMOTE_SSH, connectionState = ConnectionState.CONNECTED)
        val e = currentPathFailure(repo)
        assertTrue(e is WorkspaceNotReadyException)
        assertEquals(REMOTE_CONNECTED_MESSAGE, e?.message)
    }

    // ── awaitCurrentPathOrNull()：等到上限仍是 null，不无限等待 ──────────

    @Test
    fun awaitCurrentPathOrNull_notSettled_returnsNullInsteadOfHanging() = runTest {
        val repo = createRepository()
        assertNull(repo.awaitCurrentPathOrNull())
    }

    // ── 落定后：currentPath() == currentPathOrNull()，且不是工作区父目录 ──

    @Test
    fun initialize_localSettles_currentPathIsWorkspaceNotParentDir() = runTest {
        val repo = createRepository()
        repo.initialize()

        val expected = File(filesDir, "projects/default").absolutePath
        assertEquals(expected, repo.currentPathOrNull())
        assertEquals(repo.currentPathOrNull(), repo.currentPath())
        assertEquals(expected, repo.awaitCurrentPathOrNull())
        // 未落定修复的核心语义：路径只能是工作区本身，不能是 projectsRoot 或 filesDir 这类父目录
        assertTrue(repo.currentPath() != File(filesDir, "projects").absolutePath)
        assertTrue(repo.currentPath() != filesDir.absolutePath)
    }

    private companion object {
        const val LOCAL_MESSAGE = "工作区未就绪：本地尚未初始化完成（测试文案）"
        const val REMOTE_DISCONNECTED_MESSAGE = "工作区未就绪：远程未连接（测试文案）"
        const val REMOTE_CONNECTED_MESSAGE = "工作区未就绪：远程已连未加载完（测试文案）"
    }
}
