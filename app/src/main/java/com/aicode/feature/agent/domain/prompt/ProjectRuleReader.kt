package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper

/** 项目规则正文：全局（`~/.aicode`）与工作区（`~/workspace`）两组，未找到时为 null。 */
internal data class ProjectRules(val global: String?, val project: String?)

/**
 * 读取项目规则：全局目录与工作区各取 `AGENTS.md`，缺失时回落 `CLAUDE.md`。
 *
 * 一律经 [FileAccessProvider] 用容器路径读取，而不是构造 [java.io.File]：远程模式下工作区在服务器上，
 * 本机那份 `File(projectRoot)` 永远不存在，项目规则会**静默失效**（既不报错也不进提示词）。
 * 走 [FileAccessProvider] 与其它文件读取同口径——本地落到宿主目录，远程经 SFTP 落到服务器。
 *
 * 读失败（断线、权限、超限）**不进缓存**，只沿用上一次成功结果：把失败缓存成「没有规则」会让
 * 项目规则从此永久消失，而规则是模型必须遵守的硬约束。
 */
internal class ProjectRuleReader(private val fileAccess: FileAccessProvider) {

    /** 缓存与签名必须整体替换：分两步写时，并发调用可能读到「A 工作区的规则 + B 工作区的签名」。 */
    private data class RuleCache(val signature: String, val rules: ProjectRules)

    @Volatile
    private var cache: RuleCache? = null

    /** 某个目录下规则文件的探测结果。 */
    private sealed interface Lookup {
        /** 找到规则文件。[lastModified] 只用于缓存签名。 */
        class Found(val path: String, val lastModified: Long) : Lookup
        /** 目录可访问，但没有规则文件——这是「确认没有规则」。 */
        object Absent : Lookup
        /** 探测过程本身失败（断线等）——不能当成「没有规则」。 */
        object Unreadable : Lookup
    }

    /**
     * @param hasProject 当前是否有工作区；没有时只读全局规则。
     * @return 两组正文；两边都没有规则时返回 null。
     */
    fun read(hasProject: Boolean): ProjectRules? {
        val global = resolveRuleFile(GLOBAL_RULE_DIR)
        val project = if (hasProject) resolveRuleFile(WorkspacePathMapper.CONTAINER_ROOT) else Lookup.Absent
        if (global is Lookup.Unreadable || project is Lookup.Unreadable) {
            FileLogger.w(TAG, "项目规则读取失败，沿用上一次成功结果")
            return cache?.rules
        }

        val signature = signatureOf(global) + "|" + signatureOf(project)
        cache?.let { if (it.signature == signature) return it.rules }

        val rules = try {
            ProjectRules(
                global = (global as? Lookup.Found)?.let(::readRule),
                project = (project as? Lookup.Found)?.let(::readRule)
            )
        } catch (e: Exception) {
            // 正文读取失败与探测失败同规：不写缓存，不让一次断线把规则钉成空。
            FileLogger.w(TAG, "项目规则正文读取失败: ${e.message}")
            return cache?.rules
        }
        if (rules.global == null && rules.project == null) return null
        cache = RuleCache(signature, rules)
        return rules
    }

    private fun signatureOf(lookup: Lookup): String = when (lookup) {
        is Lookup.Found -> "${lookup.path}:${lookup.lastModified}"
        Lookup.Absent -> "none"
        Lookup.Unreadable -> "error"
    }

    private fun resolveRuleFile(dir: String): Lookup = try {
        var found: Lookup.Found? = null
        for (name in RULE_FILE_NAMES) {
            val path = "$dir/$name"
            if (fileAccess.isFile(path)) {
                found = Lookup.Found(path, fileAccess.lastModified(path))
                break
            }
        }
        found ?: Lookup.Absent
    } catch (_: Exception) {
        Lookup.Unreadable
    }

    private fun readRule(found: Lookup.Found): String? {
        val text = fileAccess.readFile(found.path)
        if (text.isBlank()) return null
        return if (text.length > MAX_RULE_CHARS) {
            (text.take(MAX_RULE_CHARS) + "\n…（规则过长，已截断）").trim()
        } else {
            text.trim()
        }
    }

    private companion object {
        const val TAG = "ProjectRuleReader"

        /** 全局 AI 配置目录的容器路径：本地是 `/root/.aicode`，远程是服务器用户 home 下的 `.aicode`。 */
        const val GLOBAL_RULE_DIR = "~/.aicode"

        /** `AGENTS.md` 优先、`CLAUDE.md` 兜底。 */
        val RULE_FILE_NAMES = listOf("AGENTS.md", "CLAUDE.md")

        const val MAX_RULE_CHARS = 32_000
    }
}
