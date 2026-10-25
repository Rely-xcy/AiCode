package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单类注入块的预算截断：放得下必须逐字不动（否则正常规模下会凭空抖动缓存），
 * 放不下时必须保住「有多少项、去哪看」——摘要行用真实的那两个函数，别用测试自己的替身。
 */
class PromptListBudgetTest {

    private fun line(index: Int) = "- skill-$index: ${"描述".repeat(20)}"

    private val fortyLines = (1..40).map(::line)

    private fun note(omitted: Int, total: Int) = "- 其余 $omitted 个未展开（共 $total 项）：见 ~/.aicode/skills/ 目录"

    @Test
    fun 放得下时逐字返回原清单() {
        val lines = listOf("- a: A", "- b: B")

        assertEquals(lines.joinToString("\n"), PromptListBudget.limit(lines, 400, ::note))
    }

    @Test
    fun 技能清单超预算_写出总数与检索入口() {
        assertTrue("前置条件：这批清单确实超过技能预算", fortyLines.joinToString("\n").length > PromptListBudget.SKILLS)

        val text = PromptListBudget.limit(fortyLines, PromptListBudget.SKILLS, PromptListBudget::skillsNote)

        assertTrue("总项数必须写出来", text.contains("共 40 个技能"))
        // 未列出的技能只能靠检索找回来：摘要行必须指向那个真的能用的入口，而不是目录名。
        assertTrue("必须指向检索入口", text.contains("loadSkill(keyword="))
        assertTrue("未列出的仍属可用，不能读成不存在", text.contains("均可检索后按名加载"))
        assertTrue("截断后不得超过预算", text.length <= PromptListBudget.SKILLS)
        assertTrue("保留的是原清单的前几条且顺序不变", text.startsWith(fortyLines.first()))
        // 最后一行是摘要，它报的省略数加上展示条数必须等于总数（不能把条目算丢）
        val shown = text.lines().size - 1
        val omitted = Regex("其余 (\\d+) 个").find(text)!!.groupValues[1].toInt()
        assertEquals("展示条数 + 省略条数 = 总数", 40, shown + omitted)
    }

    @Test
    fun 技能清单受条数上限约束() {
        val lines = (1..20).map { "- s$it: ${"描述".repeat(3)}" }
        assertTrue("前置条件：整份清单放不进预算", lines.joinToString("\n").length > 200)

        val text = PromptListBudget.limit(lines, 200, PromptListBudget::skillsNote, maxItems = 4)

        assertEquals("4 条正文 + 1 行摘要", 5, text.lines().size)
        assertTrue("省略数按总条数算，不按传进来的条数算", text.contains("其余 16 个"))
        assertTrue(text.contains("共 20 个技能"))
    }

    @Test
    fun 子代理清单超预算_仍写出总数与目录() {
        assertTrue(fortyLines.joinToString("\n").length > PromptListBudget.SUBAGENTS)

        val text = PromptListBudget.limit(fortyLines, PromptListBudget.SUBAGENTS, PromptListBudget::subAgentsNote)

        assertTrue(text.contains("共 40 个子代理"))
        assertTrue(text.contains("`~/.aicode/agents/`"))
        assertTrue(text.length <= PromptListBudget.SUBAGENTS)
    }

    @Test
    fun 单条就超预算_仍给出总数而不是空串() {
        val text = PromptListBudget.limit(listOf(line(1).repeat(10)), 100, ::note)

        assertTrue("至少要保住总数", text.contains("共 1 项"))
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun 空清单返回空串() {
        assertEquals("", PromptListBudget.limit(emptyList(), 400, ::note))
    }
}
