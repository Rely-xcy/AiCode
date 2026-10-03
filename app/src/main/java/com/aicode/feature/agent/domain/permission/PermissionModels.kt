package com.aicode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * 工具授权规则的作用域。scope 不存在规则对象里，而是由「存在哪个文件」隐含：
 * 项目文件（`.aicode/permissions.json`）或全局文件。管理界面「提升为全局」= 从项目文件删、往全局文件加。
 */
enum class PermissionScope { PROJECT, GLOBAL }

/**
 * 规则的判定方向。弹窗的「始终允许」只会产生 [ALLOW]；[DENY] 仅由权限配置文件（deny 列表）提供，
 * 评估时 DENY 跨 scope 优先于 ALLOW（安全优先：全局禁可挡项目允许）。
 */
enum class PermissionDecision { ALLOW, DENY }

/**
 * 授权弹窗的用户选择。
 * - [REJECT]：拒绝本次调用，不记忆。
 * - [ONCE]：仅放行本次，不记忆。
 * - [ALWAYS]：放行并记忆为规则（弹窗路径固定写入当前项目 scope）。
 */
enum class PermissionChoice { REJECT, ONCE, ALWAYS }

/**
 * 一条工具授权规则。
 *
 * @param toolName 适用的工具名，如 `Bash`、`writeFile`。
 * @param pattern 匹配模式。对 shell 命令是「命令前缀」，按 token 前缀匹配：子命令分发器记
 *   `git pull`（仅命中 `git pull ...`，不命中 `git clone`），普通程序记 `cat`/`ls`（命中其所有调用）；
 *   对非 shell 工具是通配 `*`（整工具匹配）。
 * @param decision 判定方向，默认 [PermissionDecision.ALLOW]。
 */
@Serializable
data class PermissionRule(
    val toolName: String,
    val pattern: String,
    val decision: PermissionDecision = PermissionDecision.ALLOW
) {
    companion object {
        /** 非 shell 工具的整工具匹配模式。 */
        const val WHOLE_TOOL = "*"

        /** 从紧凑格式字符串解析为 PermissionRule。
         *  格式示例：
         *  - `"Bash"` → toolName=Bash, pattern=*, ALLOW
         *  - `"Bash(git pull)"` → toolName=Bash, pattern="git pull", ALLOW
         */
        fun fromCompact(compact: String, decision: PermissionDecision = PermissionDecision.ALLOW): PermissionRule {
            val parenIdx = compact.indexOf('(')
            return if (parenIdx >= 0 && compact.endsWith(')')) {
                val toolName = compact.substring(0, parenIdx)
                val pattern = compact.substring(parenIdx + 1, compact.length - 1)
                PermissionRule(toolName, pattern, decision)
            } else {
                PermissionRule(compact, WHOLE_TOOL, decision)
            }
        }
    }

    /** 转为紧凑格式字符串。
     *  示例：
     *  - toolName=Bash, pattern=* → `"Bash"`
     *  - toolName=Bash, pattern="git pull" → `"Bash(git pull)"`
     */
    fun toCompact(): String =
        if (pattern == WHOLE_TOOL) toolName else "$toolName($pattern)"
}

// ── 权限文件顶层结构 ──────────────────────────────────────────────

/**
 * 权限规则 JSON 文件的顶层结构。
 *
 * 示例：
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 */
@Serializable
data class PermissionFile(
    val permissions: PermissionSection = PermissionSection()
) {
    @Serializable
    data class PermissionSection(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    companion object {
        val EMPTY = PermissionFile()
    }
}

// PermissionFile 和 List<PermissionRule> 转换

/** 从 [PermissionFile] 解析为规则列表。 */
fun PermissionFile.toRuleList(): List<PermissionRule> =
    permissions.allow.map { PermissionRule.fromCompact(it, PermissionDecision.ALLOW) } +
            permissions.deny.map { PermissionRule.fromCompact(it, PermissionDecision.DENY) }

/** 从规则列表构建 [PermissionFile]。 */
fun List<PermissionRule>.toPermissionFile(): PermissionFile {
    val (allow, deny) = partition { it.decision == PermissionDecision.ALLOW }
    return PermissionFile(
        permissions = PermissionFile.PermissionSection(
            allow = allow.map { it.toCompact() },
            deny = deny.map { it.toCompact() }
        )
    )
}

// ── 评估快照 ──────────────────────────────────────────────────────

/**
 * 工具授权评估用的规则快照：能用的规则 + 「项目级 / 全局级规则是否确认可读」。
 *
 * 三件事必须分开，**「读不到」不等于「没有规则」**：
 *
 * - [projectRulesConfirmed] = true：工作区已落定且项目级文件有结论——读到规则，或文件不存在
 *   （= 确认没有项目规则，[rules] 里自然没有项目级条目）。评估方按常规判定。
 * - [projectRulesConfirmed] = false：工作区未落定，或项目级文件读取/解析失败。此时 [rules] 只含全局规则，
 *   **不得**当成「项目级没有规则」：项目级 DENY 会被漏读，判定从「需授权」退化成「直接执行」。
 * - [globalRulesConfirmed] = false：全局级文件读取/解析失败，同理不得当成「全局没有规则」
 *   （全局文件在 app 私有目录，不依赖工作区，所以只有读失败这一种来由）。
 *
 * 由 [PermissionRulesRepository.loadEffectiveForCurrentProject] 产出，供
 * [ToolPermissionPolicyEngine] 按同一套 fail-closed 边界处理：两面都只收紧「可能造成副作用」的调用。
 */
data class EffectivePermissionRules(
    val rules: List<PermissionRule>,
    val projectRulesConfirmed: Boolean,
    val globalRulesConfirmed: Boolean = true
)
