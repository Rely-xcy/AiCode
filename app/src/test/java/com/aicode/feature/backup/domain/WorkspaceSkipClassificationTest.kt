package com.aicode.feature.backup.domain

import com.aicode.feature.workspace.domain.model.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出时「跳过某个工作区」的判定式与摘要提示条件。
 *
 * 这两种逻辑都是纯函数/纯数据，直接单测覆盖，不必搭 Room、文件系统或 SSH：
 * 真实导出流程里它们分别负责「记下为什么没导出」和「摘要要不要明示远程文件缺失」，
 * 一旦判错，用户看到的就是「导出成功」但其实一个工作区文件都没进包。
 */
class WorkspaceSkipClassificationTest {

    // ── classifyWorkspaceSkip：远程 / 目录不存在 / 正常 ──────────────────

    @Test
    fun `远程工作区未连接时按读不到文件跳过`() {
        val reason = classifyWorkspaceSkip(
            type = WorkspaceType.REMOTE,
            hostDirExists = false,
            remoteConnected = false
        )
        assertEquals(WorkspaceSkipReason.REMOTE_NOT_CONNECTED, reason)
        assertTrue(reason?.isRemote == true)
    }

    @Test
    fun `远程工作区即使已连接也不导出并单独归类`() {
        val reason = classifyWorkspaceSkip(
            type = WorkspaceType.REMOTE,
            hostDirExists = true,
            remoteConnected = true
        )
        assertEquals(WorkspaceSkipReason.REMOTE_UNSUPPORTED, reason)
        assertTrue(reason?.isRemote == true)
    }

    @Test
    fun `内部工作区宿主目录不存在时按目录缺失跳过`() {
        val reason = classifyWorkspaceSkip(
            type = WorkspaceType.INTERNAL,
            hostDirExists = false,
            remoteConnected = false
        )
        assertEquals(WorkspaceSkipReason.HOST_DIR_MISSING, reason)
        assertFalse(reason?.isRemote == true)
    }

    @Test
    fun `内部工作区目录存在时不跳过`() {
        assertNull(
            classifyWorkspaceSkip(type = WorkspaceType.INTERNAL, hostDirExists = true, remoteConnected = false)
        )
        // 已连远程也只影响远程工作区，本地内部工作区照常备份
        assertNull(
            classifyWorkspaceSkip(type = WorkspaceType.INTERNAL, hostDirExists = true, remoteConnected = true)
        )
    }

    @Test
    fun `外部本地工作区不进候选也不产生跳过记录`() {
        assertNull(
            classifyWorkspaceSkip(type = WorkspaceType.EXTERNAL_LOCAL, hostDirExists = false, remoteConnected = false)
        )
    }

    // ── ExportStats：摘要里「远程文件未包含」的触发条件 ──────────────────

    @Test
    fun `远程工作区一个文件都没导出时要明示缺失`() {
        val stats = ExportStats(
            workspaceFilesRequested = true,
            candidateWorkspaces = 1,
            exportedWorkspaces = 0,
            workspaceFiles = 0,
            skippedWorkspaces = listOf(WorkspaceSkip("srv", WorkspaceSkipReason.REMOTE_NOT_CONNECTED)),
            remoteMode = true,
            remoteConnected = false
        )
        assertEquals(1, stats.skippedRemoteWorkspaces)
        assertTrue(stats.remoteWorkspaceMissing)
    }

    @Test
    fun `导出了部分文件时不再提示远程未包含`() {
        val stats = ExportStats(
            workspaceFilesRequested = true,
            candidateWorkspaces = 2,
            exportedWorkspaces = 1,
            workspaceFiles = 12,
            skippedWorkspaces = listOf(WorkspaceSkip("srv", WorkspaceSkipReason.REMOTE_UNSUPPORTED)),
            remoteMode = true,
            remoteConnected = true
        )
        assertFalse(stats.remoteWorkspaceMissing)
    }

    @Test
    fun `远程模式未连接且连工作区列表都读不到时也要明示缺失`() {
        val stats = ExportStats(
            workspaceFilesRequested = true,
            remoteMode = true,
            remoteConnected = false
        )
        assertFalse(stats.remoteConnected)
        assertTrue(stats.remoteWorkspaceMissing)
    }

    @Test
    fun `未勾工作区文件时不提工作区相关提示`() {
        val stats = ExportStats(workspaceFilesRequested = false, remoteMode = true, remoteConnected = false)
        assertFalse(stats.remoteWorkspaceMissing)
    }

    @Test
    fun `全部工作区正常导出时不产生跳过明细`() {
        val stats = ExportStats(
            workspaceFilesRequested = true,
            candidateWorkspaces = 2,
            exportedWorkspaces = 2,
            workspaceFiles = 30,
            remoteMode = false,
            remoteConnected = false
        )
        assertTrue(stats.skippedWorkspaces.isEmpty())
        assertFalse(stats.remoteWorkspaceMissing)
    }

    // ── RestoreStats.plus：跳过的工作区文件数要能跨段合并 ────────────────

    @Test
    fun `导入统计合并时累加跳过的工作区文件数`() {
        val merged = RestoreStats(workspaceFiles = 3, skippedWorkspaceFiles = 2) +
            RestoreStats(workspaceFiles = 4, skippedWorkspaceFiles = 5)
        assertEquals(7, merged.workspaceFiles)
        assertEquals(7, merged.skippedWorkspaceFiles)
        // 跳过项不属于「已写入」，不参与 hasWrittenData 判定
        assertFalse(RestoreStats(skippedWorkspaceFiles = 9).hasWrittenData)
    }
}
