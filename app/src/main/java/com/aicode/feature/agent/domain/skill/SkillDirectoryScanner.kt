package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。远程模式另可传
 * [BulkFileSource]：一次命令取回整根的文件全文，省掉逐目录 / 逐文件的 SFTP 往返。
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /** 汇总日志里最多列这么多个失败路径（每个失败本身已经单独带路径打过一行）。 */
    private const val MAX_LOGGED_FAILURES = 5

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表；读取失败（IO 异常、远程未连接）也降级为空列表并记日志——
     * 只该给不关心成败的调用方用，会把「读不到」当成「没有技能」的调用方请用 [scanOrNull]。
     */
    fun scan(provider: FileAccessProvider, root: String, bulk: BulkFileSource = BulkFileSource.Disabled): List<Skill> =
        scanOrNull(provider, root, bulk) ?: emptyList()

    /**
     * 同 [scan]，但读取失败时返回 null（目录不存在仍是空列表）：上层据此避免把一次读失败
     * 缓存成「本会话没有技能」。
     */
    fun scanOrNull(
        provider: FileAccessProvider,
        root: String,
        bulk: BulkFileSource = BulkFileSource.Disabled
    ): List<Skill>? {
        val outcomes = collect(provider, root, bulk) ?: return null
        return summarize(outcomes, root)
    }

    /** 收集本次扫描的全部解析结果；读失败（IO、远程未连接）返回 null。 */
    private fun collect(
        provider: FileAccessProvider,
        root: String,
        bulk: BulkFileSource
    ): List<SkillParser.ParseOutcome>? {
        // 批量读取只是加速路径：它出任何事都不能把异常抛给上层（提示词构建 / 设置页）。
        val bulkFiles = runCatching { bulk.filesUnder(root) }.getOrNull()
        if (bulkFiles != null) {
            val parsed = runCatching { parseBulkFiles(bulkFiles, root) }.getOrElse { e ->
                FileLogger.w(TAG, "解析批量读取的技能内容失败: $root", e)
                null
            }
            if (parsed != null) return parsed
        }
        return runCatching { scanByProvider(provider, root) }.getOrElse { e ->
            FileLogger.w(TAG, "扫描技能目录失败: $root", e)
            null
        }
    }

    /**
     * 按名称排序并汇总本次扫描的 frontmatter 失败数：单个失败已在解析器里带路径打过日志，
     * 这里补一行总数，否则要翻整天的日志逐条数才知道坏了几个。
     */
    private fun summarize(outcomes: List<SkillParser.ParseOutcome>, root: String): List<Skill> {
        val failed = outcomes.filter { it.frontmatterFellBack }
        if (failed.isNotEmpty()) {
            val shown = failed.take(MAX_LOGGED_FAILURES)
                .joinToString(", ") { it.skill.dirPath ?: it.skill.name }
            FileLogger.w(
                TAG,
                "扫描 $root: 共 ${outcomes.size} 个技能，${failed.size} 个 frontmatter 解析失败（已按行兜底）: $shown"
            )
        }
        return outcomes.map { it.skill }.sortedBy { it.name.lowercase() }
    }

    /** 用批量读到的「相对路径 → 全文」组装技能；不再对每个目录单独列一次目录。 */
    private fun parseBulkFiles(files: Map<String, String>, root: String): List<SkillParser.ParseOutcome> {
        val base = root.trimEnd('/')
        return preferredInstructionFiles(files.keys).map { (dirRelative, fileName) ->
            val dirPath = if (dirRelative.isEmpty()) base else "$base/$dirRelative"
            val relativeFile = if (dirRelative.isEmpty()) fileName else "$dirRelative/$fileName"
            val outcome = SkillParser.parseTextWithStatus(
                files.getValue(relativeFile),
                dirPath.substringAfterLast('/').ifBlank { dirPath },
                "$base/$relativeFile"
            )
            outcome.copy(skill = outcome.skill.copy(dirPath = dirPath))
        }
    }

    private fun scanByProvider(provider: FileAccessProvider, root: String): List<SkillParser.ParseOutcome> {
        val base = root.trimEnd('/')
        return preferredInstructionFiles(provider.listFilesRecursive(root, MAX_DEPTH)).mapNotNull { (dirRelative, fileName) ->
            val dirPath = if (dirRelative.isEmpty()) base else "$base/$dirRelative"
            // 文件名在扫描阶段已经知道，交给解析器就省掉它再列一次目录的往返。
            SkillParser.parseWithStatus(provider, dirPath, fileName)
        }
    }

    /**
     * 每个目录挑一个指令文件：SKILL.md 优先、其次 CLAUDE.md（兼容只用 CLAUDE.md 的技能），
     * 名称匹配忽略大小写。返回「相对目录 → 文件名」，相对目录为空串表示根目录本身。
     */
    private fun preferredInstructionFiles(paths: Collection<String>): Map<String, String> {
        val chosen = LinkedHashMap<String, String>()
        for (path in paths) {
            val name = path.substringAfterLast('/')
            val isSkillFile = name.equals(SKILL_FILE, ignoreCase = true)
            if (!isSkillFile && !name.equals(CLAUDE_FILE, ignoreCase = true)) continue
            val dir = path.substringBeforeLast('/', "")
            val existing = chosen[dir]
            // 同一目录先撞上 CLAUDE.md、后撞上 SKILL.md 时要换成后者，保持「SKILL.md 优先」的选择顺序。
            if (existing != null && existing.equals(SKILL_FILE, ignoreCase = true)) continue
            chosen[dir] = name
        }
        return chosen
    }
}
