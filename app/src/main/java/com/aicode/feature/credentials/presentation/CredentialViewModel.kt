package com.aicode.feature.credentials.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicode.feature.credentials.data.remote.AppInstall
import com.aicode.feature.credentials.data.remote.GitHubDeviceFlowClient
import com.aicode.feature.credentials.data.remote.GitHubTokenPollResult
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 快速登录（设备码流）界面状态。 */
sealed interface QuickLoginUiState {
    data object Idle : QuickLoginUiState
    data object RequestingCode : QuickLoginUiState
    data class Awaiting(val userCode: String, val verificationUri: String) : QuickLoginUiState
    data class Success(
        val username: String,
        /** App 确实未安装：引导去安装页。 */
        val needsInstall: Boolean,
        /** 安装情况查询失败（网络/权限受限）：既不报「去安装」，也不假装一切正常。 */
        val installUnknown: Boolean = false,
        /** 安装已授权但只有只读权限：推送会被拒，提前提醒改用 PAT 或调权限。 */
        val readOnly: Boolean = false,
        /** 本次安装已授权的仓库全名（用于让用户看到「到底认得哪些仓库」）。 */
        val repositories: List<String> = emptyList()
    ) : QuickLoginUiState
    data class Failed(val message: String) : QuickLoginUiState
}

