package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.mcp.McpAuthorizeResult
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthCancelledException

/**
 * 把一次 OAuth 授权结果转成弹窗状态。
 *
 * 授权成功与连接成功是两件事：[McpAuthorizeResult.connected] 原样透传到
 * [McpAuthUiState.connected]，只有 true 才允许 UI 说「已连接」；false 与 null（没拿到重连结果）
 * 都不得声称已连接，否则界面会把「授权完但连不上」说成「授权成功，已连接」。
 *
 * 取消授权不展示失败原因（用户自己点的取消），回到初始状态。
 */
internal fun mcpAuthUiState(serverName: String, result: Result<McpAuthorizeResult>): McpAuthUiState {
    val error = result.exceptionOrNull()
    return when {
        error == null -> McpAuthUiState(
            serverName = serverName,
            success = true,
            connected = result.getOrNull()?.connected
        )
        error is McpOAuthCancelledException -> McpAuthUiState()
        else -> McpAuthUiState(serverName = serverName, error = error.message)
    }
}
