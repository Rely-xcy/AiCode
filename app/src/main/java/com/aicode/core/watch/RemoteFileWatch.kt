package com.aicode.core.watch

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 单目录每轮扫描保留的条目上限（超出的按名字排序取前若干条，见 [buildRemoteScanCommand]）。 */
internal const val REMOTE_SCAN_MAX_ENTRIES = 100

/** 递归订阅（技能 / 子代理这类小目录）在远端允许的最大深度，避免远端 find 深度不限。 */
internal const val REMOTE_RECURSIVE_MAX_DEPTH = 4

private const val REMOTE_WATCH_TAG = "RemoteFileWatch"

/** 能力探测行前缀：`P` 行回传 `stat` 的格式化输出与 `find` 路径。 */
private const val PROBE_PREFIX = "P\t"

/** 目录块起始行前缀：`D` 行回传目录绝对路径。 */
internal const val SCAN_DIR_PREFIX = "D\t"

/** 目录块状态行前缀：`R` 行回传目录绝对路径与该块 find 的退出码。 */
internal const val SCAN_RESULT_PREFIX = "R\t"

/** 整轮扫描的结束标记；缺了它说明命令没跑完（断线 / 被杀），整轮结果丢弃。 */
internal const val SCAN_END_MARKER = "END"

/** 单目录扫描深度：非递归订阅只看直接子项。 */
internal const val REMOTE_SHALLOW_DEPTH = 1

/**
 * 远端（SSH）工作区文件变更的轮询器。
 *
 * 为什么是轮询：远端工作区在 SSH 服务器上，宿主没有对应目录，inotify 无从谈起；也不要求服务器
 * 预装 inotifywait 之类的工具（不是每台都有，多一个环境依赖就多一种「有时候不工作」）。
 *
 * 成本控制（一条命令覆盖所有被监听目录）：
 * - 每轮只发**一条**命令（`sh` 复合语句），内部按目录分段、每段自带起止标记与退出码；
 * - 单目录输出封顶 [REMOTE_SCAN_MAX_ENTRIES] 条（按名字排序取窗口），传输量不随目录规模增长；
 * - 每轮最多扫 [DIR_LIMIT_MAX] 个目录（轮转，保证每个目录都能轮到），整轮输出被引擎截断时
 *   自动把预算减半并丢弃该轮；
 * - 无人订阅时不持有任何协程、不发任何命令；连续无变化时轮询间隔从 [MIN_INTERVAL_MS] 退避到
 *   [MAX_INTERVAL_MS]，一有变化立刻回到 [MIN_INTERVAL_MS]。
 *
 * 不落任何远端状态：不做 `find -newer <marker>`（要先在服务器上建 marker 文件、还得清理，且有
 * 一秒粒度盲区与「删除不可见」的问题），改为回传目录快照、在本地与上一轮比对——增 / 删 / 改都能
 * 判出来，服务器上不留任何文件。
 *
 * 失败一律降级：连接未就绪、命令缺失、权限不足、输出不完整都只记日志（同类按间隔去重）并跳过该轮，
 * 不抛给订阅方、不刷屏、不影响主流程。
 */
