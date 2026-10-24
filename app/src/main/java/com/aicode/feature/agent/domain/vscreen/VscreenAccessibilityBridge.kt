package com.aicode.feature.agent.domain.vscreen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.aicode.core.util.FileLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 无障碍节点快照：与 Android 类型解耦的纯数据，便于格式化与单测。
 */
data class UiNode(
    val depth: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: String,
    val clickable: Boolean,
    val focusable: Boolean,
    val editable: Boolean,
    val password: Boolean
)

/**
 * 无障碍桥：节点树采集 + 按 display 截图，把 [AccessibilityService] 与工具侧连接起来。
 *
 * 读屏的前提是用户手动开启本 App 的无障碍服务（系统设置），服务连上后把自身登记进这里；
 * 工具侧只在虚拟屏会话存活时查询，且只取**当前虚拟屏那个 displayId** 的窗口——
 * 不扫真机前台，也不落盘。
 *
 * 按屏取窗口与按屏截图都是 API 30 起的能力，更低版本没有等价入口，按「取不到」处理，
 * 由调用方回退到别的通道。
 */
object VscreenAccessibilityBridge {

    @Volatile
    private var service: AccessibilityService? = null

    fun attach(service: AccessibilityService) {
        this.service = service
        FileLogger.i(TAG, "无障碍服务已连接，可提供节点树与按屏截图")
    }

    fun detach() {
        if (service != null) FileLogger.i(TAG, "无障碍服务已断开")
        service = null
    }

    /** 无障碍服务是否已开启并连接。 */
    val isReady: Boolean get() = service != null

    /**
     * 采集指定 displayId 的窗口节点（最多 [limit] 个）。
     *
     * 必须用 `getWindowsOnAllDisplays()`：`getWindows()` 只返回**默认屏**的窗口，
     * 拿它按 displayId 过滤虚拟屏永远是空表。前者返回的 SparseArray 的 key 就是 displayId。
     */
    fun capture(displayId: Int, limit: Int): List<UiNode> {
        val svc = service ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val windows = runCatching { svc.windowsOnAllDisplays?.get(displayId) }.getOrNull() ?: return emptyList()
        val out = ArrayList<UiNode>()
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            walk(root, 0, out, limit)
        }
        return out
    }

    /**
     * 截取指定 display 的合成结果（API 30+）。
     *
     * 为什么优先走它：拿到的是系统合成后的画面（含系统 UI 与输入法层），不需要 MediaProjection
     * 授权、不需要前台服务，也不受本进程取帧队列的新鲜度影响。
     *
     * 前置条件：无障碍服务配置里必须有 `android:canTakeScreenshot="true"`，否则平台**同步抛
     * SecurityException**（不会走 onFailure 回调）。服务没开、配置没声明、ROM 不支持时返回
     * 带原因的失败，由调用方回退到屏自带的取帧通道。
     *
     * 不能在主线程调用：这里要阻塞等回调，回调投递可能依赖同一套消息循环，主线程被占住会互相等死。
     */
    fun takeScreenshot(displayId: Int, timeoutMs: Long): Result<Bitmap> {
        val svc = service ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(
                IllegalStateException("无障碍按屏截图需要 API 30+（当前 ${Build.VERSION.SDK_INT}）")
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.failure(IllegalStateException("无障碍截图不能在主线程等待回调"))
        }

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var lastReason = "无障碍截图失败"
        for (attempt in 0 until SCREENSHOT_MAX_ATTEMPTS) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            val latch = CountDownLatch(1)
            val bitmapRef = AtomicReference<Bitmap?>(null)
            val errorRef = AtomicInteger(NO_ERROR_CODE)
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    bitmapRef.set(hardwareToBitmap(screenshot))
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    errorRef.set(errorCode)
                    latch.countDown()
                }
            }
            try {
                svc.takeScreenshot(displayId, DIRECT_EXECUTOR, callback)
            } catch (e: Throwable) {
                // 服务配置缺 canTakeScreenshot 时走这里（SecurityException），是配置问题不是偶发失败。
                return Result.failure(
                    IllegalStateException(
                        "无障碍截图不可用（${e.javaClass.simpleName}: ${e.message}）；$CAN_TAKE_SCREENSHOT_HINT",
                        e
                    )
                )
            }
            if (!latch.await(remainingMs, TimeUnit.MILLISECONDS)) {
                return Result.failure(IllegalStateException("等待无障碍截图回调超时（${remainingMs}ms）"))
            }
            bitmapRef.get()?.let { return Result.success(it) }

            val errorCode = errorRef.get()
            if (errorCode == NO_ERROR_CODE) {
                return Result.failure(IllegalStateException("无障碍截图回调成功但取不到位图（缓冲转换失败）"))
            }
            lastReason = describeScreenshotFailure(errorCode)
            if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                return Result.failure(IllegalStateException(lastReason))
            }
            // 配额错误：间隔由系统定（Android 11 是 1000ms，12+ 是 333ms，判定是「小于等于」），
            // 所以退避后重试，不写死一个间隔数字。
            val backoff = SCREENSHOT_RETRY_BACKOFF_MS.getOrElse(attempt) { SCREENSHOT_RETRY_BACKOFF_MS.last() }
            if (System.nanoTime() + backoff * 1_000_000L >= deadline) break
            SystemClock.sleep(backoff)
        }
        return Result.failure(IllegalStateException("$lastReason（已重试 $SCREENSHOT_MAX_ATTEMPTS 次）"))
    }

    /**
     * 截图缓冲 → 软件 Bitmap。
     *
     * 先 wrap 成硬件 Bitmap 再拷一份软件位图：压缩 PNG 只认软件位图。缓冲区在拷贝后就可以关，
     * 但顺序必须是「拷贝 → 关缓冲」，不要反过来。
     */
    private fun hardwareToBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
            if (hardware == null) {
                null
            } else {
                try {
                    hardware.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardware.recycle()
                }
            }
        } catch (e: Throwable) {
            FileLogger.w(TAG, "无障碍截图转 Bitmap 失败：${e.message}", e)
            null
        } finally {
            runCatching { buffer.close() }
        }
    }

    private fun describeScreenshotFailure(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "无障碍截图请求过于频繁（系统配额）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "无障碍服务没有截图能力（可能已被系统撤销）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "该 display 不允许截图（需要本应用创建的 public 虚拟屏）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "窗口中含 secure 内容，系统拒绝截图"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "系统内部错误"
        else -> "无障碍截图失败（错误码 $code）"
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<UiNode>, limit: Int) {
        if (node == null || out.size >= limit) return
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val password = runCatching { node.isPassword }.getOrDefault(false)
        out += UiNode(
            depth = depth,
            className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
            // 密码框的内容一律不采集：读屏是给 AI 看结构，不该把用户在虚拟屏里输入的凭据带走。
            text = if (password) null else node.text?.toString()?.take(MAX_TEXT_CHARS),
            contentDescription = if (password) null else node.contentDescription?.toString()?.take(MAX_TEXT_CHARS),
            viewId = node.viewIdResourceName,
            bounds = "${rect.left},${rect.top}-${rect.right},${rect.bottom}",
            clickable = runCatching { node.isClickable }.getOrDefault(false),
            focusable = runCatching { node.isFocusable }.getOrDefault(false),
            editable = runCatching { node.isEditable }.getOrDefault(false),
            password = password
        )
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until childCount) {
            walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1, out, limit)
        }
    }

    private const val TAG = "VscreenAccessibilityBridge"
    private const val MAX_TEXT_CHARS = 80

    /** 失败回调里错误码一定从 1 起；0 表示「没拿到错误码」的哨兵。 */
    private const val NO_ERROR_CODE = 0

    private const val SCREENSHOT_MAX_ATTEMPTS = 3

    /** 命中配额错误后的退避：给 Android 11 的 1000ms 与 12+ 的 333ms 都留出余量。 */
    private val SCREENSHOT_RETRY_BACKOFF_MS = longArrayOf(400L, 900L)

    /** 回调只做赋值与放行锁存，直接在投递线程上跑即可，不需要额外线程池。 */
    private val DIRECT_EXECUTOR = Executor { it.run() }

    private const val CAN_TAKE_SCREENSHOT_HINT =
        "无障碍服务配置需要 android:canTakeScreenshot=\"true\"（API 30+）"
}

