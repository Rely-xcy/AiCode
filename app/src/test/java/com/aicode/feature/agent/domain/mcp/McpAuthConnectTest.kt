package com.aicode.feature.agent.domain.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权成功后的连接重试（用假连接验证次数）：授权前因无令牌失败的连接不会自己恢复，
 * 成功即停；首次没连上才补一次；两次都失败交给用户手动重连。
 */
class McpAuthConnectTest {

    @Test
    fun connects_once_when_first_attempt_succeeds() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            McpServerStatus.State.CONNECTED
        }

        assertTrue(connected)
        assertEquals(1, attempts)
    }

    @Test
    fun retries_once_when_first_attempt_fails() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            if (attempts == 1) McpServerStatus.State.NEEDS_AUTH else McpServerStatus.State.CONNECTED
        }

        assertTrue(connected)
        assertEquals(2, attempts)
    }

    @Test
    fun stops_after_one_retry_and_reports_failure() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            McpServerStatus.State.FAILED
        }

        assertFalse(connected)
        assertEquals(2, attempts)
    }
}