/**
 * 凭据页 UI 编排：凭据 CRUD + 提交署名(user.name/email) 配置。
 *
 * 提交署名读写走 `git config`（[GitRepository.setUserIdentity] / [getUserName] / [getUserEmail]）：
 * **优先项目级**（当前工作区 ~/workspace/.git/config），无则退全局（持久挂载 /root/.aicode/.gitconfig）。
 * UI 与终端敲 `git config user.name` 读到的是同一份署名——优先项目级、无则退全局，无两套源头竞争。
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    private val gitHubDeviceFlow: GitHubDeviceFlowClient,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object {
        const val TAG = "CredentialViewModel"

        /** slow_down 后每次增加的轮询间隔（秒）。 */
        const val SLOW_DOWN_STEP_SECONDS = 5

        /** 快速登录写入凭据的固定主机。 */
        const val GITHUB_HOST = "github.com"

        /** 轮询阶段需立即终止的协议错误码；其余（含网络异常）按瞬时错误继续重试到设备码过期。 */
        val TERMINAL_POLL_ERRORS = setOf(
            "incorrect_client_credentials",
            "device_flow_disabled",
            "unsupported_grant_type",
            "incorrect_device_code"
        )

        /** 拿到令牌后拉取用户名的重试次数与间隔，避开瞬时断网。 */
        const val USER_FETCH_ATTEMPTS = 5
        const val USER_FETCH_RETRY_DELAY_MS = 3000L
    }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        /** 容器 git config 当前 user.name，作编辑框初值。 */
        val userName: String = "",
        /** 容器 git config 当前 user.email，作编辑框初值。 */
        val userEmail: String = "",
        /** 容器 `git config --global` 实际 user.name，回显「git 实际值」（与 userName 同源）。 */
        val globalUserName: String = "",
        /** 容器 git config 当前 remote.origin.url，作仓库地址编辑框初值。 */
        val repoUrl: String = "",
        /** 远程模式下「自动注入凭证到远程服务器」开关状态（按服务器独立记忆）。 */
        val autoInjectEnabled: Boolean = false,
        /** 远程模式下该服务器是否首次进入（未询问过），UI 据此弹确认框。 */
        val showAutoInjectPrompt: Boolean = false,
        /** 当前是否远程模式（决定开关是否显示）。 */
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    // 非反应式来源的状态：git config 读出的署名、仓库地址、注入开关、toast。credentials 走文件仓储 Flow。
    private val _extra = MutableStateFlow(Extra())
    private data class Extra(
        val userName: String = "",
        val userEmail: String = "",
        val globalUserName: String = "",
        val repoUrl: String = "",
        val autoInjectEnabled: Boolean = false,
        val showAutoInjectPrompt: Boolean = false,
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    val state: StateFlow<UiState> = combine(credentialRepository.getAll(), _extra) { creds, extra ->
        UiState(
            credentials = creds,
            userName = extra.userName,
            userEmail = extra.userEmail,
            globalUserName = extra.globalUserName,
            repoUrl = extra.repoUrl,
            autoInjectEnabled = extra.autoInjectEnabled,
            showAutoInjectPrompt = extra.showAutoInjectPrompt,
            isRemote = extra.isRemote,
            toast = extra.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _quickLogin = MutableStateFlow<QuickLoginUiState>(QuickLoginUiState.Idle)
    val quickLogin: StateFlow<QuickLoginUiState> = _quickLogin.asStateFlow()

    /** 设备码轮询协程；取消或重开时替换。 */
    private var pollJob: Job? = null

    init {
        refreshIdentity()
        refreshAutoInjectState()
        // 工作区落定 / 切换后补一次注入：开关已经是打开状态时，若上一次因「工作区未就绪」跳过了
        // 上传（见 applyInjection），这里补上。原先只有凭据增删会触发同步，用户只拨开关、之后
        // 不改凭据，远端就永远不会补上，而开关看着是开着的。
        viewModelScope.launch {
            workspaceRepository.current.collectLatest { workspace ->
                if (workspace?.path.isNullOrBlank()) return@collectLatest
                syncInjectionIfEnabled()
            }
        }
    }

    /** 从容器 git config 读取当前署名与仓库地址刷新 UI（编辑框初值 + 实际值回显）。可重入，进凭据页时调一次兜住终端改动。 */
    fun refreshIdentity() {
        viewModelScope.launch {
            runCatching {
                _extra.update {
                    it.copy(
                        userName = gitRepository.getUserName(),
                        userEmail = gitRepository.getUserEmail(),
                        globalUserName = gitRepository.getUserName(),
                        repoUrl = gitRepository.getRepoUrl()
                    )
                }
            }.onFailure { FileLogger.w(TAG, "读取 git 全局署名失败: ${it.message}") }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            try {
                credentialRepository.save(credential)
                syncInjectionIfEnabled() // 凭据变更后重新同步到已开启注入的远程服务器
                toast(context.getString(R.string.credential_toast_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存凭据失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
            syncInjectionIfEnabled() // 删凭据后从远程注入文件移除，否则远程仍能用旧凭据
            toast(context.getString(R.string.credential_toast_deleted))
        }
    }

    // ---------- 远程自动注入开关 ----------

    /** 当前远程服务器标识（host:port 哈希）；本地模式/未连接时为 null。 */
    private fun currentServerKey(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return injectSettings.serverKey(cfg.host, cfg.port)
    }

    /** 按当前服务器刷新开关状态与「是否首次询问」。进入凭据页/连接变化时调用。 */
    fun refreshAutoInjectState() {
        val key = currentServerKey()
        _extra.update {
            it.copy(
                isRemote = key != null,
                autoInjectEnabled = key?.let { k -> injectSettings.isAutoInjectEnabled(k) } ?: false,
                showAutoInjectPrompt = key?.let { k -> !injectSettings.hasAsked(k) } ?: false
            )
        }
    }

    /** 用户切换开关：持久化 + 立即注入/撤销。 */
    fun setAutoInject(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.setAutoInject(key, enabled)
            if (!applyInjection(key, enabled)) reportInjectionDeferred()
            refreshAutoInjectState()
        }
    }

    /** 首次询问弹窗选择后调用：标记已询问 + 应用选择。 */
    fun dismissAutoInjectPrompt(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.markAsked(key)
            injectSettings.setAutoInject(key, enabled)
            if (!applyInjection(key, enabled)) reportInjectionDeferred()
            refreshAutoInjectState()
        }
    }

    /**
     * 开启时上传远程注入配置（凭据 + includeIf 限定的 gitconfig），关闭时撤销。
     * 返回 false 表示「开关要开、但工作区未就绪」——本次没上传，由调用方提示（工作区就绪后会自己补）。
     */
    private suspend fun applyInjection(key: String, enabled: Boolean): Boolean {
        if (!enabled) {
            remoteSshConnection.removeGitCredentialConfig()
            return true
        }
        val creds = credentialRepository.getAll().first()
        // 工作区未落定时不退回 remoteWorkspacePath：那是所有工作区的父目录，includeIf 会指向公共父级。
        // 宁可本次不上传（开关已持久化，工作区落定后由 init 里的收集器补上），也不写错作用域。
        val workspaceRoot = workspaceRepository.currentPathOrNull()?.takeIf { it.isNotBlank() }
        if (workspaceRoot == null) {
            FileLogger.w(TAG, "工作区未就绪，跳过 git 凭据注入配置上传")
            return false
        }
        remoteSshConnection.uploadGitCredentialConfig(creds, workspaceRoot)
        return true
    }

    private fun reportInjectionDeferred() {
        toast(context.getString(R.string.credential_toast_inject_deferred))
    }

    /** 凭据增删后若当前服务器已开启注入，重新同步到远程。 */
    private suspend fun syncInjectionIfEnabled() {
        val key = currentServerKey() ?: return
        if (injectSettings.isAutoInjectEnabled(key)) applyInjection(key, true)
    }

    /** 保存提交署名与仓库地址：跑 `git config` 写入对应 key 真源 + 刷新回显。UI 与命令行同一文件。 */
    fun saveUserIdentity(name: String, email: String, repoUrl: String) {
        viewModelScope.launch {
            try {
                gitRepository.setUserIdentity(name, email)
                gitRepository.setRepoUrl(repoUrl)
                refreshIdentity()
                toast(context.getString(R.string.credential_toast_identity_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存署名失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun consumeToast() = _extra.update { it.copy(toast = null) }

    private fun toast(msg: String) = _extra.update { it.copy(toast = msg) }

    // ---------- 快速登录（GitHub 设备码流） ----------

    /** 发起 GitHub 快速登录：申请设备码后进入等待授权态并开始轮询。 */
    fun startGitHubLogin() {
        pollJob?.cancel()
        _quickLogin.value = QuickLoginUiState.RequestingCode
        pollJob = viewModelScope.launch {
            val device = try {
                gitHubDeviceFlow.requestDeviceCode()
            } catch (e: Exception) {
                FileLogger.e(TAG, "申请 GitHub 设备码失败", e)
                _quickLogin.value = QuickLoginUiState.Failed(mapDeviceCodeError(e.message))
                return@launch
            }
            _quickLogin.value = QuickLoginUiState.Awaiting(device.userCode, device.verificationUri)

            var interval = device.intervalSeconds.coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                // 用户去浏览器授权时 App 可能被切到后台、瞬时断网：异常一律视为瞬时不可用，
                // 继续轮询直到设备码过期，不因此终止流程。
                val result = runCatching { gitHubDeviceFlow.pollAccessToken(device.deviceCode) }.getOrNull()
                when (result) {
                    null -> Unit
                    is GitHubTokenPollResult.Success -> {
                        onGitHubToken(result.accessToken)
                        return@launch
                    }
                    GitHubTokenPollResult.Pending -> Unit
                    GitHubTokenPollResult.SlowDown -> interval += SLOW_DOWN_STEP_SECONDS
                    GitHubTokenPollResult.Expired -> {
                        _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_expired))
                        return@launch
                    }
                    GitHubTokenPollResult.Denied -> {
                        _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_denied))
                        return@launch
                    }
                    is GitHubTokenPollResult.Error -> {
                        // 仅明确的协议错误才终止；未知错误（含服务端 5xx）按瞬时错误继续轮询。
                        if (result.code in TERMINAL_POLL_ERRORS) {
                            _quickLogin.value = QuickLoginUiState.Failed(result.code)
                            return@launch
                        }
                    }
                }
            }
            _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_expired))
        }
    }

    /** 拿到 access token 后拉取用户名并写入 github.com 凭据。 */
    private suspend fun onGitHubToken(accessToken: String) {
        val login = fetchLoginWithRetry(accessToken)
        if (login == null) {
            FileLogger.e(TAG, "GitHub 登录后拉取用户名失败")
            _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_unknown_error))
            return
        }
        try {
            credentialRepository.save(
                GitCredential(id = GITHUB_HOST, host = GITHUB_HOST, username = login, token = accessToken)
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "GitHub 登录后写入凭据失败", e)
            _quickLogin.value = QuickLoginUiState.Failed(e.message ?: context.getString(R.string.credential_device_unknown_error))
            return
        }
        // 仅授权未安装时令牌无仓库访问权：按三态处理，不再把「查询失败」当「已安装」。
        val install = runCatching { gitHubDeviceFlow.queryAppInstall(accessToken) }
            .getOrDefault(AppInstall.Unknown)
        runCatching { syncInjectionIfEnabled() }
        _quickLogin.value = when (install) {
            is AppInstall.Installed -> QuickLoginUiState.Success(
                username = login,
                needsInstall = false,
                readOnly = !install.contentsWrite,
                repositories = install.repositories
            )
            AppInstall.NotInstalled -> QuickLoginUiState.Success(username = login, needsInstall = true)
            AppInstall.Unknown -> QuickLoginUiState.Success(
                username = login,
                needsInstall = false,
                installUnknown = true
            )
        }
    }

    /** 拉取用户名：网络抖动时重试若干次，避免刚拿到令牌就因瞬时断网判失败。 */
    private suspend fun fetchLoginWithRetry(accessToken: String): String? {
        repeat(USER_FETCH_ATTEMPTS) { attempt ->
            runCatching { gitHubDeviceFlow.fetchUserLogin(accessToken) }.getOrNull()?.let { return it }
            if (attempt < USER_FETCH_ATTEMPTS - 1) delay(USER_FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    /** 取消快速登录：停止轮询并复位状态。 */
    fun cancelQuickLogin() {
        pollJob?.cancel()
        pollJob = null
        _quickLogin.value = QuickLoginUiState.Idle
    }

    /** 消费成功态：收起设备码弹层并复位（凭据列表由仓储 Flow 自动刷新）。 */
    fun consumeQuickLoginResult() {
        if (_quickLogin.value is QuickLoginUiState.Success) _quickLogin.value = QuickLoginUiState.Idle
    }

    /** 把设备码申请阶段返回的错误码映射为用户可读文案。 */
    private fun mapDeviceCodeError(code: String?): String = when (code) {
        "device_flow_disabled" -> context.getString(R.string.credential_device_flow_disabled)
        "incorrect_client_credentials" -> context.getString(R.string.credential_device_bad_client)
        else -> code ?: context.getString(R.string.credential_device_unknown_error)
    }
}
