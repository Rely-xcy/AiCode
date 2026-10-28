package com.aicode.feature.workspace.domain.remote.agent

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 协议版本。`hello` 的 `proto` 必须与之一致；不一致时不降级使用，直接回落 SFTP
 * （见 tools/aicode-agent/PROTOCOL.md 第 7 节）。
 */
const val REMOTE_AGENT_PROTOCOL_VERSION: Int = 1

private val NO_BODY = ByteArray(0)

/**
 * 请求头。字段名与 PROTOCOL.md 第 2 节逐项对齐，改动这里等于改协议。
 *
 * [bodyLen] 由 [RemoteAgentCodec.encode] 按实际 body 覆写：调用方算错长度会让对端少读或多读，
 * 整条字节流从此错位，后面所有帧都解不出来。
 */
@Serializable
data class AgentRequest(
    val id: Int,
    val op: String,
    val path: String? = null,
    val offset: Long? = null,
    val len: Int? = null,
    val mode: Int? = null,
    val overwrite: Boolean? = null,
    val maxDepth: Int? = null,
    val recursive: Boolean? = null,
    val pattern: String? = null,
    val maxResults: Int? = null,
    val to: String? = null,
    val bodyLen: Int = 0
)

/**
 * 某个 op 的可选参数。与 [AgentRequest] 字段重复是为把「id / bodyLen 由会话决定」
 * 和「参数由调用方决定」分开，避免调用方手填序号。
 */
data class AgentRequestParams(
    val path: String? = null,
    val offset: Long? = null,
    val len: Int? = null,
    val mode: Int? = null,
    val overwrite: Boolean? = null,
    val maxDepth: Int? = null,
    val recursive: Boolean? = null,
    val pattern: String? = null,
    val maxResults: Int? = null,
    val to: String? = null
)

internal fun AgentRequestParams.toRequest(id: Int, op: String, bodyLen: Int): AgentRequest = AgentRequest(
    id = id,
    op = op,
    path = path,
    offset = offset,
    len = len,
    mode = mode,
    overwrite = overwrite,
    maxDepth = maxDepth,
    recursive = recursive,
    pattern = pattern,
    maxResults = maxResults,
    to = to,
    bodyLen = bodyLen
)

/**
 * 响应头 + body。`body` 是裸二进制（read 的文件片段、list/grep 的 JSON 数组），
 * 不参与 JSON 序列化——它不会出现在 header 里。
 *
 * 注意 data class 对 [ByteArray] 的 equals 是引用比较，按内容比较请用 `contentEquals`。
 */
data class AgentResponse(
    val id: Int,
    val ok: Boolean,
    val err: String? = null,
    val msg: String? = null,
    val size: Long? = null,
    val mtime: Long? = null,
    val isDir: Boolean? = null,
    /**
     * 权限的线格式协议未写死（PROTOCOL.md 的响应字段表里没有它）。这里按 Android 侧
     * `FileEntry.permissions` 需要的 `rw-r--r--` 形态接收，Go 端需输出同形态字符串。
     */
    val perm: String? = null,
    val hash: String? = null,
    /** hello 能力字段。 */
    val proto: Int? = null,
    val go: String? = null,
    val os: String? = null,
    val arch: String? = null,
    val hasRg: Boolean? = null,
    val home: String? = null,
    val canHash: Boolean? = null,
    val body: ByteArray = NO_BODY
) {
    /** body 实际字节数；解码后与线格式里的 bodyLen 一致。 */
    val bodyLen: Int get() = body.size
}

/** hello 握手成功后缓存的能力位。 */
data class AgentCapabilities(
    val proto: Int,
    val go: String? = null,
    val os: String? = null,
    val arch: String? = null,
    val hasRg: Boolean = false,
    val home: String? = null,
    val canHash: Boolean = false
)

/**
 * 响应头在 JSON 里的形态。单独建模而不是给 [AgentResponse] 加 `@Serializable`：
 * 后者会把 body 字节数组也序列化进 JSON，响应头与 body 的边界就失效了。
 *
 * `bodyLen` 不给默认值：协议要求它必填，缺失时宁可解码失败（抛 IOException）
 * 也不能猜 0——猜错会让后续 body 字节被当成下一帧的 MAGIC，整条流错位。
 */
@Serializable
private data class AgentResponseHeader(
    val id: Int,
    val ok: Boolean,
    val err: String? = null,
    val msg: String? = null,
    val size: Long? = null,
    val mtime: Long? = null,
    val isDir: Boolean? = null,
    val perm: String? = null,
    val hash: String? = null,
    val proto: Int? = null,
    val go: String? = null,
    val os: String? = null,
    val arch: String? = null,
    val hasRg: Boolean? = null,
    val home: String? = null,
    val canHash: Boolean? = null,
    val bodyLen: Int
)

