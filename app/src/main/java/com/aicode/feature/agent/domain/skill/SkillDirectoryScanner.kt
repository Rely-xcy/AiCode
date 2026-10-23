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

    /** 用批量读到的「相对路径 → 全文」组装技能；不再对每个目录单独列一次目录。 */
    private fun parseBulkFiles(files: Map<String, String>, root: String): List<Skill> {
        val base = root.trimEnd('/')
        return preferredInstructionFiles(files.keys).map { (dirRelative, fileName) ->
            val dirPath = if (dirRelative.isEmpty()) base else "$base/$dirRelative"
            val relativeFile = if (dirRelative.isEmpty()) fileName else "$dirRelative/$fileName"
            SkillParser.parseText(files.getValue(relativeFile), dirPath.substringAfterLast('/').ifBlank { dirPath })
                .copy(dirPath = dirPath)
        }.sortedBy { it.name.lowercase() }
    }

    private fun scanByProvider(provider: FileAccessProvider, root: String): List<Skill> {
        val base = root.trimEnd('/')
        return preferredInstructionFiles(provider.listFilesRecursive(root, MAX_DEPTH)).mapNotNull { (dirRelative, fileName) ->
            val dirPath = if (dirRelative.isEmpty()) base else "$base/$dirRelative"
            // 文件名在扫描阶段已经知道，交给解析器就省掉它再列一次目录的往返。
            SkillParser.parse(provider, dirPath, fileName)
        }.sortedBy { it.name.lowercase() }
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
