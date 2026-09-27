package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.dao.ProfileEntryDao
import com.aicode.feature.agent.data.local.entity.ProfileEntryEntity
import com.aicode.feature.agent.data.local.entity.ProfileSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 用户画像页的状态与动作。
 *
 * 画像由 ProfileModule 在每轮对话后自动沉淀，这里只负责「看 + 删」：
 * 用户需要能核对 AI 到底记住了什么，也需要能删掉记错的那条——否则画像一旦跑偏就没有出口。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileEntryDao: ProfileEntryDao
) : ViewModel() {

    /** 按分节分组后的展示单元。 */
    data class SectionGroup(val section: ProfileSection, val entries: List<ProfileEntryEntity>)

    private val _groups = MutableStateFlow<List<SectionGroup>>(emptyList())
    val groups: StateFlow<List<SectionGroup>> = _groups.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun setShowHistory(show: Boolean) {
        _showHistory.value = show
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (all, active) = withContext(Dispatchers.IO) {
                val allEntries = profileEntryDao.allOnce()
                allEntries to allEntries.count {
                    it.status == com.aicode.feature.agent.data.local.entity.ProfileStatus.ACTIVE
                }
            }
            val visible = if (_showHistory.value) all else all.filter {
                it.status == com.aicode.feature.agent.data.local.entity.ProfileStatus.ACTIVE
            }
            _activeCount.value = active
            _groups.value = ProfileSection.values().mapNotNull { section ->
                val inSection = visible.filter { it.section == section.key }
                if (inSection.isEmpty()) null else SectionGroup(section, inSection)
            }
        }
    }

    /** 删除一条画像（含已取代的历史条目）。 */
    fun delete(entry: ProfileEntryEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { profileEntryDao.deleteById(entry.id) }
            _message.value = "已删除该条画像"
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
