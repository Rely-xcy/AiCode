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

    /**
     * 每轮注入系统提示词的坑位数（上限）。
     *
     * 条数不是真正的成本单位（成本是描述总长），所以调用方还会按描述长度预算再裁一道；
     * 这里给的是硬上限。同时它也是「公平性」判据：记忆条数不超过坑位数时，每条都每轮参与竞争，
     * `hitCount == 0` 才能说明「它没被选中过」——治理器判死条目用的就是这条（见 [MemoryCurator]）。
     */
    const val INJECTION_SLOTS = 5

    private const val NAME_WEIGHT = 3
    private const val DESCRIPTION_WEIGHT = 2
    private const val CONTENT_WEIGHT = 1

    /** 正文只扫前这么多字符：记忆正文可能很长，打分不必读完。 */
    private const val CONTENT_SCAN_CHARS = 2_000

    /** 陈旧阈值：创建超过这个时长且从未被用过，排序时按 [STALE_FACTOR] 降权（降级，不删除）。 */
    private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 陈旧降权系数。
     *
     * 原来是「减 1 分」——但分数是整数，命中一条名称就是 3 分，减 1 几乎永远改变不了顺序，
     * 等于这层机制写了没生效。改成按比例：强烈相关的陈旧条目仍然能排前面，
     * 但相关度接近时会输给新鲜的（这才叫「降级而不是删除」）。
     */
    private const val STALE_FACTOR = 0.5

    /** 去重阈值：token 集合的 Jaccard 相似度超过它就算「说的是同一件事」。 */
    private const val DEDUPE_THRESHOLD = 0.7

    fun rank(
        memories: List<Memory>,
        query: String,
        limit: Int,
        now: Long = System.currentTimeMillis()
    ): List<Memory> {
        if (limit <= 0) return emptyList()
        if (memories.size <= limit) return memories
        val tokens = tokenize(query)
        val ordered = if (tokens.isEmpty()) {
            byRecency(memories)
        } else {
            memories.sortedWith(
                compareByDescending<Memory> { score(it, tokens, now) }
                    .thenByDescending { updatedAt(it) }
                    .thenBy { it.name }
            )
        }
        return dedupe(ordered).take(limit)
    }

    /**
     * 低成本去重：只有 3 个坑位，两条说的是同一件事就是纯浪费。
     *
     * 用 token 集合的 Jaccard 相似度就够了——不用 MMR：实测本地 7B 跑一次 MMR 要 40 秒，
     * 代价远超收益（MemOS 落地笔记）。
     */
    private fun dedupe(ordered: List<Memory>): List<Memory> {
        val picked = mutableListOf<Memory>()
        val signatures = mutableListOf<Set<String>>()
        for (memory in ordered) {
            val signature = tokenize(
                "${memory.name} ${memory.description} ${memory.content.take(CONTENT_SCAN_CHARS)}"
            )
            if (signature.isNotEmpty() && signatures.any { similar(it, signature) }) continue
            picked.add(memory)
            if (signature.isNotEmpty()) signatures.add(signature)
        }
        return picked
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return union > 0 && intersection.toDouble() / union >= DEDUPE_THRESHOLD
    }

    private fun score(memory: Memory, tokens: Set<String>, now: Long): Double {
        val name = tokenize(memory.name)
        val description = tokenize(memory.description)
        val content = tokenize(memory.content.take(CONTENT_SCAN_CHARS))
        val overlap = tokens.count { it in name } * NAME_WEIGHT +
            tokens.count { it in description } * DESCRIPTION_WEIGHT +
            tokens.count { it in content } * CONTENT_WEIGHT
        return overlap * if (isStale(memory, now)) STALE_FACTOR else 1.0
    }

    /**
     * 陈旧判定：记了 30 天却一次都没被注入过，说明它跟你的日常话题关系不大。
     *
     * 只降权不删除——「好的记忆系统和好的遗忘机制是同一件事的两面」，
     * 但删除是不可逆的，降级把坑位让出去就够了（真需要时还能 memory(action=list) 找到）。
     * 注意：它只在记忆条数超过坑位数时才有机会生效（不超过时 [rank] 直接原样返回），
     * 那种场景下「从没被注入」不完全等于「没用」——所以这里只是软降权，
     * 硬判死（归档）在 [MemoryCurator]，那里另有公平性门禁。
     */
    private fun isStale(memory: Memory, now: Long): Boolean =
        memory.hitCount == 0 && memory.createdAt > 0 && now - memory.createdAt > STALE_AFTER_MS

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
