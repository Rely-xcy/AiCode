package com.aicode.feature.agent.domain.tool.mcp

import com.aicode.feature.agent.domain.mcp.McpAuthConfig
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageMcpToolListRedactionTest {

    @Test
    fun list_hidesAllCredentialBearingTargetsWithoutConfirmation() = runBlocking {
        val sentinels = listOf(
            "arg-secret-sentinel",
            "query-secret-sentinel",
            "userinfo-secret-sentinel",
            "fragment-secret-sentinel",
            "header-secret-sentinel",
            "env-secret-sentinel",
            "oauth-secret-sentinel"
        )
        val servers = listOf(
            McpServerConfig(
                name = "stdio-server",
                command = "/usr/bin/secret-command",
                args = listOf("--api-key=arg-secret-sentinel"),
                env = mapOf("TOKEN" to "env-secret-sentinel"),
                headers = mapOf("Authorization" to "header-secret-sentinel")
            ),
            McpServerConfig(
                name = "http-server",
                url = "https://userinfo-secret-sentinel@example.com/private/query-secret-sentinel?token=query-secret-sentinel#fragment-secret-sentinel",
                auth = McpAuthConfig(clientId = "oauth-secret-sentinel")
            )
        )
        val repository = mockk<McpConfigRepository>()
        coEvery { repository.getGlobalServers() } returns servers

        val result = ManageMcpTool(repository, mockk()).execute(mapOf("action" to JsonPrimitive("list")))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).data.jsonPrimitive.content
        sentinels.forEach { sentinel -> assertFalse("列表泄露 $sentinel: $output", output.contains(sentinel)) }
        assertFalse(output.contains("/usr/bin/secret-command"))
        assertTrue(output.contains("stdio（命令与参数已隐藏）"))
        assertTrue(output.contains("https://example.com"))
        assertTrue(output.contains("headers 键：Authorization"))
        assertTrue(output.contains("env 键：TOKEN"))
        assertTrue(output.contains("含鉴权配置"))
    }

    @Test
    fun list_hidesInvalidOrNonHttpUrlsInsteadOfEchoingThem() = runBlocking {
        val repository = mockk<McpConfigRepository>()
        coEvery { repository.getGlobalServers() } returns listOf(
            McpServerConfig(name = "invalid", url = "not-a-url?secret=sentinel"),
            McpServerConfig(name = "file", url = "file:///tmp/sentinel")
        )

        val result = ManageMcpTool(repository, mockk()).execute(mapOf("action" to JsonPrimitive("list")))
        val output = (result as ToolResult.Success).data.jsonPrimitive.content

        assertFalse(output.contains("sentinel"))
        assertTrue(output.contains("HTTP（地址已隐藏）"))
    }
}
