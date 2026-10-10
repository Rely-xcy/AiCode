package com.aicode.feature.agent.domain.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** Shizuku 可用状态，供设置页展示与工具执行前判定。 */
enum class ShizukuState {
    /**
     * binder 未就绪：服务未启动或已停止，也可能设备上根本没有可用的管理器。
     *
     * 以包名判「装没装」必然误判（管理器包名不在官方契约内：Sui 是 Magisk 模块、没有独立管理器包，
     * Stellar 等兼容层用各自的包名），所以状态判定只看 binder；
     * 「装没装」不进状态机，而由 UI 文案兼顾（未就绪时的提示同时交代「打开管理器」与「没装就去下载」，
     * 点击后 [ShizukuManager.openShizukuApp] 找不到管理器就跳下载页）。
     */
    NOT_RUNNING,

    /** 服务运行中，但本应用尚未获得授权。 */
    PERMISSION_DENIED,

    /** 就绪，可执行命令。 */
    READY
}

/** 一次 Shizuku 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class ShizukuCommandResult(val output: String, val exitCode: Int)

/**
 * Shizuku 服务端的只读身份信息，供设置页展示「当前连的是谁」。
 *
 * [version] 是服务端 API 版本，服务端未应答时为 -1；[seLinuxContext] 拿不到时为 null。
 */
data class ShizukuPeerInfo(
    val uid: Int,
    val version: Int,
    val seLinuxContext: String?,
    val isAdb: Boolean,
    val isRoot: Boolean
)

/**
 * 绑定 UserService 失败的原因类别，供上层映射成可操作提示。
 *
 * 与 [ShizukuState] 的区别：[ShizukuState] 是绑定前从 binder 推导的「当前可用性」，
 * 而这里是「绑定尝试为什么失败」——后两者（UserService 起不来、连接断开）在绑定前无法预知，
 * 只能由绑定过程本身判定。
 */
enum class ShizukuBindFailure {
    /** binder 未就绪：服务未启动，或设备上没有可用的管理器。 */
    NOT_RUNNING,

    /** 服务运行中，但本应用尚未获得授权。 */
    PERMISSION_DENIED,

    /** 已授权，但 UserService 起不来（绑定超时）。 */
    USER_SERVICE_UNAVAILABLE,

    /** 绑定途中连接断开（binder 死亡或 UserService 掉线）。 */
    DISCONNECTED
}

/**
 * 绑定 UserService 失败。[failure] 是可供上层按类映射文案的原因类别。
 *
 * 继承 [IllegalStateException] 以兼容既有的 `catch (Exception)` 调用方。
 */
