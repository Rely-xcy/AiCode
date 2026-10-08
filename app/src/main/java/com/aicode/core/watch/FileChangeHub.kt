package com.aicode.core.watch

import android.os.FileObserver
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App 级文件变更监听中心：监听当前活动工作区与 `~/.aicode` 两个根（也可订阅任意宿主目录），
 * 变更合并成批后广播；订阅方按需声明范围与过滤规则，没人订阅的目录不持有 inotify 句柄、不轮询。
 *
 * 事件来源三层：
 * - 每目录单层 inotify（订阅声明递归时随新目录自动补挂）；
 * - 订阅级快照轮询兜底（按订阅范围递归比对 mtime/size），覆盖 PRoot 绑定目录与外部 FUSE 目录上
 *   inotify 失效的机型，也能发现「整棵目录被移动/替换」这类 inotify 事件不完整的变更；
 * - 远端模式（工作区在 SSH 服务器上，宿主根本没有 inotify 可挂）：交给 [RemoteFileWatchPoller]
 *   做有界轮询，产出同一套 [FileChangeBatch]，消费方无需区分模式。
 *
 * 过滤按订阅方生效：递归剪枝与事件投递都用订阅自己的 [WatchFilter]，同一目录可以对一个订阅可见、
 * 对另一个订阅被忽略。剪枝只看父路径段，故被剪枝目录自身的增删仍会上报（父目录列表才看得到它）。
 *
 * 事件带「域」（[ChangeDomain]）：由**订阅走的是哪条路由**标注，与路径形态无关，消费方按域认领——
 * 配置类消费方不必再拿文件路径去比对（远端模式下拿到的是服务器路径，路径比对永远命不中）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FileChangeHub @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val pathMapper: WorkspacePathMapper,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteFileWatchPoller: RemoteFileWatchPoller
) {
    companion object {
        private const val TAG = "FileChangeHub"

        /** 批量窗口默认长度：窗口内同路径变更合并为一条，窗口到期必发。 */
        const val DEFAULT_BATCH_WINDOW_MS = 300

        /** 单批明细上限，超出即截断（只保留「有变更」信号）。 */
        private const val MAX_BATCH_SIZE = 512

        /** 快照轮询兜底间隔。 */
        private const val POLL_INTERVAL_MS = 2000L

        /** 订阅根尚不存在时的探测间隔。 */
        private const val ROOT_WAIT_INTERVAL_MS = 2000L

        /** 单次快照最多收集的条目数，防止超大订阅每轮遍历开销失控。 */
        private const val MAX_SNAPSHOT_ENTRIES = 4096

        /** 单订阅可持有 inotify 句柄的目录数上限，防止大仓库把 inotify watch 用尽。 */
        private const val MAX_WATCHED_DIRS = 512

        /** 待处理原始事件队列上限；超出时丢弃最旧事件（有快照轮询兜底），防止文件暴增时内存无界增长。 */
        private const val MAX_PENDING_EVENTS = 4096

        /** 待广播批次队列上限；超出时丢弃最旧批次。 */
        private const val MAX_PENDING_BATCHES = 256

        /** AI 看到的工作区根路径。 */
        const val CONTAINER_ROOT = WorkspacePathMapper.CONTAINER_ROOT

        /** AI 配置目录在容器内的根路径。 */
        const val AICODE_ROOT = WorkspacePathMapper.AICODE_ROOT

        /** AI 配置目录在宿主/服务器上的目录名：容器内 `/root/.aicode` 与远端 `$HOME/.aicode` 都是它。 */
        private const val AICODE_DIR_NAME = ".aicode"

        /**
         * 项目级配置在宿主私有配置目录下的落点目录名：远端模式下
         * [com.aicode.feature.workspace.domain.ProjectAicodeRoot] 把项目级配置写在这里的
         * `<项目键>/` 下（本地模式下项目级配置随工作区走，这个目录不使用）。
         */
        private const val PROJECTS_DIR = "projects"

        private const val IN_IGNORED = 0x00008000

        /**
         * 目录监听链路的逐目录 / 逐事件诊断打点。默认关闭：一次启动就会为每个被监听的目录
         * 打若干条，排查挂载竞态或 inotify 失效时再打开。
         */
        private const val DIAG = false

        private fun diag(message: String) {
            if (DIAG) FileLogger.d(TAG, "[diag] $message")
        }

        private val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** FileObserver 必须绑定有 Looper 的线程创建与 startWatching，故观察器起停统一走主线程。 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 存活的订阅集合与锁；[close] 与 [watchHostDir] 增删都走这里。 */
    private val activeSubscriptions = mutableSetOf<Subscription>()
    private val lock = Any()

    /**
     * 关闭 hub 主动持有的一切监听：取消所有存活订阅的协程，并清空目录观察器表。
     *
     * 这是测试用的确定性清理句柄：订阅的 job 由 [watchHostDir] 的 finally 随各自 flow 终止而清理，
     * 但两类竞态可能把 job 留到取消之后——mergeLoop 已越过取消检查、正向已 close 的
     * batchesChannel [kotlinx.coroutines.channels.send]（在 SupervisorJob 的 ioScope 上抛
     * ClosedSendChannelException，无人捕获）；或 attachJob 的 while-delay 循环同帧逃过取消。
     * [close] 先 cancel 再 join（[Subscription.stopAndJoin]），把这些协程确定性收到为零。不清 ioScope/mainScope 本身：
     * 它们是 hub 级长生命周期（App 内永活），关掉等于自杀。生产路径永不调用。
     */
    suspend fun close() {
        val subs = synchronized(lock) { activeSubscriptions.toList() }
        for (sub in subs) sub.stopAndJoin(joinJobs = true)
        dirWatches.clear()
    }

    /** 观察器起停调度兜底：单测里 Main 调度器被 resetMain 换掉后仍安全（排队任务不外抛）。 */
    private fun launchOnMain(block: () -> Unit) {
        try {
            mainScope.launch { block() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "主线程调度不可用，跳过观察器任务: ${e.message}")
        }
    }

    /** 宿主目录路径 → 该目录的观察器（多个订阅共享同一个）。 */
    private val dirWatches = ConcurrentHashMap<String, DirWatch>()

    /**
     * 订阅当前工作区（跟随工作区切换自动重建）。[containerSubPath] 是容器路径，默认整个工作区根。
     * 远程模式下工作区在服务器上、宿主没有对应目录，改走 [RemoteFileWatchPoller] 的远端轮询；
     * 本地模式下工作区未落定时不订阅（不能把工作区父目录当成工作区来观察）。
     *
     * [domain] 标给本订阅产出的事件，默认 [ChangeDomain.WORKSPACE_FILE]。监听工作区内 `.aicode` 配置目录的
     * 消费方传 [ChangeDomain.AICODE_CONFIG]，本地/远端两条分支产出的事件域一致。
     */
    fun watchWorkspace(
        containerSubPath: String = CONTAINER_ROOT,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS,
        domain: ChangeDomain = ChangeDomain.WORKSPACE_FILE
    ): Flow<FileChangeBatch> = workspaceRepository.current
        .map { workspaceRepository.currentPathOrNull() }
        .distinctUntilChanged()
        .flatMapLatest { workspacePath ->
            if (workspacePath == null) return@flatMapLatest emptyFlow()
            if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
                // 远端：容器路径直接拼到远端工作区根上（严格用已落定的工作区路径，不回退父目录）。
                val remoteDir = remoteDirOrNull(workspacePath, containerSubPath)
                    ?: return@flatMapLatest emptyFlow()
                remoteFileWatchPoller.watch(
                    remoteDir = remoteDir,
                    containerRoot = containerSubPath.trimEnd('/'),
                    recursive = recursive,
                    filter = filter,
                    domain = domain
                )
            } else {
                // 订阅建立与订阅消费之间工作区可能又失效，映射失败则退化为空流，不让流异常终止。
                val hostDir = runCatching { pathMapper.toHostFile(containerSubPath) }.getOrNull()
                    ?: return@flatMapLatest emptyFlow()
                watchHostDir(
                    hostDir = hostDir,
                    containerRootPath = containerSubPath.trimEnd('/'),
                    root = ChangeRoot.WORKSPACE,
                    recursive = recursive,
                    filter = filter,
                    fallbackPoll = fallbackPoll,
                    batchWindowMs = batchWindowMs,
                    domain = domain
                )
            }
        }

    /**
     * 容器路径 → 远端绝对目录；不属于当前工作区（`~/workspace` 之外）时返回 null。
     *
     * 只认 [WorkspacePathMapper.CONTAINER_ROOT] 前缀（消费方给的都是这个形式）；`/root/.aicode`、
     * rootfs 路径等既不在远端工作区里、也没有对应的远端目录，一律不订阅。
     */
    private fun remoteDirOrNull(workspacePath: String, containerSubPath: String): String? {
        val sub = containerSubPath.trim().trimEnd('/')
        val rel = when {
            sub == CONTAINER_ROOT -> ""
            sub.startsWith("$CONTAINER_ROOT/") -> sub.removePrefix("$CONTAINER_ROOT/")
            else -> return null
        }
        val base = workspacePath.trimEnd('/')
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /**
     * 订阅 AI 配置目录（`~/.aicode`）下的子路径，[containerSubPath] 为空表示该目录本身。
     * 产出的变更一律标 [ChangeDomain.AICODE_CONFIG]。
     *
     * 三条路由都听：
     * - 宿主私有配置目录：MCP 与权限规则的全局配置一律存在这里（读写都走 java.io.File，不随执行模式切换）；
     * - 远端模式下服务器上的 `$HOME/.aicode`：技能与子代理的全局配置按执行环境读写，就存在那里；
     * - 宿主私有配置目录下的 `projects/`（见 [PROJECTS_DIR]）：远端模式下项目级配置的落点。
     * 前两条各自独立：宿主那条不随执行模式切换重建，远端那条跟随「模式 + 连接状态」重建
     * （远端 home 要连上才知道，订阅比连接先建立时靠它补上）。
     */
    fun watchAicode(
        containerSubPath: String = "",
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> {
        val sub = containerSubPath.trim('/')
        val local = watchHostDir(
            hostDir = if (sub.isEmpty()) containerInstaller.aicodeDir else File(containerInstaller.aicodeDir, sub),
            containerRootPath = if (sub.isEmpty()) AICODE_ROOT else "$AICODE_ROOT/$sub",
            root = ChangeRoot.AICODE,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs,
            domain = ChangeDomain.AICODE_CONFIG
        )
        // 项目级配置在远端模式下落在宿主私有目录 `<aicode 目录>/projects/<项目键>/`（见 ProjectAicodeRoot）：
        // 它是 aicode 目录的**孙**层，上面那条宿主路由非递归、够不着；它也不在服务器上，远端那条路由同样
        // 够不着——于是「改项目级配置」原先没有任何订阅覆盖。该目录与执行模式无关（两种模式下都是宿主私有），
        // 所以单独一条递归路由盯它；事件标同一个域，按域认领的 MCP / 权限 / 技能 / 子代理四个消费方自动受益。
        // 只在订阅整个 aicode 目录时附带，否则 watchAicode("skills") 这类子目录订阅会让同一批事件投两遍。
        val projects = if (sub.isEmpty()) {
            watchHostDir(
                hostDir = File(containerInstaller.aicodeDir, PROJECTS_DIR),
                // 就在 aicode 目录下，容器路径交给 pathMapper 还原成 `/root/.aicode/projects/...`
                containerRootPath = null,
                root = ChangeRoot.AICODE,
                recursive = true,
                filter = filter,
                fallbackPoll = fallbackPoll,
                batchWindowMs = batchWindowMs,
                domain = ChangeDomain.AICODE_CONFIG
            )
        } else {
            emptyFlow()
        }
        val remote = combine(
            executionModeHolder.mode,
            remoteSshConnection.connectionState
        ) { mode, _ -> mode }
            .flatMapLatest { mode ->
                val remoteDir = remoteAicodeDirOrNull(mode, sub) ?: return@flatMapLatest emptyFlow()
                remoteFileWatchPoller.watch(
                    remoteDir = remoteDir,
                    // 容器路径用远端自身路径：`~/.aicode` 在远端模式下就是服务器上的这个目录。
                    containerRoot = remoteDir,
                    recursive = recursive,
                    filter = filter,
                    domain = ChangeDomain.AICODE_CONFIG
                )
            }
        return merge(local, remote, projects)
    }

    /**
     * 远端模式下 AI 看到的 `~/.aicode[/sub]` 在服务器上的绝对路径；本地模式或远端 home 未探到时返回 null。
     *
     * home 直接拼而不是走 `pathMapper`：`~` 在远端展开成哪是服务器的事（root 是 `/root`、普通用户是 `/home/xxx`），
     * 宿主这边只有 [RemoteSshConnection.remoteHome] 知道；拼出来的路径也不能带 `~`，远端命令会给它加单引号。
     */
    private fun remoteAicodeDirOrNull(mode: ExecutionMode, sub: String): String? {
        if (mode != ExecutionMode.REMOTE_SSH) return null
        val home = remoteSshConnection.remoteHome?.trim()?.trimEnd('/').orEmpty()
        if (home.isEmpty()) return null
        return if (sub.isEmpty()) "$home/$AICODE_DIR_NAME" else "$home/$AICODE_DIR_NAME/$sub"
    }

    /**
     * 订阅任意宿主目录（如同步引擎的本地镜像目录）。[containerRootPath] 为 null 时容器路径由
     * [WorkspacePathMapper.toContainerPath] 反推。目录尚不存在时不放弃订阅——等它出现后再挂 watch，
     * 并把「目录出现」当作一次变更上报（项目级 `.aicode/skills` 这类目录经常是后建的）。
     *
     * [domain] 默认 [ChangeDomain.OTHER]（未标注）：调用方如果不清楚自己属于哪个域，保持默认即可。
     */
    fun watchHostDir(
        hostDir: File,
        containerRootPath: String? = null,
        root: ChangeRoot = ChangeRoot.OTHER,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS,
        domain: ChangeDomain = ChangeDomain.OTHER
    ): Flow<FileChangeBatch> = flow {
        val subscription = Subscription(
            rootDir = hostDir,
            containerRootPath = containerRootPath,
            rootKind = root,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            windowMs = batchWindowMs,
            domain = domain
        )
        try {
            synchronized(lock) { activeSubscriptions.add(subscription) }
            subscription.start()
            emitAll(subscription.batches)
        } finally {
            synchronized(lock) { activeSubscriptions.remove(subscription) }
            subscription.stopAndJoin(joinJobs = false)
        }
    }

    private data class RawEvent(val hostPath: String, val kind: ChangeKind)

    /** 一个订阅：持有自己注册的目录集合、过滤规则、批量窗口与快照轮询。 */
    private inner class Subscription(
        private val rootDir: File,
        private val containerRootPath: String?,
        private val rootKind: ChangeRoot,
        private val recursive: Boolean,
        private val filter: WatchFilter,
        private val fallbackPoll: Boolean,
        private val windowMs: Int,
        /** 本订阅产出的事件所属的域；同一目录被多个订阅监听时各自标自己的域。 */
        private val domain: ChangeDomain
    ) {
        private val events = Channel<RawEvent>(
            capacity = MAX_PENDING_EVENTS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        private val batchesChannel = Channel<FileChangeBatch>(
            capacity = MAX_PENDING_BATCHES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val batches: Flow<FileChangeBatch> = batchesChannel.receiveAsFlow()

        private val myDirs = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var rules: IgnoreRules = IgnoreRules.of(filter, emptyList())
        private var mergeJob: Job? = null
        private var pollJob: Job? = null
        private var attachJob: Job? = null

        fun start() {
            rules = IgnoreRules.of(
                filter,
                if (filter.followGitignore) readGitignorePatterns(rootDir) else emptyList()
            )
            mergeJob = ioScope.launch { mergeLoop() }
            if (rootDir.isDirectory) {
                attachTree()
            } else {
                awaitRoot()
            }
        }

        /** 取消本订阅全部协程；[joinJobs] 为真时等它们真正退出（close 用，保证全局线程池收干净）。 */
        suspend fun stopAndJoin(joinJobs: Boolean) {
            val jobs = listOfNotNull(mergeJob, attachJob, pollJob)
            for (job in jobs) job.cancel()
            mergeJob = null
            pollJob = null
            attachJob = null
            for (path in myDirs) dirWatches[path]?.unsubscribe(this)
            myDirs.clear()
            // 先关 channel 再 join：mergeLoop 若正阻塞在 send，闭后立即异常退出，join 不会无限等。
            batchesChannel.close()
            if (joinJobs) for (job in jobs) job.join()
        }

        private fun attachTree() {
            registerTree(rootDir, reportNew = false)
            startPolling()
        }

        /** 订阅根暂不存在：等它出现再挂 watch，出现本身算一次变更。 */
        private fun awaitRoot() {
            attachJob = ioScope.launch {
                while (isActive && !rootDir.isDirectory) delay(ROOT_WAIT_INTERVAL_MS)
                if (!isActive) return@launch
                events.trySend(RawEvent(rootDir.absolutePath, ChangeKind.CREATED))
                attachTree()
            }
        }

        /** 递归注册目录；剪枝只针对父路径段，故先判后注册。[reportNew] 为真时对新挂上的目录补报其存量直接子项。 */
        private fun registerTree(dir: File, reportNew: Boolean) {
            val parts = relativePartsOf(rootDir, dir)
            if (parts.isNotEmpty() && rules.isIgnoredDir(parts)) return
            register(dir, reportNew)
            if (!recursive) return
            dir.listFiles()?.forEach { child -> if (child.isDirectory) registerTree(child, reportNew) }
        }

        private fun register(dir: File, reportNew: Boolean) {
            val path = dir.absolutePath
            if (!myDirs.add(path)) {
                diag("目录监听已登记，跳过重复注册: $path")
                return
            }
            if (dirWatches.size >= MAX_WATCHED_DIRS && !dirWatches.containsKey(path)) {
                myDirs.remove(path)
                FileLogger.w(TAG, "监听目录数达上限 $MAX_WATCHED_DIRS，停止扩展: $path")
                return
            }
            // 新出现的目录在 watch 生效前，其内条目没有任何 watcher 在场、inotify 不会汇报。
            // 补报该目录的存量直接子项来覆盖这段空洞；之后的新增仍由 inotify 汇报。初始注册不补报，
            // 否则订阅建立时会把整棵工作区当作新增全量上报。
            val onReady: (() -> Unit)? = if (reportNew) {
                { ioScope.launch { reportExistingChildren(dir) } }
            } else null
            diag("注册目录监听: $path reportNew=$reportNew")
            dirWatches.computeIfAbsent(path) { DirWatch(dir) }.subscribe(this, onReady)
        }

        /** 挂载监听后补扫子项，覆盖首次扫描与 startWatching 之间新建的目录。 */
        private fun reportExistingChildren(dir: File) {
            val children = dir.listFiles() ?: return
            diag("补报新目录子项: ${dir.absolutePath} 数量=${children.size}")
            for (child in children) {
                events.trySend(RawEvent(child.absolutePath, ChangeKind.CREATED))
                if (recursive && child.isDirectory) registerTree(child, reportNew = true)
            }
        }

        fun onWatchInvalidated(path: String) {
            if (!myDirs.remove(path)) return
            FileLogger.w(TAG, "目录监听失效，准备重新挂载: $path")
            val dir = File(path)
            if (dir.isDirectory) {
                registerTree(dir, reportNew = true)
            } else if (path == rootDir.absolutePath) {
                awaitRoot()
            }
        }

        fun onEvent(watch: DirWatch, hostPath: String, kind: ChangeKind) {
            events.trySend(RawEvent(hostPath, kind))
            // 递归订阅：新出现的目录要补挂（回调在主线程，文件系统检查切到 IO）。
            if (recursive && kind == ChangeKind.CREATED) {
                ioScope.launch {
                    val child = File(hostPath)
                    if (child.isDirectory) registerTree(child, reportNew = true)
                }
            }
        }

        /**
         * 快照轮询兜底：按订阅范围递归比对（非递归订阅只比根目录单层）。
         * 递归快照能发现 inotify 漏报的深层改动与「整棵子树被移动/替换」。
         */
        private fun startPolling() {
            if (!fallbackPoll || pollJob != null) return
            pollJob = ioScope.launch {
                var last = snapshotOfScope()
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val now = snapshotOfScope()
                    val diff = diffSnapshot(last, now)
                    last = now
                    for ((rel, kind) in diff) {
                        val hostPath =
                            if (rel.isEmpty()) rootDir.absolutePath else File(rootDir, rel).absolutePath
                        events.trySend(RawEvent(hostPath, kind))
                        if (recursive && kind == ChangeKind.CREATED) {
                            val child = File(hostPath)
                            if (child.isDirectory) registerTree(child, reportNew = true)
                        }
                    }
                }
            }
        }

        /** 订阅范围快照：键为相对订阅根的路径（根自身为空串）；剪枝目录不进入。 */
        private fun snapshotOfScope(): Map<String, String> {
            val out = HashMap<String, String>()
            if (!rootDir.isDirectory) return out
            var count = 0

            fun walk(dir: File, rel: String) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (count >= MAX_SNAPSHOT_ENTRIES) return
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    count++
                    if (child.isDirectory) {
                        out[childRel] = "d"
                        if (recursive && !rules.isIgnoredDir(relativePartsOf(rootDir, child))) {
                            walk(child, childRel)
                        }
                    } else {
                        out[childRel] = "f:${child.lastModified()}:${child.length()}"
                    }
                }
            }

            walk(rootDir, "")
            return out
        }

        private fun containerPathOf(hostPath: String): String {
            val base = containerRootPath ?: return pathMapper.toContainerPath(hostPath)
            val rel = relativePartsOf(rootDir, File(hostPath)).joinToString("/")
            return if (rel.isEmpty()) base else "$base/$rel"
        }

        private suspend fun mergeLoop() {
            while (true) {
                val first = events.receive()
                val merged = LinkedHashMap<String, ChangeKind>()
                var truncated = false

                fun accept(event: RawEvent) {
                    val existing = merged[event.hostPath]
                    if (existing == null) {
                        if (merged.size >= MAX_BATCH_SIZE) {
                            truncated = true
                            return
                        }
                        merged[event.hostPath] = event.kind
                    } else {
                        merged[event.hostPath] = mergeKind(existing, event.kind)
                    }
                }

                accept(first)
                // 固定窗口：到期必发。静默 debounce 会在编译这类持续变更期间一直不发出，把刷新吞掉。
                val deadline = System.currentTimeMillis() + windowMs
                while (true) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    val next = withTimeoutOrNull(remain) { events.receive() } ?: break
                    accept(next)
                }

                val changes = merged.map { (path, kind) ->
                    FileChange(rootKind, path, containerPathOf(path), kind, domain)
                }
                batchesChannel.send(FileChangeBatch(changes, truncated))
            }
        }
    }

    /** 一个宿主目录的观察器，按订阅引用计数启停（多个订阅共享同一个）。 */
    private inner class DirWatch(private val dir: File) {
        private val path = dir.absolutePath
        private val subscribers: MutableSet<Subscription> =
            Collections.newSetFromMap(ConcurrentHashMap<Subscription, Boolean>())
        private val lock = Any()

        private var observer: FileObserver? = null

        fun subscribe(sub: Subscription, onReady: (() -> Unit)? = null) {
            val firstSubscriber = synchronized(lock) {
                val first = subscribers.isEmpty()
                subscribers.add(sub)
                first
            }
            // watch 已生效时立即回调；否则排到主线程 startObserver 之后，保证 onReady 晚于 startWatching。
            if (firstSubscriber) startObserver(onReady) else launchOnMain { onReady?.invoke() }
        }

        fun unsubscribe(sub: Subscription) {
            val empty = synchronized(lock) {
                subscribers.remove(sub)
                subscribers.isEmpty()
            }
            if (empty) {
                stopObserver()
                dirWatches.remove(path, this)
            }
        }

        private fun startObserver(onReady: (() -> Unit)? = null) {
            launchOnMain {
                if (observer != null) {
                    onReady?.invoke()
                    return@launchOnMain
                }
                @Suppress("DEPRECATION")
                val created = object : FileObserver(path, MASK) {
                    override fun onEvent(event: Int, child: String?) {
                        diag("FileObserver.onEvent path=$path event=0x${Integer.toHexString(event)} child=$child")
                        if (event and IN_IGNORED != 0) {
                            invalidate(this)
                            return
                        }
                        val name = child ?: return
                        val kind = kindOf(event) ?: return
                        dispatch(name, kind)
                    }
                }
                created.startWatching()
                observer = created
                diag("startWatching path=$path")
                // 必须在 startWatching 之后：此刻起 inotify 能捕获新增，补报只负责更早的存量，二者无缝衔接。
                onReady?.invoke()
            }
        }

        private fun invalidate(current: FileObserver) {
            if (observer !== current) return
            observer = null
            dirWatches.remove(path, this)
            val affected = synchronized(lock) {
                subscribers.toList().also { subscribers.clear() }
            }
            FileLogger.w(TAG, "目录观察器被系统移除: $path，订阅数=${affected.size}")
            for (sub in affected) sub.onWatchInvalidated(path)
        }

        private fun stopObserver() {
            val current = observer ?: return
            observer = null
            launchOnMain { runCatching { current.stopWatching() } }
        }

        private fun dispatch(name: String, kind: ChangeKind) {
            val hostPath = File(dir, name).absolutePath
            diag("dispatch hostPath=$hostPath kind=$kind subscribers=${subscribers.size}")
            for (sub in subscribers) sub.onEvent(this, hostPath, kind)
        }

        private fun kindOf(event: Int): ChangeKind? = when {
            event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 -> ChangeKind.CREATED
            event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> ChangeKind.DELETED
            event and FileObserver.CLOSE_WRITE != 0 -> ChangeKind.MODIFIED
            else -> null
        }
    }
}