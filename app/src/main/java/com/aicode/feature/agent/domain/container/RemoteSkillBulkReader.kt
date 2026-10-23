package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.remotePathFor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

private const val TAG = "RemoteSkillBulkReader"

/** 成功结果复用窗口：一次扫描内部的多次调用合并成一次往返，跨轮询则重新取。 */
private const val CACHE_TTL_MS = 5_000L

/** 失败后的静默窗口：这几天注定失败的远端命令没必要每次扫描都再发一遍。 */
private const val FAILURE_BACKOFF_MS = 30_000L

/** 单条命令超时：与远端文件轮询保持一致。 */
private const val COMMAND_TIMEOUT_MS = 20_000L

/** 等在途读取的最长时间；超过就自己回退，不陪着一起卡住。 */
private const val WAIT_TIMEOUT_MS = 30_000L

/**
 * 批量读取需要的远端上下文。
 *
 * @param cacheKey 失效键：执行模式 + 工作区 + 远端 home，任一变化都换一批远端文件。
 */
data class RemoteSkillBulkContext(
    val cacheKey: String,
    val workspaceRoot: String,
    val home: String
)

/** 非远程模式、工作区未落定或 SSH 未连接时返回 null——调用方据此走逐文件回退。 */
fun interface RemoteSkillBulkContextSource {
    fun context(): RemoteSkillBulkContext?
}

/** 只声明批量读取用到的那一个命令方法：单测直接替换，不必起一条 SSH 连接。 */
fun interface RemoteBulkCommandRunner {
    /** 后端未就绪或执行抛异常时返回 null。 */
    suspend fun runUnbounded(command: String, timeoutMs: Long): CommandResult?
}

/**
 * 某个根目录下的候选文件全文（相对路径 → 内容）。
 *
 * null 表示这次读不到（非远程、远端缺工具、命令失败），调用方必须回退到逐文件读取；
 * 空 map 表示「读到了，就是没有文件」——两者是不同的事实，不能混为一谈。
 */
interface BulkFileSource {
    fun filesUnder(root: String): Map<String, String>?

    /** 写入 / 删除后主动失效；没有缓存的实现不必理会。 */
    fun invalidate() = Unit

    companion object {
        /** 关闭批量读取：本地模式与单测的默认实现。 */
        val Disabled: BulkFileSource = object : BulkFileSource {
            override fun filesUnder(root: String): Map<String, String>? = null
        }
    }
}

/** 一个被批量读取的根：容器路径用于与扫描器的根对齐，tag 是协议里的块标识。 */
private data class RootSpec(
    val tag: String,
    val containerRoot: String,
    val maxDepth: Int,
    val kind: RemoteBulkRootKind
)

/**
 * 一条命令覆盖的四个根：全局 / 项目 × 技能 / 子代理，容器路径与
 * Global/Project Directory{Skill,Agent}Source 的根一致。
 *
 * 深度与技能扫描器一致（4）；子代理只看顶层，故为 1。
 */
private val ROOT_SPECS = listOf(
    RootSpec("SG", "~/.aicode/skills", 4, RemoteBulkRootKind.SKILLS),
    RootSpec("SP", "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/skills", 4, RemoteBulkRootKind.SKILLS),
    RootSpec("AG", "~/.aicode/agents", 1, RemoteBulkRootKind.AGENTS),
    RootSpec("AP", "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/agents", 1, RemoteBulkRootKind.AGENTS)
)

/**
 * 技能 / 子代理文件的远端批量读取器：一条 SSH 命令取回四个根下的候选文件，结果带短 TTL 缓存，
 * 并发调用合并成一次真实扫描（单飞）。
 *
 * 只读缓存、不落盘、不订阅远端变更：失效由写入方主动调用 [invalidate]，外加 [CACHE_TTL_MS] 兜底。
 * 之所以不把 `FileChangeHub` 的远端轮询当失效源——它自己就在烧 SFTP 往返，拿它换掉逐文件扫描毫无意义。
 *
 * 一切失败（非远程、缺 base64/find/wc、命令 rc≠0、缺 END、输出被截断、解析异常）都归于返回 null，
 * 由调用方回退到既有逐文件路径；本类从不向上抛。
 */
