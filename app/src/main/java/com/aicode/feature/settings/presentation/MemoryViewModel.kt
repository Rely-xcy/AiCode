package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆页顶部「本次会话（短期）」卡片的数据：本次会话的短期上下文规模。
 *
 * 短期（上下文 + 接手摘要）随会话结束就没了，与写盘、跨会话注入提示词的长期记忆是两回事，
 * 所以这张卡片只读、不给编辑入口。
 */
data class SessionShortTermState(
    /** 会话标题。 */
    val title: String,
    /** 仍在上下文里的消息条数（已折叠的历史不计）。 */
    val retainedMessages: Int,
    /** 已折叠次数：每触发一次上下文压缩就多一条接手摘要。 */
    val foldCount: Int,
    /** 上一次请求的输入 token 数（provider 回传的真实值，不是本地估算）；没跑过请求时为 0。 */
    val lastInputTokens: Int
)

/**
 * 记忆页状态：列出当前生效的记忆（全局与项目由 Repository 合并去重，界面不再分栏），
 * 支持删除，并提供「主动记忆」开关与治理周期（周期归零即关闭治理，与开关无关），
 * 另外给出本次会话的短期上下文规模（顶部只读卡片）。
 *
 * 扫描磁盘记忆文件与删除都是 IO，统一放 IO 线程。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val memorySettings: MemorySettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val agentMessageDao: AgentMessageDao
) : ViewModel() {

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val _shortTermSession = MutableStateFlow<SessionShortTermState?>(null)

    /** 顶部「本次会话（短期）」卡片的数据；null 表示取不到（还没有会话等），界面整块不渲染。 */
    val shortTermSession: StateFlow<SessionShortTermState?> = _shortTermSession.asStateFlow()

    private val _deleteFailed = MutableStateFlow(false)

    /** 删除失败的一次性信号（条目已不存在等）：界面提示一次后调 [clearDeleteFailed]。 */
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    fun clearDeleteFailed() {
        _deleteFailed.value = false
    }

    private val _saveFailed = MutableStateFlow(false)

    /**
     * 保存失败的一次性信号：界面提示一次后调 [clearSaveFailed]。
     *
     * 仓库侧遇到「项目级但工作区未落定」会返回 false 且不落到别的目录（见 MemoryRepository.saveMemory）。
     * 但保存入口一返回就关面板，界面上只能看到「面板关了、列表没变」——不提示的话用户以为存上了。
     */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    fun clearSaveFailed() {
        _saveFailed.value = false
    }

    val activeMemoryEnabled: StateFlow<Boolean> = memorySettings.activeMemoryEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 治理周期（小时）：0 表示关闭治理。初始值取仓库默认值，避免先闪一下「关闭」。 */
    val curationIntervalHours: StateFlow<Int> = memorySettings.curationIntervalHoursFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MemorySettingsRepository.DEFAULT_CURATION_INTERVAL_HOURS
        )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // 工作区未落定时只能读全局记忆：项目级记忆属于具体工作区，不能猜工作区父目录。
            val projectRoot = workspaceRepository.currentPathOrNull()
            _memories.value = withContext(Dispatchers.IO) {
                memoryRepository.listMemories(projectRoot)
            }
            _shortTermSession.value = if (projectRoot == null) {
                null
            } else {
                withContext(Dispatchers.IO) { loadShortTerm(projectRoot) }
            }
        }
    }

    /**
     * 读本次会话的短期上下文状态。
     *
     * 会话取的是「当前工作区里最近更新的根会话」——真正的前台会话 id 是 AIAgentViewModel 的私有
     * StateFlow，设置页拿不到，只能先用最近更新的会话当代理（与 App 冷启动「进入最近会话」同一口径，
     * 用户刚在聊天页说过话时它就是对的那个会话，但也可能落在另一个最后更新过的会话上）；
     * 等会话 id 有了共享持有者，这里换成前台会话即可。
     *
     * 取不到会话（还没开过会话、读库失败）返回 null，界面据此不显示卡片。
     */
    private suspend fun loadShortTerm(projectRoot: String): SessionShortTermState? {
        val session = runCatching { sessionUseCase.getMostRecentSessionOfWorkspace(projectRoot) }
            .getOrNull()
            ?: return null
        val stats = runCatching { agentMessageDao.sessionContextStats(session.id) }.getOrNull()
            ?: return null
        return SessionShortTermState(
            title = session.title,
            retainedMessages = stats.retainedMessages,
            foldCount = stats.foldCount,
            lastInputTokens = session.lastInputTokens
        )
    }

    fun setActiveMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            memorySettings.setActiveMemoryEnabled(enabled)
        }
    }

    fun setCurationIntervalHours(hours: Int) {
        viewModelScope.launch {
            memorySettings.setCurationIntervalHours(hours)
        }
    }

    fun delete(memory: Memory) {
        viewModelScope.launch {
            // 工作区未落定时 projectRoot 为 null：项目级删除会失败并走下面的失败提示，不静默落到别的目录。
            val projectRoot = workspaceRepository.currentPathOrNull()
            val deleted = withContext(Dispatchers.IO) {
                memoryRepository.deleteMemory(memory.name, memory.scope, projectRoot)
            }
            // 删失败不能静静吞掉：只 refresh 的话条目还在，用户不知道发生了什么
            if (!deleted) _deleteFailed.value = true
            refresh()
        }
    }

    /**
     * 保存一条记忆。
     *
     * [target] 为 null 表示新建：作用域用调用方选的 [scope]（全局或项目），类型为手动记录（[MemoryKind.NOTE]）。
     * 非 null 表示编辑已有条目：沿用它的作用域、类型、来源与创建时间，名称不可改
     * （名称是记忆的唯一标识，换名就是新建另一条）。
     * [pinned] 是本次保存显式设定的置顶状态（新建或编辑都直接落盘，取消勾选即取消置顶）。
     */
    fun save(
        target: Memory?,
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        pinned: Boolean
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPathOrNull()
            val saved = withContext(Dispatchers.IO) {
                memoryRepository.saveMemory(
                    name = target?.name ?: name.trim(),
                    description = description.trim(),
                    content = content,
                    scope = target?.scope ?: scope,
                    projectRoot = projectRoot,
                    kind = target?.kind ?: MemoryKind.NOTE,
                    source = target?.source.orEmpty(),
                    createdAt = target?.createdAt ?: 0L,
                    pinned = pinned
                )
            }
            // 存失败不能静静吞掉：编辑器已经关了，只 refresh 的话用户看到的是「列表里没这条」
            if (!saved) _saveFailed.value = true
            refresh()
        }
    }
}
