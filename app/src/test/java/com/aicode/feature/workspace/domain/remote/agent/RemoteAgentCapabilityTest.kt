package com.aicode.feature.workspace.domain.remote.agent

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentCapabilityTest {

    private val target = RemoteAgentTarget(host = "10.0.0.2", user = "u", root = "/home/u/workspace")

    @Test
    fun `hello 成功且 proto 匹配：L1 且用完即关`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        val detector = RemoteAgentCapabilityDetector(
            openSession = { session },
            probeCommand = { throw AssertionError("L1 成功时不应再探测 L2 命令") }
        )

        assertEquals(RemoteAgentTier.L1_AGENT, detectWithHello(detector, channel, proto = 1))
        assertTrue("探测结束后应关闭会话，避免占着一条 exec", channel.closed)
    }

    @Test
    fun `proto 不兼容：直接落 L3，不尝试 L2`() = runBlocking {
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        var probed = false
        val detector = RemoteAgentCapabilityDetector(
            openSession = { session },
            probeCommand = {
                probed = true
                true
            }
        )

        assertEquals(RemoteAgentTier.L3_SFTP, detectWithHello(detector, channel, proto = 2))
        assertFalse("协议不兼容时按 PROTOCOL.md 第 7 节不应探测 L2", probed)
        assertTrue(channel.closed)
    }

    @Test
    fun `agent 启动失败但 find grep 可用：L2`() = runBlocking {
        var probedCommand: String? = null
        val detector = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("binary missing") },
            probeCommand = { command ->
                probedCommand = command
                true
            }
        )

        assertEquals(RemoteAgentTier.L2_EXEC, detector.detect(target))
        assertTrue("应探测 find/grep，实际 $probedCommand", probedCommand?.contains("find") == true)
        assertTrue("应探测 find/grep，实际 $probedCommand", probedCommand?.contains("grep") == true)
    }

    @Test
    fun `agent 与 find grep 都不可用：L3`() = runBlocking {
        val detector = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("binary missing") },
            probeCommand = { false }
        )
        assertEquals(RemoteAgentTier.L3_SFTP, detector.detect(target))
    }

    @Test
    fun `内存缓存命中后不再启动 agent`() = runBlocking {
        var opens = 0
        val channel = TestAgentChannel()
        val session = RemoteAgentSession("agent", { channel }, requestTimeoutMs = 2_000)
        val detector = RemoteAgentCapabilityDetector(
            openSession = {
                opens++
                session
            },
            probeCommand = { false }
        )

        assertEquals(RemoteAgentTier.L1_AGENT, detectWithHello(detector, channel, proto = 1))
        assertEquals(RemoteAgentTier.L1_AGENT, detector.detect(target))
        assertEquals("第二次应直接命中内存缓存", 1, opens)
    }

    @Test
    fun `L2 写入持久缓存，新探测器直接命中`() = runBlocking {
        val store = RecordingTierStore()
        val first = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("no agent") },
            probeCommand = { true },
            store = store
        )
        assertEquals(RemoteAgentTier.L2_EXEC, first.detect(target))
        assertEquals(RemoteAgentTier.L2_EXEC, store.saved[target.cacheKey])

        var probed = false
        val second = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("no agent") },
            probeCommand = {
                probed = true
                false
            },
            store = store
        )
        assertEquals("新探测器应命中持久缓存", RemoteAgentTier.L2_EXEC, second.detect(target))
        assertFalse(probed)

        second.invalidate(target)
        assertNull(store.saved[target.cacheKey])
    }

    @Test
    fun `L3 不落盘，缓存故障不影响探测结论`() = runBlocking {
        val store = RecordingTierStore()
        val detector = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("no agent") },
            probeCommand = { false },
            store = store
        )
        assertEquals(RemoteAgentTier.L3_SFTP, detector.detect(target))
        assertNull("失败结论不应落盘", store.saved[target.cacheKey])
        assertTrue("应清掉可能过期的正向缓存", store.cleared.contains(target.cacheKey))

        val brokenStore = object : RemoteAgentTierStore {
            override suspend fun load(key: String): RemoteAgentTier? {
                throw IOException("load failed")
            }

            override suspend fun save(key: String, tier: RemoteAgentTier) {
                throw IOException("save failed")
            }

            override suspend fun clear(key: String) {
                throw IOException("clear failed")
            }
        }
        val resilient = RemoteAgentCapabilityDetector(
            openSession = { throw RemoteAgentHandshakeException("no agent") },
            probeCommand = { true },
            store = brokenStore
        )
        assertEquals("缓存故障不能影响探测结论", RemoteAgentTier.L2_EXEC, resilient.detect(target))
    }

    private suspend fun detectWithHello(
        detector: RemoteAgentCapabilityDetector,
        channel: TestAgentChannel,
        proto: Int
    ): RemoteAgentTier = coroutineScope {
        val job = async(Dispatchers.IO) { detector.detect(target) }
        val hello = channel.awaitRequest(0)
        channel.feed(agentFrame("""{"id":${hello.id},"ok":true,"proto":$proto,"bodyLen":0}"""))
        job.await()
    }
}

private class RecordingTierStore : RemoteAgentTierStore {
    val saved = ConcurrentHashMap<String, RemoteAgentTier>()
    val cleared: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    override suspend fun load(key: String): RemoteAgentTier? = saved[key]

    override suspend fun save(key: String, tier: RemoteAgentTier) {
        saved[key] = tier
    }

    override suspend fun clear(key: String) {
        saved.remove(key)
        cleared.add(key)
    }
}
