package com.aicode.feature.workspace.domain.remote.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal val TEST_MAGIC = byteArrayOf(0x41, 0x49, 0x43, 0x44)

/** 按协议独立拼帧，不复用生产编码器：编码器有对称 bug 时往返测试才不会互相掩盖。 */
internal fun agentFrame(
    headerJson: String,
    body: ByteArray = ByteArray(0),
    magic: ByteArray = TEST_MAGIC
): ByteArray {
    val header = headerJson.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(8 + header.size + body.size)
    out.write(magic)
    out.write((header.size ushr 24) and 0xFF)
    out.write((header.size ushr 16) and 0xFF)
    out.write((header.size ushr 8) and 0xFF)
    out.write(header.size and 0xFF)
    out.write(header)
    out.write(body)
    return out.toByteArray()
}

/** 记录 agent 侧收到的请求帧字节。 */
internal class RecordingOutputStream : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        buffer.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
    }

    @Synchronized
    fun snapshot(): ByteArray = buffer.toByteArray()
}

/**
 * 内存版 agent 通道：请求字节写进 [RecordingOutputStream]，响应由测试用 [feed] 注入。
 * 用阻塞队列而不是 Piped 流，避免管道「写端未连/读端已死」的额外异常干扰断言。
 */
internal class TestAgentChannel : AgentChannel {
    val recorder = RecordingOutputStream()
    private val inbound = BlockingQueueInputStream()

    override val input: InputStream = inbound
    override val output: OutputStream = recorder
    override val error: InputStream = ByteArrayInputStream(ByteArray(0))

    @Volatile
    var closed = false
        private set

    override fun close() {
        closed = true
        inbound.closeInput()
    }

    fun feed(bytes: ByteArray) = inbound.feed(bytes)

    /** 等第 [index] 个（0 起）完整请求帧出现并解出 header。 */
    suspend fun awaitRequest(index: Int, timeoutMs: Long = 5_000L): AgentRequest {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val requests = parseRequests()
            if (requests.size > index) return requests[index]
            delay(5)
        }
        throw AssertionError("等不到第 ${index + 1} 个请求帧；已写入 ${recorder.snapshot().size} 字节")
    }

    fun parseRequests(): List<AgentRequest> {
        val bytes = recorder.snapshot()
        val result = mutableListOf<AgentRequest>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val headerLen = ((bytes[offset + 4].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 8) or
                (bytes[offset + 7].toInt() and 0xFF)
            if (headerLen <= 0) break
            val headerStart = offset + 8
            val headerEnd = headerStart + headerLen
            if (headerEnd > bytes.size) break
            val request = TEST_JSON.decodeFromString<AgentRequest>(
                String(bytes, headerStart, headerLen, Charsets.UTF_8)
            )
            val bodyEnd = headerEnd + request.bodyLen
            if (bodyEnd > bytes.size) break
            result += request
            offset = bodyEnd
        }
        return result
    }

    private companion object {
        val TEST_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** 按块阻塞读取：没有数据就挂起等待，[closeInput] 用哨兵块唤醒。 */
internal class BlockingQueueInputStream : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = ByteArray(0)
    private var pos = 0

    @Volatile
    private var closed = false

    fun feed(bytes: ByteArray) {
        if (!closed) chunks.put(bytes)
    }

    fun closeInput() {
        closed = true
        chunks.offer(EOF)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            if (pos < current.size) {
                val n = minOf(len, current.size - pos)
                System.arraycopy(current, pos, b, off, n)
                pos += n
                return n
            }
            val next = try {
                chunks.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("读取被中断", e)
            }
            if (next === EOF) return -1
            current = next
            pos = 0
        }
    }

    override fun close() = closeInput()

    private companion object {
        val EOF = ByteArray(0)
    }
}

/**
 * 启动会话并替它接住 hello。测试不能只等着 start()，因为 hello 响应必须由测试侧注入。
 */
internal suspend fun startSessionWithHello(
    session: RemoteAgentSession,
    channel: TestAgentChannel,
    helloHeader: (Int) -> String = { id ->
        """{"id":$id,"ok":true,"proto":1,"go":"1.22","os":"linux","arch":"amd64","hasRg":true,"home":"/home/u","canHash":true,"bodyLen":0}"""
    }
) = coroutineScope {
    val job = launch(Dispatchers.IO) { session.start() }
    val hello = channel.awaitRequest(0)
    channel.feed(agentFrame(helloHeader(hello.id)))
    job.join()
}
