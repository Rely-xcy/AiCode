package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器页状态。 */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** 保存结果，一次性事件，经 [CodeEditorViewModel.saveEvents] 下发。 */
sealed interface SaveResult {
    data object Success : SaveResult
    data class Error(val detail: String?) : SaveResult

    /**
     * 文件在打开之后被别的程序/会话改过（mtime 变新），本次没有写盘。
     * 由 UI 二次确认后带 force = true 重来——只提示，不硬拦。
     */
    data object Conflict : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** 是否正在写盘（远程模式下经 SFTP，耗时较长，供 UI 转圈）。 */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    private var loadedPath: String? = null

    /** 打开时记录的文件修改时间（毫秒；0 表示取不到）。供保存前判断文件是否被外部改过。 */
    private var loadedModifiedAt: Long = 0L

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        // 换文件先清掉基准：否则会拿上一个文件的时间戳去判新文件的冲突
        loadedModifiedAt = 0L
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching EditorUiState.Error(null)
                }
                // 保存冲突检测的基准：取不到时间戳（0）时后面不做冲突判定，宁可漏报也不误报
                loadedModifiedAt = runCatching { fileAccess.lastModified(path) }.getOrDefault(0L)
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching EditorUiState.TooLarge(size)
                }
                val bytes = fileAccess.readBytes(path)
                if (looksBinary(bytes)) {
                    return@runCatching EditorUiState.Binary
                }
                // 语法包解析放在这里，确保 AndroidView factory 在主线程创建编辑器时 registry 已就绪。
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    // 预热 grammar：某种语法首次构造要编译大量正则，不在这里做就会压到主线程并拖后首次上色。
                    // 不将对象传给 UI：Language 绑编辑器生命周期，editor.release() 会连带销毁它，
                    // 跨重建复用已销毁实例会出问题——此处仅为把编译结果缓进 registry。
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                EditorUiState.Success(
                    content = bytes.toString(Charsets.UTF_8),
                    scopeName = scope
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "打开文件失败: $path", e)
                EditorUiState.Error(e.message)
            }
        }
    }

    /**
     * 把编辑器当前内容写回文件。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。
     *
     * [force] = false 时先比对 mtime：文件在打开之后被别的程序/会话改过就先发 [SaveResult.Conflict]
     * 并且**不写盘**，由 UI 提示用户二次确认（确认后带 force = true 重来）。只提示不硬拦：
     * SFTP v3 的 mtime 只有秒级精度、远端 FS 时间戳也未必可靠，硬拦会误报卡人。
     */
    fun save(content: String, force: Boolean = false) {
        val path = loadedPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                if (!force && hasExternalChange()) {
                    FileLogger.w(TAG, "保存前检测到文件已被外部修改: $path")
                    _saveEvents.send(SaveResult.Conflict)
                    return@launch
                }
                val result = runCatching { fileAccess.writeFile(path, content) }
                    .fold(
                        onSuccess = {
                            // 自己写完就刷新基准，否则下一次保存必然把自己的写入当成外部改动
                            loadedModifiedAt = runCatching { fileAccess.lastModified(path) }.getOrDefault(0L)
                            SaveResult.Success
                        },
                        onFailure = { e ->
                            FileLogger.w(TAG, "保存文件失败: $path", e)
                            SaveResult.Error(e.message)
                        }
                    )
                _saveEvents.send(result)
            } finally {
                _saving.value = false
            }
        }
    }

    /** 文件是否在打开之后被外部改动过（mtime 变新）。基准或当前值取不到（0）时不判定冲突。 */
    private fun hasExternalChange(): Boolean {
        val path = loadedPath ?: return false
        if (loadedModifiedAt <= 0L) return false
        val current = runCatching { fileAccess.lastModified(path) }.getOrDefault(0L)
        return current > loadedModifiedAt
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** 全量读入内存，超过该体积拒绝打开以避免 OOM 与长时间卡顿。 */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

        /** 二进制嗅探的采样字节数：与 git 一致，仅看开头一段。 */
        const val BINARY_SNIFF_BYTES = 8000

        /** 二进制判定：开头采样段内出现 NUL 字节即视为二进制（文本文件不含 NUL，误判率极低）。 */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
