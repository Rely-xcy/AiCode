package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RemoteAgentSession"

/** 单请求默认超时。远端正常时 stat/list 都是毫秒级，30s 已足够覆盖慢盘与首帧启动。 */
const val REMOTE_AGENT_DEFAULT_TIMEOUT_MS: Long = 30_000L

/** 连续超时达到此数才判定连接不可用；1~2 次先按「该请求慢」处理，不拆连接。 */
const val REMOTE_AGENT_MAX_CONSECUTIVE_TIMEOUTS: Int = 3

/** 已废弃 id 的记忆上限：只为把迟到响应的日志从「无主」升级为「已超时废弃」，有界即可。 */
private const val MAX_ABANDONED_IDS = 256

private val NO_BODY = ByteArray(0)

/**
 * 一条 agent exec 会话的字节通道。生产实现包 sshj 的 `Session.Command`
 * （stdout 走 [input]、stdin 走 [output]、stderr 走 [error]），测试用内存流即可。
 *
 * 不直接依赖 `RemoteSshConnection` 是这一期刻意留的缝：期 1 只验证协议与会话语义，
 * 不接管真实文件路径，接真连接是期 2 的事。
 */
interface AgentChannel {
    val input: InputStream
    val output: OutputStream
    val error: InputStream
    fun close()
}

/** agent 侧的通用失败。网络、协议、握手失败都归到它的子类，调用方按类型区分处置。 */
open class RemoteAgentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 打开通道或 hello 握手失败。能力探测据此降级到 L2。 */
open class RemoteAgentHandshakeException(message: String, cause: Throwable? = null) : RemoteAgentException(message, cause)

/** 协议版本不一致。继承 [RemoteAgentHandshakeException]：握手失败的一种，但探测层要先于普通失败识别它，按 PROTOCOL.md 第 7 节直接回落 SFTP。 */
class RemoteAgentProtocolMismatchException(val proto: Int) : RemoteAgentHandshakeException(
    "agent 协议版本不兼容: proto=$proto（本端仅支持 $REMOTE_AGENT_PROTOCOL_VERSION）"
)

/** 单个请求超时。连接仍然可用，调用方可以重试或改走别的请求。 */
class RemoteAgentTimeoutException(
    val requestId: Int,
    val op: String,
    message: String
) : RemoteAgentException(message)

/** 连接级不可用（读通道中断、写失败、连续超时、会话已关闭）。交给上层重连。 */
class RemoteAgentUnavailableException(message: String, cause: Throwable? = null) : RemoteAgentException(message, cause)

/**
 * 远端 agent 的会话层：一次 [start] 握手，之后多路请求按 id 配对。
 *
 * 生命周期是「单次」的：一旦 [close] 或判定不可用，就只能新建实例重连。这样状态机只有
 * 「未启动 / 已启动 / 已死」三态，不会出现半死不活的通道被继续复用。
 *
 * 并发模型：
 * - 写：所有帧经 [writeMutex] 串行写，帧与帧不交叉；
 * - 读：一个独立协程循环 [RemoteAgentCodec.decode]，按 id 分派到 [pending]；
 * - 等待：每个请求持有自己的 [CompletableDeferred]，不共享锁，慢请求不阻塞其它请求。
 *
 * 不用 `runBlocking`：本类全部接口都是 `suspend`，阻塞读写一律经 [Dispatchers.IO]。
 */
