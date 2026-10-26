package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏硬护栏：会话级授权 + 支付类黑名单 + 注入限速。
 *
 * 权限模型（与「禁用安全拦截」开关完全解耦）：
 * - [vscreen_start] 工具是**唯一**的 ASK 点，用户放行后本会话被 [authorize]；
 * - 屏存活期间，同一会话的 input / observe 免弹窗（AI 自主流转）；
 * - 屏销毁（或超时）后 [revoke]，下次重新确认。
 *
 * 与弹窗无关、始终生效的护栏：
 * - 支付/银行类包名**直接拒绝**（不弹窗、返回原因）；
 * - 注入串行 + 最小间隔，防止事件洪泛把目标 app 打挂造成误报。
 *
 * [temp] root 分支实验件。
 */
@Singleton
class VirtualScreenGuard @Inject constructor() {

    private val authorizedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val lastInjectionAtMs = ConcurrentHashMap<String, Long>()

    fun authorize(sessionId: String) {
        authorizedSessions.add(sessionId)
        FileLogger.i(TAG, "会话 $sessionId 已获虚拟屏授权")
    }

    fun revoke(sessionId: String) {
        if (authorizedSessions.remove(sessionId)) {
            FileLogger.i(TAG, "会话 $sessionId 的虚拟屏授权已收回")
        }
        lastInjectionAtMs.remove(sessionId)
    }

    fun isAuthorized(sessionId: String): Boolean = sessionId in authorizedSessions

    /**
     * 目标包名是否在黑名单里。命中返回拒绝原因，未命中返回 null。
     *
     * 只挡最敏感的支付/银行类（按包名关键字粗筛，宁可漏一两个也不误伤正常 app）；
     * 设计上不追求穷举——黑名单是「别把用户的支付环境卷进自动化测试」的兜底。
     */
    fun blockReason(packageName: String): String? {
        val lower = packageName.lowercase()
        val hit = BLOCKED_KEYWORDS.firstOrNull { lower.contains(it) } ?: return null
        return "目标包名「$packageName」命中黑名单关键字「$hit」：虚拟屏任务不操控支付/银行类应用。"
    }

    /** 注入限速：同一会话两次注入之间至少间隔 [MIN_INJECTION_INTERVAL_MS]，串行等待。 */
    suspend fun awaitInjectionSlot(sessionId: String) {
        val last = lastInjectionAtMs[sessionId] ?: 0L
        val wait = MIN_INJECTION_INTERVAL_MS - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        lastInjectionAtMs[sessionId] = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "VirtualScreenGuard"
        const val MIN_INJECTION_INTERVAL_MS = 300L

        /** 包名关键字黑名单（小写匹配）。 */
        val BLOCKED_KEYWORDS = listOf(
            "bank", "pay", "wallet", "alipay", "tenpay", "unionpay", "money", "securities"
        )
    }
}
