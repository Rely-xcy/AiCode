package com.aicode.feature.terminal.domain

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    /**
     * 标签 id → 它的 SSH shell backend。用于在会话结束时判断是「正常结束」还是「断线结束」：
     * [SshShellBackend.closedByDisconnect] 只能从 backend 上读到。
     */
    private val backends = mutableMapOf<String, SshShellBackend>()

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /**
     * 远程终端不可用的原因；null 表示可用。
     *
     * 分两种说清楚（模式不对 / 连接断了）而不是笼统的「非远程模式或 SSH 未连接」：
     * 前者要去设置里改执行模式，后者该等重连或去连接配置页测试连通性。
     * 第三种「远程已连但工作区没落定」由后面的 [WorkspaceRepository.notReadyException] 单独报。
     */
    private fun remoteUnavailableReason(): String? = when {
        modeHolder.currentMode() != ExecutionMode.REMOTE_SSH ->
            context.getString(R.string.terminal_unavailable_not_remote_mode)
        !connection.isConnected() ->
            context.getString(R.string.terminal_unavailable_disconnected)
        else -> null
    }

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        remoteUnavailableReason()?.let { throw IllegalStateException(it) }
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        remoteUnavailableReason()?.let { throw IllegalStateException(it) }
        val id = openShellTab(command, isBackground = true, notify = notify, title = title, sourceSessionId = sourceSessionId)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?
    ): String {
        val id = nextId()
        // 先等工作区落定（带上限）再开 shell：没落定就没有可靠的 cd 目标，
        // 宁可直接报错，也不要留下一个停在远端 home 的标签页（窗口期不往错目录跑命令）。
        val wsPath = workspaceRepository.awaitCurrentPathOrNull()
            ?: throw workspaceRepository.notReadyException()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = withContext(Dispatchers.IO) {
            connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
        }
        val backend = SshShellBackend(shell) { connection.isConnected() }
        backends[id] = backend
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // shell 登录后默认在 home，先 cd 到当前工作区，与命令执行链路（RemoteSshEngine.buildCdCommand）保持一致：
        // 优先 ~/workspace 符号链接，失败回退到真实工作区路径。
        termSession.write("cd ~/workspace 2>/dev/null || cd '${wsPath.trimEnd('/')}' 2>/dev/null\n")
        if (command != null) {
            val init = command + (if (notify) "" else "; exec /bin/sh")
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        backends.remove(id)
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()
        FileLogger.i(TAG, "关闭远程终端标签 $id")
        return true
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            tab.session.write(bytes, 0, bytes.size)
        }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                // 断线导致的结束：远程 shell 没有有意义的退出码，只看 Termux 的
                // 「[Process completed]」分不出是用户敲了 exit 还是网断了，得看 backend 的标记。
                val dropped = backends[target.id]?.closedByDisconnect == true
                target.runState = RunState.Finished(0)
                if (dropped) {
                    target.droppedByDisconnect = true
                    FileLogger.w(TAG, "远程 shell 因连接断开而结束: ${target.id}")
                }
                bumpRevision()
                if (target.notifyOnExit) {
                    _tabFinishedEvents.tryEmit(
                        TabFinishedEvent(
                            target.id, target.title, target.command, 0, target.sourceSessionId,
                            tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                        )
                    )
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
