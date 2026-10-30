package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.subagent.SubagentModelTier
import com.aicode.feature.agent.domain.tool.subagent.TaskTool
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolTest {

    private class TestTool(
        override val name: String = "test_tool",
        override val description: String = "Test Tool",
        override val parameters: Map<String, ToolParameter>
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            return ToolResult.Success(kotlinx.serialization.json.JsonNull)
        }
    }

    @Test
    fun arrayParameter_withItemsSchema_serializesGivenSchema() {
        val customItems = mapOf(
            "type" to "object",
            "properties" to mapOf("key" to mapOf("type" to "string"))
        )
        val tool = TestTool(
            parameters = mapOf(
                "items" to ToolParameter(
                    name = "items",
                    type = ParameterType.ARRAY,
                    description = "Test array",
                    itemsSchema = customItems
                )
            )
        )

        val schema = tool.toJsonSchema()
        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Map<String, Any>>
        val itemsProp = props["items"]
        assertNotNull(itemsProp)
        assertEquals("array", itemsProp?.get("type"))
        assertEquals(customItems, itemsProp?.get("items"))
    }

    @Test
    fun arrayParameter_withoutItemsSchema_fallbacksToStringItemSchema() {
        val tool = TestTool(
            parameters = mapOf(
                "tags" to ToolParameter(
                    name = "tags",
                    type = ParameterType.ARRAY,
                    description = "Tags array without explicit itemsSchema"
                )
            )
        )

        val schema = tool.toJsonSchema()
        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Map<String, Any>>
        val tagsProp = props["tags"]
        assertNotNull(tagsProp)
        assertEquals("array", tagsProp?.get("type"))
        // 保证即使未配置 itemsSchema，也必须生成 items 属性，防止 Gemini / OpenAPI 报 400 missing field
        val itemsSchema = tagsProp?.get("items")
        assertNotNull("Array 必须包含 items 字段", itemsSchema)
        assertEquals(mapOf("type" to "string"), itemsSchema)
    }

    @Test
    fun taskTool_tasksParameter_hasCorrectItemsSchema() {
        val taskTool = TaskTool(
            sessionUseCase = mockk(relaxed = true),
            chatSessionDao = mockk(relaxed = true),
            agentMessageDao = mockk(relaxed = true),
            eventBus = mockk(relaxed = true),
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk(relaxed = true),
            appContext = mockk(relaxed = true)
        )

        val tasksParam = taskTool.parameters["tasks"]
        assertNotNull("TaskTool 必须包含 tasks 参数", tasksParam)
        assertEquals(ParameterType.ARRAY, tasksParam?.type)

        val itemsSchema = tasksParam?.itemsSchema
        assertNotNull("tasks 参数必须配置 itemsSchema", itemsSchema)
        assertEquals("object", itemsSchema?.get("type"))

        @Suppress("UNCHECKED_CAST")
        val properties = itemsSchema?.get("properties") as? Map<String, Map<String, Any>>
        assertNotNull("itemsSchema 必须有 properties", properties)
        assertTrue("properties 必须包含 prompt", properties?.containsKey("prompt") == true)
        assertTrue("properties 必须包含 description", properties?.containsKey("description") == true)
        assertTrue("properties 必须包含 agent", properties?.containsKey("agent") == true)
        assertTrue("properties 必须包含 tier", properties?.containsKey("tier") == true)

        @Suppress("UNCHECKED_CAST")
        val tierEnum = properties?.get("tier")?.get("enum") as? List<String>
        assertEquals(SubagentModelTier.entries.map { it.apiValue }, tierEnum)

        val required = itemsSchema?.get("required") as? List<*>
        assertEquals(listOf("prompt"), required)

        // 验证 toJsonSchema 生成的结果
        val toolSchema = taskTool.toJsonSchema()
        @Suppress("UNCHECKED_CAST")
        val toolProps = toolSchema["properties"] as Map<String, Map<String, Any>>
        val tasksProp = toolProps["tasks"]
        assertNotNull(tasksProp)
        assertNotNull("生成的 schema 中 tasks.items 字段不可为空", tasksProp?.get("items"))
    }
}
