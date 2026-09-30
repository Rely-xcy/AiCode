package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.datastore.ListOrderStore
import com.aicode.feature.agent.domain.prompt.PromptFragmentRepository
import com.aicode.feature.agent.domain.prompt.UserPrompt
import com.aicode.feature.agent.domain.prompt.UserPromptPosition
import com.aicode.feature.agent.domain.prompt.UserPromptScope
import com.aicode.feature.agent.domain.prompt.UserPromptStore
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 自定义提示词页的可变状态。 */
data class PromptsUiState(
    val globalPrompts: List<UserPrompt> = emptyList(),
    val projectPrompts: List<UserPrompt> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 是否已读过使用说明：未读时提示词页先展示帮助，读完才放行。 */
    val helpRead: Boolean = false,
    /** 内置静态片段清单（含覆盖状态），首页固定行与「高级设置」共用这一份。 */
    val fragments: List<PromptFragmentRepository.Fragment> = emptyList(),
    /** 没有选中工作区时项目组不可用。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
) {
    /** 首页固定行展示的内置片段（默认 00 身份/总纲）；读不到时为 null。 */
    val defaultFragment: PromptFragmentRepository.Fragment?
        get() = fragments.firstOrNull { it.number == PromptsViewModel.DEFAULT_FRAGMENT_NUMBER }
}

/**
 * 自定义提示词页状态：用户提示词的增删改 + 固定内置片段（00）的覆盖 + 内置开关。
 *
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val userPromptStore: UserPromptStore,
    private val fragmentRepository: PromptFragmentRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val listOrderStore: ListOrderStore
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    /** 各作用域排序落盘的防抖 job，见 [reorderPrompts]。 */
    private val orderWriteJobs = mutableMapOf<UserPromptScope, Job>()

    /**
     * 写覆盖副本失败的一次性提示信号。
     *
     * 单独一个流而不是塞进 [state]：刷新会把 state 整个换掉，信号会跟着没；
     * 而且失败时用户已经返回列表页，信号得在那里等着被消费。与记忆页删除失败同一做法。
     */
    private val _fragmentSaveFailed = MutableStateFlow(false)
    val fragmentSaveFailed: StateFlow<Boolean> = _fragmentSaveFailed.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    globalPrompts = userPromptStore.list(UserPromptScope.GLOBAL, projectRoot),
                    projectPrompts = userPromptStore.list(UserPromptScope.PROJECT, projectRoot),
                    builtinDisabled = fragmentRepository.isBuiltinDisabled(),
                    helpRead = fragmentRepository.isHelpRead(),
                    fragments = fragmentRepository.listFragments(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /**
     * 保存某个内置片段的覆盖副本（写入用户目录，不动 App 内置文件）。
     *
     * 标题只是新增片段的文件名兜底，内置片段沿用内置文件名，见
     * [PromptFragmentRepository.saveOverride]。
     */
    fun saveFragment(number: Int, title: String, content: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                fragmentRepository.saveOverride(number, title, content)
            }
            // 失败要报出来：目录不可写、磁盘满都会走到这里，静默的话用户只会看到「改了没反应」
            if (!ok) _fragmentSaveFailed.value = true
            refresh()
        }
    }

    /** 提示已消费。 */
    fun clearFragmentSaveFailed() {
        _fragmentSaveFailed.value = false
    }

    /** 删除覆盖副本，恢复 App 自带内容。 */
    fun restoreBuiltin(number: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fragmentRepository.deleteOverride(number) }
            refresh()
        }
    }

    fun savePrompt(
        existing: UserPrompt?,
        name: String,
        scope: UserPromptScope,
        position: UserPromptPosition,
        content: String,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                val prompt = existing?.copy(name = name, position = position, content = content, enabled = enabled)
                    ?: userPromptStore.newPrompt(name, position, content, enabled)
                userPromptStore.save(scope, projectRoot, prompt)
            }
            refresh()
        }
    }

    fun deletePrompt(prompt: UserPrompt, scope: UserPromptScope) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { userPromptStore.delete(scope, projectRoot, prompt.id) }
            refresh()
        }
    }

    /**
     * 提示词列表长按拖拽排序：先同步改内存列表（reorderable 库要求 onMove 返回前列表已变，否则拖拽项闪烁），
     * 停手 [REORDER_WRITE_DEBOUNCE_MS] 后把顺序表写一次；注入顺序走 [UserPromptStore.list]，同一张顺序表。
     *
     * 参数用 prompt id 而不是下标：列表下标是含分组标题、内置片段行的全局下标，
     * 用 id 在权威列表里定位更不容易错。
     */
    fun reorderPrompts(scope: UserPromptScope, movedId: String, targetId: String) {
        val current = _state.value
        val scoped = if (scope == UserPromptScope.GLOBAL) current.globalPrompts else current.projectPrompts
        val fromIndex = scoped.indexOfFirst { it.id == movedId }
        val toIndex = scoped.indexOfFirst { it.id == targetId }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        val moved = scoped.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _state.value = if (scope == UserPromptScope.GLOBAL) {
            current.copy(globalPrompts = moved)
        } else {
            current.copy(projectPrompts = moved)
        }
        val key = if (scope == UserPromptScope.GLOBAL) {
            ListOrderStore.KEY_PROMPTS_GLOBAL
        } else {
            ListOrderStore.KEY_PROMPTS_PROJECT
        }
        orderWriteJobs[scope]?.cancel()
        orderWriteJobs[scope] = viewModelScope.launch {
            delay(REORDER_WRITE_DEBOUNCE_MS)
            listOrderStore.save(key, moved.map { it.id })
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fragmentRepository.setBuiltinDisabled(disabled) }
            refresh()
        }
    }

    /** 读完使用说明，放行进入提示词页。 */
    fun markHelpRead() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fragmentRepository.markHelpRead() }
            refresh()
        }
    }

    companion object {
        /** 首页固定行展示的内置片段：00 身份/总纲。其余内置片段在「高级设置」里逐条编辑。 */
        const val DEFAULT_FRAGMENT_NUMBER = 0

        /** 拖拽排序的落盘延迟：拖动中只改内存，停手这么久后写一次。 */
        const val REORDER_WRITE_DEBOUNCE_MS = 400L
    }
}
