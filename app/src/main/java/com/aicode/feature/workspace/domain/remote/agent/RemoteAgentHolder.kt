package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteFileOps
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.Lazy
import java.nio.file.FileAlreadyExistsException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "RemoteAgentHolder"

/**
 * 当前 SSH 连接上的 agent 会话持有者：连接后拉起一次，断开或失败就置空。
 *
 * 为什么单独一层而不是把它塞进 `DelegatingFileAccess`：agent 的建立是 suspend 的
 * （要上传二进制、要等 hello），而 `FileAccessProvider` 是同步接口。持有者负责「什么时候连」，
 * 文件访问实现只负责「连上了就用」。两者分开，同步接口里就不会出现阻塞式的重连逻辑。
 *
 * 失败一律降级：这里永远不向调用方抛异常，只返回 null，让文件访问退回 SFTP。
 * 远端可能没有 python、可能是未知架构、可能装不上二进制——这些都只该影响「快不快」，
 * 不该影响「能不能用」。
 *
 * [fileAccessProvider] 用 [Lazy] 注入：本类要给 [RemoteAgentProvisioner] 提供上传能力，
 * 而那个接口的实现（DelegatingFileAccess）又要反过来问本类有没有 agent 会话。
 * 用 Lazy 把这条边延后到真正用到时才解，避免 Hilt 在构造期发现环。
 */
