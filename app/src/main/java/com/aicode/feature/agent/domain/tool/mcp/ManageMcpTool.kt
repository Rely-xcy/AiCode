package com.aicode.feature.agent.domain.tool.mcp

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ManageMcpTool @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository,
    @param:ApplicationContext private val appContext: Context
) : AgentTool() {
    private companion object {
        const val TAG = "ManageMcpTool"
    }

    /**
     * 列出服务器：只给名称、安全端点（仅 scheme/host/port）、键名与开关状态；
     * stdio 的命令与参数、headers / env 的**值**、URL 的 userinfo / 路径 / query / fragment 一律不回显。
     *
     * 该动作走 READ_AGENT_CONFIG 免弹窗，所以输出本身就是「无人工确认也能进上下文」的内容，
     * 凭据不值得也不需要出现在这里。
     */
    private fun describeServers(servers: List<com.aicode.feature.agent.domain.mcp.McpServerConfig>): String {
        if (servers.isEmpty()) return "（没有已配置的 MCP server）"
        return servers.joinToString("\n") { server ->
            val target = if (server.isStdio) {
                "stdio（命令与参数已隐藏）"
            } else {
                safeHttpEndpoint(server.url)
            }
            buildString {
                append("- ").append(server.name)
                if (!server.enabled) append("（已停用）")
                append("：").append(target)
                if (server.headers.isNotEmpty()) {
                    append("；headers 键：").append(server.headers.keys.joinToString(", "))
                }
                if (server.env.isNotEmpty()) {
                    append("；env 键：").append(server.env.keys.joinToString(", "))
                }
                if (server.auth != null) append("；含鉴权配置")
                if (server.disabledTools.isNotEmpty()) {
                    append("；已禁用工具 ").append(server.disabledTools.size).append(" 个")
                }
            }
        }
    }

    private fun safeHttpEndpoint(raw: String?): String {
        val uri = runCatching { java.net.URI(raw.orEmpty()) }.getOrNull() ?: return "HTTP（地址已隐藏）"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return "HTTP（地址已隐藏）"
        return runCatching {
            java.net.URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
        }.getOrDefault("HTTP（地址已隐藏）")
    }

    override val name = "manageMcp"
    override val description = "管理 Model Context Protocol (MCP) 服务器。支持添加、删除与列表查询。添加或删除会修改 Agent 配置，下一次会话生效；不会自动安装 Node/Python 等运行时。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：list=列出服务器；add_stdio=添加本地 stdio 服务；add_http=添加远程 HTTP 服务；remove=移除服务器",
            enum = listOf("list", "add_stdio", "add_http", "remove")
        ),
        "server_name" to ToolParameter(
            name = "server_name",
            type = ParameterType.STRING,
            description = "MCP 服务器名称（操作非 list 时必填），只能含 ASCII 字母、数字、下划线与连字符",
            required = false
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "执行命令，如 npx 或 python (仅 add_stdio 必填)",
            required = false
        ),
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.ARRAY,
            description = "命令参数数组 (仅 add_stdio 可选)",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "HTTP 远程服务地址 (仅 add_http 必填)",
            required = false
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "配置作用域：global（全局，默认）或 project（当前项目，仅当前工作区生效，项目级优先于全局）",
            enum = listOf("global", "project"),
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val server = args["server_name"]?.jsonPrimitive?.contentOrNull ?: "未指定"
        val scope = resolveScope(args)
        val scopeLabel = appContext.getString(if (scope == McpScope.PROJECT) R.string.mcp_scope_project else R.string.mcp_scope_global)
        val summary = when (action) {
            "add_stdio" -> "添加本地 MCP server：$server（$scopeLabel）"
            "add_http" -> "添加 HTTP MCP server：$server（$scopeLabel）"
            "remove" -> "移除 MCP server：$server（$scopeLabel）"
            "list" -> "列出 MCP server（$scopeLabel）"
            else -> "管理 MCP server"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认修改 MCP 配置",
            summary = summary,
            details = "操作：$action\n服务：$server\n作用域：$scopeLabel\n该操作会修改 Agent 的 MCP 配置，新增配置将在下一次会话生效。",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少必需参数：action")
        val scope = resolveScope(args)
        val scopeLabel = appContext.getString(if (scope == McpScope.PROJECT) R.string.mcp_scope_project else R.string.mcp_scope_global)

        suspend fun readServers(): List<McpServerConfig> =
            if (scope == McpScope.PROJECT) mcpConfigRepository.getProjectServers() else mcpConfigRepository.getGlobalServers()

        suspend fun writeServers(servers: List<McpServerConfig>): Boolean {
            if (scope == McpScope.PROJECT) return mcpConfigRepository.setProjectServers(servers)
            mcpConfigRepository.setGlobalServers(servers)
            return true
        }

        // 项目级写入被跳过（工作区未落定）时的失败结果：不能让 AI 以为已经写成功——
        // 否则用户看到的就是「MCP 加了不出现」。
        val projectWriteSkipped = ToolResult.Error("项目级 MCP 配置未写入：工作区尚未就绪，请稍后重试，或改用全局作用域（scope=global）")

        return try {
            when (action) {
                "list" -> {
                    val servers = readServers()
                    // headers / env 里常放 API Key 与 Token，而 list 走 READ_AGENT_CONFIG 免弹窗
                    // （见 ToolPermissionPolicyEngine），原样回显等于把凭据交给模型、进而进上下文与日志。
                    ToolResult.Success(JsonPrimitive(describeServers(servers)))
                }
                "remove" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("remove 缺少必需参数：server_name")
                    val servers = readServers().toMutableList()
                    val removed = servers.removeIf { it.name == name }
                    if (removed) {
                        if (!writeServers(servers)) return projectWriteSkipped
                        ToolResult.Success(JsonPrimitive("已移除 $scopeLabel MCP server：$name"))
                    } else {
                        ToolResult.Error("未找到 $scopeLabel MCP server：$name")
                    }
                }
                "add_stdio" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少必需参数：server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少必需参数：command")
                    val commandArgs = args["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    
                    val newServer = McpServerConfig(
                        name = name,
                        command = command,
                        args = commandArgs,
                        env = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    if (!writeServers(servers)) return projectWriteSkipped
                    
                    ToolResult.Success(JsonPrimitive(appContext.getString(R.string.tool_mcp_stdio_added, scopeLabel, name)))
                }
                "add_http" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少必需参数：server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少必需参数：url")
                    
                    val newServer = McpServerConfig(
                        name = name,
                        url = url,
                        headers = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    if (!writeServers(servers)) return projectWriteSkipped
                    
                    ToolResult.Success(JsonPrimitive("已添加 $scopeLabel HTTP MCP server：$name，配置将在下一次会话生效。"))
                }
                else -> ToolResult.Error("未知的 action：$action")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "manage_mcp 执行失败: ${e.message}", e)
            ToolResult.Error("管理 MCP 失败：${e.message}")
        }
    }

    private fun invalidNameMessage(name: String): String =
        appContext.getString(R.string.tool_mcp_invalid_name, name)

    private fun resolveScope(args: Map<String, JsonElement>): McpScope =
        when (args["scope"]?.jsonPrimitive?.contentOrNull) {
            "project", "PROJECT" -> McpScope.PROJECT
            else -> McpScope.GLOBAL
        }
    
}