class RemoteAgentSession(
    private val launchCommand: String,
    private val openChannel: suspend (String) -> AgentChannel,
    private val requestTimeoutMs: Long = REMOTE_AGENT_DEFAULT_TIMEOUT_MS,
    private val maxConsecutiveTimeouts: Int = REMOTE_AGENT_MAX_CONSECUTIVE_TIMEOUTS
) {

    init {
        require(launchCommand.isNotBlank()) { "launchCommand 不能为空" }
        require(requestTimeoutMs > 0L) { "requestTimeoutMs 必须为正数" }
        require(maxConsecutiveTimeouts >= 1) { "maxConsecutiveTimeouts 至少为 1" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateMutex = Mutex()
    private val writeMutex = Mutex()

    private val closed = AtomicBoolean(false)
    private val unusable = AtomicReference<Throwable?>(null)
    private val consecutiveTimeouts = AtomicInteger(0)
    private val idCounter = AtomicInteger(0)

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<AgentResponse>>()

    /** 已超时废弃的 id；迟到响应到达时据此给出更准确的日志，随后移除。 */
    private val abandoned = LinkedHashSet<Int>()

    @Volatile
    private var started = false

    @Volatile
    private var channel: AgentChannel? = null

    @Volatile
    private var readJob: Job? = null

    @Volatile
    private var stderrJob: Job? = null

    @Volatile
    private var capabilitiesCache: AgentCapabilities? = null

    /** hello 握手成功后的能力位；未完成握手（含握手失败）时为 null。 */
    val capabilities: AgentCapabilities? get() = capabilitiesCache

    /**
     * 打开通道并完成 hello 握手。幂等：已启动时直接返回。
     *
     * 握手失败会拆掉通道并让本实例进入不可用状态——半握手的连接无法安全复用。
     */
    suspend fun start() {
        stateMutex.withLock {
            check(!closed.get()) { "agent 会话已关闭" }
            unusable.get()?.let { throw it }
            if (started) return@withLock

            val opened = try {
                openChannel(launchCommand)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RemoteAgentHandshakeException("启动远端 agent 失败: ${e.message}", e)
            }
            if (closed.get()) {
                runCatching { opened.close() }
                throw RemoteAgentUnavailableException("agent 会话在启动过程中被关闭")
            }

            channel = opened
            readJob = launchReadLoop(opened)
            stderrJob = launchStderrPump(opened)

            try {
                performHello()
            } catch (e: Exception) {
                teardown(e)
                when (e) {
                    is CancellationException -> throw e
                    is RemoteAgentHandshakeException -> throw e
                    else -> throw RemoteAgentHandshakeException("hello 握手失败: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun performHello() {
        val hello = send("hello", AgentRequestParams(), NO_BODY)
        if (!hello.ok) {
            throw RemoteAgentHandshakeException("hello 被拒: ${hello.err ?: "EIO"} ${hello.msg ?: ""}".trim())
        }
        val proto = hello.proto ?: throw RemoteAgentHandshakeException("hello 响应缺少 proto 字段")
        if (proto != REMOTE_AGENT_PROTOCOL_VERSION) throw RemoteAgentProtocolMismatchException(proto)
        capabilitiesCache = AgentCapabilities(
            proto = proto,
            go = hello.go,
            os = hello.os,
            arch = hello.arch,
            hasRg = hello.hasRg == true,
            home = hello.home,
            canHash = hello.canHash == true
        )
        consecutiveTimeouts.set(0)
        started = true
        FileLogger.d(TAG, "agent 握手成功: proto=$proto os=${hello.os} arch=${hello.arch} hasRg=${hello.hasRg}")
    }

    /**
     * 发一个请求并等它的响应。id 由本类分配，响应按 id 配对。
     *
     * [params] 的字段平铺进请求头；[body] 是裸字节（write 的待写内容）。响应 body 原样放在
     * [AgentResponse.body] 里，由调用方按 op 解释。
     */
    suspend fun request(
        op: String,
        params: AgentRequestParams = AgentRequestParams(),
        body: ByteArray = NO_BODY
    ): AgentResponse {
        unusable.get()?.let { throw it }
        if (closed.get()) throw RemoteAgentUnavailableException("agent 会话已关闭")
        if (!started) throw IllegalStateException("agent 会话尚未完成 start() 握手")
        return send(op, params, body)
    }

    /** 关闭通道、取消读写协程、让所有未完成请求立刻失败。幂等。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        teardown(RemoteAgentUnavailableException("agent 会话已关闭"))
    }

    private suspend fun send(op: String, params: AgentRequestParams, body: ByteArray): AgentResponse {
        unusable.get()?.let { throw it }
        if (closed.get()) throw RemoteAgentUnavailableException("agent 会话已关闭")
        val ch = channel ?: throw IllegalStateException("agent 会话尚未 start()")

        val id = nextRequestId()
        // 先登记再写：对端可能在 write 返回前就回包，先写后登记会丢响应
        val deferred = CompletableDeferred<AgentResponse>()
        pending[id] = deferred
        val frame = try {
            RemoteAgentCodec.encode(params.toRequest(id, op, body.size), body)
        } catch (e: IOException) {
            pending.remove(id)
            throw e
        }

        try {
            writeMutex.withLock {
                unusable.get()?.let { throw it }
                withContext(Dispatchers.IO) {
                    ch.output.write(frame)
                    ch.output.flush()
                }
            }
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        } catch (e: RemoteAgentException) {
            pending.remove(id)
            throw e
        } catch (e: Exception) {
            // 帧可能只写出去一半，字节流已不可信：不能只是丢掉这个请求，必须判死整条连接
            pending.remove(id)
            val failure = RemoteAgentUnavailableException("写入 agent 请求失败: ${e.message}", e)
            teardown(failure)
            throw failure
        }
        return awaitResponse(id, op, deferred)
    }

    private suspend fun awaitResponse(
        id: Int,
        op: String,
        deferred: CompletableDeferred<AgentResponse>
    ): AgentResponse {
        val response = try {
            withTimeoutOrNull(requestTimeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            // 调用方主动取消：请求已发出但没人再等它，按废弃处理，避免迟到响应变成「无主」
            if (pending.remove(id) != null) rememberAbandoned(id)
            throw e
        }
        if (response != null) {
            consecutiveTimeouts.set(0)
            return response
        }

        val removed = pending.remove(id)
        if (removed == null && deferred.isCompleted) {
            // 响应恰好在超时边界完成：以实际结果为准，不记超时
            val completed = deferred.await()
            consecutiveTimeouts.set(0)
            return completed
        }
        if (removed != null) rememberAbandoned(id)

        val streak = consecutiveTimeouts.incrementAndGet()
        if (streak >= maxConsecutiveTimeouts) {
            val failure = RemoteAgentUnavailableException("连续 $streak 次请求超时，判定 agent 连接不可用")
            FileLogger.w(TAG, "连续超时: id=$id op=$op streak=$streak，关闭会话交给上层重连")
            teardown(failure)
            throw failure
        }
        // 单次超时只废掉这个请求：连接、读协程与其它在途请求都不动
        FileLogger.w(TAG, "请求超时: id=$id op=$op streak=$streak，只废弃该请求，连接保持")
        throw RemoteAgentTimeoutException(id, op, "请求超时: id=$id op=$op（${requestTimeoutMs}ms）")
    }

    /**
     * 读循环：一次只解一帧，按 id 分派。乱序响应天然支持（Map 查找），多余/迟到响应只记日志。
     * 循环退出即视为传输层中断，整条连接判死。
     */
    private fun launchReadLoop(ch: AgentChannel): Job = scope.launch(Dispatchers.IO) {
        var failure: Throwable? = null
        try {
            while (isActive) {
                val response = RemoteAgentCodec.decode(ch.input)
                dispatch(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        val error = failure ?: return@launch
        if (closed.get() || unusable.get() != null) return@launch
        FileLogger.w(TAG, "agent 读通道中断: ${error.message}", error)
        val cause = if (error is RemoteAgentException) error else {
            RemoteAgentUnavailableException("agent 读通道中断: ${error.message}", error)
        }
        teardown(cause)
    }

    private fun dispatch(response: AgentResponse) {
        val deferred = pending.remove(response.id)
        if (deferred != null) {
            deferred.complete(response)
            return
        }
        val abandonedBefore = forgetAbandoned(response.id)
        if (abandonedBefore) {
            FileLogger.w(TAG, "收到已超时请求的迟到响应，丢弃: id=${response.id}")
        } else {
            FileLogger.w(TAG, "收到无法配对的响应（id 乱序或多余），丢弃: id=${response.id}")
        }
    }

    /**
     * stderr 只用于观测（PROTOCOL.md 第 6 节），读失败不影响协议。用 [BoundedLineReader]
     * 而非 `BufferedReader.readLine`：远端进程若吐一条没有换行的超长行，后者会整行拼进内存。
     */
    private fun launchStderrPump(ch: AgentChannel): Job = scope.launch(Dispatchers.IO) {
        val reader = BoundedLineReader(InputStreamReader(ch.error, Charsets.UTF_8))
        try {
            while (isActive) {
                val line = reader.readLine() ?: break
                FileLogger.d(TAG, "[agent] ${line.text}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) FileLogger.w(TAG, "读取 agent stderr 失败: ${e.message}")
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * 拆会话：先落不可用原因，再取消协程、关通道、唤醒所有等待者。
     * 从读协程内部调用也安全——之后没有挂起点，自我取消不会把清理流程掐断。
     */
    private fun teardown(cause: Throwable) {
        unusable.compareAndSet(null, cause)
        started = false
        readJob?.cancel()
        stderrJob?.cancel()
        runCatching { channel?.close() }
        failAllPending(cause)
        scope.cancel()
    }

    private fun failAllPending(cause: Throwable) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(cause)
        }
        synchronized(abandoned) { abandoned.clear() }
    }

    private fun nextRequestId(): Int {
        while (true) {
            val current = idCounter.get()
            // 协议要求 1 起单调递增；溢出后回到 1，旧 id 早已完成或废弃
            val next = if (current >= Int.MAX_VALUE) 1 else current + 1
            if (idCounter.compareAndSet(current, next)) return next
        }
    }

    private fun rememberAbandoned(id: Int) {
        synchronized(abandoned) {
            abandoned.add(id)
            while (abandoned.size > MAX_ABANDONED_IDS) {
                abandoned.remove(abandoned.first())
            }
        }
    }

    private fun forgetAbandoned(id: Int): Boolean = synchronized(abandoned) { abandoned.remove(id) }
}

/**
 * 启动命令拼装。命令最终由远端 shell 执行，路径来自配置（可能含空格、引号、`$`），
 * 因此统一走 shell 转义，绝不做裸拼接。
 */
object RemoteAgentLaunch {

    /** 例：`'/home/u/.aicode/agent/aicode-agent-v1-linux-amd64' -root '/home/u/workspace'` */
    fun command(binaryPath: String, root: String): String {
        require(binaryPath.isNotBlank()) { "binaryPath 不能为空" }
        require(root.isNotBlank()) { "root 不能为空" }
        return "${shellPath(binaryPath)} -root ${shellPath(root)}"
    }
}

/**
 * 单个路径转成 shell 安全形式。
 *
 * `~` / `~/...` 必须展开成 `$HOME` 而不是放进单引号：单引号里的 `~` 不会被 shell 展开，
 * `chmod +x '~/.aicode/agent/x'` 会去找一个字面名为 `~` 的相对目录。含 `~` 时用双引号并
 * 转义双引号串里有特殊含义的字符；其余情况用单引号（唯一需要处理的是单引号本身）。
 */
internal fun shellPath(path: String): String {
    if (path == "~" || path.startsWith("~/")) {
        val rest = path.removePrefix("~")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        return "\"\$HOME$rest\""
    }
    return "'" + path.replace("'", "'\\''") + "'"
}
