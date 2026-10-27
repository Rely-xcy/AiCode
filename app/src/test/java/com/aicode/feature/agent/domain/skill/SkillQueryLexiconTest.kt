package com.aicode.feature.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillQueryLexiconTest {

    @Test
    fun tokenize_keepsWholeChunkAndCjkBigrams() {
        assertEquals(
            listOf("逆向分析", "逆向", "向分", "分析"),
            SkillQueryLexicon.tokenize("逆向分析")
        )
    }

    @Test
    fun tokenize_splitsOnWhitespace() {
        assertEquals(
            listOf("逆向", "分析"),
            SkillQueryLexicon.tokenize("逆向 分析")
        )
    }

    @Test
    fun tokenize_keepsWholeWordForAliasLookup() {
        // 整串「反编译」必须留着：问法表按整词登记，只剩二元组时对不上 decompile
        val tokens = SkillQueryLexicon.tokenize("apk 反编译")
        assertEquals(listOf("apk", "反编译", "反编", "编译"), tokens)
    }

    @Test
    fun tokenize_blankReturnsEmpty() {
        assertTrue(SkillQueryLexicon.tokenize("   ").isEmpty())
    }

    @Test
    fun aliases_areBidirectional() {
        assertTrue(SkillQueryAliases.aliasesOf("脱壳").contains("unpack"))
        assertTrue(SkillQueryAliases.aliasesOf("unpack").contains("脱壳"))
        assertTrue(SkillQueryAliases.aliasesOf("不存在的词").isEmpty())
    }
}
