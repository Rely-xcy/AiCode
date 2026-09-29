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
    /** 用户可改的固定内置片段（00 身份/总纲）的生效正文；读不到时为 null。 */
    val defaultFragmentContent: String? = null,
    val defaultFragmentOverridden: Boolean = false,
    val globalPrompts: List<UserPrompt> = emptyList(),
    val projectPrompts: List<UserPrompt> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 是否已读过使用说明：未读时提示词页先展示帮助，读完才放行。 */
    val helpRead: Boolean = false,
    /** 内置静态片段清单（含覆盖状态），供「高级设置」只读展示。 */
    val fragments: List<PromptFragmentRepository.Fragment> = emptyList(),
    /** 没有选中工作区时项目组不可用。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                val default = fragmentRepository.fragment(DEFAULT_FRAGMENT_NUMBER)
                PromptsUiState(
                    defaultFragmentContent = default?.content,
                    defaultFragmentOverridden = default?.isOverridden == true,
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

    /** 保存固定内置片段（00）的覆盖；内容为空视为删除覆盖（恢复内置默认）。 */
    fun saveDefaultFragment(content: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (content.isBlank()) {
                    fragmentRepository.deleteOverride(DEFAULT_FRAGMENT_NUMBER)
                } else {
                    fragmentRepository.saveOverride(DEFAULT_FRAGMENT_NUMBER, DEFAULT_FRAGMENT_TITLE, content)
                }
            }
            refresh()
        }
    }

    /** 恢复固定内置片段的默认内容（删掉覆盖）。 */
    fun resetDefaultFragment() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fragmentRepository.deleteOverride(DEFAULT_FRAGMENT_NUMBER) }
            refresh()
        }
    }

    fun savePrompt(
        existing: UserPrompt?,
        name: String,
        scope: UserPromptScope,
        position: UserPromptPosition,
        content: String
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                val prompt = existing?.copy(name = name, position = position, content = content)
                    ?: userPromptStore.newPrompt(name, position, content)
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
        /** 用户可改的固定片段：00 身份/总纲。其余内置片段在「高级设置」里只读展示。 */
        const val DEFAULT_FRAGMENT_NUMBER = 0

        /** 拖拽排序的落盘延迟：拖动中只改内存，停手这么久后写一次。 */
        const val REORDER_WRITE_DEBOUNCE_MS = 400L

        private const val DEFAULT_FRAGMENT_TITLE = "identity"
    }
}
