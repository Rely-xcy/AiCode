package com.aicode.core.util

/**
 * 本地检索共用的分词与字段重合度打分（记忆召回、技能检索同一套口径）。
 *
 * 为什么要抽出来：两处都在做「中文关键词能不能对上文本」这件事，各写一份的话，一边改成 bigram
 * 另一边还是整串，检索行为就会莫名其妙地不一致；口子只有一份，调分词规则时两边一起动。
 *
 * 分词规则：ASCII 字母数字连续段（长度 ≥ 2，转小写）+ CJK 相邻二元组。
 * 中文没有空格，整句匹配过严，二元组是「不上分词器」时最便宜的近似。
 */
object SearchTokens {

    /** ASCII 与 CJK 的分界；`char.code < ` 它才算 ASCII 字母数字（全角字母数字也走 ASCII 分支）。 */
    private const val ASCII_CEILING = 0x2E80

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

    /**
     * 关键词 token 在名称 / 描述 / 正文三处的加权命中分：每处按「该处有几个 token 命中」计数再加权。
     *
     * 三个字段的权重由调用方给（记忆用 3/2/1），但「名称 > 描述 > 正文」这个顺序是共用的口径：
     * 名称与描述是作者写给人看的摘要，正文里蹭到词只能说明沾边。
     */
    fun overlapScore(
        tokens: Set<String>,
        nameTokens: Set<String>,
        descriptionTokens: Set<String>,
        contentTokens: Set<String>,
        nameWeight: Double,
        descriptionWeight: Double,
        contentWeight: Double
    ): Double {
        if (tokens.isEmpty()) return 0.0
        return tokens.count { it in nameTokens } * nameWeight +
            tokens.count { it in descriptionTokens } * descriptionWeight +
            tokens.count { it in contentTokens } * contentWeight
    }

    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF
    }
}
