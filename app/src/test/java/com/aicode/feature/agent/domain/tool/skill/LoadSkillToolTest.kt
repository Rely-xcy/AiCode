package com.aicode.feature.agent.domain.tool.skill

import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadSkillToolTest {

    private val repository = mockk<SkillRepository>()
    private val tool = LoadSkillTool(repository)

    private fun execute(args: Map<String, JsonElement>): ToolResult = runBlocking { tool.execute(args) }

    private fun textOf(result: ToolResult): String {
        assertTrue("期望成功结果，实际 $result", result is ToolResult.Success)
        return (result as ToolResult.Success).data.jsonPrimitive.content
    }

    private fun body(total: Int): String {
        val sb = StringBuilder(total)
        for (i in 0 until total) sb.append(('a'.code + i % 26).toChar())
        return sb.toString()
    }

    @Test
    fun load_withoutOffsetReadsFirstPageAndPointsToNextOffset() {
        every { repository.loadInstructions("big") } returns body(20_000)

        val text = textOf(execute(mapOf("skill_name" to JsonPrimitive("big"))))

        assertTrue(text, text.contains("本次返回 9000 字符"))
        assertTrue(text, text.contains("总长 20000 字符"))
        assertTrue(text, text.contains("offset=9000"))
        assertTrue(text, !text.contains("已读到结尾"))
    }

    @Test
    fun load_middlePageReportsRange() {
        every { repository.loadInstructions("big") } returns body(20_000)

        val text = textOf(execute(mapOf("skill_name" to JsonPrimitive("big"), "offset" to JsonPrimitive(9000))))

        assertTrue(text, text.contains("第 9001-18000 字符"))
        assertTrue(text, text.contains("offset=18000"))
    }

    @Test
    fun load_lastPageSaysEndReached() {
        every { repository.loadInstructions("big") } returns body(20_000)

        val text = textOf(execute(mapOf("skill_name" to JsonPrimitive("big"), "offset" to JsonPrimitive(18_000))))

        assertTrue(text, text.contains("本次返回 2000 字符"))
        assertTrue(text, text.contains("已读到结尾"))
    }

    @Test
    fun load_defaultOffsetBehavesLikeOffsetZero() {
        every { repository.loadInstructions("big") } returns body(20_000)

        val implicit = textOf(execute(mapOf("skill_name" to JsonPrimitive("big"))))
        val explicit = textOf(execute(mapOf("skill_name" to JsonPrimitive("big"), "offset" to JsonPrimitive(0))))

        assertEquals(implicit, explicit)
    }

    @Test
    fun load_offsetOutOfRangeReturnsReadableError() {
        every { repository.loadInstructions("small") } returns body(10)

        val result = execute(mapOf("skill_name" to JsonPrimitive("small"), "offset" to JsonPrimitive(10)))

        assertTrue(result is ToolResult.Error)
        assertEquals("OFFSET_OUT_OF_RANGE", (result as ToolResult.Error).code)
    }

    @Test
    fun load_negativeOffsetReturnsReadableError() {
        every { repository.loadInstructions("small") } returns body(10)

        val result = execute(mapOf("skill_name" to JsonPrimitive("small"), "offset" to JsonPrimitive(-1)))

        assertTrue(result is ToolResult.Error)
        assertEquals("INVALID_OFFSET", (result as ToolResult.Error).code)
    }

    @Test
    fun load_unknownSkillKeepsNotFoundError() {
        every { repository.loadInstructions("ghost") } returns null

        val result = execute(mapOf("skill_name" to JsonPrimitive("ghost")))

        assertTrue(result is ToolResult.Error)
        assertEquals("SKILL_NOT_FOUND", (result as ToolResult.Error).code)
    }
}
