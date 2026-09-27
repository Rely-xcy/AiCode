package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆页状态：列出当前生效的记忆（全局与项目由 Repository 合并去重，界面不再分栏），支持删除。
 *
 * 扫描的是磁盘上的记忆文件，同为 IO，故与删除一起放到 IO 线程。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

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
