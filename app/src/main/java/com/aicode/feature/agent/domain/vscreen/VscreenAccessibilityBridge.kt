package com.aicode.feature.agent.domain.vscreen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aicode.core.util.FileLogger

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
 * 无障碍树采集桥：把 [AccessibilityService] 与工具侧连接起来。
 *
 * 读屏的前提是用户手动开启本 App 的无障碍服务（系统设置），服务连上后把自身登记进这里；
 * 工具侧只在虚拟屏会话存活时查询，且只取**当前虚拟屏那个 displayId** 的窗口——
 * 不扫真机前台，也不落盘。
 *
 * API 30+ 才有 [AccessibilityWindowInfo.getDisplayId]；更低版本无法按屏过滤，
 * 此时按「取不到」处理（返回空），由工具提示改用截图。
 */
object VscreenAccessibilityBridge {

    @Volatile
    private var service: AccessibilityService? = null

    fun attach(service: AccessibilityService) {
        this.service = service
        FileLogger.i(TAG, "无障碍服务已连接，可提供节点树")
    }

    fun detach() {
        if (service != null) FileLogger.i(TAG, "无障碍服务已断开")
        service = null
    }

    /** 无障碍服务是否已开启并连接。 */
    val isReady: Boolean get() = service != null

    /** 采集指定 displayId 的窗口节点（最多 [limit] 个）。 */
    fun capture(displayId: Int, limit: Int): List<UiNode> {
        val svc = service ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val windows = runCatching { svc.windows }.getOrNull() ?: return emptyList()
        val out = ArrayList<UiNode>()
        for (window in windows) {
            if (window.displayId != displayId) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            walk(root, 0, out, limit)
        }
        return out
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
        sb.append(" ".repeat(node.depth.coerceAtMost(MAX_INDENT)))
        sb.append('[').append(index).append("] ").append(node.className.ifBlank { "View" })
        if (node.password) sb.append(" (密码框，内容已略)")
        node.text?.takeIf { it.isNotBlank() }?.let { sb.append(" text=\"").append(it).append('"') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(" desc=\"").append(it).append('"') }
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