@Singleton
class RemoteSkillBulkReader @Inject constructor(
    private val contextSource: RemoteSkillBulkContextSource,
    private val runner: RemoteBulkCommandRunner
) : BulkFileSource {

    private val lock = ReentrantLock()
    private val finished = lock.newCondition()

    private var loading = false

    /** 最近一次结果；[Cached.files] 为 null 表示那次读取失败（与「读到空目录」区分）。 */
    private var cached: Cached? = null

    private class Cached(
        val key: String,
        val at: Long,
        val files: Map<String, Map<String, String>>?
    )

    override fun filesUnder(root: String): Map<String, String>? {
        val spec = ROOT_SPECS.firstOrNull { it.containerRoot == root.trimEnd('/') } ?: return null
        val context = runCatching { contextSource.context() }.getOrNull() ?: return null
        return snapshot(context)?.get(spec.tag)
    }

    override fun invalidate() {
        lock.lock()
        try {
            cached = null
        } finally {
            lock.unlock()
        }
    }

    /**
     * 取（或触发）一次批量读取。并发调用只让第一个真的发命令，其余等它的结果。
     *
     * 用锁 + 条件变量而不是协程单飞：调用方是同步接口（扫描器 / `listSkills`），这里必须能阻塞，
     * 阻塞点已经是 IO 线程（与 SFTP 实现就地 `runBlocking` 同理）。
     */
    private fun snapshot(context: RemoteSkillBulkContext): Map<String, Map<String, String>>? {
        val key = context.cacheKey
        lock.lock()
        try {
            cached?.let { entry -> if (usable(entry, key)) return entry.files }
            while (loading) {
                if (!finished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
                cached?.let { entry -> if (usable(entry, key)) return entry.files }
                // 在途的那次已经结束却没给出可用结果（失败或换了工作区）：本次直接回退，不再补发。
                if (!loading) return null
            }
            loading = true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            lock.unlock()
        }

        val files = try {
            fetch(context)
        } catch (e: Exception) {
            FileLogger.w(TAG, "批量读取技能/子代理文件失败，本次回退逐文件读取", e)
            null
        }
        lock.lock()
        try {
            cached = Cached(key, System.currentTimeMillis(), files)
            loading = false
            finished.signalAll()
        } finally {
            lock.unlock()
        }
        return files
    }

    private fun usable(entry: Cached, key: String): Boolean {
        if (entry.key != key) return false
        val ttl = if (entry.files != null) CACHE_TTL_MS else FAILURE_BACKOFF_MS
        return System.currentTimeMillis() - entry.at < ttl
    }

    private fun fetch(context: RemoteSkillBulkContext): Map<String, Map<String, String>>? {
        val remoteRoots = ROOT_SPECS.associate { spec ->
            spec.tag to remotePathFor(spec.containerRoot, context.workspaceRoot, context.home)
        }
        val command = buildRemoteSkillBulkCommand(
            ROOT_SPECS.map { spec ->
                RemoteBulkRoot(
                    tag = spec.tag,
                    remotePath = remoteRoots.getValue(spec.tag),
                    maxDepth = spec.maxDepth,
                    kind = spec.kind
                )
            }
        )
        val result = runBlocking { runner.runUnbounded(command, COMMAND_TIMEOUT_MS) } ?: return null
        if (result.outputTruncated) {
            FileLogger.w(TAG, "批量读取输出被引擎截断，本次回退逐文件读取")
            return null
        }
        if (result.exitCode != 0) {
            FileLogger.w(TAG, "批量读取命令退出码=${result.exitCode}，本次回退逐文件读取")
            return null
        }
        return parseRemoteSkillBulkOutput(result.output, remoteRoots)
    }
}
