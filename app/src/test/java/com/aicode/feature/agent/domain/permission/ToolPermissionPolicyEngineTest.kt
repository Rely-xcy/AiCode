package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权策略引擎的分支覆盖。规则加载经 mock 注入，聚焦 [ToolPermissionPolicyEngine.evaluate]
 * 的裁决逻辑：PLAN/AUTO 模式特判、DENY 优先、白名单/记忆规则、rm 高危删除防护与不可记忆降级。
 * 底层 [ShellCommandParser] / [BuiltInSafeCommands] 的解析细节由各自独立测试覆盖，不在此重复。
 */
class ToolPermissionPolicyEngineTest {

    private fun engine(
        vararg rules: PermissionRule,
        safetyDisabled: Boolean = false,
        projectRulesConfirmed: Boolean = true,
        globalRulesConfirmed: Boolean = true
    ): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        // projectRulesConfirmed=false 模拟「项目级规则读不到」（工作区未落定 / 读取失败）；
        // globalRulesConfirmed=false 模拟「全局级规则读不到」（全局文件读取 / 解析失败）；
        // 默认 true 即「已确认：读到规则，或确认没有规则」。
        coEvery { repo.loadEffectiveForCurrentProject() } returns
            EffectivePermissionRules(rules.toList(), projectRulesConfirmed, globalRulesConfirmed)
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        return ToolPermissionPolicyEngine(repo, safety)
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf("command" to JsonPrimitive(command))

    private fun terminal(action: String) = mapOf("action" to JsonPrimitive(action))

    // ── PLAN 模式：写/执行类工具一律拒绝 ─────────────────────────────

    @Test
    fun planMode_deniesWorkspaceWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertNotNull(r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN)
        // 无任何规则时按整工具 ASK（可记忆），而非 DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── AUTO 模式：放行但保留灾难性 rm 防护 ──────────────────────────

