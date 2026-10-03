package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * search 工具（[SearchCodeTool]）在**工作区未落定**时的行为：不执行 rg，直接返回
 * code 为 WORKSPACE_NOT_READY 的错误并带上未就绪文案。
 *
 * projectPath 决定 rg 的工作目录，落到工作区父目录会把「搜当前项目」变成「搜所有项目」，
 * 这里钉住「先等工作区、等不到就不搜」。纯 JVM：CommandEngine / WorkspaceRepository /
 * PathHomeResolver 全部 mock。
 */
class SearchCodeToolTest {

    private val workPath = "/root/workspace/projects/demo"
    private val notReadyMessage = "工作区未就绪：本地尚未初始化完成（测试文案）"

    /** 含引号与 `~/` 的正常参数：确保走到「取工作区路径」这一步，而不是提前被参数校验拦下。 */
    private val searchArgs = mapOf("args" to JsonPrimitive("-n \"fun main\" ~/workspace/app"))

    private val engine = mockk<CommandEngine>()
    private val workspace = mockk<WorkspaceRepository>()
    private val pathHomeResolver = mockk<PathHomeResolver>()

    private fun tool() = SearchCodeTool(engine, workspace, pathHomeResolver)

    @Test
    fun search_workspaceNotReady_returnsErrorWithoutRunningRg() = runTest {
        // home() 在取工作区路径之前就会被调用（构造 rg 命令），strict mock 下必须 stub
        every { pathHomeResolver.home() } returns "/root"
        coEvery { workspace.awaitCurrentPathOrNull() } returns null
        every { workspace.notReadyMessage() } returns notReadyMessage

        val result = tool().execute(searchArgs)

        assertEquals(ToolResult.Error(notReadyMessage, "WORKSPACE_NOT_READY"), result)
        coVerify(exactly = 0) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test
    fun search_workspaceReady_searchesInWorkspaceDir() = runTest {
        // 正向对照：落定时 rg 仍以工作区路径为工作目录（闸门不误伤正常路径）
        every { pathHomeResolver.home() } returns "/root"
        coEvery { workspace.awaitCurrentPathOrNull() } returns workPath
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("", 0)

        val result = tool().execute(searchArgs)

        assertTrue(result is ToolResult.Success)
        coVerify(exactly = 1) { engine.runCommandSyncIfReady(any(), workPath, any()) }
    }
}