@Singleton
class RemoteFileWatchPoller @Inject constructor(
    private val commandEngine: CommandEngine,
    private val executionModeHolder: ExecutionModeHolder
) {
    companion object {
        /** 前台轮询起止间隔：一有变化回到 [MIN_INTERVAL_MS]，连续无变化逐次翻倍到 [MAX_INTERVAL_MS]。 */
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 30_000L

        /** 单轮最多扫描的目录数（目录多时轮转，避免一轮命令过长）。 */
        private const val DIR_LIMIT_MAX = 8

        /** 单条命令的超时；超时由命令引擎强制终止并返回部分输出，本轮按失败处理。 */
        private const val COMMAND_TIMEOUT_MS = 20_000L

        /** 监听目录数上限，超过的订阅直接忽略（日志提示），避免一条命令塞进几十个目录。 */
        private const val MAX_WATCH_DIRS = 24

        /** 同类失败日志的最小间隔，避免断线期间刷屏。 */
        private const val FAIL_LOG_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 被监听的远端目录（键 = 目录 + 深度）→ 共享快照与订阅集合。所有读写都在 [lock] 内。 */
    private val watched = HashMap<WatchKey, WatchEntry>()

    private val lock = Any()

    private var loopJob: Job? = null
    private var dirLimit = DIR_LIMIT_MAX
    private var cursor = 0

    /** 能力探测结果：可能由登记 / 注销线程重置，故与轮询线程之间按 volatile 传递。 */
    @Volatile
    private var capabilityChecked = false

    @Volatile
    private var capabilityOk = false

    private val lastLogAt = HashMap<String, Long>()

    private data class WatchKey(val dir: String, val depth: Int)

    /** 一个被监听目录：多订阅共享同一份快照与同一次扫描。 */
    private class WatchEntry(val key: WatchKey) {
        val subscribers = CopyOnWriteArraySet<RemoteSubscription>()

        /** 上一轮快照（子路径 → 不透明指纹）；null 表示尚未建立基线。 */
        @Volatile
        var snapshot: Map<String, String>? = null
    }

    /** 一个订阅：自带容器根路径、过滤规则与批次通道。 */
    private inner class RemoteSubscription(
        val containerRoot: String,
        filter: WatchFilter,
        private val domain: ChangeDomain
    ) {
        /** 远端订阅不做 .gitignore 剪枝（要读服务器上的文件），只按订阅方的忽略名单过滤。 */
        private val rules = IgnoreRules.of(filter, emptyList())

        private val channel = Channel<FileChangeBatch>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        val batches: Flow<FileChangeBatch> = channel.receiveAsFlow()

        fun emit(dir: String, diff: List<Pair<String, ChangeKind>>, overflow: Boolean) {
            val changes = remoteChangesOf(dir, containerRoot, diff, domain, rules)
            if (changes.isEmpty()) return
            channel.trySend(FileChangeBatch(changes, overflow))
        }

        fun close() {
            channel.close()
        }
    }

    /**
     * 订阅一个远端目录（[remoteDir] 为远端绝对路径，[containerRoot] 为该目录对应的容器路径）。
     * 冷流：订阅开始才登记并起轮询协程，订阅取消即注销；无人订阅时全局不发任何命令。
     *
     * [domain] 原样标给产出的事件：远端产出的路径是服务器路径，消费方按域认领而不是靠路径比对。
     */
    fun watch(
        remoteDir: String,
        containerRoot: String,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        domain: ChangeDomain = ChangeDomain.OTHER
    ): Flow<FileChangeBatch> = flow {
        val key = WatchKey(remoteDir, if (recursive) REMOTE_RECURSIVE_MAX_DEPTH else REMOTE_SHALLOW_DEPTH)
        val subscription = RemoteSubscription(containerRoot, filter, domain)
        val entry = register(key, subscription) ?: return@flow
        try {
            emitAll(subscription.batches)
        } finally {
            unregister(key, entry, subscription)
        }
    }

    private fun register(key: WatchKey, subscription: RemoteSubscription): WatchEntry? {
        synchronized(lock) {
            val existing = watched[key]
            if (existing == null && watched.size >= MAX_WATCH_DIRS) {
                logThrottled("limit", "远端监听目录数达上限 $MAX_WATCH_DIRS，忽略: ${key.dir}")
                return null
            }
            val entry = existing ?: WatchEntry(key).also {
                watched[key] = it
                // 目录集合变了：重置每轮目录预算与轮转位置（此前按少量目录退让过的预算不该一直卡着）。
                dirLimit = DIR_LIMIT_MAX
                cursor = 0
            }
            entry.subscribers.add(subscription)
            if (loopJob?.isActive != true) loopJob = scope.launch { runLoop() }
            return entry
        }
    }

    private fun unregister(key: WatchKey, entry: WatchEntry, subscription: RemoteSubscription) {
        subscription.close()
        synchronized(lock) {
            entry.subscribers.remove(subscription)
            if (entry.subscribers.isEmpty()) {
                watched.remove(key)
                dirLimit = DIR_LIMIT_MAX
                cursor = 0
            }
            if (watched.isEmpty()) {
                loopJob?.cancel()
                loopJob = null
                // 回到空集：下次订阅重新探测远端能力（可能已经换了另一台服务器）。
                capabilityChecked = false
                capabilityOk = false
            }
        }
    }

    private suspend fun runLoop() {
        var interval = MIN_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            val changed = tick()
            if (capabilityChecked && !capabilityOk) return
            interval = if (changed) MIN_INTERVAL_MS else (interval * 2).coerceAtMost(MAX_INTERVAL_MS)
            delay(interval)
        }
    }

    /** 一轮：一条命令扫一批目录，逐目录与上一轮快照比对；返回本轮是否有变更（用于间隔自适应）。 */
    private suspend fun tick(): Boolean {
        // 能力探测已失败（远端缺 stat/find）：不再发命令，等订阅清空后按新服务器重新探测。
        if (capabilityChecked && !capabilityOk) return false

        val batch: List<WatchEntry>
        val probe: Boolean
        synchronized(lock) {
            if (watched.isEmpty()) return false
            val keys = watched.keys.sortedWith(compareBy({ it.dir }, { it.depth }))
            probe = !capabilityChecked
            val take = dirLimit.coerceIn(1, keys.size)
            batch = (0 until take).map { watched.getValue(keys[(cursor + it) % keys.size]) }
            cursor = (cursor + take) % keys.size
        }

        // 模式切到本地（或切回）的瞬间可能还留有订阅：本地不该被这条远端命令碰到，直接跳过本轮。
        if (executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH) return false

        val output = runCatching {
            commandEngine.runCommandSyncIfReady(
                command = buildRemoteScanCommand(batch.map { RemoteScanDir(it.key.dir, it.key.depth) }, probe),
                projectPath = null,
                timeoutMs = COMMAND_TIMEOUT_MS
            )
        }.getOrNull()
        if (output == null) {
            logThrottled("exec", "远端文件监控命令未执行（连接未就绪或执行失败），本轮跳过")
            return false
        }

        if (probe) {
            capabilityChecked = true
            capabilityOk = remoteScanCapabilityOk(output.output)
            if (!capabilityOk) {
                FileLogger.w(
                    REMOTE_WATCH_TAG,
                    "远端缺少可用的 stat/find（需要 stat -c '%Y|%s|%F' 与 find），远端文件监控停用"
                )
                return false
            }
        }

        if (output.outputTruncated) {
            synchronized(lock) { dirLimit = (batch.size / 2).coerceAtLeast(1) }
            logThrottled("truncated", "远端文件监控输出被截断，本轮丢弃并把每轮目录数降到 $dirLimit")
            return false
        }

        val scans = parseRemoteScanOutput(output.output, batch.map { it.key.dir }.toSet())
        var changed = false
        for (entry in batch) {
            val scan = scans[entry.key.dir] ?: continue
            val previous = entry.snapshot
            entry.snapshot = scan.entries
            // 首轮只建基线：订阅建立时把整棵目录当新增全量上报没有意义（消费方本来就要重新列目录）。
            if (previous == null) continue
            val diff = diffSnapshot(previous, scan.entries)
            if (diff.isEmpty()) continue
            changed = true
            for (subscriber in entry.subscribers) subscriber.emit(entry.key.dir, diff, scan.overflow)
        }
        return changed
    }

    private fun logThrottled(key: String, message: String) {
        val now = System.currentTimeMillis()
        synchronized(lastLogAt) {
            if (now - (lastLogAt[key] ?: 0L) < FAIL_LOG_INTERVAL_MS) return
            lastLogAt[key] = now
        }
        FileLogger.w(REMOTE_WATCH_TAG, message)
    }
}

