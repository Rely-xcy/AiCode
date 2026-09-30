package com.aicode.feature.agent.domain.prompt

import java.io.File
import java.io.IOException

/**
 * `prompts.custom/` 的片段解析规则（纯文件系统逻辑，便于单测）。
 *
 * 目录里分两类片段：
 * - 顶层 `<两位数字>-<名称>.md`：数字即身份。数字命中内置静态片段即为覆盖，否则作为新增片段并入静态基线按数字排序。
 * - 其它文件（含 `agent/` 子目录）：按精确同名覆盖，无数字身份。
 */
internal object PromptFragmentResolver {

    /** 存在该文件即完全禁用内置提示词（仅主代理生效）。 */
    const val DISABLE_BUILTIN_FILE = ".no-builtin"

    /**
     * 单个片段的字符数上限。内置片段都在 4KB 以内，这里给同一量级的余量：
     * 片段是整段拼进系统提示词的，超长基本都是粘错了东西（贴进整个文件、误贴日志），
     * 与其让它静静占满上下文，不如当场拒绝。
     */
    const val MAX_FRAGMENT_CHARS = 32_000

    private val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")

    /** 顶层数字片段的数字值；非两位数字前缀返回 null。 */
    fun parseNumber(fileName: String): Int? =
        NUMBERED.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

    /**
     * 目录顶层 `<两位数字>-<名称>.md` 文件，按数字升序。
     * 同一数字有多个文件时只保留字典序首个，避免排序不稳定。
     */
    fun numberedFragments(dir: File): List<Pair<Int, File>> {
        val files = dir.listFiles() ?: return emptyList()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val number = parseNumber(file.name) ?: return@forEach
                byNumber.putIfAbsent(number, file)
            }
        return byNumber.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** `.no-builtin` 是否存在。 */
    fun isBuiltinDisabled(dir: File): Boolean = File(dir, DISABLE_BUILTIN_FILE).isFile

    /**
     * 覆盖副本的读取结果。
     *
     * 三态是必要的：[Absent]（没放副本）与 [Unreadable]（副本在却读不出来）对调用方都是「回落内置」，
     * 但只有后者是异常，必须记日志——覆盖副本坏掉却静默用回内置内容，用户看到的就是「改了没反应」。
     */
    sealed interface OverrideRead {
        /** 副本可读。空内容不算损坏：那是有意清空（等价于关掉该片段）。 */
        data class Present(val content: String) : OverrideRead

        /** 没有副本文件。 */
        data object Absent : OverrideRead

        /** 副本路径存在但不可用（不是普通文件，或读取抛异常）。 */
        data class Unreadable(val cause: Throwable) : OverrideRead
    }

    /**
     * 读覆盖副本。
     *
     * 与 [numberedFragments] 不同，这里不看文件名、不做编号解析，只判「能不能用」：
     * 目录占了副本路径、文件被外部工具写成半截导致读取失败，都归到 [OverrideRead.Unreadable]，
     * 由调用方回落到内置并记日志。
     */
    fun readOverride(file: File?): OverrideRead {
        if (file == null || !file.exists()) return OverrideRead.Absent
        if (!file.isFile) return OverrideRead.Unreadable(IOException("覆盖副本不是普通文件: ${file.name}"))
        return try {
            OverrideRead.Present(file.readText())
        } catch (e: Exception) {
            OverrideRead.Unreadable(e)
        }
    }

    /**
     * 生效正文：有可用覆盖用覆盖，否则回落内置。
     *
     * @param fallbackCause 非 null 表示「副本在却读不出来、已回落内置」，调用方须据此记日志。
     * @param content null 表示覆盖与内置都拿不到正文，该片段没有内容可用。
     */
    data class EffectiveContent(val content: String?, val fallbackCause: Throwable?)

    fun effectiveContent(overrideFile: File?, builtinContent: String?): EffectiveContent =
        when (val read = readOverride(overrideFile)) {
            is OverrideRead.Present -> EffectiveContent(read.content, null)
            OverrideRead.Absent -> EffectiveContent(builtinContent, null)
            is OverrideRead.Unreadable -> EffectiveContent(builtinContent, read.cause)
        }

    /**
     * 合并静态基线：以 [builtinNumbers] 顺序为骨架，[custom] 中命中这些数字的作为覆盖、
     * 其余作为新增片段，整体按数字升序排列。
     *
     * @return 有序的 (数字, 覆盖文件或 null)；null 表示该数字没有自定义文件、用内置默认内容。
     */
    fun mergeStatic(
        builtinNumbers: List<Int>,
        custom: List<Pair<Int, File>>
    ): List<Pair<Int, File?>> {
        val customByNumber = custom.toMap()
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtinNumbers)
            custom.forEach { if (it.first !in this) add(it.first) }
        }
        return numbers.sorted().map { it to customByNumber[it] }
    }
}