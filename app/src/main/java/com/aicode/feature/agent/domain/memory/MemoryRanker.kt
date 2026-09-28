package com.aicode.feature.agent.domain.memory

/**
 * 记忆召回排序：不引入向量库，用「文本重合度 + 新鲜度」做粗排。
 *
 * 为什么不按名字序取前 N：名字是写入时随手起的 slug，与「这轮该用哪条」无关。
 * 实测结论（MemOS 落地笔记）是记忆条数多了反而干扰决策，10 条已开始干扰、3 条是甜点值，
 * 所以宁可少注入，但必须挑对——挑错的 3 条比不注入更糟。
 *
 * 打分口径：
 * - 与当前这轮文本的重合度：名称权重最高（名字就是这条记忆的主题），描述次之，正文最低；
 * - 中文没有空格，按二元组（bigram）匹配，否则整句对不上就一条都不命中；
 * - 分数相同时按文件修改时间（越新越可能仍然有效），再按名字保证稳定。
 */
object MemoryRanker {

    private const val NAME_WEIGHT = 3
    private const val DESCRIPTION_WEIGHT = 2
    private const val CONTENT_WEIGHT = 1

    /** 正文只扫前这么多字符：记忆正文可能很长，打分不必读完。 */
    private const val CONTENT_SCAN_CHARS = 2_000

    fun rank(memories: List<Memory>, query: String, limit: Int): List<Memory> {
        if (limit <= 0) return emptyList()
        if (memories.size <= limit) return memories
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return byRecency(memories).take(limit)
        return memories
            .sortedWith(
                compareByDescending<Memory> { score(it, tokens) }
                    .thenByDescending { updatedAt(it) }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    private fun score(memory: Memory, tokens: Set<String>): Int {
        val name = tokenize(memory.name)
        val description = tokenize(memory.description)
        val content = tokenize(memory.content.take(CONTENT_SCAN_CHARS))
        return tokens.count { it in name } * NAME_WEIGHT +
            tokens.count { it in description } * DESCRIPTION_WEIGHT +
            tokens.count { it in content } * CONTENT_WEIGHT
    }

    private fun byRecency(memories: List<Memory>): List<Memory> =
        memories.sortedWith(compareByDescending<Memory> { updatedAt(it) }.thenBy { it.name })

    private fun updatedAt(memory: Memory): Long = memory.file?.lastModified() ?: 0L

    /**
     * 分词：ASCII 词（长度 ≥ 2，转小写）+ CJK 二元组。
     * 单字 CJK 不成词，只有连续两个以上汉字才会产出 token。
     */
    fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = mutableSetOf<String>()
        val ascii = StringBuilder()
        var previousCjk: Char? = null

        fun flushAscii() {
            if (ascii.length >= 2) tokens.add(ascii.toString().lowercase())
            ascii.clear()
        }

        for (char in text) {
            when {
                char.isLetterOrDigit() && char.code < ASCII_CEILING -> ascii.append(char)

                isCjk(char) -> {
                    flushAscii()
                    previousCjk?.let { tokens.add("$it$char") }
                    previousCjk = char
                }

                else -> {
                    flushAscii()
                    previousCjk = null
                }
            }
        }
        flushAscii()
        return tokens
    }

    private const val ASCII_CEILING = 0x2E80

    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF
    }
}
