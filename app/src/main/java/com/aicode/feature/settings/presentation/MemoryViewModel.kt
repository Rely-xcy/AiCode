package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
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
 * 支持删除，并提供「长期记忆自动沉淀」开关与治理周期。
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

    fun setCurationIntervalHours(hours: Int) {
        viewModelScope.launch {
            memorySettings.setCurationIntervalHours(hours)
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

    /**
     * 保存一条记忆。
     *
     * [target] 为 null 表示新建：写入全局作用域、类型为手动记录（[MemoryKind.NOTE]）。
     * 非 null 表示编辑已有条目：沿用它的作用域、类型、来源与创建时间，名称不可改
     * （名称是记忆的唯一标识，换名就是新建另一条）。
     */
    fun save(target: Memory?, name: String, description: String, content: String) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                memoryRepository.saveMemory(
                    name = target?.name ?: name.trim(),
                    description = description.trim(),
                    content = content,
                    scope = target?.scope ?: MemoryScope.GLOBAL,
                    projectRoot = projectRoot,
                    kind = target?.kind ?: MemoryKind.NOTE,
                    source = target?.source.orEmpty(),
                    createdAt = target?.createdAt ?: 0L
                )
            }
            refresh()
        }
    }
}
