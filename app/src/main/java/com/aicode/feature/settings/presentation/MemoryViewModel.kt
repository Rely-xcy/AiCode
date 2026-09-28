package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryRepository
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
 * 记忆页状态：列出当前生效的记忆（全局与项目由 Repository 合并去重，界面不再分栏），
 * 支持删除，并提供「长期记忆自动沉淀」开关。
 *
 * 扫描磁盘记忆文件与删除都是 IO，统一放 IO 线程。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val memorySettings: MemorySettingsRepository
) : ViewModel() {

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    val autoDistillEnabled: StateFlow<Boolean> = memorySettings.autoDistillEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            _memories.value = withContext(Dispatchers.IO) {
                memoryRepository.listMemories(projectRoot)
            }
        }
    }

    fun setAutoDistillEnabled(enabled: Boolean) {
        viewModelScope.launch {
            memorySettings.setAutoDistillEnabled(enabled)
        }
    }

    fun delete(memory: Memory) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                memoryRepository.deleteMemory(memory.name, memory.scope, projectRoot)
            }
            refresh()
        }
    }
}
