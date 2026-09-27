package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.CustomSystemPrompt
import com.aicode.feature.agent.domain.prompt.CustomSystemPromptStore
import com.aicode.feature.agent.domain.prompt.SystemPromptMode
import com.aicode.feature.settings.data.remote.PromptDocsRepository
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
 * 主页面只开放一项：**系统提示词**（`prompts.custom/system.md`）。它以内置提示词之上的
 * 注入层生效，内置的 9 个静态片段一个都不给改——这样用户拿到控制权的同时，工具与技能
 * 的可发现性不会被牺牲。片段级自定义（覆盖/新增单个片段）收在「高级」二级页。
 *
 * 首次进入必须先读使用说明（[docsRead] 为 null 表示还在读标记，UI 应显示加载态）。
 */
@HiltViewModel
class PromptRulesViewModel @Inject constructor(
    private val service: PromptRulesService,
    private val systemPromptStore: CustomSystemPromptStore,
    private val promptDocsRepository: PromptDocsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository
) : ViewModel() {

    /**
     * 系统提示词编辑态。[baseline] 是打开时磁盘上的内容，按值比较判脏——
     * 只改 mode 或只改正文都算改动，什么都没动就不该弹放弃确认。
     */
    data class SystemPromptState(
        val mode: SystemPromptMode,
        val body: String,
        val baseline: CustomSystemPrompt
    ) {
        val current: CustomSystemPrompt get() = CustomSystemPrompt(mode, body)

        val isDirty: Boolean get() = current != baseline

        val isOverLimit: Boolean get() = body.trim().length > CustomSystemPromptStore.BODY_CHAR_LIMIT

        val hasCustom: Boolean get() = baseline.mode != SystemPromptMode.OFF || baseline.body.isNotBlank()
    }

    /** 编辑态：content 与 original 不同即为未保存改动。 */
    data class EditorState(
        val fragment: PromptFragmentInfo,
        val content: String,
        val original: String,
        val isNew: Boolean
    ) {
        val isDirty: Boolean get() = content != original
    }

    /** 使用说明的加载状态：正文来自 App 内置的官方文档（`assets/docs/guide/custom-prompts.md`）。 */
    sealed interface DocsUiState {
        data object Loading : DocsUiState
        data class Ready(val text: String) : DocsUiState
        data object Unavailable : DocsUiState
    }

    private val _docs = MutableStateFlow<DocsUiState>(DocsUiState.Loading)
    val docs: StateFlow<DocsUiState> = _docs.asStateFlow()

    private val _fragments = MutableStateFlow<List<PromptFragmentInfo>>(emptyList())
    val fragments: StateFlow<List<PromptFragmentInfo>> = _fragments.asStateFlow()

    private val _docsRead = MutableStateFlow<Boolean?>(null)
    val docsRead: StateFlow<Boolean?> = _docsRead.asStateFlow()

    private val _systemPrompt = MutableStateFlow<SystemPromptState?>(null)
    val systemPrompt: StateFlow<SystemPromptState?> = _systemPrompt.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
        loadDocs()
        loadSystemPrompt()
        viewModelScope.launch {
            _docsRead.value = generalSettingsRepository.promptRulesDocsReadFlow.first()
        }
    }

    /** 加载使用说明（内置资源，离线可用）。 */
    fun loadDocs() {
        viewModelScope.launch {
            if (_docs.value !is DocsUiState.Ready) _docs.value = DocsUiState.Loading
            _docs.value = promptDocsRepository.load().fold(
                onSuccess = { DocsUiState.Ready(it) },
                onFailure = { DocsUiState.Unavailable }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _fragments.value = withContext(Dispatchers.IO) { service.fragments() }
        }
    }

    fun loadSystemPrompt() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { systemPromptStore.load() }
            _systemPrompt.value = SystemPromptState(loaded.mode, loaded.body, loaded)
        }
    }

    fun setSystemPromptMode(mode: SystemPromptMode) {
        _systemPrompt.value = _systemPrompt.value?.copy(mode = mode)
    }

    fun updateSystemPromptBody(body: String) {
        _systemPrompt.value = _systemPrompt.value?.copy(body = body)
    }

    fun saveSystemPrompt() {
        val state = _systemPrompt.value ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { systemPromptStore.save(state.current) }
            result.fold(
                onSuccess = {
                    _message.value = "已保存，下一轮对话生效"
                    loadSystemPrompt()
                },
                onFailure = { _message.value = it.message ?: "保存失败" }
            )
        }
    }

    /**
     * 导入外部文件内容。超限直接拒绝并报错，**不截断**——截断会悄悄改坏用户看不见的提示词。
     */
    fun importSystemPromptBody(text: String) {
        if (systemPromptStore.isOverLimit(text)) {
            _message.value = "文件超过 ${CustomSystemPromptStore.BODY_CHAR_LIMIT} 字符上限，未导入"
            return
        }
        _systemPrompt.value = _systemPrompt.value?.copy(body = text)
    }

    fun restoreSystemPrompt() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { systemPromptStore.clear() }
            result.fold(
                onSuccess = {
                    _message.value = "已恢复默认系统提示词"
                    loadSystemPrompt()
                },
                onFailure = { _message.value = it.message ?: "恢复失败" }
            )
        }
    }

    fun confirmDocsRead() {
        viewModelScope.launch {
            generalSettingsRepository.setPromptRulesDocsRead(true)
            _docsRead.value = true
        }
    }

    /** 打开片段：以「已有自定义内容，否则内置默认」为草稿与基线。 */
    fun open(fragment: PromptFragmentInfo) {
        viewModelScope.launch {
            val custom = withContext(Dispatchers.IO) { service.customText(fragment.number) }
            val builtin = withContext(Dispatchers.IO) { service.builtinText(fragment.fileName) }.orEmpty()
            _editor.value = EditorState(
                fragment = fragment,
                content = custom ?: builtin,
                // 基线必须与初值一致：未自定义过的内置片段初值是内置正文，若基线取空串，
                // 一打开就已经是「脏」的，会导致没改任何东西也弹放弃确认。
                original = custom ?: builtin,
                isNew = false
            )
        }
    }

    /** 新建片段：数字自动取 01~99 里第一个空闲的位置，用户只需填名称与正文。 */
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
                hasCustom = false
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
                _message.value = "已保存 $fileName"
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

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }
}
