package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.FileLogger

private const val TAG = "RemoteAgentLauncher"

/**
 * 一条可用的 agent 会话，附带它落在远端的身份信息。
 *
 * 把 [installation] 一起带出来是为了让调用方知道这次到底有没有重新上传（`uploaded`），
 * 排查「为什么连上去慢」时第一件事就是看这个。
 */
data class RemoteAgentConnection(
    val session: RemoteAgentSession,
    val installation: RemoteAgentInstallation
) {
    val capabilities: AgentCapabilities? get() = session.capabilities

    fun close() = session.close()
}

/**
 * 把「部署二进制 → 起 exec 通道 → hello 握手」串成一次调用。
 *
 * 这层刻意保持成普通类而不是 Hilt 单例：它需要的三样东西（[RemoteAgentProvisioner]、
 * 开通道的 lambda、工作区根目录）都要等用户连上某台具体服务器之后才有值，
 * 由持有连接的那一侧现造一个实例，比在 DI 图里传一堆 `Provider<String>` 干净。
 *
 * 失败不吞：[RemoteAgentProvisionException] 表示二进制没落好，
 * [RemoteAgentHandshakeException] 表示进程起来了但握手没成——两者对调用方的处置完全不同
 * （前者可以降级 L3，后者通常重试一次就好），所以不在这里合并成同一种异常。
 */
class RemoteAgentLauncher(
    private val provisioner: RemoteAgentProvisioner,
    /** 用 launchCommand 开一条保持 stdin 的 exec 通道；生产实现见 SshAgentChannel。 */
    private val openChannel: (String) -> AgentChannel,
    /** 远端工作区根目录，agent 的所有路径都必须落在它里面。 */
    private val root: String,
    private val requestTimeoutMs: Long = REMOTE_AGENT_DEFAULT_TIMEOUT_MS
) {

    init {
        require(root.isNotBlank()) { "root 不能为空" }
    }

    /**
     * 确保 agent 就绪并完成握手。调用方负责 [RemoteAgentConnection.close]。
     *
     * 握手失败时会先把自己开的通道关掉再抛：那条 exec 会话里跑的是个没能完成握手的进程，
     * 留着只会占着一对 channel 直到连接断开。
     */
    suspend fun connect(): RemoteAgentConnection {
        val installation = provisioner.ensureInstalled()
        FileLogger.d(
            TAG,
            "agent 就绪: ${installation.binaryPath} arch=${installation.arch.suffix} " +
                "uploaded=${installation.uploaded} timeout=${requestTimeoutMs}ms"
        )
        val launchCommand = installation.launchCommand(root)
        val session = RemoteAgentSession(
            launchCommand = launchCommand,
            openChannel = openChannel,
            requestTimeoutMs = requestTimeoutMs
        )
        try {
            session.start()
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
        FileLogger.d(TAG, "agent 握手完成: proto=${session.capabilities?.proto}")
        return RemoteAgentConnection(session, installation)
    }
}
