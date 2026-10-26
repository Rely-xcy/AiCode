package com.aicode.feature.agent.domain.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权成功后的连接重试（用假连接验证次数与结论）：授权前因无令牌失败的连接不会自己恢复，
 * 成功即停；首次没连上才补一次；两次都失败交给用户手动重连；
 * 两次都拿不到状态时结论是 null（未知），不能当成已连接。
 */
class McpAuthConnectTest {

    @Test
    fun connects_once_when_first_attempt_succeeds() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            McpServerStatus.State.CONNECTED
        }

        assertEquals(true, connected)
        assertEquals(1, attempts)
    }

    @Test
    fun retries_once_when_first_attempt_fails() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            if (attempts == 1) McpServerStatus.State.NEEDS_AUTH else McpServerStatus.State.CONNECTED
        }

        assertEquals(true, connected)
        assertEquals(2, attempts)
    }

    @Test
    fun stops_after_one_retry_and_reports_failure() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            McpServerStatus.State.FAILED
        }

        assertEquals(false, connected)
        assertEquals(2, attempts)
    }

    @Test
    fun reports_unknown_when_no_status_was_observed() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            null
        }

        assertNull(connected)
        assertEquals(2, attempts)
    }

    @Test
    fun a_failed_attempt_after_an_unknown_one_is_still_a_failure() = runBlocking {
        // 一次没拿到状态、另一次明确失败：能确定的就是「没连上」，不能退化成未知。
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            if (attempts == 1) null else McpServerStatus.State.FAILED
        }

        assertEquals(false, connected)
    }

    @Test
    fun success_on_retry_is_reported_as_connected() = runBlocking {
        var attempts = 0
        val connected = retryConnectAfterAuthorize("demo") {
            attempts++
            if (attempts == 1) null else McpServerStatus.State.CONNECTED
        }

        assertTrue(connected == true)
    }
}
