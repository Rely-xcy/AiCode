package com.aicode.feature.workspace.data.repository

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区初始化「失败可归因」的护栏。
 *
 * 上一版的毛病不是行为不对，而是**日志查不出原因**：远程扫描没进行成时（`scan == null`）
 * 只有 `_workspaces.value = emptyList()`，一条日志都不打；收尾又无论如何都打一条
 * 「工作区初始化完成，当前: null」，事后翻日志只剩一条长得像成功的 INFO。
 *
 * 这里钉两件事：**置空路径的来因必须能区分**（未配置 / 目标目录为空 / 连接或远端命令没成），
 * 以及**「完成」形状只许出现在真的落到可用工作区时**。
 *
 * 纯 JVM：只调 [WorkspaceRepository] 的两个纯函数，不起 DataStore、不连 SSH、不落盘 ——
 * 日志文案本身就是本次交付物，所以直接钉文案里的判据（原因种类 + 关键上下文），不钉整句。
 */
class WorkspaceRepositoryInitDiagnosticsTest {

    private fun config(remoteWorkspacePath: String) = RemoteConnectionConfig(
        host = "example.test",
        port = 22,
        username = "deploy",
        auth = RemoteAuth.Password("secret"),
        remoteWorkspacePath = remoteWorkspacePath
    )

    // ── remoteScanAbortReason：置空路径的来因必须可区分 ─────────────────────

    @Test
    fun remoteScanAbortReason_missingConfig_andEmptyTargetDir_areNotTheSameLine() {
        val noConfig = WorkspaceRepository.remoteScanAbortReason(cfg = null, root = null)
        val blankPath = WorkspaceRepository.remoteScanAbortReason(cfg = config(""), root = null)
        // 旧实现下这两种来因的可观测输出完全一样：scan == null 分支不打任何日志，
        // 两边都是「什么都没有」，不存在任何能把它们区分开的日志行 —— 本断言在旧实现上不成立。
        assertNotEquals(noConfig, blankPath)
        assertTrue(noConfig.contains("SSH 未配置"))
        assertTrue(blankPath.contains("remoteWorkspacePath=''"))
    }

    @Test
    fun remoteScanAbortReason_emptyTargetDir_carriesTheConfiguredPath() {
        val reason = WorkspaceRepository.remoteScanAbortReason(cfg = config("~/srv/ai code"), root = null)
        // 「目标目录为空」必须带上配的到底是什么：不然用户看一眼日志也不知道该去改哪个字段。
        assertTrue(reason.contains("远程目标目录为空"))
        assertTrue(reason.contains("~/srv/ai code"))
    }

    @Test
    fun remoteScanAbortReason_scanStartedButAborted_doesNotBlameConfiguration() {
        val reason = WorkspaceRepository.remoteScanAbortReason(cfg = config("/srv/ai/code"), root = "/srv/ai/code")
        // 根路径取到了 → 失败落在连接或远端命令上。日志不能把它说成「未配置」或「目标目录为空」，
        // 那会把排查方向带偏；同时要带上根目录，便于和 scanRemoteWorkspaceRoot 记的那条 WARN 对上。
        assertFalse(reason.contains("SSH 未配置"))
        assertFalse(reason.contains("目标目录为空"))
        assertTrue(reason.contains("/srv/ai/code"))
    }

    // ── initCompletionMessage：只有真的成功才许出现「完成」形状 ───────────────

    @Test
    fun initCompletionMessage_withoutWorkspace_isNotSuccessShaped() {
        val msg = WorkspaceRepository.initCompletionMessage(currentName = null, location = "/srv/ai/code")
        // 旧实现在这条路径打的是 INFO「工作区初始化完成，当前: null」—— 含「完成」、长得像成功，
        // 用户事后翻日志看不出初始化其实什么都没落定。本断言对旧文案必红。
        assertFalse(msg.contains("完成"))
        assertTrue(msg.contains("无可用工作区"))
        assertTrue(msg.contains("/srv/ai/code"))
    }

    @Test
    fun initCompletionMessage_withoutWorkspaceAndBlankRoot_saysRootIsNotConfigured() {
        val msg = WorkspaceRepository.initCompletionMessage(currentName = null, location = "")
        // 旧文案这里是「…根目录: 」（冒号后空着），看不出根目录是没配还是取不到。本断言对旧文案必红。
        assertFalse(msg.contains("完成"))
        assertTrue(msg.contains("未配置"))
    }

    @Test
    fun initCompletionMessage_withWorkspace_keepsNameAndRoot() {
        val msg = WorkspaceRepository.initCompletionMessage(currentName = "default", location = "/srv/ai/code")
        // 护栏（不是本次修复的红/绿证据）：成功路径不能被顺手降级 —— 旧文案同样是
        // 「工作区初始化完成，当前: default，根目录: …」，所以本条在旧实现上也是绿的，
        // 它防的是「把收尾日志一律改成 WARN/失败形状」这类改坏。
        assertTrue(msg.contains("完成"))
        assertTrue(msg.contains("default"))
        assertTrue(msg.contains("/srv/ai/code"))
    }
}
