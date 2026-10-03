package com.aicode.feature.terminal.domain

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceNotReadyException
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程终端（[RemoteTerminalSessionManager]）在**工作区未落定**时的行为：抛
 * [WorkspaceNotReadyException]，且不建 SSH shell channel。
 *
 * 窗口期（SSH 刚连上、工作区还没加载完）开 shell 不能停在远端 home，否则用户以为在项目里
 * 敲的命令其实落在了 home；这里钉住「先等工作区，等不到就不开 shell」。
 * 纯 JVM：连接、模式持有者与仓库全部 mock，不碰 Android runtime 的 Looper。
 */
class RemoteTerminalSessionManagerTest {

    private val notReadyMessage = "工作区未就绪：远程已连接但工作区尚未加载完成（测试文案）"

    /** 终端不可用的文案取自 `strings.xml`，与生产同源（见 remoteUnavailableReason）。 */
    private val context = mockk<Context>().apply {
        every { getString(R.string.terminal_unavailable_not_remote_mode) } returns "当前不是远程 SSH 执行模式（测试）"
        every { getString(R.string.terminal_unavailable_disconnected) } returns "SSH 未连接（测试）"
    }

    @Test
    fun createInteractiveTab_workspaceNotReady_throwsAndStartsNoShell() = runTest {
        val connection = mockk<RemoteSshConnection>()
        val modeHolder = mockk<ExecutionModeHolder>()
        val workspace = mockk<WorkspaceRepository>()
        // 远程模式且连接已建立（ensureRemote 通过）：正好是「已连但工作区未加载完」的窗口期
        every { modeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH
        every { connection.isConnected() } returns true
        coEvery { workspace.awaitCurrentPathOrNull() } returns null
        // 未落定分支会真的调用 notReadyException()，strict mock 下不 stub 直接抛 MockKException
        every { workspace.notReadyException() } returns WorkspaceNotReadyException(notReadyMessage)
        val manager = RemoteTerminalSessionManager(context, connection, modeHolder, workspace)

        val e = runCatching { manager.createInteractiveTab() }.exceptionOrNull()

        assertTrue(e is WorkspaceNotReadyException)
        assertEquals(notReadyMessage, e?.message)
        assertTrue(manager.tabs.value.isEmpty())
        verify(exactly = 0) { connection.startShellSession() }
    }
}
