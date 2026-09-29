package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆治理：把「越来越多、越来越脏」的记忆定期收敛一次。
 *
 * 两层，先便宜后聪明——用户明确要求「省钱又智能」：
 *
 * 1. **零成本本地规则**（不发模型）：空壳条目（无描述或无正文）、长期没被命中的死条目、
 *    近似重复条目 → 直接归档。一半的脏东西在这一层就没了，不花一分钱。
 * 2. **一次小调用**：只把 `name + description` 发给模型（每条一行，几十条也就几百 token），
 *    让它指出「哪几条是一回事」以及「哪几条该退场」。**正文不上传**——需要正文时在本地读。
 *
 * 归档 = 复制到 `.superseded/` 后删原文件（等于移动），从活动列表消失但内容留底，不真删干净。
 */
@Singleton
class MemoryCurator @Inject constructor(
    private val memoryRepository: MemoryRepository
) {

    data class Result(val archived: Int, val merged: Int) {
        val changed: Boolean get() = archived > 0 || merged > 0
    }

    /**
     * @param complete 一次性模型调用能力（给 user prompt，返回模型输出）；
     *   null 表示拿不到模型，此时只做第一层本地规则。
     */
    suspend fun curate(
        projectRoot: String,
        complete: (suspend (String) -> String?)? = null
    ): Result {
        val memories = runCatching { memoryRepository.listMemories(projectRoot) }
            .getOrDefault(emptyList())
        if (memories.size < MIN_MEMORIES_FOR_CURATION) return Result(0, 0)

        val toArchive = linkedSetOf<String>()
        val mergeGroups = mutableMapOf<String, MutableList<String>>()

        // ---- 第一层：零成本本地规则 ----
        val now = System.currentTimeMillis()
        val alive = mutableListOf<Memory>()
        memories.forEach { memory ->
            when {
                memory.description.isBlank() || memory.content.isBlank() -> toArchive += memory.name
                memory.hitCount == 0 && memory.createdAt > 0 && now - memory.createdAt > DEAD_AFTER_MS ->
                    toArchive += memory.name

                else -> alive += memory
            }
        }
        val kept = mutableListOf<Memory>()
        alive.forEach { memory ->
            val signature = signature(memory)
            val duplicate = kept.firstOrNull { similar(signature(it), signature) }
            if (duplicate == null) kept += memory
            else mergeGroups.getOrPut(duplicate.name) { mutableListOf() } += memory.name
        }

        // ---- 第二层：一次小调用，只发摘要 ----
        val survivors = kept.filterNot { it.name in mergeGroups }
        if (complete != null && survivors.size >= MIN_MEMORIES_FOR_MODEL) {
            val raw = runCatching { complete(buildDigest(survivors)) }.getOrNull()
            if (raw != null) {
                parseVerdict(raw).forEach { verdict ->
                    when (verdict) {
                        is Verdict.Archive -> if (verdict.name in survivors.map { it.name }) toArchive += verdict.name
                        is Verdict.Merge -> {
                            val keep = survivors.firstOrNull { it.name == verdict.keep }?.name
                            val others = verdict.others.filter { other -> survivors.any { it.name == other } }
                            if (keep != null && others.isNotEmpty()) {
                                mergeGroups.getOrPut(keep) { mutableListOf() } += others
                            }
                        }
                    }
                }
            }
        }

        // 被合并掉的条目本身也要退场
        mergeGroups.values.forEach { toArchive += it }
        toArchive -= mergeGroups.keys

        // ---- 执行 ----
        var archived = 0
        var merged = 0
        mergeGroups.forEach { (keepName, otherNames) ->
            if (merge(keepName, otherNames, projectRoot)) merged++
        }
        // 归档要按名字找回 Memory 对象：archive() 需要真实文件路径
        val byName = memories.associateBy { it.name }
        toArchive.forEach { name ->
            val memory = byName[name] ?: return@forEach
            if (archive(memory, projectRoot)) archived++
        }

        if (archived > 0 || merged > 0) {
            FileLogger.i(TAG, "记忆治理完成：合并 $merged 组、归档 $archived 条")
        }
        return Result(archived, merged)
    }

    /** 合并：把其它条目的正文并进保留项（本地读内容，不上传），再把它们归档。 */
    private suspend fun merge(keepName: String, otherNames: List<String>, projectRoot: String): Boolean {
        val all = runCatching { memoryRepository.listMemories(projectRoot) }.getOrDefault(emptyList())
        val keep = all.firstOrNull { it.name == keepName } ?: return false
        val others = otherNames.mapNotNull { name -> all.firstOrNull { it.name == name } }
        if (others.isEmpty()) return false

        val mergedContent = buildString {
            append(keep.content.trim())
            others.forEach { other ->
                val text = other.content.trim()
                if (text.isNotBlank() && !keep.content.contains(text)) {
                    appendLine()
                    appendLine()
                    append(text)
                }
            }
        }.trim()

        val saved = memoryRepository.saveMemory(
            name = keep.name,
            description = keep.description,
            content = mergedContent,
            scope = keep.scope,
            projectRoot = projectRoot,
            kind = keep.kind,
            source = keep.source,
            createdAt = keep.createdAt
        )
        if (!saved) return false
        others.forEach { other -> archive(other, projectRoot) }
        return true
    }

    /** 归档一条：先复制到 .superseded/（留底），再删原文件——等于移动，不真删干净。 */
    private fun archive(memory: Memory, projectRoot: String): Boolean {
        val file = memory.file ?: return false
        val root = file.parentFile ?: return false
        MemorySource.archiveBeforeOverwrite(root, file)
        return memoryRepository.deleteMemory(memory.name, memory.scope, projectRoot)
    }

    /** 给模型的输入：每条一行，只有名称与描述。 */
    private fun buildDigest(memories: List<Memory>): String = buildString {
        appendLine("Long-term memories:")
        memories.forEach { appendLine("- ${it.name}: ${it.description.ifBlank { "(no description)" }}") }
    }

    private sealed interface Verdict {
        data class Archive(val name: String) : Verdict
        data class Merge(val keep: String, val others: List<String>) : Verdict
    }

    /**
     * 解析模型输出，容错处理：
     * - `archive: <name>` → 建议退场
     * - `merge: <keep> <- <a>, <b>` → 合并
     * 分隔符 `<-` / `<-` / `→` / `:` 都认；无法识别的行直接忽略（坏一行只丢一行）。
     */
    private fun parseVerdict(raw: String): List<Verdict> {
        val verdicts = mutableListOf<Verdict>()
        raw.lines().forEach { line ->
            val cleaned = line.trim().removePrefix("-").removePrefix("*").trim()
            when {
                cleaned.startsWith("archive", ignoreCase = true) -> {
                    val name = cleaned.substringAfter(':', "").trim().trim('"', '\'', '`')
                    if (name.isNotBlank()) verdicts += Verdict.Archive(name)
                }

                cleaned.startsWith("merge", ignoreCase = true) -> {
                    val body = cleaned.substringAfter(':', "").trim()
                    val split = MERGE_SPLIT.find(body)
                    if (split != null) {
                        val keep = body.substring(0, split.range.first).trim().trim('"', '\'', '`')
                        val others = body.substring(split.range.last + 1)
                            .split(',', '，')
                            .map { it.trim().trim('"', '\'', '`') }
                            .filter { it.isNotBlank() }
                        if (keep.isNotBlank() && others.isNotEmpty()) verdicts += Verdict.Merge(keep, others)
                    }
                }
            }
        }
        return verdicts
    }

    private fun signature(memory: Memory): Set<String> =
        MemoryRanker.tokenize("${memory.name} ${memory.description} ${memory.content.take(CONTENT_SCAN_CHARS)}")

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return union > 0 && intersection.toDouble() / union >= DUPLICATE_THRESHOLD
    }

    private companion object {
        const val TAG = "MemoryCurator"

        /** 太少不值得治理（连一次模型调用都嫌贵）。 */
        const val MIN_MEMORIES_FOR_CURATION = 4

        /** 低于这个数量就不叫模型了，本地规则足够。 */
        const val MIN_MEMORIES_FOR_MODEL = 6

        /** 30 天从没被注入过 → 判定为死条目。 */
        const val DEAD_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        const val DUPLICATE_THRESHOLD = 0.75
        const val CONTENT_SCAN_CHARS = 1_000

        val MERGE_SPLIT = Regex("<-|<-|→|=>")
    }
}
