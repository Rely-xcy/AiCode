package com.aicode.feature.workspace.domain.remote.agent

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteAgentCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `编码：MAGIC、headerLen、bodyLen 与 body 一致`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val frame = RemoteAgentCodec.encode(
            AgentRequest(id = 7, op = "write", path = "/w/a.txt", overwrite = true),
            body
        )

        assertArrayEquals(TEST_MAGIC, frame.copyOfRange(0, 4))
        val headerLen = readHeaderLen(frame)
        assertEquals("8 字节定长头之外应只剩 header", frame.size - 8 - body.size, headerLen)

        val header = json.decodeFromString<AgentRequest>(
            String(frame, 8, headerLen, Charsets.UTF_8)
        )
        assertEquals(7, header.id)
        assertEquals("write", header.op)
        assertEquals("/w/a.txt", header.path)
        assertEquals(body.size, header.bodyLen)
        assertArrayEquals(body, frame.copyOfRange(8 + headerLen, frame.size))
    }

    @Test
    fun `解码：一次读满整帧时字段与 body 正确`() {
        val response = RemoteAgentCodec.decode(
            ByteArrayInputStream(
                agentFrame(
                    """{"id":42,"ok":true,"size":1234,"mtime":1699999999,"isDir":false,"perm":"rw-r--r--","bodyLen":3}""",
                    byteArrayOf(9, 8, 7)
                )
            )
        )

        assertEquals(42, response.id)
        assertTrue(response.ok)
        assertEquals(1234L, response.size)
        assertEquals(1699999999L, response.mtime)
        assertEquals(false, response.isDir)
        assertEquals("rw-r--r--", response.perm)
        assertEquals(3, response.bodyLen)
        assertArrayEquals(byteArrayOf(9, 8, 7), response.body)
    }

    @Test
    fun `解码：每次只给 1 字节（半包）也能读满`() {
        val frame = agentFrame(
            """{"id":1,"ok":true,"bodyLen":22}""",
            "abcdefghijklmnopqrstuv".toByteArray(Charsets.UTF_8)
        )
        val response = RemoteAgentCodec.decode(TrickleInputStream(frame))
        assertEquals(1, response.id)
        assertEquals("abcdefghijklmnopqrstuv", String(response.body, Charsets.UTF_8))
    }

    @Test
    fun `解码：两个帧粘在一起时每次只消费一帧`() {
        val first = agentFrame("""{"id":1,"ok":true,"bodyLen":1}""", byteArrayOf(11))
        val second = agentFrame("""{"id":2,"ok":false,"err":"ENOENT","msg":"no such file","bodyLen":1}""", byteArrayOf(22))
        val input = ByteArrayInputStream(first + second)

        val a = RemoteAgentCodec.decode(input)
        val b = RemoteAgentCodec.decode(input)

        assertEquals(1, a.id)
        assertArrayEquals(byteArrayOf(11), a.body)
        assertEquals(2, b.id)
        assertEquals("ENOENT", b.err)
        assertArrayEquals(byteArrayOf(22), b.body)
        assertEquals("流里不应再有第三帧", -1, input.read())
    }

    @Test
    fun `headerLen 超上限：抛 IOException 且不去分配`() {
        val head = TEST_MAGIC + byteArrayOf(0, 0x10, 0, 1) // 1 MiB + 1
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(head))
        }
    }

    @Test
    fun `bodyLen 超上限：抛 IOException`() {
        val header = """{"id":1,"ok":true,"bodyLen":${RemoteAgentCodec.MAX_BODY_BYTES + 1}}"""
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(agentFrame(header)))
        }
    }

    @Test
    fun `MAGIC 不符：抛 IOException`() {
        val frame = agentFrame("""{"id":1,"ok":true,"bodyLen":0}""", magic = byteArrayOf(0x41, 0x49, 0x43, 0x58))
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun `body 截断：抛 IOException`() {
        val frame = agentFrame("""{"id":1,"ok":true,"bodyLen":10}""")
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun `bodyLen 为负：抛 IOException`() {
        val frame = agentFrame("""{"id":1,"ok":true,"bodyLen":-1}""")
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun `header 缺少 bodyLen：按协议违规拒绝`() {
        val frame = agentFrame("""{"id":1,"ok":true}""")
        assertIoError {
            RemoteAgentCodec.decode(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun `未知字段被忽略`() {
        val response = RemoteAgentCodec.decode(
            ByteArrayInputStream(
                agentFrame("""{"id":5,"ok":true,"bodyLen":0,"futureField":"x","proto":1}""")
            )
        )
        assertEquals(5, response.id)
        assertEquals(1, response.proto)
        assertEquals(0, response.bodyLen)
    }
}

private fun readHeaderLen(frame: ByteArray): Int =
    ((frame[4].toInt() and 0xFF) shl 24) or
        ((frame[5].toInt() and 0xFF) shl 16) or
        ((frame[6].toInt() and 0xFF) shl 8) or
        (frame[7].toInt() and 0xFF)

private inline fun assertIoError(block: () -> Unit) {
    try {
        block()
        fail("应抛 IOException")
    } catch (expected: IOException) {
        // 预期路径：帧非法时解码必须立即失败，而不是返回残缺响应
    }
}

/** 每次 read 只返回 1 字节，复刻半包。 */
private class TrickleInputStream(private val bytes: ByteArray) : InputStream() {
    private var pos = 0

    override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return -1
        if (len == 0) return 0
        b[off] = bytes[pos++]
        return 1
    }
}
