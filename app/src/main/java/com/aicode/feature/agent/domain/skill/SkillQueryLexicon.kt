package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.SearchTokens

/**
 * 技能检索的词表：在共用分词（[SearchTokens.tokenize]）之上多保留一份「整串」。
 *
 * 为什么还要整串：共用分词只产出 CJK 二元组，而问法表里登记的口语说法是整词（反编译、脱壳、抓包）。
 * 少了整串，`反编译` 只剩 `反编`、`编译` 两个碎片，前者对不上任何英文术语，后者会蹭到「构建」这类
 * 泛词——问法表就废了。二元组仍保留：`逆向分析` 这类没登记过的复合词靠 `逆向` + `分析` 也能命中。
 *
 * 词数有上限：每个词都要在名称/描述/正文里各扫一遍，长句切出来的碎片会让一次检索从毫秒级涨到几十毫秒。
 */
internal object SkillQueryLexicon {

    /** 词数上限，超出时丢尾部（整串与靠前的词更可信）。 */
    private const val MAX_TOKENS = 24

    private val WHITESPACE = Regex("[\\s\\u3000]+")

    /** 切词结果按「整串优先、二元组随后」去重返回；空输入返回空表。 */
    fun tokenize(keyword: String): List<String> {
        val text = keyword.trim().lowercase()
        if (text.isEmpty()) return emptyList()

        val tokens = LinkedHashSet<String>()
        for (chunk in text.split(WHITESPACE)) {
            // 词首尾的标点会跟着切块进来（`file.kt`、`(word)`、`word,`），不去掉就永远匹配不上。
            val cleaned = chunk.trim { !it.isLetterOrDigit() && it != '-' && it != '_' && it != '.' }
            if (cleaned.isEmpty() || !cleaned.any { it.isLetterOrDigit() }) continue
            // 纯数字（版本号、行号）不是技能主题。
            if (cleaned.all { it.isDigit() }) continue
            // 短英文词一律丢：`ui` 命中 build/guide、`ai` 命中 detail，两个字母足够蹭到半个库。
            // 中文不受此限，`逆向` 本身就是有效查询。
            if (cleaned.all { it.code < 128 } && cleaned.length < 3) continue
            tokens.add(cleaned)
        }
        tokens.addAll(
            SearchTokens.tokenize(text).filterNot {
                // 共用分词也会吐英文词，短词那道闸同样要过一遍。
                it.all { ch -> ch.code < 128 } && it.length < 3
            }
        )
        return if (tokens.size <= MAX_TOKENS) tokens.toList() else tokens.take(MAX_TOKENS).toList()
    }
}