/** 一个待扫描目录：远端绝对路径 + find 的最大深度。 */
internal data class RemoteScanDir(val dir: String, val maxDepth: Int)

/** 单目录一轮扫描结果：[entries] 为「相对路径 → 不透明指纹」，[overflow] 表示条目超过窗口上限。 */
internal data class RemoteDirScan(val entries: Map<String, String>, val overflow: Boolean)

/**
 * 一轮远端差分 → 变更事件。
 *
 * 抽成纯函数是为了能直接单测「远端批次带对了域」（真轮询要连 SSH，测不动）：[domain] 由发起订阅的那条
 * 路由给定（工作区路由 / 配置路由），一旦确定就与服务器上的路径形态无关。
 */
internal fun remoteChangesOf(
    dir: String,
    containerRoot: String,
    diff: List<Pair<String, ChangeKind>>,
    domain: ChangeDomain,
    rules: IgnoreRules
): List<FileChange> {
    val changes = ArrayList<FileChange>(diff.size)
    for ((name, kind) in diff) {
        // 与本地一致：被剪枝目录自身仍上报，只有它**下面**的东西不再报。
        if (rules.isIgnoredDir(name.split('/').dropLast(1))) continue
        changes += FileChange(
            root = ChangeRoot.WORKSPACE,
            hostPath = "$dir/$name",
            containerPath = "$containerRoot/$name",
            kind = kind,
            domain = domain
        )
    }
    return changes
}

/**
 * 构造一条覆盖 [dirs] 的扫描命令（整轮只有这一次远端往返）。
 *
 * 每段：打印目录头 → `cd` 进目录（条目路径因此是短的 `./名字`）→ 取 mtime / 大小 / 类型 →
 * 打印带退出码的状态行 → 仅在成功时输出按名字排序、封顶 [REMOTE_SCAN_MAX_ENTRIES] 条的清单。
 *
 * `cd` 失败（目录不存在 / 无权限）时 `find` 不执行、退出码非 0，解析侧据此跳过该目录，
 * 不会因为「读到空清单」误判成「目录里所有文件都被删了」。
 *
 * @param probe 首轮附带一行能力探测（`stat` 的 `%Y|%s|%F` 与 `find` 是否可用）。
 */
