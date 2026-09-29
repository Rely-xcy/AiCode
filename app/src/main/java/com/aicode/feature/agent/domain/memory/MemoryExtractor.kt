package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长期记忆抽取：把一段对话里关于用户的稳定结论抽出来写进记忆库。
 *
 * 抽出来单独成类，是因为有两个入口需要它，而且它们抽的是同一类东西：
 * - [com.aicode.feature.agent.domain.engine.modules.MemoryModule]：一轮结束后按轮次归约；
 * - [com.aicode.feature.agent.domain.engine.modules.CompactionModule]：折叠历史之前先捞一遍
 *   （那段历史马上要离开上下文，不捞就等于永久丢失）。
 *
 * 抽取用的模型由调用方决定：两个入口都传自己那条通道（记忆归约用一次性调用、折叠用压缩专用模型），
 * 与主对话模型解耦——抽取是「审代码」，主对话是「写业务」，不该同一个人干。
 */
@Singleton
class MemoryExtractor @Inject constructor(
    private val memoryRepository: MemoryRepository
) {

    /**
     * @param complete 一次性模型调用能力：给 user prompt，返回模型输出（system prompt 由调用方按
     *   [PROMPT_FILE] 解析好，保证两个入口用的是同一份抽取提示词）。
     * @return 写入条数；没有可记的、解析失败、模型返回空都返回 0。
     */
    suspend fun extract(
        projectRoot: String,
        history: List<AgentMessage>,
        /** 写入来源标记（auto-distill / pre-fold），落盘到 frontmatter 便于日后区分与治理。 */
        source: String,
        complete: suspend (userPrompt: String) -> String?
    ): Int {
        if (history.isEmpty()) return 0
        val existing = runCatching { memoryRepository.listMemories(projectRoot) }
            .getOrDefault(emptyList())
            .filter { it.kind == MemoryKind.PROFILE }

        val raw = complete(buildUserPrompt(history, existing)) ?: return 0
        val entries = parseEntries(raw)
        if (entries.isEmpty()) return 0

        entries.forEach { entry ->
            // 抽出来的都是「关于用户」的稳定结论，跨项目通用，因此统一写全局。
            memoryRepository.saveMemory(
                name = entry.name,
                description = entry.description,
                content = entry.content,
                scope = MemoryScope.GLOBAL,
                projectRoot = projectRoot,
                kind = MemoryKind.PROFILE,
                source = source
            )
        }
        FileLogger.i(TAG, "抽取长期记忆 ${entries.size} 条: ${entries.joinToString { it.name }}")
        return entries.size
    }

    /** 交给模型的输入：已有长期记忆（防重复）+ 本轮对话（截断，控制 token）。 */
    private fun buildUserPrompt(history: List<AgentMessage>, existing: List<Memory>): String =
        buildString {
            appendLine("Existing long-term memories (same name = replaces the old entry):")
            if (existing.isEmpty()) {
                appendLine("(none)")
            } else {
                existing.forEach { appendLine("- ${it.name}: ${it.description}") }
            }
            appendLine()
            appendLine("Transcript:")
            history.takeLast(TRANSCRIPT_MESSAGES).forEach { appendLine(renderMessage(it)) }
        }

    private fun renderMessage(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> "user: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.AssistantMessage -> "assistant: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.ToolResultMessage ->
            "tool(${message.toolName}): ${(message.modelResult ?: message.result).take(MAX_TOOL_CHARS)}"
    }

    /**
     * 解析模型输出：按 `name:` / `description:` / `content:` 标签逐行读，不要求 JSON。
     *
     * 为什么不要求 JSON：模型输出 JSON 的失败率明显更高——漏引号、多逗号、把换行写进字符串、
     * 前后多一段说明文字，任何一处都会让整个数组解析失败，这一批记忆就白抽了。
     * 标签格式容错得多：空行、`---` 分隔、代码块围栏、中英文冒号、行首项目符号都能吃掉，
     * 单条写坏了也只丢那一条。
     */
    fun parseEntries(raw: String): List<DistilledEntry> {
        val entries = mutableListOf<DistilledEntry>()
        var name = ""
        var description = ""
        val content = StringBuilder()

        fun flush() {
            if (name.isNotBlank() && content.isNotBlank()) {
                entries.add(DistilledEntry(name.trim(), description.trim(), content.toString().trim()))
            }
            name = ""
            description = ""
            content.clear()
        }

        raw.lines().forEach { line ->
            val cleaned = line.trim().removePrefix("-").removePrefix("*").trim().trim('`').trim()
            when {
                cleaned.isEmpty() || cleaned == "---" -> Unit

                NAME_LABEL.matches(cleaned) -> {
                    flush()
                    name = NAME_LABEL.matchEntire(cleaned)?.groupValues?.get(1).orEmpty()
                }

                DESCRIPTION_LABEL.matches(cleaned) ->
                    description = DESCRIPTION_LABEL.matchEntire(cleaned)?.groupValues?.get(1).orEmpty()

                CONTENT_LABEL.matches(cleaned) ->
                    content.appendLine(CONTENT_LABEL.matchEntire(cleaned)?.groupValues?.get(1).orEmpty())

                // 多行 content：从属于上一条 name
                name.isNotBlank() -> content.appendLine(cleaned)
            }
        }
        flush()

        if (entries.isNotEmpty()) return entries.take(MAX_ENTRIES)
        // 模型万一还是吐了 JSON：走一次旧解析，不白白丢掉这一批
        return parseJsonFallback(raw).take(MAX_ENTRIES)
    }

    private fun parseJsonFallback(raw: String): List<DistilledEntry> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(ListSerializer(DistilledEntry.serializer()), raw.substring(start, end + 1))
                .filter { it.name.isNotBlank() && it.content.isNotBlank() }
        }.onFailure {
            FileLogger.w(TAG, "标签格式没解析出条目，JSON 兜底也失败，本轮跳过")
        }.getOrDefault(emptyList())
    }

    @Serializable
    private data class DistilledEntry(
        val name: String,
        val description: String = "",
        val content: String = ""
    )

    /** 标签格式的三个字段；中英文冒号都认，大小写不敏感。 */
    private val NAME_LABEL = Regex("^name\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val DESCRIPTION_LABEL = Regex("^description\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val CONTENT_LABEL = Regex("^content\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)

    companion object {
        const val TAG = "MemoryExtractor"

        /** 写入来源：按轮归约。 */
        const val SOURCE_AUTO_DISTILL = "auto-distill"

        /** 写入来源：压缩前从被折叠历史里抽取。 */
        const val SOURCE_PRE_FOLD = "pre-fold"

        /** 抽取提示词：与其它一次性调用一样放 assets/prompts 下。 */
        const val PROMPT_FILE = "agent/memory-distiller.md"

        private const val TRANSCRIPT_MESSAGES = 20
        private const val MAX_MESSAGE_CHARS = 1200
        private const val MAX_TOOL_CHARS = 400
        private const val MAX_ENTRIES = 5
    }
}
