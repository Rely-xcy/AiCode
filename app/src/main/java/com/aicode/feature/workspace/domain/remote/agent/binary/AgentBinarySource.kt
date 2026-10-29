package com.aicode.feature.workspace.domain.remote.agent.binary

import com.aicode.feature.workspace.domain.remote.agent.RemoteAgentArch

/**
 * agent 二进制的一个来源。按优先级串起来：缓存 → APK 内置 → 网络下载（见 RemoteAgentBinaries）。
 *
 * `load` 拿不到就返回 null，**不抛异常**：「这个来源没有」是常态，交给下一个来源；
 * 只有全部来源都拿不到时，上层才会抛错让远程模式降级回 SFTP。
 */
interface AgentBinarySource {
    /** 来源名，仅用于日志（"cache" / "assets" / "network"）。 */
    val name: String

    suspend fun load(arch: RemoteAgentArch): ByteArray?
}
