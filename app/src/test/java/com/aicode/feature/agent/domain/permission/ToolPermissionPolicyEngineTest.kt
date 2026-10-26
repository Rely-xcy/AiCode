package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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

    private val planModeDenyReason =
        "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。"

    /**
     * 弹窗标题与说明取自 `strings.xml`，用例只断言「给不给弹窗、能不能记忆、什么标题」，
     * 所以把引擎会读到的键都 stub 成真实中文（严格 mock 未 stub 会抛 MockKException）。
     * 注意两处断言依赖具体文案：rm 根目录删除的原文前缀，以及含 elevate 二字的重试提示。
     */
    private val context = mockk<Context>().apply {
        every { getString(R.string.permission_rules_unconfirmed_project_title) } returns "项目授权规则暂不可用（测试）"
        every { getString(R.string.permission_rules_unconfirmed_project_reason) } returns "项目级规则读不到，仅单次放行（测试）"
        every { getString(R.string.permission_rules_unconfirmed_global_title) } returns "全局授权规则暂不可用（测试）"
        every { getString(R.string.permission_rules_unconfirmed_global_reason) } returns "全局级规则读不到，仅单次放行（测试）"
        every { getString(R.string.permission_plan_mode_deny) } returns planModeDenyReason
        every { getString(R.string.permission_shizuku_not_rememberable) } returns
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"
        every { getString(R.string.permission_deny_tool_by_project_rule) } returns "该工具被项目权限规则策略禁止执行"
        every { getString(R.string.permission_deny_command_by_project_rule) } returns "该命令被项目权限规则策略禁止执行"
        every { getString(R.string.permission_elevation_title) } returns "高危操作提权确认"
        every { getString(R.string.permission_elevation_not_rememberable) } returns
            "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
        // 必须真的把 %1$s（拦截原因）与 %2$s（提权参数名）拼进去：真实资源就是这么展开的。
        // 改成固定串会把拦截原因连同其中的触发构造一起吞掉，断言就看不到原因里说了什么。
        every { getString(R.string.permission_deny_with_elevate_hint, *anyVararg()) } answers {
            val hintArgs = secondArg<Array<Any?>>()
            "${hintArgs[0]}。如确需执行，可在调用时加 `${hintArgs[1]}: true` 重试，系统将向用户请求授权。"
        }
        every { getString(R.string.permission_non_rememberable_capabilities) } returns
            "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
        every { getString(R.string.permission_rm_unrememberable) } returns
            "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
        every { getString(R.string.permission_rm_deny_root) } returns "安全防护：禁止执行高危删除操作（根目录删除）"
        every { getString(R.string.permission_rm_deny_relative) } returns
            "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        every { getString(R.string.permission_rm_deny_home) } returns "安全防护：禁止执行高危删除操作（用户目录删除）"
        every { getString(R.string.permission_rm_deny_workspace) } returns
            "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        every { getString(R.string.permission_rm_deny_tmp) } returns
            "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        every { getString(R.string.permission_rm_deny_system_dir) } returns
            "安全防护：禁止执行高危删除操作（系统关键目录删除）"
        every { getString(R.string.permission_rm_deny_unanalyzable) } returns
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"
    }

    /** 最近一次带格式参数的 getString 收到的参数，供断言拦截原因里回显了命中规则与触发片段。 */
    private val formatArgs = mutableListOf<String>()

    private fun appContext(): Context = mockk {
        every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
        // 带格式参数的文案（拦截原因回显命中规则 / 触发片段）：记录参数，返回资源 id 便于断言。
        every { getString(any(), *anyVararg()) } answers {
            formatArgs.clear()
            formatArgs += secondArg<Array<Any?>>().map { it.toString() }
            "resource:${firstArg<Int>()}"
        }
    }

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
        return ToolPermissionPolicyEngine(context, repo, safety, appContext())
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
    fun planMode_deniesWorkspaceWriteTool() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        // 引擎返回的是已解析的文案（PLAN 模式专用那条），不再回传 `resource:` 占位符。
        assertEquals(planModeDenyReason, r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN)
        // 无任何规则时按整工具 ASK（可记忆），而非 DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── AUTO 模式：放行但保留灾难性 rm 防护 ──────────────────────────

    @Test
    fun autoMode_allowsAnyTool() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_allowsBash() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_stillBlocksCatastrophicRm() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止执行高危删除操作（根目录删除）") == true)
    }

    @Test
    fun autoMode_stillBlocksWorkspaceRm() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun autoMode_disabledSafety_allowsCatastrophicRm() = runBlocking {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun disabledSafety_buildMode_stillBlocksCatastrophicRm() = runBlocking {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_deniedWhenWholeRuleDenies() = runBlocking {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── task 的 del（删除子会话及消息）不可逆：单次确认，不受 ALWAYS 记忆放行 ──

    @Test
    fun taskDeleteAction_asksUnrememberableEvenWithWholeAllow() = runTest {
        // 用户此前对 task 选过「始终允许」（create/stop 落下的记忆）——不得连带放行删除
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.MODIFY_SESSION_STATE), "task", terminal("del"), AgentMode.BUILD)

        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue("删除不可记忆，必须逐次确认", r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun taskDeleteAction_deniedWhenWholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.MODIFY_SESSION_STATE), "task", terminal("del"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── DENY 优先于内置白名单 ───────────────────────────────────────

    @Test
    fun denyRule_overridesSafeWhitelist() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 不可静态判定：必弹窗、不可记忆 ───────────────────────────────

    @Test
    fun unanalyzableCommand_asksWithoutRememberable() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndUnsafe_asks() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runBlocking {
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
    fun unconfirmedProjectRules_rememberedAllowRule_asksOnce() = runBlocking {
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
    fun unconfirmedProjectRules_genericWholeAllowRule_asksOnce() = runBlocking {
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
    fun unconfirmedProjectRules_safeShellCommands_stillAutoAllowed() = runBlocking {
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
    fun unconfirmedProjectRules_readOnlyGenericTool_stillAllowed() = runBlocking {
        val e = engine(
            PermissionRule("readFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW),
            projectRulesConfirmed = false
        )
        // readFile 能力只有 READ_WORKSPACE，不属危险调用：规则读不到也照旧放行
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "readFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun unconfirmedProjectRules_taskReadActions_stillAutoAllowed() = runBlocking {
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
    fun unconfirmedProjectRules_globalDenyStillDenies() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY),
            projectRulesConfirmed = false
        )
        // 收紧只针对「放行」侧：已读到的 DENY 仍优先于内置白名单
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun unconfirmedProjectRules_catastrophicRmStillDenied() = runBlocking {
        val e = engine(projectRulesConfirmed = false)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD).verdict
        )
    }

    @Test
    fun unconfirmedProjectRules_taskWholeDenyRule_stillDenies() = runBlocking {
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
    fun unconfirmedGlobalRules_rememberedAllowRule_asksOnce() = runBlocking {
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
    fun unconfirmedGlobalRules_readOnlyCalls_stillAllowed() = runBlocking {
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
    fun bareRmRule_doesNotAllowRmWithTarget() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // 规则无目标路径 → 不匹配；且 rm 单文件可记忆为完整前缀
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf("rm file.txt"), r.rememberablePatterns)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRule_allowsRecursiveRm() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runBlocking {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 提权：非 AUTO 模式下携带 elevate 将硬拒绝降级为一次性授权 ──────

    @Test
    fun catastrophicRm_elevate_asksUser() = runBlocking {
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
    fun catastrophicRm_denyReasonHintsElevate() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_autoMode_elevate_asksUser() = runBlocking {
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
    fun abnormalPathSpellings_denied() = runBlocking {
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
    fun workspaceSubdir_allowed() = runBlocking {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runBlocking {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── AUTO：无法静态判定的疑似破坏性命令保守拦截 ─────────────────

    @Test
    fun autoMode_unanalyzableDestructive_denied() = runBlocking {
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
    fun autoMode_unanalyzableBenign_allowed() = runBlocking {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_safetyDisabled_unanalyzable_allowed() = runBlocking {
        val e = engine(safetyDisabled = true)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，AUTO 不豁免 ─────────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runBlocking {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runBlocking {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillDenies() = runBlocking {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun shizuku_autoMode_asks() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_autoMode_safetyDisabled_allows() = runBlocking {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_buildMode_safetyDisabled_stillAsksWithoutRememberable() = runBlocking {
        val e = engine(safetyDisabled = true)
        // 开关仅解除 AUTO 豁免，不可记忆在 BUILD 下始终生效
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_planMode_denied() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runBlocking {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runBlocking {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeRuleDenies() = runBlocking {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 拦截原因回显：命中规则 / 触发片段 ──────────────────────────

    /**
     * DENY 只说「权限规则禁止」时，AI 不知道该改哪一段，只能整条换写法反复重试。
     * 回显命中规则与被拦段之后，一次就能定位。
     */
    @Test
    fun shellDeny_echoesMatchedRuleAndOffendingSegment() = runTest {
        val e = engine(PermissionRule("Bash", "git push", PermissionDecision.DENY))
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("ls && git push origin main"),
            AgentMode.BUILD
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertEquals("resource:${R.string.tool_command_rule_denied_detail}", r.denyReason)
        assertEquals(listOf("Bash(git push)", "git push origin main"), formatArgs)
    }

    /** AUTO 下不可静态判定 + 疑似破坏性：原因里要写明是哪个构造，AI 才知道怎么拆。 */
    @Test
    fun autoMode_unanalyzableDestructiveReasonNamesConstruct() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("rm -rf \$(pwd)/build"),
            AgentMode.AUTO
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason!!.contains("触发构造：命令替换 \$(...)"))
        assertTrue(r.denyReason!!.contains("elevate"))
    }

    /** 非 AUTO 下不可静态判定走弹窗：弹窗也要说明为什么不能「始终允许」。 */
    @Test
    fun buildMode_unanalyzableAskExplainsWhyNotRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("ls \$(pwd)"),
            AgentMode.BUILD
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertEquals("resource:${R.string.tool_command_unanalyzable_reason}", r.rememberDisabledReason)
        assertEquals(listOf("命令替换 \$(...)"), formatArgs)
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runBlocking {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        // add 现在返回 Boolean（写没写进去），默认 stub 要与返回类型一致；relaxed 不顶用在这类显式 stub 上：
        // 旧的 `just runs` 只适用于 Unit 返回的函数。
        coEvery { repo.add(any(), any()) } returns true
        val e = ToolPermissionPolicyEngine(context, repo, mockk(relaxed = true), appContext())

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }
}