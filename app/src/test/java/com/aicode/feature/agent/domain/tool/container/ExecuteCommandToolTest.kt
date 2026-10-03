package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bash 工具（[ExecuteCommandTool]）在**工作区未落定**时的行为：不执行任何命令，
 * 返回 code 为 WORKSPACE_NOT_READY 的错误并带上未就绪文案（流式版同样以该错误收尾）。
 *
 * 未就绪时命令的 cwd 没有可靠取值，落到工作区父目录会让 `npm install` / `git` 之类
 * 跑到所有项目的公共目录上；这里钉住「先等工作区、等不到就报错」这条闸门。
 * 纯 JVM：CommandEngine 与 WorkspaceRepository 全部 mock。
 */
class ExecuteCommandToolTest {

    private val workPath = "/root/workspace/projects/demo"
    private val notReadyMessage = "工作区未就绪：本地尚未初始化完成（测试文案）"

    private val engine = mockk<CommandEngine>()
    private val workspace = mockk<WorkspaceRepository>()

    private fun tool() = ExecuteCommandTool(engine, workspace)

    private fun commandArgs(command: String) = mapOf("command" to JsonPrimitive(command))

    /** 未落定：await 返回 null，且 notReadyMessage() 必须 stub（strict mock 下会真被调用）。 */
    private fun stubNotReadyWorkspace() {
        coEvery { workspace.awaitCurrentPathOrNull() } returns null
        every { workspace.notReadyMessage() } returns notReadyMessage
    }

    @Test
    fun execute_workspaceNotReady_returnsErrorWithoutRunningCommand() = runTest {
        stubNotReadyWorkspace()

        val result = tool().execute(commandArgs("ls"))

        assertEquals(ToolResult.Error(notReadyMessage, "WORKSPACE_NOT_READY"), result)
        coVerify(exactly = 0) { engine.runCommandSync(any(), any(), any()) }
    }

    @Test
    fun executeStream_workspaceNotReady_completesWithErrorWithoutRunningCommand() = runTest {
        stubNotReadyWorkspace()

        val events = tool().executeStream(commandArgs("ls"), mockk<AgentContext>()).toList()

        assertEquals(
            listOf(ToolStreamEvent.Completed(ToolResult.Error(notReadyMessage, "WORKSPACE_NOT_READY"))),
            events
        )
        // 流式入口在这里就收尾，不该真的起流（runCommandStream 是非 suspend 方法，用 verify）
        verify(exactly = 0) { engine.runCommandStream(any(), any(), any()) }
    }

    @Test
    fun execute_workspaceReady_runsCommandInWorkspaceDir() = runTest {
        // 正向对照：落定时命令仍以工作区路径为 cwd、返回原始输出（闸门不误伤正常路径）
        coEvery { workspace.awaitCurrentPathOrNull() } returns workPath
        coEvery { engine.runCommandSync(any(), any(), any()) } returns "ok"

        val result = tool().execute(commandArgs("ls"))

        assertEquals(ToolResult.Success(JsonPrimitive("ok")), result)
        coVerify(exactly = 1) { engine.runCommandSync("ls", workPath, any()) }
    }
}
