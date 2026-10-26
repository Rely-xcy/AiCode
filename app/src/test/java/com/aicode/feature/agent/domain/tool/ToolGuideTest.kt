package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [renderToolGuide] 的渲染契约：只输出声明过元数据的工具，未声明项不留悬空分隔符，同一输入逐字节稳定。
 *
 * 用最小假工具而不是真工具：这里验的是渲染规则本身，真工具的文案随产品调整，绑上来只会让测试跟着文案改。
 */
class ToolGuideTest {

    private class FakeTool(
        override val name: String,
        override val useWhen: String = "",
        override val avoidWhen: String = "",
        override val successCriteria: String = ""
    ) : AgentTool() {
        override val description = "假工具"
        override val parameters: Map<String, ToolParameter> = emptyMap()

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    @Test
    fun no_declared_metadata_renders_empty_string() {
        val tools = listOf(FakeTool(name = "a"), FakeTool(name = "b"))

        assertEquals("", renderToolGuide(tools))
    }

    @Test
    fun all_three_fields_render_one_line_with_every_segment() {
        val tools = listOf(
            FakeTool(
                name = "readFile",
                useWhen = "读文件",
                avoidWhen = "写文件用 writeFile",
                successCriteria = "返回带 content"
            )
        )

        assertEquals(
            "- readFile：适用 读文件；不适用 写文件用 writeFile；成功判定 返回带 content",
            renderToolGuide(tools)
        )
    }

    @Test
    fun a_single_declared_field_renders_without_dangling_separator() {
        val tools = listOf(FakeTool(name = "todo", successCriteria = "返回里 next 为空"))

        assertEquals("- todo：成功判定 返回里 next 为空", renderToolGuide(tools))
    }

    @Test
    fun blank_fields_count_as_undeclared_and_are_trimmed() {
        val tools = listOf(FakeTool(name = "t", useWhen = "  ", avoidWhen = "\n", successCriteria = " 看到 ok "))

        assertEquals("- t：成功判定 看到 ok", renderToolGuide(tools))
    }

    @Test
    fun tools_without_metadata_are_skipped_and_order_follows_input() {
        val tools = listOf(
            FakeTool(name = "a"),
            FakeTool(name = "b", useWhen = "B 的适用场景"),
            FakeTool(name = "c"),
            FakeTool(name = "d", useWhen = "D 的适用场景")
        )

        assertEquals("- b：适用 B 的适用场景\n- d：适用 D 的适用场景", renderToolGuide(tools))
    }

    @Test
    fun same_input_renders_byte_identical_text_twice() {
        val first = renderToolGuide(
            listOf(
                FakeTool(name = "a", useWhen = "x", avoidWhen = "y", successCriteria = "z"),
                FakeTool(name = "b", useWhen = "w")
            )
        )
        val second = renderToolGuide(
            listOf(
                FakeTool(name = "a", useWhen = "x", avoidWhen = "y", successCriteria = "z"),
                FakeTool(name = "b", useWhen = "w")
            )
        )

        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
    }
}
