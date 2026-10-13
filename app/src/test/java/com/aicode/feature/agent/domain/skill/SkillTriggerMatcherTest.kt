package com.aicode.feature.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发词匹配的行为对照表测试（验收基准 = 设计文档 §1.1）。
 *
 * 全部纯字符串逻辑，不依赖 Android。
 */
class SkillTriggerMatcherTest {

    private fun skill(vararg triggers: String) = Skill(
        name = "ig5-triage",
        description = "新样本快速侦察流",
        triggers = triggers.toList(),
        instructions = "正文"
    )

    private fun hitWords(request: String, vararg triggers: String): List<String> =
        SkillTriggerMatcher.match(request, listOf(skill(*triggers))).map { it.hitWord }

    // ---- 对照表（触发词「逆向」）----

    @Test
    fun `对照表 - 独立token命中`() {
        assertEquals(listOf("逆向"), hitWords("逆向", "逆向"))
        assertEquals(listOf("逆向"), hitWords("逆向 帮我分析下", "逆向"))
        assertEquals(listOf("逆向"), hitWords("帮我 逆向 看看这个", "逆向"))
    }

    @Test
    fun `对照表 - 连写不命中`() {
        assertTrue(SkillTriggerMatcher.match("帮我逆向看看", listOf(skill("逆向"))).isEmpty())
    }

    @Test
    fun `对照表 - 标点粘连不命中`() {
        assertTrue(SkillTriggerMatcher.match("逆向。", listOf(skill("逆向"))).isEmpty())
        assertTrue(SkillTriggerMatcher.match("帮我，逆向", listOf(skill("逆向"))).isEmpty())
    }

    @Test
    fun `对照表 - token不全等不命中`() {
        assertTrue(SkillTriggerMatcher.match("reversed logic", listOf(skill("逆向"))).isEmpty())
        assertTrue(SkillTriggerMatcher.match("reverse", listOf(skill("逆向"))).isEmpty())
    }

    @Test
    fun `大小写不敏感`() {
        // hitWord 返回技能配置的触发词原文（非消息 token 原文）
        assertEquals(listOf("reverse"), hitWords("REVERSE this", "reverse"))
        assertEquals(listOf("reverse"), hitWords("do a Reverse thing", "reverse"))
    }

    @Test
    fun `全角空格与换行同样分词`() {
        assertEquals(listOf("逆向"), hitWords("帮我\u3000逆向\u3000看看", "逆向"))
        assertEquals(listOf("逆向"), hitWords("逆向\n帮我分析", "逆向"))
    }

    // ---- 优先级 ----

    @Test
    fun `句首优先于句中`() {
        val skill = skill("逆向", "分析")
        val hits = SkillTriggerMatcher.match("逆向 帮我 分析", listOf(skill))
        assertEquals(1, hits.size)
        assertTrue(hits.first().atStart)
        assertEquals("逆向", hits.first().hitWord)
    }

    @Test
    fun `同技能多触发词命中取更长者`() {
        val hits = SkillTriggerMatcher.match("帮我 reverse 逆向", listOf(skill("reverse", "逆向")))
        assertEquals(1, hits.size)
        assertEquals("reverse", hits.first().hitWord)
    }

    @Test
    fun `多技能命中按触发词长度排序`() {
        val short = Skill(name = "short", description = "", triggers = listOf("逆向"), instructions = "")
        val long = Skill(name = "long", description = "", triggers = listOf("逆向分析"), instructions = "")
        val hits = SkillTriggerMatcher.match("帮我 逆向分析 逆向", listOf(short, long))
        assertEquals(listOf("long", "short"), hits.map { it.skill.name })
    }

    @Test
    fun `含空格的触发词被sanitize丢弃`() {
        // 多词触发词在 token 全等规则下永远命中不了，解析/保存时就要丢弃，不留「配了没用」的假象
        assertEquals(emptyList<String>(), SkillTriggerMatcher.sanitize(listOf("reverse engineering")))
        assertEquals(listOf("逆向"), SkillTriggerMatcher.sanitize(listOf(" 逆向 ", "a", "逆向")))
        // 同一个技能里配了一个多词 + 一个单词：多词被丢，单词照常命中
        val hits = SkillTriggerMatcher.match("逆向", listOf(skill("reverse engineering", "逆向")))
        assertEquals(listOf("逆向"), hits.map { it.hitWord })
    }

    // ---- 边界 ----

    @Test
    fun `短于2字符的触发词被忽略`() {
        assertTrue(SkillTriggerMatcher.match("我 的 看", listOf(skill("我", "的"))).isEmpty())
    }

    @Test
    fun `无触发词的技能不参与`() {
        assertTrue(SkillTriggerMatcher.match("逆向", listOf(skill())).isEmpty())
    }

    @Test
    fun `空消息不命中`() {
        assertTrue(SkillTriggerMatcher.match("", listOf(skill("逆向"))).isEmpty())
        assertTrue(SkillTriggerMatcher.match("   ", listOf(skill("逆向"))).isEmpty())
    }

    @Test
    fun `未命中的技能不出现在结果里`() {
        val a = Skill(name = "a", description = "", triggers = listOf("逆向"), instructions = "")
        val b = Skill(name = "b", description = "", triggers = listOf("脱壳"), instructions = "")
        val hits = SkillTriggerMatcher.match("帮我 逆向", listOf(a, b))
        assertEquals(listOf("a"), hits.map { it.skill.name })
    }

    // ---- SkillParser 的 triggers 往返 ----

    @Test
    fun `parser解析triggers并roundtrip`() {
        val text = """
            ---
            name: "ig5-triage"
            description: "侦察流"
            triggers: [逆向, 脱壳, reverse]
            ---
            正文内容
        """.trimIndent()
        val parsed = SkillParser.parseText(text, "fallback")
        assertEquals(listOf("逆向", "脱壳", "reverse"), parsed.triggers)

        val serialized = SkillParser.serialize(
            name = parsed.name,
            description = parsed.description,
            requiredTools = parsed.requiredTools,
            triggers = parsed.triggers,
            instructions = parsed.instructions
        )
        assertEquals(parsed.triggers, SkillParser.parseText(serialized, "fallback").triggers)
    }

    @Test
    fun `旧格式无triggers解析为空且不回归`() {
        val text = """
            ---
            name: "legacy"
            description: "老技能"
            required_tools: [Bash]
            ---
            正文
        """.trimIndent()
        val parsed = SkillParser.parseText(text, "fallback")
        assertEquals(emptyList<String>(), parsed.triggers)
        assertEquals(listOf("Bash"), parsed.requiredTools)
        assertTrue(parsed.instructions.isNotBlank())
    }

    @Test
    fun `triggers为空时serialize不写字段`() {
        val text = SkillParser.serialize("n", "d", emptyList(), emptyList(), "body")
        assertFalse(text.contains("triggers"))
    }
}
