package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.agent.domain.prompt.assignReorderNumbers
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提示词页状态：按编号列出最终生效的片段（含来源），以及内置开关。 */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 没有选中工作区时项目层不可写，编辑/新建回落到全局层。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * 提示词页状态：四级来源（项目 > 全局 > 本地 > 内置）的生效片段列表 + 内置开关。
 *
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 当前工作区根路径；工作区未落定时为空串（项目层不可用，UI 据 hasWorkspace 关掉项目作用域）。
     */
    private fun currentProjectRoot(): String = workspaceRepository.currentPathOrNull().orEmpty()

    /**
     * 作用域写入或删除因「工作区未就绪」被跳过时的一次性提示；UI 展示后调 [consumeConfigWriteError] 清空。
     *
     * 与设置页其它作用域写入同源：提示语取 [WorkspaceRepository.notReadyMessage]。不提示的话，
     * 用户看到的只是「编辑器关掉了、列表没变」，分不清是没保存还是存错了层。
     */
    private val _configWriteError = MutableStateFlow<String?>(null)
    val configWriteError: StateFlow<String?> = _configWriteError.asStateFlow()

    fun consumeConfigWriteError() {
        _configWriteError.value = null
    }

    private fun reportConfigWriteSkipped() {
        _configWriteError.value = workspaceRepository.notReadyMessage()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = currentProjectRoot()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** 保存某编号的覆盖（新建与编辑同一入口）：写到所选作用域层；编辑改编号时清掉旧编号。
     *
     * 项目层写入被跳（工作区未落定）时给出可见提示，并把结果如实反映到 [configWriteError]。
     */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = currentProjectRoot()
            val saved = withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            if (!saved) reportConfigWriteSkipped()
            refresh()
        }
    }

    /**
     * 删除某编号在 [scope] 层的覆盖（列表行左滑：删的就是该行标的那一层），删后自动回退到下一层。
     *
     * 项目层删除被跳（工作区未落定）时给出可见提示。只有项目层会因未落定被拒；全局层的删除不
     * 依赖工作区，返回 false 是别的原因（文件已不在、IO 失败），不套「未就绪」文案。
     */
    fun deleteFragment(number: Int, scope: PromptFragmentSource) {
        viewModelScope.launch {
            val projectRoot = currentProjectRoot()
            val deleted = withContext(Dispatchers.IO) {
                catalog.deleteOverride(number, projectRoot, scope)
            }
            if (!deleted && scope == PromptFragmentSource.PROJECT) reportConfigWriteSkipped()
            refresh()
        }
    }

    /**
     * 拖拽重排：按新顺序重新编号并落盘，改变注入顺序。
     *
     * 编号只分配给可改名的片段（内置片段留原位），并且每条写回自己所属的层，来源徽章不变。
     * 乐观更新本地状态，避免重读导致列表跳动。
     */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val renumbered = assignReorderNumbers(reordered)
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = currentProjectRoot()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }
}
