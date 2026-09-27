package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.service.PromptFragmentInfo
import com.aicode.feature.settings.domain.service.PromptRulesService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 自定义提示词页的状态与动作。
 *
 * 首次进入必须先读使用说明（[docsRead] 为 null 表示还在读标记，UI 应显示加载态），
 * 读完才允许编辑；关键片段（身份、安全）只读。
 */
@HiltViewModel
class PromptRulesViewModel @Inject constructor(
    private val service: PromptRulesService,
    private val generalSettingsRepository: GeneralSettingsRepository
) : ViewModel() {

    /** 编辑态：content 与 original 不同即为未保存改动。 */
    data class EditorState(
        val fragment: PromptFragmentInfo,
        val content: String,
        val original: String,
        val isNew: Boolean
    ) {
        val isDirty: Boolean get() = content != original
    }

    private val _fragments = MutableStateFlow<List<PromptFragmentInfo>>(emptyList())
    val fragments: StateFlow<List<PromptFragmentInfo>> = _fragments.asStateFlow()

    private val _docsRead = MutableStateFlow<Boolean?>(null)
    val docsRead: StateFlow<Boolean?> = _docsRead.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            _docsRead.value = generalSettingsRepository.promptRulesDocsReadFlow.first()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _fragments.value = withContext(Dispatchers.IO) { service.fragments() }
        }
    }

    fun confirmDocsRead() {
        viewModelScope.launch {
            generalSettingsRepository.setPromptRulesDocsRead(true)
            _docsRead.value = true
        }
    }

    /** 打开片段：受保护的只读查看；其余以「已有自定义内容，否则内置默认」为草稿。 */
    fun open(fragment: PromptFragmentInfo) {
        viewModelScope.launch {
            val custom = withContext(Dispatchers.IO) { service.customText(fragment.number) }
            val builtin = withContext(Dispatchers.IO) { service.builtinText(fragment.fileName) }.orEmpty()
            _editor.value = EditorState(
                fragment = fragment,
                content = custom ?: builtin,
                original = custom.orEmpty(),
                isNew = false
            )
        }
    }

    /** 新建片段：数字自动取 01~99 里第一个空闲且非关键的位置，用户只需填名称与正文。 */
    fun openNew() {
        val used = _fragments.value.map { it.number }.toSet()
        val free = (1..99).firstOrNull { it !in used } ?: run {
            _message.value = "没有可用的编号了（01~99 已用满）"
            return
        }
        _editor.value = EditorState(
            fragment = PromptFragmentInfo(
                number = free,
                fileName = "",
                title = "",
                subtitle = "自定义新增片段",
                isBuiltin = false,
                hasCustom = false,
                isProtected = false
            ),
            content = "",
            original = "",
            isNew = true
        )
    }

    fun updateContent(content: String) {
        _editor.value = _editor.value?.copy(content = content)
    }

    /** 保存当前草稿；[newName] 仅新建片段时用于生成文件名。 */
    fun save(newName: String = "") {
        val state = _editor.value ?: return
        val fileName = if (state.isNew) {
            val name = newName.ifBlank {
                _message.value = "请填写片段名称"
                return
            }
            service.newFileName(state.fragment.number, name)
        } else {
            state.fragment.fileName
        }
        val result = service.save(state.fragment.number, fileName, state.content)
        result.fold(
            onSuccess = {
                _message.value = "已保存 ${fileName}"
                _editor.value = null
                refresh()
            },
            onFailure = { _message.value = it.message ?: "保存失败" }
        )
    }

    /** 删除自定义文件（内置片段即恢复默认）。 */
    fun deleteCustom() {
        val state = _editor.value ?: return
        val deleted = service.deleteCustom(state.fragment.number)
        _message.value = if (deleted) "已删除自定义内容，恢复内置默认" else "没有可删除的自定义内容"
        if (deleted) {
            _editor.value = null
            refresh()
        }
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
