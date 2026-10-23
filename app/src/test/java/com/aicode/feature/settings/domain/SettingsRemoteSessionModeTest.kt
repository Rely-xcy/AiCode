package com.aicode.feature.settings.domain

import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切回本地模式时远程会话必须真正停下：只改 ExecutionModeHolder 不会，连接层自己不看模式。
 *
 * 两个被测点分别对应两种漏法——连接没断（transport 与 30s keepalive 继续跑）、
 * 重连监督没停（断开后又被指数退避拉回来，重复建连）。
 */
class SettingsRemoteSessionModeTest {

    @Test
    fun switching_to_local_mode_disconnects_the_remote_session_exactly_once() = runTest {
        val connection = mockk<RemoteSshConnection>(relaxed = true)

        connection.disconnectQuietly()

        coVerify(exactly = 1) { connection.disconnect() }
    }

    @Test
    fun disconnect_failure_is_logged_and_not_rethrown() = runTest {
        val connection = mockk<RemoteSshConnection>()
        coEvery { connection.disconnect() } throws IllegalStateException("transport 已失效")
        val logged = mutableListOf<String>()

        connection.disconnectQuietly { message, _ -> logged += message }

        assertEquals("断开失败必须留下一条日志", 1, logged.size)
        assertTrue(logged.single().contains("断开"))
    }

    @Test
    fun local_mode_neither_starts_nor_keeps_the_reconnect_supervisor() = runTest {
        var starts = 0
        var stops = 0
        val gate = RemoteSupervisorGate(start = { starts++ }, stop = { stops++ })

        gate.onMode(ExecutionMode.LOCAL_PROOT)

        assertEquals("本地模式下不得启动重连监督", 0, starts)
        assertEquals("从未启动过就无需停止", 0, stops)
    }

    @Test
    fun switching_back_to_remote_starts_the_supervisor_once_per_switch() = runTest {
        var starts = 0
        var stops = 0
        val gate = RemoteSupervisorGate(start = { starts++ }, stop = { stops++ })

        gate.onMode(ExecutionMode.REMOTE_SSH)
        gate.onMode(ExecutionMode.REMOTE_SSH)
        assertEquals("同一模式下重复回调不得再起一个监督循环，否则会重复建连", 1, starts)

        gate.onMode(ExecutionMode.LOCAL_PROOT)
        gate.onMode(ExecutionMode.LOCAL_PROOT)
        assertEquals("切回本地只停一次", 1, stops)

        gate.onMode(ExecutionMode.REMOTE_SSH)
        assertEquals("切回远程要重新启动监督，否则断线后不再重连", 2, starts)
    }
}