@Singleton
class RemoteAgentHolder @Inject constructor(
    private val connection: RemoteSshConnection,
    private val binaries: RemoteAgentBinaries,
    private val fileAccessProvider: Lazy<FileAccessProvider>
) : RemoteFileOps {

    init {
        // 把「怎么写远端文件」交给连接层：连接层用它优先走 agent、拿不到就退回 SFTP。
        // 用事后注册而不是构造注入，避免 Connection → Holder → Connection 的环。
        connection.agentFileOps = this
    }

    private val mutex = Mutex()

    /**
     * 预热用的事件循环。启动阶段只调 [warmUp]：首次要传 2.4MB 二进制，同步等会把
     * 工作区初始化拖成好几秒；agent 没就绪期间文件访问会自动退回 SFTP，不影响可用性。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var current: RemoteAgentConnection? = null

    /** 上次失败的原因，供日志与调试入口查看；成功后清空。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 已就绪的会话；没有或已判定不可用时返回 null，调用方据此退回 SFTP。 */
    fun sessionOrNull(): RemoteAgentSession? = current?.session

    /**
     * 后台拉起 agent，不阻塞调用方。已就绪或正在连时直接返回。
     *
     * 用在本进程的启动路径（工作区初始化）上：那里已经在 IO 线程，但首次上传二进制要
     * 好几秒，不能让用户等着它才能用文件功能。
     */
    fun warmUp() {
        if (current != null) return
        scope.launch { ensureConnected() }
    }

    /** 是否已经连上。 */
    val isReady: Boolean get() = current != null

    /**
     * 确保 agent 就绪。幂等：已连上直接返回 true。
     *
     * 连接失败只记日志并返回 false——远端缺少依赖、架构不支持、权限不足都属于
     * 「这次用不了快路径」，不该让文件操作整体失败。
     */
    suspend fun ensureConnected(): Boolean {
        current?.let { return true }
        return mutex.withLock {
            current?.let { return@withLock true }
            if (!connection.isConnected()) {
                lastFailure = "SSH 未连接"
                return@withLock false
            }
            try {
                // 先拿到登录 home：agent 的 -root 必须是绝对路径，而 $HOME 会被 shellPath
                // 加引号变成字面量，远端 shell 不会展开它。
                val home = runRemote("echo \"\$HOME\"").trim()
                if (home.isEmpty() || !home.startsWith("/")) {
                    lastFailure = "拿不到远端 home（返回「$home」）"
                    FileLogger.w(TAG, lastFailure!!)
                    return@withLock false
                }
                val provisioner = RemoteAgentProvisioner(
                    fileAccess = fileAccessProvider.get(),
                    runCommand = { command -> runRemote(command) },
                    loadBinary = { arch -> binaries.load(arch) },
                    agentDir = "$home/.aicode/agent"
                )
                val launcher = RemoteAgentLauncher(
                    provisioner = provisioner,
                    openChannel = { command ->
                        SshAgentChannel(connection.startInteractiveExecSession(command))
                    },
                    root = home
                )
                val connected = launcher.connect()
                current = connected
                lastFailure = null
                FileLogger.i(
                    TAG,
                    "agent 就绪: arch=${connected.installation.arch.suffix} " +
                        "uploaded=${connected.installation.uploaded} " +
                        "proto=${connected.capabilities?.proto}"
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e.message ?: e::class.simpleName
                FileLogger.w(TAG, "agent 建立失败，退回 SFTP: $lastFailure", e)
                false
            }
        }
    }

    /** 连接断开或切换服务器时调用：关掉会话，下次 [ensureConnected] 重新拉。 */
    fun reset() {
        current?.let { conn ->
            runCatching { conn.close() }
            FileLogger.d(TAG, "agent 会话已关闭")
        }
        current = null
    }

    // ---- RemoteFileOps：会话不就绪时直接抛，由连接层退回 SFTP ----

    override suspend fun mkdirs(path: String) {
        val session = requireSession()
        val response = session.request("mkdir", AgentRequestParams(path = remotePath(path)))
        if (!response.ok) throw RemoteAgentUnavailableException("mkdir 失败: ${response.err} ${response.msg}")
    }

    override suspend fun exists(path: String): Boolean {
        val session = requireSession()
        val response = session.request("stat", AgentRequestParams(path = remotePath(path)))
        return response.ok
    }

    override suspend fun write(path: String, bytes: ByteArray, executable: Boolean, overwrite: Boolean) {
        val session = requireSession()
        val target = remotePath(path)
        val write = session.request(
            "write",
            AgentRequestParams(path = target, overwrite = overwrite, mode = if (executable) 0x1ED else null),
            bytes
        )
        if (!write.ok) {
            // 已存在且不允许覆盖：抛接口约定的异常，不要一律归成「agent 不可用」
            // 否则调用方会把它当成传输层故障而退回 SFTP，把「目标已存在」这个真实结果弄丢。
            if (write.err == "EEXIST") throw FileAlreadyExistsException(target)
            throw RemoteAgentUnavailableException("write 失败: ${write.err} ${write.msg}")
        }
    }

    override suspend fun remove(path: String) {
        val session = requireSession()
        val response = session.request("remove", AgentRequestParams(path = remotePath(path)))
        if (!response.ok && response.err != "ENOENT") {
            throw RemoteAgentUnavailableException("remove 失败: ${response.err} ${response.msg}")
        }
    }

    /** 取会话；没就绪就抛，让调用方退回 SFTP，而不是在这里默默失败。 */
    private fun requireSession(): RemoteAgentSession =
        sessionOrNull() ?: throw RemoteAgentUnavailableException("agent 尚未就绪")

    /** 把 `~/...` 展开成绝对路径；其余路径原样传（agent 只接受绝对路径）。 */
    private fun remotePath(path: String): String {
        val home = current?.session?.capabilities?.home ?: return path
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home.trimEnd('/') + path.substring(1)
            else -> path
        }
    }

    /**
     * 在一条独立 exec 会话上跑命令并取回 stdout。
     *
     * 不复用 RemoteSshEngine：那边要经过命令引擎的权限与超时封装，这里只需要「跑一条
     * 诊断命令、读回文本」，走更薄的路径能少一层耦合。stdin 由 startExecSession 关掉。
     */
    private suspend fun runRemote(command: String): String = withContext(Dispatchers.IO) {
        val session = connection.startExecSession(command)
        try {
            session.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { session.close() }
        }
    }
}
