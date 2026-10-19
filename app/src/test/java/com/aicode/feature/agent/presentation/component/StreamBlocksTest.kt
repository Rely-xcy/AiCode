package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [splitStreamingBlocks] 的保守切分规则。
 * 所有用例都同时断言「frozen 拼接 + tail == 原文」，这是内容不丢不重的底线。
 */
class StreamBlocksTest {

    private fun StreamBlocks.rebuilt(): String = frozen.joinToString(separator = "") + tail

    @Test
    fun plain_paragraphs_freeze_at_blank_lines() {
        val text = "第一段\n\n第二段\n\n第三段"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("第一段\n\n", "第二段\n\n"), blocks.frozen)
        assertEquals("第三段", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun unclosed_fence_only_freezes_the_intro_paragraph() {
        val text = "说明\n\n```kotlin\nval a = 1\n\nval b = 2\n"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("说明\n\n"), blocks.frozen)
        assertEquals("```kotlin\nval a = 1\n\nval b = 2\n", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun closed_fence_freezes_and_text_after_it_stays_tail() {
        val text = "说明\n\n```kotlin\nval a = 1\n```\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("说明\n\n", "```kotlin\nval a = 1\n```\n"), blocks.frozen)
        assertEquals("\n后续", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun list_continued_after_blank_line_is_not_frozen() {
        val text = "开头\n\n- 一\n\n- 二\n\n结尾"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("开头\n\n- 一\n\n- 二\n\n"), blocks.frozen)
        assertEquals("结尾", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun table_is_one_block_until_blank_line() {
        val text = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("| a | b |\n| --- | --- |\n| 1 | 2 |\n\n"), blocks.frozen)
        assertEquals("后续", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun quote_block_freezes_before_following_paragraph() {
        val text = "> 引用一\n> 引用二\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("> 引用一\n> 引用二\n\n"), blocks.frozen)
        assertEquals("后续", blocks.tail)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun empty_and_blank_text_has_no_frozen_block() {
        assertEquals(StreamBlocks(emptyList(), ""), splitStreamingBlocks(""))
        val blank = "  \n\n\t\n"
        val blocks = splitStreamingBlocks(blank)
        assertTrue(blocks.frozen.isEmpty())
        assertEquals(blank, blocks.tail)
    }

    @Test
    fun html_and_indented_code_blocks_are_not_frozen() {
        val html = "<div>\n内容\n</div>\n\n后续"
        val htmlBlocks = splitStreamingBlocks(html, minTailChars = 1)
        assertTrue(htmlBlocks.frozen.isEmpty())
        assertEquals(html, htmlBlocks.rebuilt())

        val indented = "    line1\n\n    line2\n\n后续"
        val indentedBlocks = splitStreamingBlocks(indented, minTailChars = 1)
        assertTrue(indentedBlocks.frozen.isEmpty())
        assertEquals(indented, indentedBlocks.rebuilt())
    }

    @Test
    fun math_block_inside_paragraph_is_not_frozen_midway() {
        val text = "说明\n\n$$\nx = 1\n$$\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(text, blocks.rebuilt())
        assertEquals(listOf("说明\n\n", "$$\nx = 1\n$$\n"), blocks.frozen)
        assertEquals("\n后续", blocks.tail)
    }

    @Test
    fun unbalanced_inline_backtick_blocks_the_cut() {
        val text = "开头 `代码\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertTrue(blocks.frozen.isEmpty())
        assertEquals(text, blocks.tail)
    }

    @Test
    fun frozen_blocks_respect_the_budget() {
        val text = (1..20).joinToString(separator = "") { "段落$it\n\n" } + "结尾"
        val blocks = splitStreamingBlocks(text, maxFrozenBlocks = 3, minTailChars = 1)
        assertEquals(3, blocks.frozen.size)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun short_tail_is_not_cut() {
        val text = "第一段\n\n尾巴"
        val blocks = splitStreamingBlocks(text)
        assertTrue(blocks.frozen.isEmpty())
        assertEquals(text, blocks.tail)
    }

    @Test
    fun reference_definition_blocks_the_cut() {
        val text = "说明\n\n[1]: https://example.com\n\n使用 [链接][1]"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertEquals(listOf("说明\n\n"), blocks.frozen)
        assertEquals(text, blocks.rebuilt())
    }

    @Test
    fun unbalanced_block_math_blocks_the_cut() {
        val text = "价格 $$ 未闭合\n\n后续"
        val blocks = splitStreamingBlocks(text, minTailChars = 1)
        assertTrue(blocks.frozen.isEmpty())
        assertEquals(text, blocks.rebuilt())
    }

    /** 最重要的一条：各种结构混排时，块拼接必须逐字还原原始流式文本。 */
    @Test
    fun rebuilding_frozen_blocks_and_tail_restores_original_text() {
        val samples = listOf(
            "普通一句话",
            "第一段\n\n第二段\n\n第三段",
            "前言\n\n```python\nprint(1)\n\nprint(2)\n```\n\n中段\n\n```bash\necho hi\n",
            "列表\n\n1. 甲\n2. 乙\n\n- 丙\n\n  - 缩进项\n\n收尾",
            "> 引用\n>\n> 内部空行\n\n正文",
            "| h1 | h2 |\n| -- | -- |\n| 1 | 2 |\n\n段落",
            "<div>\n<p>x</p>\n</div>\n\n段落",
            "公式\n\n$$\na+b\n$$\n\n段落",
            "行内 `code` 与 **粗体**\n\n下一段",
            "结尾没有换行",
            "结尾带一个换行\n",
            "结尾带空行\n\n",
            "\n\n\n开头空行\n\n正文",
            "```\n未闭合\n\n还在代码里",
            "混合\n\n- 项\n\n> 引用\n\n```js\nconst a = 1\n```\n\n最后",
            "引用定义\n\n[1]: https://example.com\n\n使用 [链接][1]",
        )
        for (sample in samples) {
            for (budget in listOf(1, 2, 8, 64)) {
                val blocks = splitStreamingBlocks(sample, maxFrozenBlocks = budget, minTailChars = 1)
                assertEquals("sample=$sample budget=$budget", sample, blocks.rebuilt())
            }
            val withDefaults = splitStreamingBlocks(sample)
            assertEquals("defaults sample=$sample", sample, withDefaults.rebuilt())
        }
    }
}
