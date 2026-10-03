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

        /** 绑定 UserService 的等待上限（毫秒）。Shizuku 启动服务自身超时为 30 秒。 */
        const val BIND_TIMEOUT_MS = 30_000L

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
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku 服务已断开"))
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
            pendingBind?.completeExceptionally(IllegalStateException("Shizuku UserService 已断开"))
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
            if (computeState() != ShizukuState.READY) {
                throw IllegalStateException("Shizuku 未就绪（${_state.value}）")
            }
            val deferred = CompletableDeferred<IShizukuShellService>()
            pendingBind = deferred
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                ?: throw IllegalStateException("绑定 Shizuku 服务超时")
        }
    }
}
