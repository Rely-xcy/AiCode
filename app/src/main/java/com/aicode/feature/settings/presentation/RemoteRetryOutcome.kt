package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.container.SshHostKeyPendingException
import com.aicode.feature.workspace.presentation.remote.PendingHostKeyConfirmation

/**
 * 聊天页「重试连接」一次的结果分类。
 *
 * 重连链 [com.aicode.feature.agent.domain.container.RemoteSshConnection.tryReconnectIfDisconnected]
 * 只回报一个布尔值：返回 false 既可能是网络不通、认证被拒，也可能是主机密钥尚未确认。把它直接丢进
 * FAILED 状态，用户看到的就是一个什么都不说的红点——这正是「点重试没反应」的来源。
 */
sealed interface RemoteRetryOutcome {
    /** 已连通。 */
    data object Connected : RemoteRetryOutcome

    /** 主机密钥待确认：聊天页据此弹出与「连接配置」页同一个确认弹窗。 */
    data class HostKeyPending(val confirmation: PendingHostKeyConfirmation) : RemoteRetryOutcome

    /** 当前没有任何连接配置（连接层 config 为空），重连链直接返回 false，无需尝试重连。 */
    data object NotConfigured : RemoteRetryOutcome

    /** 有配置但没连上，且不是主机密钥待确认：网络或认证层面的失败。 */
    data object Failed : RemoteRetryOutcome
}

/**
 * 归类一次「重试连接」的结果，决定聊天页把哪一样东西露给用户。
 *
 * 先判连通：重连成功时上一次失败留下的待确认详情必须一并清掉，否则连接已恢复而确认弹窗还挂着，
 * 用户会以为连接没成功。
 *
 * 再判待确认：指纹只从 [SshHostKeyPendingException] 原样搬运，不在这里重新计算，
 * 保证与「连接配置」页展示的是同一个指纹、同一份 changed 语义。
 *
 * @param connected 重连链是否最终连通。
 * @param pendingHostKey 仅当主机密钥待确认时非空；调用方需按 host/port 匹配当前配置后再传入。
 * @param hasConfig 连接层是否持有连接配置（决定失败时能否给出「未配置」这一具体原因）。
 */
internal fun classifyRemoteRetry(
    connected: Boolean,
    pendingHostKey: SshHostKeyPendingException?,
    hasConfig: Boolean
): RemoteRetryOutcome = when {
    connected -> RemoteRetryOutcome.Connected
    pendingHostKey != null -> RemoteRetryOutcome.HostKeyPending(
        PendingHostKeyConfirmation(
            host = pendingHostKey.host,
            port = pendingHostKey.port,
            keyType = pendingHostKey.keyType,
            fingerprint = pendingHostKey.fingerprint,
            changed = pendingHostKey.changed
        )
    )
    !hasConfig -> RemoteRetryOutcome.NotConfigured
    else -> RemoteRetryOutcome.Failed
}