private fun AgentResponseHeader.toResponse(body: ByteArray): AgentResponse = AgentResponse(
    id = id,
    ok = ok,
    err = err,
    msg = msg,
    size = size,
    mtime = mtime,
    isDir = isDir,
    perm = perm,
    hash = hash,
    proto = proto,
    go = go,
    os = os,
    arch = arch,
    hasRg = hasRg,
    home = home,
    canHash = canHash,
    body = body
)

/**
 * 帧编解码。
 *
 * 帧格式：`MAGIC(4) | headerLen(大端 uint32) | header(JSON) | body(裸字节)`。
 * 编解码两侧都必须按长度精确读满，不能假设一次 `read` 就拿到完整切片：SSH 通道与 socket
 * 都可能把一帧拆成多次返回；反过来一次 read 也可能带出后续帧（粘包），所以 [decode]
 * 只消耗一帧、不预读多余字节。
 */
object RemoteAgentCodec {

    /** header JSON 上限；超了直接失败，避免恶意/错乱的 headerLen 撑爆内存。 */
    const val MAX_HEADER_BYTES: Int = 1 * 1024 * 1024

    /** body 上限（64 MiB），与 PROTOCOL.md 第 1 节一致。 */
    const val MAX_BODY_BYTES: Int = 64 * 1024 * 1024

    private const val FIXED_HEAD_BYTES = 8

    private val MAGIC = byteArrayOf(0x41, 0x49, 0x43, 0x44) // "AICD"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 编码一帧。头部里的 `bodyLen` 一律取 [body] 的实际长度，不信任调用方填的值。
     *
     * @throws IOException header 或 body 超出上限
     */
    fun encode(request: AgentRequest, body: ByteArray): ByteArray {
        if (body.size > MAX_BODY_BYTES) {
            throw IOException("body 超出上限: ${body.size} > $MAX_BODY_BYTES")
        }
        val headerBytes = json.encodeToString(request.copy(bodyLen = body.size)).toByteArray(Charsets.UTF_8)
        if (headerBytes.size > MAX_HEADER_BYTES) {
            throw IOException("header 超出上限: ${headerBytes.size} > $MAX_HEADER_BYTES")
        }
        val frame = ByteArray(FIXED_HEAD_BYTES + headerBytes.size + body.size)
        MAGIC.copyInto(frame, 0)
        writeUInt32(frame, 4, headerBytes.size)
        headerBytes.copyInto(frame, FIXED_HEAD_BYTES)
        body.copyInto(frame, FIXED_HEAD_BYTES + headerBytes.size)
        return frame
    }

    /**
     * 从 [reader] 精确读出一帧（含 body）。读到流结束或帧不完整时抛 [EOFException]；
     * 帧结构非法（MAGIC 不符、长度超限、header JSON 坏）时抛 [IOException]。
     *
     * 本方法只在持有读循环的协程里调用，不做任何重试或跳过：字节流一旦错位就无法自愈，
     * 只能让会话层把连接判死、交给上层重连。
     */
    fun decode(reader: InputStream): AgentResponse {
        val magic = readFully(reader, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("帧 MAGIC 不符: ${magic.joinToString(" ") { hex(it) }}")
        }
        val headerLen = readUInt32(reader)
        if (headerLen <= 0L || headerLen > MAX_HEADER_BYTES.toLong()) {
            throw IOException("headerLen 非法: $headerLen（上限 $MAX_HEADER_BYTES）")
        }
        val headerBytes = readFully(reader, headerLen.toInt())
        val header = try {
            json.decodeFromString<AgentResponseHeader>(String(headerBytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IOException("响应头 JSON 解析失败（${headerBytes.size} 字节）", e)
        }
        if (header.bodyLen < 0 || header.bodyLen > MAX_BODY_BYTES) {
            throw IOException("bodyLen 非法: ${header.bodyLen}（上限 $MAX_BODY_BYTES）")
        }
        val body = if (header.bodyLen == 0) NO_BODY else readFully(reader, header.bodyLen)
        return header.toResponse(body)
    }

    /**
     * 读满 [length] 字节。普通容器流的 [InputStream.read] 可能只返回一部分（半包），
     * 也可能返回 0；返回 0 时退化成单字节读，避免用 `continue` 空转烧 CPU。
     */
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            when {
                n < 0 -> throw EOFException("帧不完整: 期望 $length 字节，实际 $read")
                n == 0 -> {
                    val single = input.read()
                    if (single < 0) throw EOFException("帧不完整: 期望 $length 字节，实际 $read")
                    buffer[read] = single.toByte()
                    read++
                }
                else -> read += n
            }
        }
        return buffer
    }

    private fun readUInt32(input: InputStream): Long {
        val bytes = readFully(input, 4)
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    private fun writeUInt32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun hex(value: Byte): String = (value.toInt() and 0xFF).toString(16).padStart(2, '0')
}
