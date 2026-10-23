package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"

    /**
     * 扫描 [root] 下顶层 `*.md`，每个文件一个定义。目录不存在时返回空列表；
     * 扫描过程异常（IO 失败、远程工作区未连接等）时降级为空列表并记日志，不向上抛。
     *
     * [bulk] 可用时一次拿到整根的文件全文，省掉逐文件的 SFTP 往返；不可用时回退逐文件读取。
     */
    fun scan(
        provider: FileAccessProvider,
        root: String,
        bulk: BulkFileSource = BulkFileSource.Disabled
    ): List<AgentDefinition> {
        // 批量读取只是加速路径：它出任何事都不能把异常抛给上层。
        val bulkFiles = runCatching { bulk.filesUnder(root) }.getOrNull()
        if (bulkFiles != null) {
            val parsed = runCatching { parseBulkFiles(bulkFiles, root) }.getOrElse { e ->
                FileLogger.w(TAG, "解析批量读取的子代理定义失败: $root", e)
                null
            }
            if (parsed != null) return parsed
        }
        return runCatching {
            if (!provider.isDirectory(root)) return@runCatching emptyList()
            val base = root.trimEnd('/')
            provider.listFiles(root)
                .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                .mapNotNull { entry -> AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
                .sortedBy { it.name.lowercase() }
        }.getOrElse { e ->
            FileLogger.w(TAG, "扫描子代理定义目录失败: $root", e)
            emptyList()
        }
    }

    /** 批量结果里只有顶层文件算定义（子目录里的 `*.md` 一律忽略，与逐文件路径一致）。 */
    private fun parseBulkFiles(files: Map<String, String>, root: String): List<AgentDefinition> {
        val base = root.trimEnd('/')
        return files.asSequence()
            .filter { (relative, _) -> !relative.contains('/') && relative.endsWith(".md", ignoreCase = true) }
            .mapNotNull { (relative, text) -> AgentDefinitionParser.parseText(text, "$base/$relative") }
            .sortedBy { it.name.lowercase() }
            .toList()
    }
}
