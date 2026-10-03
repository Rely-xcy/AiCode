package com.aicode.feature.terminal.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
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
 * 本地终端（[TerminalSessionManager]）在**工作区未落定**时的行为：抛
 * [WorkspaceNotReadyException]，且不构造 PTY 会话、不留标签页。
 *
 * 未就绪时没有可靠的 `cd` 目标，留一个停在容器 home 的标签页会让后续命令都跑错目录，
 * 故这里钉住「先等工作区，等不到就报错、不建会话」。
 * 纯 JVM：容器引擎与仓库全部 mock，不碰 Android runtime 的 Looper。
 */
class TerminalSessionManagerTest {

    private val notReadyMessage = "工作区未就绪：本地尚未初始化完成（测试文案）"

    @Test
    fun createInteractiveTab_workspaceNotReady_throwsAndBuildsNoSession() = runTest {
        val engine = mockk<LinuxContainerEngine>()
        val workspace = mockk<WorkspaceRepository>()
        // 走到取工作区路径这一步之前会经过容器就绪检查与 shell 命令拼装
        coEvery { engine.ensureInstalled() } returns Unit
        every { engine.isContainerInstalled() } returns true
        every { engine.defaultShell() } returns "/bin/sh"
        coEvery { workspace.awaitCurrentPathOrNull() } returns null
        // 未落定分支会真的调用 notReadyException()，strict mock 下不 stub 直接抛 MockKException
        every { workspace.notReadyException() } returns WorkspaceNotReadyException(notReadyMessage)
        val manager = TerminalSessionManager(mockk<Context>(), engine, workspace)

        val e = runCatching { manager.createInteractiveTab() }.exceptionOrNull()

        assertTrue(e is WorkspaceNotReadyException)
        assertEquals(notReadyMessage, e?.message)
        assertTrue(manager.tabs.value.isEmpty())
        // 没有工作区就不该构造 proot 调用（PTY 会话根本没开始建）
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
    }
}
