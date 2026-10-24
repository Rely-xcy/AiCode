package com.aicode.feature.agent.domain.vscreen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 虚拟屏读屏用的无障碍服务：只负责把自身登记进 [VscreenAccessibilityBridge]，
 * 不做任何事件监听与响应（不拦截、不注入、不改别人界面）。
 *
 * 为什么需要它：`uiautomator dump` 不支持按 displayId 取树，路由窗口也读不到控件；
 * 而 `getWindowsOnAllDisplays()`（按 displayId 取窗口）与 `takeScreenshot(displayId, …)`
 * （按屏截图）都是无障碍服务才有的能力。采集与截图都由工具侧在用时触发，
 * 服务自身不主动收集、不落盘。
 */
class VscreenAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        VscreenAccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        VscreenAccessibilityBridge.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        VscreenAccessibilityBridge.detach()
        super.onDestroy()
    }
}