class ShizukuBindException(
    val failure: ShizukuBindFailure,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Shizuku 后端：以 adb shell（uid 2000）身份执行命令。
 *
 * 通过 UserService（[ShizukuShellService]）而非已弃用的 `Shizuku#newProcess` 执行命令：
 * App 绑定一个运行在 shell 进程的服务，调用其 AIDL 接口代执行，进程间只传命令与结果。
 *
 * 状态由 Shizuku 的 binder 存活/死亡与授权结果驱动，[state] 变化时设置页与工具据此响应。
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ShizukuManager"

        /** Shizuku 官方应用包名。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Stellar：兼容 Shizuku 的管理器，包名与官方不同，binder 协议一致。 */
        const val STELLAR_PACKAGE = "roro.stellar.manager"

        /** 已知管理器包名，按尝试顺序排列；都没命中时退回 provider authority 扫描。 */
        val KNOWN_MANAGER_PACKAGES = listOf(SHIZUKU_PACKAGE, STELLAR_PACKAGE)

        /** 管理器 provider authority 的命名后缀约定：官方用 `.shizuku`，Stellar 用 `.stellar`。 */
        const val SHIZUKU_PROVIDER_SUFFIX = ".shizuku"
        const val STELLAR_PROVIDER_SUFFIX = ".stellar"

        /** adb shell 身份对应的 Linux uid。 */
        const val UID_SHELL = 2000

        /** root 身份对应的 Linux uid。 */
        const val UID_ROOT = 0

        const val PERMISSION_REQUEST_CODE = 1001

        /** 服务身份 tag：不设时用类名，类名经 R8 混淆后不稳定，故显式固定。 */
        const val SERVICE_TAG = "shizuku_shell"

        /** 首次绑定 UserService 的等待上限（毫秒）。Shizuku 启动服务自身超时为 30 秒。 */
        const val BIND_TIMEOUT_MS = 30_000L

        /**
         * 超时解绑后重绑一次的等待上限（毫秒）。较首次更短：前面已经等满 30 秒，
         * 缩短是为了让两次尝试总等待不超过 45 秒（AI 侧工具调用会一直等到绑定结束）。
         */
        const val BIND_RETRY_TIMEOUT_MS = 15_000L

        /** 命令超时上限（毫秒），与 [com.aicode.feature.agent.domain.container.CommandEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val bindMutex = Mutex()

    @Volatile
    private var shellService: IShizukuShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        pendingBind?.completeExceptionally(
            ShizukuBindException(ShizukuBindFailure.DISCONNECTED, "Shizuku 服务已断开")
        )
        pendingBind = null
        refreshState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = IShizukuShellService.Stub.asInterface(service)
            shellService = bound
            pendingBind?.complete(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            pendingBind?.completeExceptionally(
                ShizukuBindException(ShizukuBindFailure.DISCONNECTED, "Shizuku UserService 已断开")
            )
            pendingBind = null
            refreshState()
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .processNameSuffix("shizuku_shell")
            .debuggable(isDebuggable)
            .version(appVersionCode)
    }

    /** 项目未开启 BuildConfig，版本号从 PackageManager 读取（升级后自动重建 UserService）。 */
    @Suppress("DEPRECATION")
    private val appVersionCode: Int by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private val isDebuggable: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    init {
        runCatching {
            // Sticky：binder 已就绪时立即回调，无需额外探测首帧状态。
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { FileLogger.w(TAG, "注册 Shizuku 监听失败: ${it.message}") }
        refreshState()
    }

    /** 重新计算并发布当前状态。 */
    fun refreshState() {
        _state.value = computeState()
    }

    /**
     * 按 binder 存活与授权结果推导状态。
     *
     * 不以管理器包名判「装没装」：官方契约里 binder 由服务端推来（客户端 provider 只收不拉），
     * 与管理器叫什么包名无关，而 Sui 与 Stellar 也都不使用官方包名。
     * 任一 binder 调用抛异常（服务恰好在探测途中断开）都按未运行处理，状态机不崩。
     */
    private fun computeState(): ShizukuState = runCatching {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            // pre-v11 无 UserService，等同不可用。
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            // READY 只由服务端按 uid 校验的授权结果决定，本地判断不参与。
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.PERMISSION_DENIED
        }
    }.getOrDefault(ShizukuState.NOT_RUNNING)

    /**
     * 读取当前服务端的只读身份信息（adb / root、API 版本、SELinux 上下文）。
     *
     * 纯查询：不触发授权、不改状态。[state] 不是 [ShizukuState.READY] 时直接返回 null，
     * 其余异常（binder 中途断开等）也一并降级为 null。
     */
    fun peerInfo(): ShizukuPeerInfo? {
        if (_state.value != ShizukuState.READY) return null
        return runCatching {
            val uid = Shizuku.getUid()
            ShizukuPeerInfo(
                uid = uid,
                version = Shizuku.getVersion(),
                seLinuxContext = Shizuku.getSELinuxContext(),
                isAdb = uid == UID_SHELL,
                isRoot = uid == UID_ROOT
            )
        }.onFailure { FileLogger.w(TAG, "读取 Shizuku 服务端信息失败: ${it.message}") }
            .getOrNull()
    }

    /** 申请 Shizuku 授权。需在主线程调用（Shizuku 内部要求）。 */
    fun requestPermission() {
        if (computeState() != ShizukuState.PERMISSION_DENIED) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { FileLogger.w(TAG, "申请 Shizuku 授权失败: ${it.message}") }
    }

    /** 打开管理器应用；一个都找不到时跳转官方下载页。 */
    fun openShizukuApp() {
        val launch = findManagerLaunchIntent()
        runCatching {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure { FileLogger.w(TAG, "打开 Shizuku 失败: ${it.message}") }
    }

    /**
     * 找一个可启动的管理器：先按已知包名（官方、Stellar），再按 provider authority 命名约定扫描。
     *
     * 扫描只认「包自身声明了 authority 等于 `<包名>.shizuku` 或 `<包名>.stellar`」这一条，
     * 且排除本应用自己（自身就声明 `<applicationId>.shizuku`）。authority 必须精确相等，
     * 不做前缀或包含匹配，否则抢注相似 authority 的应用能被当成管理器打开。
     */
    private fun findManagerLaunchIntent(): Intent? {
        for (pkg in KNOWN_MANAGER_PACKAGES) {
            launchIntentFor(pkg)?.let { return it }
        }
        val discovered = runCatching { findManagerByProviderAuthority() }
            .onFailure { FileLogger.w(TAG, "扫描 Shizuku 管理器包失败: ${it.message}") }
            .getOrNull()
        return discovered?.let { launchIntentFor(it) }
    }

    /** 取包名的启动入口；未安装或不可见时返回 null。 */
    private fun launchIntentFor(pkg: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun findManagerByProviderAuthority(): String? {
        val self = context.packageName
        for (info in context.packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS)) {
            val pkg = info.packageName
            if (pkg == self) continue
            val matched = info.providers.orEmpty().any { provider ->
                val authority = provider.authority ?: return@any false
                authority == pkg + SHIZUKU_PROVIDER_SUFFIX || authority == pkg + STELLAR_PROVIDER_SUFFIX
            }
            if (matched) return pkg
        }
        return null
    }

    /** 执行 shell 命令。未就绪或绑定失败时抛异常，由调用方转成工具错误。 */
    suspend fun runCommand(command: String, timeoutMs: Long): ShizukuCommandResult {
        val service = ensureBound()
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
        return withContext(Dispatchers.IO) {
            val bundle = service.exec(command, timeout)
            ShizukuCommandResult(
                output = bundle.getString(ShizukuShellService.KEY_OUTPUT).orEmpty(),
                exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_FAILURE)
            )
        }
    }

    /** 幂等绑定 UserService，返回可用的 AIDL 代理。 */
    private suspend fun ensureBound(): IShizukuShellService {
        shellService?.let { return it }
        return bindMutex.withLock {
            shellService?.let { return@withLock it }
            bindWithRetry()
        }
    }

    /**
     * 绑定 UserService，失败时解绑并重试一次。
     *
     * 最多两次尝试：首次 [BIND_TIMEOUT_MS]，超时后先 [Shizuku.unbindUserService] 解绑再以
     * [BIND_RETRY_TIMEOUT_MS] 重绑；两次都超时才抛
     * [ShizukuBindFailure.USER_SERVICE_UNAVAILABLE]。只重试一次是取舍：UserService 起不来
     * 多因 Shizuku 服务版本不符、服务进程被杀或 `.version(...)` 触发重装失败，多试无益，
     * 而 AI 侧工具调用会一直等到绑定结束，重试越多等待越久。
     *
     * 全程持 [bindMutex]，保证同一时刻最多一次绑定尝试（并发调用串行等待）。
     */
    private suspend fun bindWithRetry(): IShizukuShellService {
        requireReady()
        logBindSignal()

        awaitBind(BIND_TIMEOUT_MS)?.let { return it }
        // 迟到的 onServiceConnected 可能在超时判定后才填上 shellService，别把它重绑掉。
        shellService?.let { return it }

        FileLogger.w(TAG, "绑定 Shizuku UserService 超时，解绑后重试一次")
        try {
            withContext(Dispatchers.Main) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "解绑 Shizuku UserService 失败: ${e.message}")
        }

        // 解绑或等待期间服务可能已停 / 掉授权，重新校验，避免重试白等。
        requireReady()

        awaitBind(BIND_RETRY_TIMEOUT_MS)?.let { return it }
        shellService?.let { return it }

        throw ShizukuBindException(
            ShizukuBindFailure.USER_SERVICE_UNAVAILABLE,
            "Shizuku UserService 绑定超时（已重试一次）"
        )
    }

    /**
     * 发起一次绑定并等待 [timeoutMs]，返回代理；超时返回 null。
     *
     * 每次尝试都用全新的 [CompletableDeferred]，并在结束（成功/超时/异常）时把 [pendingBind]
     * 复位，避免上一次遗留的 deferred 被后续回调完成、污染状态。连接断开时 deferred 以
     * [ShizukuBindException]（[ShizukuBindFailure.DISCONNECTED]）完成，异常原样抛出、不重试。
     */
    private suspend fun awaitBind(timeoutMs: Long): IShizukuShellService? {
        val deferred = CompletableDeferred<IShizukuShellService>()
        pendingBind = deferred
        try {
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
        } catch (e: CancellationException) {
            clearPendingBind(deferred)
            throw e
        } catch (e: Exception) {
            clearPendingBind(deferred)
            throw ShizukuBindException(ShizukuBindFailure.DISCONNECTED, "绑定 Shizuku UserService 失败", e)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            // 成功时回调已清空 pendingBind，这里是空操作；超时时清理遗留 deferred。
            clearPendingBind(deferred)
        }
    }

    /** 只清空仍是本次尝试的 [pendingBind]，避免误清后续尝试设置的 deferred。 */
    private fun clearPendingBind(deferred: CompletableDeferred<IShizukuShellService>) {
        if (pendingBind === deferred) pendingBind = null
    }

    /** 校验当前状态，非 READY 时抛带原因类别的 [ShizukuBindException]。 */
    private fun requireReady() {
        val state = computeState()
        when (state) {
            ShizukuState.READY -> return
            ShizukuState.PERMISSION_DENIED -> throw ShizukuBindException(
                ShizukuBindFailure.PERMISSION_DENIED,
                "Shizuku 尚未获得授权（$state）"
            )
            ShizukuState.NOT_RUNNING -> throw ShizukuBindException(
                ShizukuBindFailure.NOT_RUNNING,
                "Shizuku 未运行（$state）"
            )
        }
    }

    /** 记录绑定前的服务端信号（uid / API 版本），供排查 UserService 起不来的原因。 */
    private fun logBindSignal() {
        val signal = peerInfo()?.let {
            "uid=${it.uid} version=${it.version} adb=${it.isAdb} root=${it.isRoot}"
        } ?: "不可用"
        FileLogger.i(TAG, "绑定 Shizuku UserService 前置信号：$signal")
    }
}
