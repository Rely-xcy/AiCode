package com.aicode.feature.agent.domain.permission

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 评估顺序（Bash）：
 *   1) 任一段命中 DENY 规则 → DENY（即便命令不可静态判定，DENY 也对已解析的段生效；可覆盖内置白名单）；
 *   2) 命令不可静态判定（含命令替换/分组/绝对路径重定向等）→ ASK，且不可记忆；
 *   3) 所有段都命中内置安全白名单（ls/git status 等只读命令，见 [BuiltInSafeCommands]）→ ALLOW；
 *   4) 项目级规则读不到 → ASK，且不可记忆（见下）；
 *   5) 所有段都命中已记忆的 ALLOW 规则 → ALLOW；
 *   6) 否则 → ASK，可记忆前缀为各段的「程序名」或「程序名+子命令」（对 git/npm 等子命令分发器，
 *      记 `git pull` 而非 `git`，使不同子命令各自独立授权）。
 * 非 shell 的 ASK 工具按整工具（pattern=`*`）匹配。
 *
 * **规则文件可能暂时读不到**（项目级：工作区未落定 / 读取失败；全局级：读取或解析失败）。此时不能按
 * 「这一层没有规则」判定：合并结果里可能缺一条 DENY，判定会从「需授权」退化成「直接执行」。所以规则读不到时，
 * 对**可能造成副作用**的调用一律降级为一次性授权（不可记忆）；只读调用不受影响——shell 侧只有
 * 「全部段命中内置只读白名单」（第 3 步）才继续放行，非 shell 侧按 [isDangerousTool] 区分，
 * 避免出现「远程没连上就什么都干不了」。两层共用同一条边界，弹窗文案指明是哪一层读不到。
 *
 * 规则读不到期间的 ASK 一律不给「始终允许」（[unconfirmedRulesAsk] 的 rememberablePatterns 为空）：
 * 「始终允许」写的就是读不到的那一层，要么没就绪（写入被 [PermissionRulesRepository.add] 静默跳过，
 * 用户以为记住了、磁盘上什么都没写），要么是解析失败后拿到的空表（写进去会把原有规则覆盖掉）。
 * 这类兜底 ASK 同时带上 [EvalResult.rulesUnconfirmed]，调用方据此不让工具自己声明的自动批准
 * （见 StatefulAgentWorkflow 的权限闸门）把它短路掉。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：无论内置白名单或已记忆规则，一律 ASK 且不可记忆；
 * AUTO 模式也不自动放行（除非已开启「禁用安全拦截」）。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository
) {
    private companion object {
        const val TAG = "ToolPermission"

        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"

        /**
         * 规则读不到（项目级：工作区未落定 / 读取失败；全局级：读取或解析失败）时的弹窗标题与单次放行说明。
         * 命中时一律 ASK、不可记忆：无法确认那一层有没有 DENY，也不能让「始终允许」写进一个
         * 还没就绪、或解析失败后只剩空表的那一层。
         */
        const val ASK_TITLE_PROJECT_RULES_UNCONFIRMED = "项目授权规则暂不可用"
        const val REASON_PROJECT_RULES_UNCONFIRMED =
            "项目级授权规则当前读不到（工作区未就绪或读取失败），无法确认是否存在项目级 DENY 规则；" +
                "为避免绕过项目授权，本次仅支持单次放行，不可记忆"

        const val ASK_TITLE_GLOBAL_RULES_UNCONFIRMED = "全局授权规则暂不可用"
        const val REASON_GLOBAL_RULES_UNCONFIRMED =
            "全局级授权规则当前读不到（文件读取或解析失败），无法确认是否存在全局 DENY 规则；" +
                "为避免绕过全局授权，本次仅支持单次放行，不可记忆"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * 提权参数：非 AUTO 模式下，命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "安全防护：禁止执行高危删除操作（根目录删除）"
        const val REASON_RM_RELATIVE = "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        const val REASON_RM_HOME = "安全防护：禁止执行高危删除操作（用户目录删除）"
        const val REASON_RM_WORKSPACE = "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        const val REASON_RM_TMP = "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        const val REASON_RM_SYSTEM_DIR = "安全防护：禁止执行高危删除操作（系统关键目录删除）"

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        const val REASON_UNANALYZABLE_DESTRUCTIVE =
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     * @param rulesUnconfirmed 本次 ASK 是否为「规则读不到」的兜底（见 [unconfirmedRulesAsk]）。
     *   为 true 时工具自己声明的 [com.aicode.feature.agent.domain.tool.ToolPermissionPolicy.AUTO_APPROVE]
     *   不得短路掉这次询问：那正是无法确认有没有 DENY 的时刻，直接跑等于把那一层规则整个旁路。
     *   仅由 [unconfirmedRulesAsk] 置为 true，故 AUTO 模式下恒为 false——AUTO 模式的
     *   放行发生在读规则之前（见 [evaluate]），AUTO 语义不受影响。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        val rulesUnconfirmed: Boolean = false
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在计划模式下仅调用只读工具探索代码，不要尝试修改文件或执行命令。")
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.AUTO) {
            // AUTO 模式放行所有权限；灾难性 rm 防护（根目录/系统目录删除）默认保留，
            // 可在「工具授权」设置中关闭（禁用安全拦截）。遭遇拦截时仍可凭 elevate 参数提权重试。
            val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
            if (!safetyDisabled && isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    checkCatastrophicRm(analysis.segments)?.let { return elevationOrDeny(it, args) }
                    // 命令替换/子 shell/绝对路径重定向等无法静态判定的构造，删除目标不可知；
                    // 若同时疑似破坏性，宁可拦下（可提权），避免绕过安全防护。
                    if (!analysis.analyzable && looksDestructive(command)) {
                        return elevationOrDeny(REASON_UNANALYZABLE_DESTRUCTIVE, args)
                    }
                }
            }
            // Shizuku 直接操作宿主系统，AUTO 下也不自动放行，仍需逐次确认（除非已开启「禁用安全拦截」）。
            if (toolName == SHIZUKU_TOOL && !safetyDisabled) {
                return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
            }
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 规则快照：项目级与全局级都可能读不到（见 [EffectivePermissionRules]），
        // 任一层读不到都不能当作「那一层没有规则」——见 [unconfirmedRulesAsk]。
        val effective = rulesRepo.loadEffectiveForCurrentProject()
        val unconfirmedLayer = when {
            !effective.projectRulesConfirmed -> UnconfirmedLayer.PROJECT
            !effective.globalRulesConfirmed -> UnconfirmedLayer.GLOBAL
            else -> null
        }

        // task 只读动作（read/list）：不放行 DENY 规则，其余直接自动放行（不弹窗）。
        // read/list/send 的 effectiveCapabilities 为空集（见 TaskTool），不属「可能造成副作用」的调用，
        // 故项目级规则读不到时也不收紧。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) {
                val rules = effective.rules.filter { it.toolName == toolName }
                val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
                if (whole.any { it.decision == PermissionDecision.DENY }) {
                    return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
                }
                return EvalResult(Verdict.ALLOW, emptyList())
            }
        }

        val rules = effective.rules.filter { it.toolName == toolName }
        return if (isShellTool(toolName, args)) {
            evaluateShell(
                rules = rules,
                args = args,
                forceAsk = toolName == SHIZUKU_TOOL,
                unconfirmedLayer = unconfirmedLayer
            )
        } else {
            evaluateGeneric(
                rules = rules,
                capabilities = capabilities,
                // 只对可能造成副作用的调用收紧：只读工具（readFile/viewImage/webfetch 等）规则读不到时
                // 仍按已有规则判定，避免「远程没连上就什么都干不了」。
                dangerous = isDangerousTool(toolName, args, capabilities),
                unconfirmedLayer = unconfirmedLayer
            )
        }
    }

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 该命令是否疑似破坏性（仅在命令不可静态判定时使用）：含输出重定向、`-delete`
     * 或常见破坏性程序名。宁可多问，不误放。
     */
    private fun looksDestructive(command: String): Boolean =
        command.contains('>') || command.contains("-delete") || DESTRUCTIVE_PROGRAM.containsMatchIn(command)

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "高危操作提权确认",
                rememberDisabledReason = "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason。如确需执行，可在调用时加 `$ELEVATE_ARG: true` 重试，系统将向用户请求授权。"
            )
        }

    /**
     * 读不到规则的那一层。项目级与全局级共用同一条收紧边界，只差弹窗文案（得告诉用户是哪一层不可用）；
     * 两层同时读不到时先报项目级（工作区未落定是最常见的来由）。
     */
    private enum class UnconfirmedLayer { PROJECT, GLOBAL }

    /**
     * 规则读不到（工作区未落定 / 项目文件或全局文件读取、解析失败）时的兜底判定：
     * 无法确认那一层是否存在 DENY 规则，故不放行本次调用，只给一次性授权且不可记忆
     * （此时「始终允许」写那一层也可能落不下去、或把原文件覆盖成空表）。
     * 只用于**可能造成副作用**的调用；只读调用不受影响，见 [evaluate]。
     */
    private fun unconfirmedRulesAsk(layer: UnconfirmedLayer): EvalResult = when (layer) {
        UnconfirmedLayer.PROJECT -> EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            askTitle = ASK_TITLE_PROJECT_RULES_UNCONFIRMED,
            rememberDisabledReason = REASON_PROJECT_RULES_UNCONFIRMED,
            rulesUnconfirmed = true
        )
        UnconfirmedLayer.GLOBAL -> EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            askTitle = ASK_TITLE_GLOBAL_RULES_UNCONFIRMED,
            rememberDisabledReason = REASON_GLOBAL_RULES_UNCONFIRMED,
            rulesUnconfirmed = true
        )
    }

    /**
     * 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。
     *
     * @return 是否**全部**写成功。false 表示至少一条没落到项目文件里：工作区在弹窗挂起期间变得未就绪
     *   （[PermissionRulesRepository.add] 拿不到当前工作区时会静默跳过），或者写盘本身失败。
     *   调用方必须把这个结果告诉用户——用户点的是「始终允许」，没记住却装作记住了比直接拒绝更坑。
     *   逐条尝试、不提前返回：一条失败不影响其余模式照常写入。
     */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope): Boolean {
        var allWritten = true
        patterns.distinct().forEach { pattern ->
            // 写盘异常（IO、序列化）不应把整轮对话带崩：本次调用已经批准，只是没记住。
            // 取消要原样抛出，否则中途停下的这一轮会被当成「写入失败」继续往下跑。
            val written = try {
                rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                FileLogger.w(TAG, "记忆授权规则失败: $toolName $pattern", error)
                false
            }
            if (!written) allWritten = false
        }
        return allWritten
    }

    /**
     * 非 shell 工具的整工具判定。
     *
     * @param dangerous 本次调用是否可能造成副作用（见 [isDangerousTool]）。
     * @param unconfirmedLayer 读不到规则的那一层（见 [EffectivePermissionRules]）；非 null 时
     *   一律不给「始终允许」：那一层写不进去，且无法确认有没有该层 DENY 覆盖别的层的 ALLOW，
     *   故只读调用按已有规则判定、危险调用降级为一次性授权，两条路都落到 [unconfirmedRulesAsk]。
     *   两个参数都无默认值——调用点必须显式表态，避免漏传后静默 fail-open。
     */
    private fun evaluateGeneric(
        rules: List<PermissionRule>,
        capabilities: Set<ToolCapability>,
        dangerous: Boolean,
        unconfirmedLayer: UnconfirmedLayer?
    ): EvalResult {
        val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
        if (whole.any { it.decision == PermissionDecision.DENY }) return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
        if (dangerous && unconfirmedLayer != null) return unconfirmedRulesAsk(unconfirmedLayer)
        if (whole.any { it.decision == PermissionDecision.ALLOW }) return EvalResult(Verdict.ALLOW, emptyList())
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        // 走到这里仍可能是「规则读不到」：只读工具不受上面那道收紧影响，但同样不能给「始终允许」。
        if (unconfirmedLayer != null) return unconfirmedRulesAsk(unconfirmedLayer)
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    /**
     * shell 工具的指令级判定（编号见类文档）。
     *
     * @param unconfirmedLayer 读不到规则的那一层（见 [EffectivePermissionRules]）；非 null 时
     *   只有「全部段命中内置只读白名单」才自动放行，其余一律降级为一次性授权。
     */
    private fun evaluateShell(
        rules: List<PermissionRule>,
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        unconfirmedLayer: UnconfirmedLayer?
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)
        val allow = rules.filter { it.decision == PermissionDecision.ALLOW }
        val deny = rules.filter { it.decision == PermissionDecision.DENY }

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权。
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return elevationOrDeny(catastrophicReason, args)
        }

        // 1) DENY 优先（含对内置安全白名单的覆盖）：任一段命中 DENY 即拒。
        if (analysis.segments.any { seg -> deny.any { ShellCommandParser.matches(it.pattern, seg) } }) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "该命令被项目权限规则策略禁止执行")
        }

        // 2) Shizuku 高危工具：无论内置白名单或已记忆规则，一律弹窗且不可记忆。
        if (forceAsk) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 3) 不可静态判定 → 必须弹窗、不可记忆（内置白名单也不适用）。
        if (!analysis.analyzable) return EvalResult(Verdict.ASK, emptyList())

        // 4) 内置安全白名单：每段都命中安全前缀（ls/git status 等）→ 自动放行，不弹窗。
        //    这是代码内置、不可篡改的只读清单，故项目级规则读不到时（第 5 步）仍适用。
        if (analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 5) 规则读不到（项目级或全局级）→ 不能放行：合并结果里可能缺一条 DENY。
        //    能走到这里说明至少有一段不在内置只读白名单内（可证的只读命令已在第 4 步放行），
        //    故一律降级为一次性授权且不可记忆。
        if (unconfirmedLayer != null) return unconfirmedRulesAsk(unconfirmedLayer)

        // 6) 已记忆的 ALLOW：每段都命中 → 放行。
        // 对 rm 命令进行精细化校验：避免存量或宽泛的 "rm"/"rm -rf" 无目标规则放行高风险删除
        val allAllowed = analysis.segments.isNotEmpty() &&
            analysis.segments.all { seg ->
                val rmInfo = ShellCommandParser.parseRmInfo(seg)
                allow.any { rule ->
                    if (!ShellCommandParser.matches(rule.pattern, seg)) {
                        false
                    } else if (rmInfo.isRm) {
                        val ruleRmInfo = ShellCommandParser.parseRmInfo(rule.pattern.split(Regex("\\s+")))
                        ruleRmInfo.targetPaths.isNotEmpty() && (!rmInfo.isRecursive || ruleRmInfo.isRecursive)
                    } else {
                        true
                    }
                }
            }
        if (allAllowed) return EvalResult(Verdict.ALLOW, emptyList())

        // 7) 否则弹窗，可记忆前缀（子命令分发器记 程序名+子命令）。
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        if (hasRmUnrememberable) {
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
            )
        }

        val rememberable = analysis.segments.mapNotNull { ShellCommandParser.rememberablePrefix(it) }.distinct()
        return EvalResult(Verdict.ASK, rememberable)
    }
}
