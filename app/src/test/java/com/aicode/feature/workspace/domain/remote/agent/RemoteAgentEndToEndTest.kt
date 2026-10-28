package com.aicode.feature.workspace.domain.remote.agent

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 端到端：真实的 Go agent 二进制 + 真实的 [RemoteAgentSession]，只把 SSH 传输换成进程管道。
 *
 * 为什么值得单独一层测试：单元测试用的是内存 channel，验证的是「Kotlin 侧编解码与会话语义
 * 自洽」；但协议是两边互认的，光自己闭合不能说明对面那只二进制听得懂。这里直接把它拉起来
 * 对话——握手、写、读、越界拒绝四条，覆盖了最容易两边理解不一致的地方。
 *
 * 仍然测不到的：SSH 传输本身（SshAgentChannel）、上传与 chmod（Provisioner）、
 * 以及远端文件系统差异。那些只能真机验证。
 *
 * 二进制取自 assets 里当前架构那份 gzip，解到临时目录再执行；架构不支持时用
 * [assumeTrue] 跳过而不是让测试变红——CI 跑的是 linux/amd64，本地开发机可能是 arm64。
 */
class RemoteAgentEndToEndTest {

    @Test
    fun `真实二进制跑通 hello 写读与根外拒绝`() = runBlocking {
        val binary = extractBuiltinBinary()
        assumeTrue("当前架构没有内置 agent 二进制，跳过", binary != null)
        val root = Files.createTempDirectory("agent-e2e-root").toFile()
        val process = ProcessBuilder(binary!!.absolutePath, "-root", root.absolutePath).start()
        val session = RemoteAgentSession(
            launchCommand = "test",
            openChannel = { ProcessAgentChannel(process) }
        )
        try {
            session.start()

            val caps = session.capabilities
            assertNotNull("hello 应带回能力位", caps)
            assertEquals("协议版本", REMOTE_AGENT_PROTOCOL_VERSION, caps?.proto)

            val payload = "你好 agent\nsecond line\n".toByteArray(Charsets.UTF_8)
            val target = File(root, "a.txt").absolutePath
            val wrote = session.request("write", AgentRequestParams(path = target), payload)
            assertTrue("write 应成功：${wrote.err} ${wrote.msg}", wrote.ok)
            assertEquals("写回的文件大小", payload.size.toLong(), wrote.size)

            val read = session.request(
                "read",
                AgentRequestParams(path = target, offset = 0, len = 65536)
            )
            assertTrue("read 应成功：${read.err} ${read.msg}", read.ok)
            assertArrayEquals("读回的字节应与写入一致", payload, read.body)

            val denied = session.request("stat", AgentRequestParams(path = "/etc/passwd"))
            assertFalse("根外路径必须被拒", denied.ok)
            assertEquals("越界错误码", "EINVAL", denied.err)
        } finally {
            session.close()
            root.deleteRecursively()
        }
    }

    /** 取 assets 里当前架构的二进制到临时文件并加可执行位；架构不支持时返回 null。 */
    private fun extractBuiltinBinary(): File? {
        val suffix = when (System.getProperty("os.arch")?.lowercase()) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        val asset = File("src/main/assets/remote-agent/aicode-agent-v1-linux-$suffix")
        if (!asset.isFile) return null
        val out = Files.createTempFile("aicode-agent-", "").toFile()
        asset.copyTo(out, overwrite = true)
        out.setExecutable(true)
        return out
    }
}

/** 把一个真实子进程的三根流包成 [AgentChannel]：stdout 读、stdin 写、stderr 后台抽干。 */
private class ProcessAgentChannel(private val process: Process) : AgentChannel {

    // 不抽干 stderr 会把进程堵死：agent 每处理一帧就往 stderr 写一行，
    // 管道缓冲（通常 64KB）满了之后它就会阻塞在写日志上，不再响应请求。
    // 也不用 ProcessBuilder.Redirect.DISCARD —— 那是 Java 9+ API，本项目编译目标不是它。
    private val drain = Thread {
        runCatching { process.errorStream.readBytes() }
    }.apply {
        isDaemon = true
        start()
    }

    override val input: InputStream get() = process.inputStream

    override val output: OutputStream get() = process.outputStream

    override val error: InputStream get() = InputStream.nullInputStream()

    override fun close() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