    @Test
    fun autoMode_allowsAnyTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_allowsBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止执行高危删除操作（根目录删除）") == true)
    }

    @Test
    fun autoMode_stillBlocksWorkspaceRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun autoMode_disabledSafety_allowsCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun disabledSafety_buildMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_deniedWhenWholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── DENY 优先于内置白名单 ───────────────────────────────────────

    @Test
    fun denyRule_overridesSafeWhitelist() = runTest {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 不可静态判定：必弹窗、不可记忆 ───────────────────────────────

    @Test
    fun unanalyzableCommand_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndUnsafe_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 项目级规则读不到（未落定 / 读取失败）：fail-closed ───────────────
    // projectRulesConfirmed=false 只意味着「读不到」，不等于「项目级没有规则」：
    // 合并结果里可能少一条项目级 DENY，所以不能凭全局 ALLOW 规则或白名单以外的放行口直接跑；
    // 但也不能把可证的只读调用一并挡住（否则远程没连上就什么都干不了）。

    @Test
    fun unconfirmedProjectRules_rememberedAllowRule_asksOnce() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW),
            projectRulesConfirmed = false
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        // 收紧前这里是 ALLOW：项目级若有一条 DENY 覆盖这条全局 ALLOW，那条 DENY 会被漏读，现在只能单次放行
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
        assertNotNull(r.askTitle)
    }

    @Test
    fun unconfirmedProjectRules_genericWholeAllowRule_asksOnce() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW),
            projectRulesConfirmed = false
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
        assertNotNull(r.askTitle)
    }

    @Test
    fun unconfirmedProjectRules_safeShellCommands_stillAutoAllowed() = runTest {
        val e = engine(projectRulesConfirmed = false)
        // 内置只读白名单是代码内置、不可篡改的，不因项目级规则读不到而弹窗（避免「远程没连上就啥都干不了」）
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git status -s"), AgentMode.BUILD).verdict
        )
    }

    @Test
    fun unconfirmedProjectRules_readOnlyGenericTool_stillAllowed() = runTest {
        val e = engine(
            PermissionRule("readFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW),
            projectRulesConfirmed = false
        )
        // readFile 能力只有 READ_WORKSPACE，不属危险调用：规则读不到也照旧放行
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "readFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun unconfirmedProjectRules_taskReadActions_stillAutoAllowed() = runTest {
        val e = engine(projectRulesConfirmed = false)
        // task read/list/send 的 effectiveCapabilities 为空集（见 TaskTool），不算有副作用的调用
        for (action in listOf("read", "list", "send")) {
            assertEquals(
                "task $action",
                ToolPermissionPolicyEngine.Verdict.ALLOW,
                e.evaluate(tool(), "task", terminal(action), AgentMode.BUILD).verdict
            )
        }
    }

    @Test
    fun unconfirmedProjectRules_globalDenyStillDenies() = runTest {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY),
            projectRulesConfirmed = false
        )
        // 收紧只针对「放行」侧：已读到的 DENY 仍优先于内置白名单
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun unconfirmedProjectRules_catastrophicRmStillDenied() = runTest {
        val e = engine(projectRulesConfirmed = false)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD).verdict
        )
    }

    @Test
    fun unconfirmedProjectRules_taskWholeDenyRule_stillDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY),
            projectRulesConfirmed = false
        )
        val r = e.evaluate(tool(), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 全局级规则读不到（读取 / 解析失败）：与项目级同一条边界 ─────────
    // 全局文件在 app 私有目录、不依赖工作区，来由只有读失败；但语义相同：读不到 ≠ 没有规则，
    // 不能凭别层规则放行可能有副作用的调用，而可证的只读调用不许误伤。

    @Test
    fun unconfirmedGlobalRules_rememberedAllowRule_asksOnce() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW),
            globalRulesConfirmed = false
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        // 收紧前这里是 ALLOW：全局层可能有一条 DENY 覆盖同层的这条 ALLOW 而没被读到
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
        assertNotNull(r.askTitle)
    }

    @Test
    fun unconfirmedGlobalRules_readOnlyCalls_stillAllowed() = runTest {
        val e = engine(
            PermissionRule("readFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW),
            globalRulesConfirmed = false
        )
        // 只读的不许误伤：内置只读白名单与只读工具照旧放行
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.READ_WORKSPACE), "readFile", emptyMap(), AgentMode.BUILD).verdict
        )
    }

    // ── rm 精细校验：无目标 / 递归 / 通配的规则不得放行 ───────────────

    @Test
    fun bareRmRule_doesNotAllowRmWithTarget() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // 规则无目标路径 → 不匹配；且 rm 单文件可记忆为完整前缀
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf("rm file.txt"), r.rememberablePatterns)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRule_allowsRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 提权：非 AUTO 模式下携带 elevate 将硬拒绝降级为一次性授权 ──────

    @Test
    fun catastrophicRm_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.askTitle)
    }

    @Test
    fun catastrophicRm_denyReasonHintsElevate() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_autoMode_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.AUTO
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 路径归一化：多种等价写法均需拦截，工作区子目录放行 ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(denied("rm -rf \${HOME}/foo"))
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── AUTO：无法静态判定的疑似破坏性命令保守拦截 ─────────────────

    @Test
    fun autoMode_unanalyzableDestructive_denied() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf \$(echo /etc)"), AgentMode.AUTO).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_unanalyzableBenign_allowed() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，AUTO 不豁免 ─────────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillDenies() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun shizuku_autoMode_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_autoMode_safetyDisabled_allows() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_buildMode_safetyDisabled_stillAsksWithoutRememberable() = runTest {
        val e = engine(safetyDisabled = true)
        // 开关仅解除 AUTO 豁免，不可记忆在 BUILD 下始终生效
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_planMode_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        // add 现在返回 Boolean（写没写进去），默认 stub 要与返回类型一致；relaxed 不顶用在这类显式 stub 上：
        // 旧的 `just runs` 只适用于 Unit 返回的函数。
        coEvery { repo.add(any(), any()) } returns true
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true))

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }
}