internal fun buildRemoteScanCommand(dirs: List<RemoteScanDir>, probe: Boolean): String {
    val sb = StringBuilder()
    if (probe) {
        sb.append("printf '").append(PROBE_PREFIX).append("%s\\t%s\\n' ")
            .append("\"\$(stat -c '%Y|%s|%F' / 2>/dev/null)\" ")
            .append("\"\$(command -v find 2>/dev/null)\"; ")
    }
    for (entry in dirs) {
        val quoted = remoteShellQuote(entry.dir)
        sb.append("printf '").append(SCAN_DIR_PREFIX).append("%s\\n' ").append(quoted).append("; ")
        sb.append("cd ").append(quoted).append(" 2>/dev/null; rc=\$?; ")
        sb.append("if [ \"\$rc\" = 0 ]; then out=\$(find . -maxdepth ").append(entry.maxDepth)
            .append(" -mindepth 1 -exec stat -c '%n|%Y|%s|%F' {} + 2>/dev/null); rc=\$?; fi; ")
        sb.append("printf '").append(SCAN_RESULT_PREFIX).append("%s\\t%s\\n' ").append(quoted)
            .append(" \"\$rc\"; ")
        sb.append("if [ \"\$rc\" = 0 ]; then printf '%s\\n' \"\$out\" | sort 2>/dev/null | head -n ")
            .append(REMOTE_SCAN_MAX_ENTRIES + 1).append("; fi; ")
    }
    sb.append("printf '").append(SCAN_END_MARKER).append("\\n'")
    return sb.toString()
}

/**
 * 解析一轮扫描输出，只保留结构完整（有目录头、有状态行、退出码为 0 且被请求过）的目录。
 * 缺结束标记（命令没跑完）、畸形行（含换行或 `|` 的文件名、引擎插入的截断提示）都不影响其余块。
 */
internal fun parseRemoteScanOutput(output: String, expected: Set<String>): Map<String, RemoteDirScan> {
    val result = HashMap<String, RemoteDirScan>()
    var current: String? = null
    var resultSeen = false
    var failed = true
    var overflow = false
    var entries = LinkedHashMap<String, String>()

    fun flush() {
        val dir = current
        if (dir != null && resultSeen && !failed && dir in expected) {
            result[dir] = RemoteDirScan(entries, overflow)
        }
        current = null
        resultSeen = false
        failed = true
        overflow = false
        entries = LinkedHashMap()
    }

    for (line in output.split('\n')) {
        when {
            line == SCAN_END_MARKER -> flush()
            line.startsWith(SCAN_DIR_PREFIX) -> {
                flush()
                current = line.removePrefix(SCAN_DIR_PREFIX)
            }
            line.startsWith(SCAN_RESULT_PREFIX) -> {
                val fields = line.split('\t')
                if (fields.size == 3 && fields[1] == current) {
                    resultSeen = true
                    failed = fields[2] != "0"
                } else {
                    // 状态行对不上当前块（乱序 / 被截断）：整块作废，宁可不报也不误报删除。
                    failed = true
                }
            }
            line.isEmpty() -> Unit
            else -> {
                if (current == null || failed) continue
                val fields = line.split('|')
                if (fields.size != 4 || !fields[0].startsWith("./")) continue
                val name = fields[0].removePrefix("./")
                if (name.isEmpty()) continue
                if (entries.size >= REMOTE_SCAN_MAX_ENTRIES) {
                    overflow = true
                    continue
                }
                entries[name] = fields[1] + "|" + fields[2] + "|" + fields[3]
            }
        }
    }
    // 末尾不再 flush：块只在看到下一块的头或结束标记时才提交，命令没跑完的最后一块宁可不报。
    return result
}

/** 能力探测：`stat` 的 `%Y` / `%s` / `%F` 三段都要有值，且 `find` 要能找到。 */
internal fun remoteScanCapabilityOk(output: String): Boolean {
    val line = output.split('\n').firstOrNull { it.startsWith(PROBE_PREFIX) } ?: return false
    val fields = line.split('\t')
    if (fields.size < 3) return false
    val statFields = fields[1].split('|')
    return statFields.size == 3 && statFields.all { it.isNotEmpty() } && fields[2].isNotBlank()
}

/** 单引号转义，保证目录名进 shell 后不被解释（远端命令全部走这一处拼接）。 */
internal fun remoteShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
