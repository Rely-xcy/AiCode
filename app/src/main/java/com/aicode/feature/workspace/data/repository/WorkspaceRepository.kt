package com.aicode.feature.workspace.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.UriPathResolver
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore by preferencesDataStore(
    name = "workspace_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 当前工作区尚未落定（连接中 / 初始化未完成 / 无可用工作区）时的路径访问错误。
 *
 * 窗口期不能返回「合法但错误」的兜底目录（工作区父目录或远端根）——那会让 git、写文件、
 * 命令 cwd、备份、凭据、记忆静默落到错地方；抛出本异常让调用方明确报错或降级。
 */
class WorkspaceNotReadyException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 远程工作区根取不到、或远程扫描没进行成时的来因。
 *
 * 日志文案（[WorkspaceRepository.remoteScanAbortReason]）与给用户的提示文案
 * （[WorkspaceRepository.initialize] 里默认工作区没建出来时）都从这一份分类派生，
 * 免得同一条链路上出现两套判据。
 *
 * 远程路径配成 `/`（或只有斜杠）时，结尾斜杠会被 `trimEnd('/')` 吃光得到空串，这与「压根没配
 * 远程连接」是两回事：一个要去改路径，一个要去建连接，文案必须分开。
 */
internal enum class RemoteScanAbort {
    /** 没有已保存的远程连接配置。 */
    NotConfigured,

    /** 有配置，但 remoteWorkspacePath 展开后为空，没有任何可用的工作区根。 */
    TargetDirEmpty,

    /** 工作区根取到了，失败落在连接或远端命令上。 */
    ScanAborted
}

/**
 * 管理 App 内的"工作区/项目"。
 *
 * **本地模式**：所有项目放在内部私有目录 `filesDir/projects/<name>` 下——ext4 真实路径，
 * [java.io.File] 工具与 PRoot 容器挂载都能直接使用，无需运行时存储权限，且支持 symlink。
 *
 * **远程模式**：工作区 = 远程 SSH 服务器上 `remoteWorkspacePath` 下的子文件夹。
 * 列表/新建/删除通过 SFTP 操作远程目录，[Workspace.path] 为远程绝对路径。
 *
 * 当前选中的工作区名持久化在 DataStore 中，重启后保留（本地/远程共用同一份名字）。
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val pathHomeResolver: PathHomeResolver,
    private val sessionUseCase: SessionUseCase,
    private val generalSettingsRepository: GeneralSettingsRepository
) {
    /** 校验失败时给 UI 的提示文案（如目录不可写/无法解析），消费后置 null。 */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** UI 消费添加失败提示后调用，清除待展示的文案。 */
    fun consumeAddError() {
        _addError.value = null
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val DEFAULT_WORKSPACE = "default"

        /**
         * [awaitCurrentPathOrNull] 的默认等待上限：覆盖一次 SSH 建连 + 列工作区（含可能的新建默认工作区）
         * 的往返耗时；超过这个时间还不落定，宁可明确报错也不继续等。
         */
        private const val READY_WAIT_TIMEOUT_MS = 8_000L

        /**
         * [waitForConnection] 的等待上限：SSH 建连（含认证）超过它就不等了，
         * 按「本次没连上」继续初始化（工作区保持空，由连接监督协程与手动刷新后续补上）。
         */
        private const val CONNECTION_WAIT_TIMEOUT_MS = 5_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** 外部工作区记录 → 工作区列表；[isDir] 判定目录当前是否存在，决定 available。 */
        internal fun mapExternalWorkspaces(
            records: List<ExternalWorkspaceRecord>,
            isDir: (String) -> Boolean
        ): List<Workspace> = records.map { rec ->
            Workspace(
                name = rec.name,
                path = rec.path,
                type = WorkspaceType.EXTERNAL_LOCAL,
                available = isDir(rec.path)
            )
        }

        /** 生成不与现有工作区重名的目录名，冲突时追加 " (n)" 后缀。 */
        internal fun uniqueName(base: String, existing: Set<String>): String {
            if (base !in existing) return base
            var i = 2
            while ("$base ($i)" in existing) i++
            return "$base ($i)"
        }

        /**
         * 远程工作区根取不到（[root] 为 null）、或扫描没进行成时的来因分类 —— 判据只有这一份。
         *
         * [root] 为 null 只有两种可能，且都不是「服务器上确实没有工作区目录」那种可自愈的预期情况：
         * 没有已保存的远程连接配置，或配置的远程目标目录展开后为空。两者对用户的含义完全不同
         * （一个要去连远程，一个要去改目标目录），日志与提示文案都必须分开。
         *
         * [root] 不为 null 说明根路径取到了，失败落在连接或远端命令上 —— 那两处已各自在
         * [scanRemoteWorkspaceRoot] 里记了带上下文的 WARN，这里只说结论，避免把原因说错。
         */
        internal fun remoteScanAbort(cfg: RemoteConnectionConfig?, root: String?): RemoteScanAbort {
            if (root != null) return RemoteScanAbort.ScanAborted
            return if (cfg == null) RemoteScanAbort.NotConfigured else RemoteScanAbort.TargetDirEmpty
        }

        /** [remoteScanAbort] 的分类结果对应的失败级日志来因，带上可核对的上下文（路径 / 根目录）。 */
        internal fun remoteScanAbortReason(cfg: RemoteConnectionConfig?, root: String?): String {
            return when (remoteScanAbort(cfg, root)) {
                RemoteScanAbort.ScanAborted ->
                    "连接或远端命令未能完成扫描（原因见上一条远程工作区日志），根目录=$root"
                RemoteScanAbort.NotConfigured -> "SSH 未配置（没有已保存的远程连接配置）"
                RemoteScanAbort.TargetDirEmpty ->
                    "远程目标目录为空（remoteWorkspacePath='${cfg?.remoteWorkspacePath}'）"
            }
        }

        /**
         * [initialize] 收尾那条日志的文案。
         *
         * [currentName] 为 null 表示收尾时仍没有可用工作区：这种情况下文案里不能出现「完成」——
         * 原来不论成败都打「工作区初始化完成，当前: null」，事后翻日志只看到一条长得像成功的记录，
         * 查不出「工作区列表为什么是空的」。
         */
        internal fun initCompletionMessage(currentName: String?, location: String): String {
            val root = location.ifBlank { "未配置" }
            if (currentName != null) return "工作区初始化完成，当前: $currentName，根目录: $root"
            return "工作区初始化结束，无可用工作区（列表为空），根目录: $root"
        }
    }

    private val currentNameKey = stringPreferencesKey("current_workspace_name")
    private val externalWarningDismissedKey = booleanPreferencesKey("external_workspace_warning_dismissed")
    val externalWarningDismissed = context.workspaceDataStore.data
        .map { it[externalWarningDismissedKey] ?: false }

    suspend fun setExternalWarningDismissed(dismissed: Boolean) {
        context.workspaceDataStore.edit { it[externalWarningDismissedKey] = dismissed }
    }

    /** 外部本地工作区列表（用户所选设备目录），JSON 序列化持久化，重启保留。 */
    private val externalWorkspacesKey = stringPreferencesKey("external_workspaces")

    /**
     * 所有项目的父目录，固定用内部 filesDir（app 私有 ext4）。
     *
     * 必须是 ext4：外部私有目录（getExternalFilesDir）落在 emulated/FUSE 存储，内核拒绝
     * symlink()，npm/pnpm/yarn/git 建软链时会 `EACCES symlink` 而失败。filesDir 是 ext4，
     * symlink 原生可用，所有工具链零配置即可跑。对外可见性由 DocumentsProvider 暴露，不依赖物理位置。
     */
    private val projectsRoot: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /** 移除外部本地工作区时是否一并删除其聊天记录（供删除确认文案判断）。 */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        generalSettingsRepository.deleteExternalWorkspaceSessionsFlow

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    /** 远程工作区初始化失败（根路径/默认工作区创建失败）的提示文案；UI 消费后置 null。 */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    /** UI 消费错误提示后调用，清除待展示的文案。 */
    fun consumeInitError() {
        _initError.value = null
    }

    /** 扫描并恢复上次选中的工作区；首次启动本地模式会创建默认工作区。应在 App/ViewModel 启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // 符号链接候选：远程模式下「建根目录 + 列子目录 + 更新 ~/workspace 符号链接」要合并成一次远端往返，
        // 就得在发命令前知道该指向哪个工作区。DataStore 读取不产生 SSH 往返，先读没代价。
        // 后面选择目标时仍重新读一次（列表刷新可能刚回退过），两者不一致时按每次扫描的候选决定要不要补一次符号链接。
        val symlinkCandidateName = context.workspaceDataStore.data.first()[currentNameKey]
        var symlinkHandledByScan = false
        // 远程模式：等 SSH 连接就绪后再列工作区，避免启动时序竞争
        if (!isLocal()) {
            waitForConnection()
            val cfg = remoteSshConnection.config
            val wsRoot = remoteWorkspaceRootPath()
            val scan = wsRoot?.let { root -> scanRemoteWorkspaceRoot(root, symlinkCandidateName?.let { "$root/$it" }) }
            if (scan == null) {
                // 扫描没进行成：保持空列表，但必须留下失败级日志并写清是哪一种来因
                // （未配置 / 目标目录为空 / 未连接 / 远端命令失败），否则事后只剩一句「列表为空」。
                FileLogger.w(TAG, "远程工作区初始化中止，工作区列表置空：${remoteScanAbortReason(cfg, wsRoot)}")
                _workspaces.value = emptyList()
            } else {
                val rootCreateFailed = scan.rootExit != null && scan.rootExit != 0
                if (rootCreateFailed) {
                    FileLogger.w(TAG, "创建远程工作区根目录失败: $wsRoot (exit=${scan.rootExit})")
                    _initError.value = context.getString(R.string.workspace_remote_root_create_failed)
                }
                symlinkHandledByScan = scan.symlinkDone
                _workspaces.value = scan.dirs.map { name ->
                    Workspace(name = name, path = "$wsRoot/$name", type = WorkspaceType.REMOTE)
                }
                // 「服务器上确实没有工作区目录」是可自愈的预期情况（根目录刚被 mkdir -p 建出来也一样）：
                // 用 INFO 与上面那条 WARN 分开，紧接着的默认工作区创建会把条目补上。
                if (scan.dirs.isEmpty() && !rootCreateFailed) {
                    FileLogger.i(TAG, "远程工作区根下暂无子目录，按预期创建默认工作区: $wsRoot")
                }
            }
            ensureCurrentReachable()
        } else {
            refreshWorkspaces()
        }

        // 没有可用工作区时创建默认工作区（本地/远程一致），保证 AI 始终有可用目录；
        // 全部工作区失联（如唯一外部目录被删除）也走这里，避免 currentPath 回退到父目录。
        if (_workspaces.value.none { it.available }) {
            if (isLocal()) {
                createLocalFallbackWorkspace()
                refreshWorkspaces()
            } else {
                // 刚列过且列表为空，不再 test -d、也不再重新列：一条 mkdir 后本地拼出条目
                val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                val created = createRemoteWorkspaceWithoutRelist(fallbackName)
                if (created != null) {
                    _workspaces.value = _workspaces.value + created
                } else {
                    // 默认工作区没建出来：按同一份判据（remoteScanAbort）区分来因再决定文案。
                    // 目标目录没配、远程连接没配这两种都指向配置，不能借「请检查服务器权限与磁盘空间」
                    // 把用户带去查服务器；只有根路径取到了却没建成，才确实是权限或磁盘的问题。
                    when (remoteScanAbort(remoteSshConnection.config, remoteWorkspaceRootPath())) {
                        RemoteScanAbort.TargetDirEmpty ->
                            _initError.value = context.getString(R.string.workspace_remote_target_dir_empty)
                        RemoteScanAbort.NotConfigured ->
                            _initError.value = context.getString(R.string.workspace_remote_not_configured)
                        RemoteScanAbort.ScanAborted -> {
                            if (remoteSshConnection.isConnected()) {
                                _initError.value = context.getString(R.string.workspace_remote_default_create_failed)
                            }
                        }
                    }
                }
            }
        }

        val savedName = context.workspaceDataStore.data.first()[currentNameKey]
        val saved = savedName?.let { name -> _workspaces.value.firstOrNull { it.name == name } }
        val target = saved?.takeIf { it.available }
            ?: _workspaces.value.firstOrNull { it.available }
        if (saved != null && !saved.available) {
            _initError.value = target?.let {
                context.getString(R.string.workspace_unavailable_fallback, saved.name, it.name)
            } ?: context.getString(R.string.workspace_unavailable_current, saved.name)
            FileLogger.w(TAG, "上次工作区不可用，回退: ${saved.name} -> ${target?.name}")
        }
        _current.value = target
        val location = if (isLocal()) projectsRoot.absolutePath else remoteSshConnection.config?.remoteWorkspacePath ?: ""
        // 只有真的落到可用工作区才报成功形状的 INFO；否则降为 WARN 并写明「无可用工作区」，
        // 免得日志里只留下一条「工作区初始化完成，当前: null」—— 看着像成功，实则什么都没落定。
        val completion = initCompletionMessage(target?.name, location)
        if (target != null) {
            FileLogger.i(TAG, completion)
        } else {
            FileLogger.w(TAG, completion)
        }
        // 远程模式：选中工作区后更新符号链接，让 Bash 的 ~/workspace 指向当前工作区
        // （若本次扫描已经指向同一个工作区并执行过，就省掉这次往返）
        if (!isLocal() && target != null && !(symlinkHandledByScan && target.name == symlinkCandidateName)) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    private fun isLocal(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    /**
     * 远程模式下挂起等待 SSH 连接就绪（CONNECTED），最多 [CONNECTION_WAIT_TIMEOUT_MS]；
     * 连接失败（FAILED）或等待超时都提前返回，工作区保持空，由后续刷新补齐。
     */
    private suspend fun waitForConnection() {
        val state = withTimeoutOrNull(CONNECTION_WAIT_TIMEOUT_MS) {
            remoteSshConnection.connectionState.first {
                it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
            }
        }
        if (state == null) {
            FileLogger.w(TAG, "等待 SSH 连接就绪超时（${CONNECTION_WAIT_TIMEOUT_MS}ms），工作区保持空")
        } else if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    /** 远程工作区根（remoteWorkspacePath 展开 ~ 后）的绝对路径；未配置或为空时 null。 */
    private fun remoteWorkspaceRootPath(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/')).ifEmpty { null }
    }

    /** 单次扫描远程工作区根得到的结果，见 [scanRemoteWorkspaceRoot]。 */
    private data class RemoteRootScan(
        /** mkdir -p 工作区根的退出码；null 表示没拿到标记（命令没跑完/输出不全），不据此断言根目录建失败。 */
        val rootExit: Int?,
        /** 根下的子目录名（已按小写排序）。 */
        val dirs: List<String>,
        /** 本次是否已把 ~/workspace 指向候选工作区（用于省掉随后单独的一次符号链接往返）。 */
        val symlinkDone: Boolean
    )

    /**
     * 一次远端往返完成「建工作区根 + 列子目录 + 按需更新 ~/workspace 符号链接」。
     *
     * 合并的理由：这三步串行时是 2 次往返，每次都要新建一个 exec session，跨洋 VPS 上累计是秒级。
     * 退出码按 `MK:` / `LN:` 分段落回，失败依旧能归因成「根目录没建出来」，不会退化成一个笼统的「没列到目录」。
     *
     * @param symlinkCandidate 候选工作区真实路径；已存在时顺手把 ~/workspace 指过去（不存在则不碰，
     *   避免留下悬空链接——目录不存在时目标选择还会走后面单独一次更新）。
     * @return null 表示连接不可用/未配置，调用方保持空列表。
     */
    private suspend fun scanRemoteWorkspaceRoot(wsRoot: String, symlinkCandidate: String?): RemoteRootScan? {
        if (!remoteSshConnection.isConnected()) {
            FileLogger.w(TAG, "远程工作区扫描中止：SSH 未连接（连接状态=${remoteSshConnection.connectionState.value}），根目录=$wsRoot")
            return null
        }
        val root = shellQuote(wsRoot)
        val linkPart = if (symlinkCandidate == null) {
            "echo LN:skip"
        } else {
            // 不自行判断符号链接规则：复用 RemoteSshConnection 里那一份（~/workspace 已是真实目录时
            // 必须跳过，否则会建成自引用链接）；它自己输出 skip/done，前缀 LN: 后当作标记回传。
            "if [ -d ${shellQuote(symlinkCandidate)} ]; then printf 'LN:'; " +
                "{ ${remoteSshConnection.workspaceSymlinkCommand(symlinkCandidate)} ; } 2>/dev/null; " +
                "else echo LN:skip; fi"
        }
        val command = "mkdir -p $root; echo \"MK:\$?\"; $linkPart; " +
            "for d in $root/*/; do [ -d \"\$d\" ] && basename \"\$d\"; done; echo \"LS:done\""
        val output = runCatching { execRemote(command) }.getOrElse {
            FileLogger.w(TAG, "扫描远程工作区失败: $wsRoot", it)
            return null
        }
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return RemoteRootScan(
            rootExit = lines.firstOrNull { it.startsWith("MK:") }?.removePrefix("MK:")?.toIntOrNull(),
            dirs = lines
                .filterNot { it.startsWith("MK:") || it.startsWith("LN:") || it.startsWith("LS:") }
                .sortedBy { it.lowercase() },
            symlinkDone = lines.any { it == "LN:done" }
        )
    }

    /**
     * 初始化时「远程工作区列表为空」而建的默认工作区：只发一条 `mkdir -p`，不再 test -d、不再重新列目录
     * ——列表刚列过且为空是已知事实，条目直接本地拼（`mkdir -p` 本身幂等，名字被别的客户端抢先建出也无害）。
     */
    private suspend fun createRemoteWorkspaceWithoutRelist(name: String): Workspace? {
        val wsRoot = remoteWorkspaceRootPath()
        if (wsRoot == null) {
            FileLogger.w(TAG, "远程默认工作区创建中止：远程连接未配置或目标目录展开后为空")
            return null
        }
        val path = "$wsRoot/$name"
        val exit = execRemoteExit("mkdir -p ${shellQuote(path)}")
        if (exit != 0) {
            FileLogger.e(TAG, "远程新建默认工作区失败: $path (exit=$exit)")
            return null
        }
        FileLogger.i(TAG, "新建工作区(远程, 初始化默认): $name")
        return Workspace(name = name, path = path, type = WorkspaceType.REMOTE)
    }

    /** 重新扫描工作区可用性（打开面板、拔插存储后调用），失联项置灰并在必要时回退当前工作区。 */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        refreshWorkspaces()
    }

    /** 重新读取工作区目录列表。本地扫 projectsRoot + 外部本地工作区，远程 exec ls remoteWorkspacePath。 */
    private suspend fun refreshWorkspaces() {
        _workspaces.value = if (isLocal()) refreshLocalWorkspaces() else refreshRemoteWorkspaces()
        ensureCurrentReachable()
    }

    /**
     * 刷新后校验当前工作区：已被移除或不可用（目录被移动/删除）时回退到第一个可用工作区，
     * 并在实际发生切换时通过 [_initError] 提示用户。
     */
    private suspend fun ensureCurrentReachable() {
        val current = _current.value ?: return
        val matched = _workspaces.value.firstOrNull { it.name == current.name }
        if (matched != null && matched.available) return
        var fallback = _workspaces.value.firstOrNull { it.available }
        if (fallback == null && isLocal()) {
            fallback = createLocalFallbackWorkspace()
        }
        _current.value = fallback
        context.workspaceDataStore.edit { prefs ->
            if (fallback != null) prefs[currentNameKey] = fallback.name else prefs.remove(currentNameKey)
        }
        if (fallback != null && fallback.name != current.name) {
            _initError.value = context.getString(R.string.workspace_unavailable_fallback, current.name, fallback.name)
        }
        FileLogger.w(TAG, "当前工作区不可用，回退: ${current.name} -> ${fallback?.name}")
    }

    private suspend fun createLocalFallbackWorkspace(): Workspace? {
        val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
        val fallbackDir = File(projectsRoot, fallbackName)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) {
            FileLogger.e(TAG, "创建本地默认工作区失败: ${fallbackDir.absolutePath}")
            return null
        }
        _workspaces.value = refreshLocalWorkspaces()
        return _workspaces.value.firstOrNull { it.name == fallbackName && it.available }
    }

    private suspend fun refreshLocalWorkspaces(): List<Workspace> {
        val internalDirs = projectsRoot.listFiles { f -> f.isDirectory }
        // 列不出来（返回 null）与「目录里确实没有子目录」（空数组）在列表上长得一样，日志里得分开
        if (internalDirs == null) {
            FileLogger.w(TAG, "本地工作区根目录列目录失败，内部工作区按空处理: ${projectsRoot.absolutePath}")
        }
        val internal = internalDirs
            ?.sortedBy { it.name.lowercase() }
            ?.map { Workspace(name = it.name, path = it.absolutePath) }
            ?: emptyList()
        // 外部本地工作区：失联（目录被移动/删除）时保留记录并标记不可用，目录恢复后自动重新可用
        val external = mapExternalWorkspaces(readExternalWorkspaces()) { File(it).isDirectory }
        return internal + external
    }

    /** exec 列出 remoteWorkspacePath 下的子目录作为工作区（不用 SFTP，避免 sshj Buffer bug）。 */
    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "远程工作区列表失败：SSH 未配置")
            return emptyList()
        }
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        return runCatching {
            // ls -d */ 列出子目录，取基名
            val output = execRemote("ls -d ${wsRoot}/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null")
            if (output.isBlank()) emptyList()
            else output.lines().filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }
                .map { Workspace(name = it.trim(), path = "$wsRoot/${it.trim()}", type = WorkspaceType.REMOTE) }
        }.getOrElse {
            FileLogger.w(TAG, "远程工作区列表失败: $wsRoot", it)
            emptyList()
        }
    }

    /** 同步执行远程命令并返回 stdout（供工作区列表/新建/删除用）。 */
    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                val output = java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    /** 同步执行远程命令并返回退出码。 */
    private suspend fun execRemoteExit(command: String): Int =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                -1
            }
        }

    /** 切换当前工作区并持久化。 */
    suspend fun selectWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name && it.available } ?: return@withContext
        _current.value = target
        context.workspaceDataStore.edit { it[currentNameKey] = name }
        FileLogger.i(TAG, "切换工作区: $name")
        // 远程模式：切换后更新符号链接指向新工作区
        if (!isLocal()) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    /**
     * 新建工作区目录。名称会被清洗为安全的文件夹名。
     * 本地模式 mkdirs projectsRoot/name；远程模式 SFTP mkdirs remoteWorkspacePath/name。
     * @return 创建成功的 [Workspace]；名称非法或已存在返回 null。
     */
    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'")
            return@withContext null
        }
        if (isLocal()) {
            val dir = File(projectsRoot, name)
            if (dir.exists() || _workspaces.value.any { it.name == name }) {
                FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                return@withContext null
            }
            if (!dir.mkdirs()) {
                FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}")
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区: $name")
            Workspace(name = name, path = dir.absolutePath)
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext null
            val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
            val remotePath = "$wsRoot/$name"
            runCatching {
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return@withContext null
                }
                execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
            }.getOrElse {
                FileLogger.e(TAG, "远程新建工作区失败: $remotePath", it)
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区(远程): $name")
            Workspace(name = name, path = remotePath, type = WorkspaceType.REMOTE)
        }
    }

    /**
     * 注册用户所选设备目录为外部本地工作区（双向直读该目录，不复制）。
     * 校验：能解析为真实路径、是目录、可写。与现有工作区同名时自动加后缀。
     * @return 注册成功的 [Workspace]；校验失败返回 null（同时通过 [addError] 给出提示文案）。
     */
    suspend fun addExternalWorkspace(uri: Uri): Workspace? = withContext(Dispatchers.IO) {
        val uriFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val path = UriPathResolver.toFilePath(context, uri)
        if (path == null) {
            _addError.value = context.getString(R.string.workspace_external_unresolvable)
            FileLogger.w(TAG, "添加本地工作区失败：无法解析 uri")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        // 实际读写走 targetSdk 28 的 legacy storage + java.io.File；SAF grant 仅记录目录选择授权，不是文件后端。
        if (path.contains(':')) {
            _addError.value = context.getString(R.string.workspace_external_unsupported_path)
            FileLogger.w(TAG, "添加本地工作区失败：路径包含不支持的冒号 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val dir = File(path)
        val existing = readExternalWorkspaces()
        if (existing.any { it.path == path }) {
            _addError.value = context.getString(R.string.workspace_external_exists)
            FileLogger.w(TAG, "添加本地工作区失败：目录已是工作区 $path")
            return@withContext null
        }
        if (!dir.isDirectory) {
            _addError.value = context.getString(R.string.workspace_external_invalid)
            FileLogger.w(TAG, "添加本地工作区失败：不是目录 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val writable = runCatching {
            val probe = File.createTempFile(".aicode_write_probe_", ".tmp", dir)
            try {
                probe.writeText("ok")
                if (!probe.delete()) error("probe delete failed")
                true
            } catch (e: Exception) {
                runCatching { probe.delete() }
                throw e
            }
        }.getOrDefault(false)
        if (!writable) {
            _addError.value = context.getString(R.string.workspace_external_unwritable)
            FileLogger.w(TAG, "添加本地工作区失败：目录不可写 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val allNames = (_workspaces.value.map { it.name } + existing.map { it.name }).toSet()
        val baseName = sanitize(dir.name).ifBlank { DEFAULT_WORKSPACE }
        val name = uniqueName(baseName, allNames)
        writeExternalWorkspaces(existing + ExternalWorkspaceRecord(name, path, uri.toString()))
        refreshWorkspaces()
        val created = _workspaces.value.firstOrNull { it.path == path }
        FileLogger.i(TAG, "添加本地工作区: $name ($path)")
        created
    }

    private fun releaseUriGrant(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** 删除工作区。内部/远程工作区连同文件与会话记录删除；外部本地工作区只解除关联，不删除物理目录，
     *  其会话记录是否一并删除由「偏好设置」中的开关决定。
     *  若删的是当前工作区，则自动切到剩余的第一个。 */
    suspend fun deleteWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name }
        if (isLocal() && target?.type == WorkspaceType.EXTERNAL_LOCAL) {
            val records = readExternalWorkspaces()
            val removed = records.firstOrNull { it.name == name }
            writeExternalWorkspaces(records.filterNot { it.name == name })
            removed?.let { record ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(record.uri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            if (generalSettingsRepository.deleteExternalWorkspaceSessions()) {
                sessionUseCase.deleteSessionsByWorkspace(target.path)
            }
            FileLogger.i(TAG, "移除外部工作区关联: $name")
        } else if (isLocal()) {
            File(projectsRoot, name).deleteRecursively()
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        } else {
            val cfg = remoteSshConnection.config
            if (cfg != null) {
                val remotePath = "${pathHomeResolver.expandHome(cfg.remoteWorkspacePath).trimEnd('/')}/$name"
                runCatching { execRemoteExit("rm -rf ${shellQuote(remotePath)}") }
                    .onFailure { FileLogger.e(TAG, "远程删除工作区失败: $remotePath", it) }
            }
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        }
        refreshWorkspaces()
        FileLogger.i(TAG, "删除工作区: $name")
    }

    /** 外部本地工作区持久化记录。 */
    @Serializable
    internal data class ExternalWorkspaceRecord(
        val name: String,
        val path: String,
        val uri: String
    )

    private suspend fun readExternalWorkspaces(): List<ExternalWorkspaceRecord> {
        val raw = context.workspaceDataStore.data.first()[externalWorkspacesKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExternalWorkspaceRecord>>(raw) }
            .getOrElse {
                FileLogger.w(TAG, "外部工作区记录解析失败，已忽略", it)
                emptyList()
            }
    }

    private suspend fun writeExternalWorkspaces(list: List<ExternalWorkspaceRecord>) {
        context.workspaceDataStore.edit { it[externalWorkspacesKey] = json.encodeToString(list) }
    }

    /** 单引号转义，保证 shell 命令安全。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前工作区路径；**未落定时为 null**，不猜测兜底目录。供能自然处理「暂无工作区」的调用方使用。 */
    fun currentPathOrNull(): String? = _current.value?.path

    /**
     * 取当前工作区路径，窗口期先等工作区落定（最多 [timeoutMs]）；仍未落定返回 null。
     *
     * 供工具 / 命令 / 终端 / git 等「要一个 cwd 或项目根」的入口使用：刚连上 SSH 的那几秒先等，
     * 超时或确定无可用工作区时返回 null，由调用方给出「工作区未就绪」的明确提示，
     * 而不是让命令跑到工作区父目录上。已落定时零等待。
     */
    suspend fun awaitCurrentPathOrNull(timeoutMs: Long = READY_WAIT_TIMEOUT_MS): String? {
        currentPathOrNull()?.let { return it }
        return withTimeoutOrNull(timeoutMs) { current.mapNotNull { it?.path }.first() }
    }

    /**
     * 未落定原因的提示文案（区分本地初始化未完成 / 远程未连接 / 远程已连但未加载完），
     * 供 [currentPath] 抛出的异常与调用方自定义提示（工具错误、终端提示）复用。
     */
    fun notReadyMessage(): String = when {
        isLocal() -> context.getString(R.string.workspace_not_ready_local)
        remoteSshConnection.connectionState.value == ConnectionState.CONNECTED ->
            context.getString(R.string.workspace_not_ready_remote_connected)
        else -> context.getString(R.string.workspace_not_ready_remote_disconnected)
    }

    /** 未落定时构造路径访问异常，供路径映射、配置目录解析等需要自己抛错的调用方复用同一文案。 */
    fun notReadyException(): WorkspaceNotReadyException = WorkspaceNotReadyException(notReadyMessage())

    /** 当前工作区的路径，供 projectRoot / 命令执行目录使用。
     * 本地模式返回宿主工作区绝对路径；远程模式返回选中工作区的远程绝对路径（命令 cd 到此）。
     *
     * **未落定（连接中 / 初始化未完成 / 无可用工作区）时抛 [WorkspaceNotReadyException]**：
     * 窗口期返回工作区父目录（远端 remoteWorkspacePath / 本地 projectsRoot）或 "/" 同样是
     * 「合法路径」，会让命令、文件写入、git、备份、凭据、记忆静默落到错地方。
     * 需要容错的调用方改用 [currentPathOrNull]，或先经入口等待工作区落定。 */
    fun currentPath(): String = currentPathOrNull() ?: throw notReadyException()

    /** 仅保留字母数字、下划线、连字符、点和空格，去掉路径分隔符等危险字符。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
