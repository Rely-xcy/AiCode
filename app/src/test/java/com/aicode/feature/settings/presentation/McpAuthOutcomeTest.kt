package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.mcp.McpAuthorizeResult
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mcpAuthUiState]：授权结果到弹窗状态的三档映射。
 *
 * 钉的是同一件事——授权成功不等于已连接：只有重连结果为 true 时状态才带「已连接」，
 * 明确失败与拿不到结果（null）都不能让 UI 声称连接成功。
 */
class McpAuthOutcomeTest {

    @Test
    fun authorized_and_connected_keeps_both_facts() {
        val state = mcpAuthUiState("demo", Result.success(McpAuthorizeResult(connected = true)))

        assertTrue(state.success)
        assertEquals(true, state.connected)
        assertEquals("demo", state.serverName)
        assertNull(state.error)
    }

    @Test
    fun authorized_but_not_connected_is_not_reported_as_connected() {
        val state = mcpAuthUiState("demo", Result.success(McpAuthorizeResult(connected = false)))

        // 授权本身成功（不能报成授权失败），但连接结果必须如实是 false
        assertTrue(state.success)
        assertEquals(false, state.connected)
        assertFalse(state.connected == true)
    }

    @Test
    fun unknown_connection_result_stays_unknown() {
        val state = mcpAuthUiState("demo", Result.success(McpAuthorizeResult(connected = null)))

        assertTrue(state.success)
        assertNull(state.connected)
    }

    @Test
    fun cancellation_resets_state_without_error() {
        val state = mcpAuthUiState("demo", Result.failure(McpOAuthCancelledException()))

        assertFalse(state.success)
        assertNull(state.serverName)
        assertNull(state.error)
        assertNull(state.connected)
    }

    @Test
    fun authorization_failure_keeps_reason_and_drops_connection_claim() {
        val state = mcpAuthUiState("demo", Result.failure(IllegalStateException("boom")))

        assertFalse(state.success)
        assertNull(state.connected)
        assertEquals("demo", state.serverName)
        assertNotNull(state.error)
        assertEquals("boom", state.error)
    }
}
