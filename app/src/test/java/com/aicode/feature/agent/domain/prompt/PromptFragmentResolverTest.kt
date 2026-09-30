package com.aicode.feature.agent.domain.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `prompts.custom/` 片段解析规则：两位数字身份、扫描排序去重、`.no-builtin` 判定、静态基线合并。
 */
class PromptFragmentResolverTest {

    private val dir: File = Files.createTempDirectory("prompt-resolver-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun parseNumber_两位数字前缀_取值() {
        assertEquals(0, PromptFragmentResolver.parseNumber("00-identity.md"))
        assertEquals(5, PromptFragmentResolver.parseNumber("05-我的规则.md"))
        assertEquals(50, PromptFragmentResolver.parseNumber("50-safety.md"))
        assertEquals(99, PromptFragmentResolver.parseNumber("99-any.md"))
    }

    @Test
    fun parseNumber_非两位数字前缀_返回null() {
        assertNull(PromptFragmentResolver.parseNumber("notes.md"))
        assertNull("一位数不算", PromptFragmentResolver.parseNumber("0-x.md"))
        assertNull("四位数不算", PromptFragmentResolver.parseNumber("2024-x.md"))
        assertNull("名称不能为空", PromptFragmentResolver.parseNumber("10-.md"))
        assertNull("子目录不算顶层片段", PromptFragmentResolver.parseNumber("agent/title-generator.md"))
        assertNull(PromptFragmentResolver.parseNumber(".no-builtin"))
    }

    @Test
    fun numberedFragments_按数字升序_过滤非匹配文件() {
        File(dir, "50-安全.md").writeText("a")
        File(dir, "05-开头.md").writeText("b")
        File(dir, "notes.md").writeText("c")
        File(dir, "agent").apply { mkdirs() }
        File(dir, "agent/title-generator.md").writeText("d")
        File(dir, ".no-builtin").writeText("")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(listOf(5, 50), result.map { it.first })
        assertTrue(result.all { it.second.isFile })
    }

    @Test
    fun numberedFragments_同数字_取字典序首个() {
        File(dir, "50-zzz.md").writeText("z")
        File(dir, "50-aaa.md").writeText("a")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(1, result.size)
        assertEquals("50-aaa.md", result.first().second.name)
    }

    @Test
    fun numberedFragments_目录不存在_返回空() {
        assertTrue(PromptFragmentResolver.numberedFragments(File(dir, "missing")).isEmpty())
    }

    @Test
    fun isBuiltinDisabled_仅精确名命中() {
        File(dir, ".DS_Store").writeText("x")
        assertFalse(PromptFragmentResolver.isBuiltinDisabled(dir))

        File(dir, ".no-builtin").writeText("")
        assertTrue(PromptFragmentResolver.isBuiltinDisabled(dir))
    }

    @Test
    fun mergeStatic_覆盖与新增片段按数字合并() {
        val custom5 = File(dir, "05-新增.md")
        val custom50 = File(dir, "50-自定义安全.md")
        val custom80 = File(dir, "80-结尾.md")
        val custom = listOf(5 to custom5, 50 to custom50, 80 to custom80)

        val merged = PromptFragmentResolver.mergeStatic(listOf(0, 10, 50), custom)

        assertEquals(listOf(0, 5, 10, 50, 80), merged.map { it.first })
        assertNull("内置数字无自定义文件时用默认内容", merged[0].second)
        assertEquals(custom5, merged[1].second)
        assertEquals(custom50, merged[3].second)
        assertEquals(custom80, merged[4].second)
    }

    @Test
    fun effectiveContent_有覆盖_用覆盖正文() {
        val override = File(dir, "00-identity.md").apply { writeText("我的身份") }

        val result = PromptFragmentResolver.effectiveContent(override, "App 自带身份")

        assertEquals("我的身份", result.content)
        assertNull("正常读用覆盖不算回落", result.fallbackCause)
    }

    @Test
    fun effectiveContent_无覆盖_回落内置且不算异常() {
        val result = PromptFragmentResolver.effectiveContent(null, "App 自带身份")

        assertEquals("App 自带身份", result.content)
        assertNull("没放副本是正常情况，不该当异常报", result.fallbackCause)
        assertEquals(PromptFragmentResolver.OverrideRead.Absent, PromptFragmentResolver.readOverride(null))
    }

    @Test
    fun effectiveContent_副本损坏_回落内置并带回落原因() {
        // 目录占了副本路径：路径存在但不是普通文件，读不出来。
        // 这是可稳定复现的「损坏」形态（普通文件的读异常走的是同一个 catch 分支）。
        val override = File(dir, "00-identity.md").apply { mkdirs() }

        val result = PromptFragmentResolver.effectiveContent(override, "App 自带身份")

        assertEquals("受损副本不能让片段变空，必须回落内置", "App 自带身份", result.content)
        assertNotNull("覆盖读不出来必须带回落原因，调用方据此记日志", result.fallbackCause)
        assertTrue(PromptFragmentResolver.readOverride(override) is PromptFragmentResolver.OverrideRead.Unreadable)
    }

    @Test
    fun effectiveContent_副本与内置都拿不到_返回null() {
        val result = PromptFragmentResolver.effectiveContent(File(dir, "missing.md"), null)

        assertNull(result.content)
        assertNull(result.fallbackCause)
    }

    @Test
    fun readOverride_空副本_视为有意清空而非损坏() {
        // 清空是文档写明的「关掉这个片段」方式（如 agent/subagent-rules.md），不能当损坏回落内置
        val override = File(dir, "50-safety.md").apply { writeText("") }

        val result = PromptFragmentResolver.effectiveContent(override, "App 自带安全规则")

        assertEquals("", result.content)
        assertNull(result.fallbackCause)
    }
}