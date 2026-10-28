package com.aicode.feature.workspace.domain.remote.agent

import java.io.InputStream
import java.io.OutputStream
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * 把 sshj 的 exec 通道包成 [AgentChannel]，供 [RemoteAgentSession] 收发协议帧。
 *
 * 三根流与 sshj 的对应关系容易搞反，记在这里：`command.inputStream` 是远端进程的
 * **stdout**（我们读它），`command.outputStream` 是它的 **stdin**（我们写它），
 * `command.errorStream` 是 stderr（日志，不参与协议）。
 *
 * 为什么不在 [com.aicode.feature.agent.domain.container.RemoteSshConnection] 里直接返回
 * [AgentChannel]：那一层属于 agent domain，不该知道 workspace 侧的协议抽象；反过来
 * [AgentChannel] 也不想依赖 sshj 的具体类型——单测用内存流就能跑完整个会话逻辑。
 * 这一个类就是两条边之间的适配器，跨依赖只此一处。
 *
 * 关闭只关命令通道：`Session.Command` 不回指 `Session`，而远端 agent 是「stdin 读到 EOF
 * 就退出」的主循环，关掉通道就等于通知它收工，不需要另去关会话。
 */
internal class SshAgentChannel(private val command: Session.Command) : AgentChannel {

    override val input: InputStream get() = command.inputStream

    override val output: OutputStream get() = command.outputStream

    override val error: InputStream get() = command.errorStream

    override fun close() {
        // Session.Command 没有回指 Session 的引用，只能关通道本身；
        // 会话随远端进程退出自然结束（stdin 关闭即 EOF，agent 主循环 return）。
        runCatching { command.close() }
    }
}
