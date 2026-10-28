package com.aicode.feature.workspace.domain.remote.agent

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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
        val process = ProcessBuilder(binary!!.absolutePath, "-root", root.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
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

    /** 把 assets 里当前架构的 gzip 解到临时文件并加可执行位；架构不支持时返回 null。 */
    private fun extractBuiltinBinary(): File? {
        val suffix = when (System.getProperty("os.arch")?.lowercase()) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        val gz = File("src/main/assets/remote-agent/aicode-agent-v1-linux-$suffix.gz")
        if (!gz.isFile) return null
        val out = Files.createTempFile("aicode-agent-", "").toFile()
        GZIPInputStream(gz.inputStream()).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out.setExecutable(true)
        return out
    }
}

/** 把一个真实子进程的三根流包成 [AgentChannel]：stdout 读、stdin 写、stderr 丢弃。 */
private class ProcessAgentChannel(private val process: Process) : AgentChannel {

    override val input: InputStream get() = process.inputStream

    override val output: OutputStream get() = process.outputStream

    // 测试里不看 stderr：agent 的日志行会写满管道缓冲，读走丢掉才不会把进程堵死。
    override val error: InputStream get() = InputStream.nullInputStream()

    override fun close() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
