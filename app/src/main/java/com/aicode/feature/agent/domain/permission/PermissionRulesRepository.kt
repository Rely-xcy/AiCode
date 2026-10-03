package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权规则的持久化。全局规则存 app 私有目录 (`filesDir/aicode/permissions.json`)，
 * 项目级规则存工作区目录 (`.aicode/permissions.json`)，方便团队通过 git 共享。
 *
 * 文件格式为紧凑的 `Tool(pattern)` 风格，示例：
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 *
 * 安全要点：全局规则存在 app 私有目录，AI 无法篡改；项目级规则存在工作区内，
 * 可被 AI 修改，但作为项目级声明式配置这是有意为之（可 git 追踪/回滚）。
 *
 * 读取语义：「确认没有规则」与「读不到规则」严格区分（文件不存在 = 确认没有；工作区未落定或
 * 解析失败 = 读不到），后者不得当成「这一层没有规则」——项目级与全局级都适用，
 * 见 [loadEffectiveForCurrentProject]。
 *
 * 并发模式参考 [McpConfigRepository]：Mutex 保护文件 IO + MutableStateFlow 缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "PermissionRules"
        const val PERMISSIONS_FILE = "permissions.json"
        /** 项目级配置目录名，容器内即 `~/workspace/.aicode`。 */
        const val AICODE_DIR_NAME = ".aicode"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    /** 全局权限文件：`filesDir/aicode/permissions.json`，与 mcp.json 同级。 */
    private val globalFile: File
        get() = File(File(context.filesDir, "aicode"), PERMISSIONS_FILE)

    /** 当前工作区的项目级权限文件：`workspacePath/.aicode/permissions.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), PERMISSIONS_FILE)

    // ── 内存缓存与响应式流 ──────────────────────────────────────

    /**
     * 一次规则文件读取的结果，区分「确认没有规则」与「读不到」：
     *
     * - [confirmed] = true：文件不存在（= 确认没有规则）或解析成功；
     * - [confirmed] = false：读取/解析失败——此时 [rules] 不能当作「没有规则」使用。
     */
    private data class LoadedRules(val rules: List<PermissionRule>, val confirmed: Boolean)

    private val globalState = MutableStateFlow<LoadedRules?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<LoadedRules?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑 permissions.json 后刷新缓存，UI 与评估即时生效 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 启动配置文件监听：外部修改后按磁盘现状刷新缓存。App 启动调用一次（幂等）。
     * 订阅驱动：[FileChangeHub] 监听两个权限文件所在目录并合并成批上报，不再自建轮询。
     * 保持常驻订阅是因为 AI 权限评估随时要读到最新规则（不像纯 UI 列表可以暂停刷新）。
     */
    fun startWatching() {
        watchScope.launch {
            merge(
                fileChangeHub.watchAicode(),
                fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
            ).collect { batch -> refreshFromDisk(batch) }
        }
    }

    /** 只处理命中的文件；内容确实变化才更新缓存（touch 不算）。 */
    private suspend fun refreshFromDisk(batch: FileChangeBatch) {
        val globalPath = globalFile.absolutePath
        if (batch.changes.any { it.hostPath == globalPath }) {
            // 与项目级同一套语义：读失败写入 confirmed=false，此后不再当作「全局没有规则」。
            val loaded = loadFromFile(globalFile)
            if (loaded != globalState.value) {
                globalState.value = loaded
                FileLogger.i(TAG, "检测到全局权限配置变化，已刷新")
            }
        }
        val path = workspaceRepository.currentPathOrNull()
        if (path != null && batch.changes.any { it.hostPath == projectFileForPath(path).absolutePath }) {
            val state = getProjectState(path)
            // 缓存尚未加载时不处理：首次加载由 [ensureProjectLoaded] 完成，工作区切换不算外部变更。
            if (state.value != null) {
                // 刷新失败时写入 confirmed=false：文件刚被改成读不出来的样子，此后不再当作「没有项目规则」。
                val loaded = loadFromFile(projectFileForPath(path))
                if (loaded != state.value) {
                    state.value = loaded
                    FileLogger.i(TAG, "检测到项目权限配置变化，已刷新")
                }
            }
        }
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<LoadedRules?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow<LoadedRules?>(null) }

    // ── 懒加载 ──────────────────────────────────────────────────

    /** 加载并缓存全局规则；返回本次（或缓存中）的读取结果，含「是否确认可读」。 */
    private suspend fun ensureGlobalLoaded(): LoadedRules {
        globalState.value?.let { return it }
        return mutex.withLock {
            globalState.value ?: loadFromFile(globalFile).also { globalState.value = it }
        }
    }

    /** 加载并缓存指定工作区的项目级规则；返回本次（或缓存中）的读取结果，含「是否确认可读」。 */
    private suspend fun ensureProjectLoaded(workspacePath: String): LoadedRules {
        val state = getProjectState(workspacePath)
        state.value?.let { return it }
        return mutex.withLock {
            state.value ?: loadFromFile(projectFileForPath(workspacePath)).also { state.value = it }
        }
    }

    private suspend fun loadFromFile(file: File): LoadedRules =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext LoadedRules(emptyList(), confirmed = true)
            runCatching {
                JSON.decodeFromString<PermissionFile>(file.readText()).toRuleList()
            }.fold(
                onSuccess = { LoadedRules(it, confirmed = true) },
                onFailure = {
                    FileLogger.w(TAG, "读取 ${file.path} 失败: ${it.message}")
                    LoadedRules(emptyList(), confirmed = false)
                }
            )
        }

    private fun writeToFile(file: File, rules: List<PermissionRule>) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
    }

    // ── 公共 API ────────────────────────────────────────────────

    /** 当前选中的项目名；无选中时为 null（此时项目级规则不可用，仅全局生效）。 */
    fun currentProjectName(): String? = workspaceRepository.current.value?.name

    /** 当前项目名流，跟随工作区切换自动更新，供 UI 订阅。 */
    val currentProjectNameFlow: Flow<String?> = workspaceRepository.current.map { it?.name }

    /**
     * 当前项目规则流：跟随 [workspaceRepository.current] 切换自动重新加载对应项目规则；
     * 无选中工作区时发空列表。供 UI 订阅，避免一次性快照在初始化未完成时读到 null。
     */
    val currentProjectRulesFlow: Flow<List<PermissionRule>> =
        workspaceRepository.current.flatMapLatest { ws ->
            if (ws == null) flowOf(emptyList()) else projectRulesFlow(ws.name)
        }

    /** 全局规则流，供管理界面观察。读不到规则时发空列表（UI 只能展示已读到的部分），评估路径不走这里。 */
    val globalRulesFlow: Flow<List<PermissionRule>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull().map { it.rules })
    }

    /** 一次性读取全部全局规则（备份用）。 */
    suspend fun getGlobalRulesOnce(): List<PermissionRule> = ensureGlobalLoaded().rules

    /** 全量替换全局规则（备份导入用），原子写文件并更新缓存。 */
    suspend fun setGlobalRules(rules: List<PermissionRule>) {
        mutex.withLock {
            withContext(Dispatchers.IO) { writeToFile(globalFile, rules) }
            // 刚写盘成功，缓存可直接标为「确认可读」。
            globalState.value = LoadedRules(rules, confirmed = true)
        }
    }

    /**
     * 指定项目的规则流，供管理界面观察；工作区未落定时发空列表。
     * 读不到规则时发空列表（UI 只能展示已读到的部分），评估路径不走这里、不受此影响。
     */
    fun projectRulesFlow(projectName: String): Flow<List<PermissionRule>> {
        val workspacePath = workspaceRepository.currentPathOrNull() ?: return flowOf(emptyList())
        val state = getProjectState(workspacePath)
        return flow {
            ensureProjectLoaded(workspacePath)
            emitAll(state.filterNotNull().map { it.rules })
        }
    }

    /**
     * 评估用：当前项目规则 + 全局规则合并（项目在前）。一次性读取快照。
     *
     * **「确认没有某一层规则」与「读不到某一层规则」不是一回事**（项目级与全局级同规）：
     * - 工作区已落定且项目文件读到（含文件不存在 = 确认没有项目规则）→ `projectRulesConfirmed = true`；
     * - 全局文件读到（含文件不存在）→ `globalRulesConfirmed = true`；
     * - 工作区未落定 / 项目文件读取或解析失败 → `projectRulesConfirmed = false`；
     * - 全局文件读取或解析失败 → `globalRulesConfirmed = false`。
     * 任一为 false 时，调用方都不得据此放宽放行判定（否则对应层的 DENY 会被漏读）。
     */
    suspend fun loadEffectiveForCurrentProject(): EffectivePermissionRules {
        val global = ensureGlobalLoaded()
        val workspacePath = workspaceRepository.currentPathOrNull()
            ?: return EffectivePermissionRules(
                global.rules,
                projectRulesConfirmed = false,
                globalRulesConfirmed = global.confirmed
            )
        val project = ensureProjectLoaded(workspacePath)
        return EffectivePermissionRules(
            project.rules + global.rules,
            projectRulesConfirmed = project.confirmed,
            globalRulesConfirmed = global.confirmed
        )
    }

    /**
     * 按 scope 新增规则。PROJECT 写入当前项目；无当前项目或工作区未落定时忽略并告警
     * （返回 false，由记忆授权调用方决定怎么提示）。
     */
    suspend fun add(scope: PermissionScope, rule: PermissionRule): Boolean {
        FileLogger.i(TAG, "记忆授权规则[$scope]: ${rule.toolName} ${rule.pattern}")
        when (scope) {
            PermissionScope.GLOBAL -> editGlobal { if (rule !in it) it.add(rule) }
            PermissionScope.PROJECT -> {
                val workspacePath = workspaceRepository.currentPathOrNull() ?: run {
                    FileLogger.w(TAG, "工作区未就绪，无法新增项目级规则: ${rule.toolName} ${rule.pattern}")
                    return false
                }
                editProject(workspacePath) { if (rule !in it) it.add(rule) }
            }
        }
        return true
    }

    suspend fun removeGlobalRule(rule: PermissionRule) = editGlobal { it.remove(rule) }

    /** 删除项目级规则；工作区未落定时不写（不知道写哪个项目），仅记日志。@return false 表示被跳过。 */
    suspend fun removeProjectRule(projectName: String, rule: PermissionRule): Boolean {
        val workspacePath = workspaceRepository.currentPathOrNull() ?: run {
            FileLogger.w(TAG, "工作区未就绪，忽略项目级规则删除: ${rule.toolName} ${rule.pattern}")
            return false
        }
        editProject(workspacePath) { it.remove(rule) }
        return true
    }

    /** 把一条项目规则提升为全局：项目删、全局加。工作区未落定时不写（避免只删到一半），仅记日志。
     *  @return false 表示被跳过（项目层没删、全局层也没加）。 */
    suspend fun promoteToGlobal(projectName: String, rule: PermissionRule): Boolean {
        val workspacePath = workspaceRepository.currentPathOrNull() ?: run {
            FileLogger.w(TAG, "工作区未就绪，忽略提升为全局: ${rule.toolName} ${rule.pattern}")
            return false
        }
        editProject(workspacePath) { it.remove(rule) }
        editGlobal { if (rule !in it) it.add(rule) }
        FileLogger.i(TAG, "提升为全局: ${rule.toolName} ${rule.pattern}")
        return true
    }

    // ── 内部写入 ────────────────────────────────────────────────

    private suspend fun editGlobal(mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureGlobalLoaded()
        mutex.withLock {
            val list = (globalState.value?.rules ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(globalFile, list) }
            // 刚写盘成功，缓存可直接标为「确认可读」。
            globalState.value = LoadedRules(list, confirmed = true)
        }
    }

    private suspend fun editProject(workspacePath: String, mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureProjectLoaded(workspacePath)
        mutex.withLock {
            val state = getProjectState(workspacePath)
            val list = (state.value?.rules ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(projectFileForPath(workspacePath), list) }
            // 刚写盘成功，缓存可直接标为「确认可读」。
            state.value = LoadedRules(list, confirmed = true)
        }
    }
}
