package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.container.SshHostKeyPendingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [classifyRemoteRetry]：聊天页「重试连接」的失败分类。
 *
 * 这五个用例钉的是同一件事——重连链只回报布尔值，调用方必须能把「指纹待确认」与「其它失败」分开，
 * 并且把待确认详情原样露给 UI（弹窗里的指纹必须就是连接层算出来的那个）。
 */
class RemoteRetryOutcomeTest {

    private fun pendingHostKey(changed: Boolean = false) = SshHostKeyPendingException(
        host = "10.0.0.5",
        port = 2222,
        keyType = "ssh-ed25519",
        fingerprint = "SHA256:AbCdEf123456",
        changed = changed
    )

    @Test
    fun pending_host_key_is_surfaced_as_confirmation_state() {
        val outcome = classifyRemoteRetry(
            connected = false,
            pendingHostKey = pendingHostKey(),
            hasConfig = true
        )
        assertTrue(outcome is RemoteRetryOutcome.HostKeyPending)
        val confirmation = (outcome as RemoteRetryOutcome.HostKeyPending).confirmation
        // 指纹逐字搬运，不在归类时重算：弹窗里的指纹必须与连接层校验失败时算出的那个一致
        assertEquals("SHA256:AbCdEf123456", confirmation.fingerprint)
        assertEquals("10.0.0.5", confirmation.host)
        assertEquals(2222, confirmation.port)
        assertEquals("ssh-ed25519", confirmation.keyType)
    }

    @Test
    fun changed_fingerprint_goes_through_the_same_entry() {
        val outcome = classifyRemoteRetry(
            connected = false,
            pendingHostKey = pendingHostKey(changed = true),
            hasConfig = true
        )
        assertTrue(outcome is RemoteRetryOutcome.HostKeyPending)
        assertTrue((outcome as RemoteRetryOutcome.HostKeyPending).confirmation.changed)
    }

    @Test
    fun connected_wins_over_stale_pending() {
        // 重连成功时上一次失败留下的待确认详情必须作废，否则弹窗会在已经连上之后还挂着
        val outcome = classifyRemoteRetry(
            connected = true,
            pendingHostKey = pendingHostKey(),
            hasConfig = true
        )
        assertEquals(RemoteRetryOutcome.Connected, outcome)
    }

    @Test
    fun missing_config_reports_not_configured() {
        val outcome = classifyRemoteRetry(
            connected = false,
            pendingHostKey = null,
            hasConfig = false
        )
        assertEquals(RemoteRetryOutcome.NotConfigured, outcome)
    }

    @Test
    fun configured_but_unreachable_reports_failure() {
        val outcome = classifyRemoteRetry(
            connected = false,
            pendingHostKey = null,
            hasConfig = true
        )
        assertEquals(RemoteRetryOutcome.Failed, outcome)
    }
}
