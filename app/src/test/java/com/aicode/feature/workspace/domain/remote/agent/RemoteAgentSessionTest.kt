package com.aicode.feature.workspace.domain.remote.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentSessionTest {

    @Test
    fun `hello 握手成功后缓存能力位`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        startSessionWithHello(session, channel)

        assertEquals(1, session.capabilities?.proto)
        assertEquals("amd64", session.capabilities?.arch)
        assertEquals(true, session.capabilities?.hasRg)
        assertEquals(true, session.capabilities?.canHash)
        session.close()
    }

    @Test
    fun `响应乱序到达仍按 id 配对`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        startSessionWithHello(session, channel)

        val a = async(Dispatchers.IO) { session.request("stat", AgentRequestParams(path = "/a")) }
        val b = async(Dispatchers.IO) { session.request("stat", AgentRequestParams(path = "/b")) }
        val first = channel.awaitRequest(1)
        val second = channel.awaitRequest(2)
        val aFrame = listOf(first, second).first { it.path == "/a" }
        val bFrame = listOf(first, second).first { it.path == "/b" }

        // 先回 b 再回 a：配对必须靠 id，不能靠到达顺序
        channel.feed(agentFrame("""{"id":${bFrame.id},"ok":true,"size":300,"bodyLen":0}"""))
        channel.feed(agentFrame("""{"id":${aFrame.id},"ok":true,"size":200,"bodyLen":0}"""))

        assertEquals(200L, a.await().size)
        assertEquals(300L, b.await().size)
        session.close()
    }

    @Test
    fun `多余响应被丢弃且不影响后续请求`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        startSessionWithHello(session, channel)

        channel.feed(agentFrame("""{"id":999,"ok":true,"size":1,"bodyLen":0}"""))
        val request = async(Dispatchers.IO) {
            session.request("stat", AgentRequestParams(path = "/a"))
        }
        val frame = channel.awaitRequest(1)
        channel.feed(agentFrame("""{"id":${frame.id},"ok":true,"size":7,"bodyLen":0}"""))

        assertEquals(7L, request.await().size)
        session.close()
    }

    @Test
    fun `单次超时只废弃该请求，连接仍可用`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 120)
        startSessionWithHello(session, channel)

        val slow = runCatching {
            session.request("stat", AgentRequestParams(path = "/slow"))
        }.exceptionOrNull()
        assertTrue("首次超时应是可恢复错误，实际 $slow", slow is RemoteAgentTimeoutException)
        assertFalse("超时不应关闭连接", channel.closed)

        val slowFrame = channel.awaitRequest(1)
        // 迟到响应只能被丢弃，不能错配给下一个请求
        channel.feed(agentFrame("""{"id":${slowFrame.id},"ok":true,"size":111,"bodyLen":0}"""))

        val next = async(Dispatchers.IO) {
            session.request("stat", AgentRequestParams(path = "/next"))
        }
        val nextFrame = channel.awaitRequest(2)
        channel.feed(agentFrame("""{"id":${nextFrame.id},"ok":true,"size":9,"bodyLen":0}"""))
        assertEquals(9L, next.await().size)
        session.close()
    }

    @Test
    fun `连续 3 次超时判定连接不可用并关闭`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession(
            "agent",
            { channel },
            requestTimeoutMs = 80,
            maxConsecutiveTimeouts = 3
        )
        startSessionWithHello(session, channel)

        val errors = (1..3).map { i ->
            runCatching { session.request("stat", AgentRequestParams(path = "/t$i")) }.exceptionOrNull()
        }
        assertTrue("第 1 次应是请求级超时，实际 ${errors[0]}", errors[0] is RemoteAgentTimeoutException)
        assertTrue("第 2 次应是请求级超时，实际 ${errors[1]}", errors[1] is RemoteAgentTimeoutException)
        assertTrue("第 3 次应判连接不可用，实际 ${errors[2]}", errors[2] is RemoteAgentUnavailableException)
        assertTrue("判定不可用后应关闭通道", channel.closed)

        val after = runCatching {
            session.request("stat", AgentRequestParams(path = "/x"))
        }.exceptionOrNull()
        assertTrue("后续请求应快速失败，实际 $after", after is RemoteAgentUnavailableException)
    }

    @Test
    fun `close 让未完成请求立即失败`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 5_000)
        startSessionWithHello(session, channel)

        val pending = async(Dispatchers.IO) {
            runCatching { session.request("stat", AgentRequestParams(path = "/hang")) }.exceptionOrNull()
        }
        channel.awaitRequest(1)
        session.close()

        val error = withTimeout(2_000) { pending.await() }
        assertTrue("关闭后未完成请求应抛不可用，实际 $error", error is RemoteAgentUnavailableException)
        assertTrue(channel.closed)
    }

    @Test
    fun `启动命令对路径做 shell 转义并展开 home`() {
        assertEquals(
            "'/opt/a b/agent' -root '/home/u/my work'",
            RemoteAgentLaunch.command("/opt/a b/agent", "/home/u/my work")
        )
        assertEquals(
            "\"\$HOME/.aicode/agent/x\" -root \"\$HOME/workspace\"",
            RemoteAgentLaunch.command("~/.aicode/agent/x", "~/workspace")
        )
    }
}
