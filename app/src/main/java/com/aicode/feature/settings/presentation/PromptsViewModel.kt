package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.PromptFragmentRepository
import com.aicode.feature.agent.domain.prompt.UserPrompt
import com.aicode.feature.agent.domain.prompt.UserPromptPosition
import com.aicode.feature.agent.domain.prompt.UserPromptScope
import com.aicode.feature.agent.domain.prompt.UserPromptStore
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

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

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fragmentRepository.setBuiltinDisabled(disabled) }
            refresh()
        }
    }

    companion object {
        /** 用户可改的固定片段：00 身份/总纲。其余内置片段在「高级设置」里只读展示。 */
        const val DEFAULT_FRAGMENT_NUMBER = 0
        private const val DEFAULT_FRAGMENT_TITLE = "identity"
    }
}