/**
 * 节点快照 → 紧凑文本。
 *
 * 一行一个节点：缩进表示层级、`bounds` 直接给坐标（AI 拿它算 tap 坐标），
 * 只标出可点/可编辑这类「能用得上」的属性，避免把整棵树的原始字段铺进上下文。
 */
internal fun formatUiNodes(nodes: List<UiNode>, limit: Int): String {
    if (nodes.isEmpty()) return "（未取到任何节点）"
    val sb = StringBuilder()
    nodes.take(limit).forEachIndexed { index, node ->
        // 密码框的文本/描述一律不输出：桥里已置空，但格式化层再兜一道，
        // 免得日后有人直接构造 UiNode 时把凭据漏出去。
        val text = if (node.password) null else node.text
        val desc = if (node.password) null else node.contentDescription
        sb.append(" ".repeat(node.depth.coerceAtMost(MAX_INDENT)))
        sb.append('[').append(index).append("] ").append(node.className.ifBlank { "View" })
        if (node.password) sb.append(" (密码框，内容已略)")
        text?.takeIf { it.isNotBlank() }?.let { sb.append(" text=\"").append(it).append('"') }
        desc?.takeIf { it.isNotBlank() }?.let { sb.append(" desc=\"").append(it).append('"') }
        node.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { sb.append(" id=").append(it) }
        sb.append(" bounds=").append(node.bounds)
        if (node.clickable) sb.append(" clickable")
        if (node.editable) sb.append(" editable")
        sb.append('\n')
    }
    if (nodes.size >= limit) {
        sb.append("…（已达 $limit 节点上限；更细的界面用 mode=screenshot 看图）")
    }
    return sb.toString().trimEnd()
}

private const val MAX_INDENT = 6
