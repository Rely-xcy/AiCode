package com.aicode.feature.agent.presentation.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权审批通知：AI 在后台阻塞等待用户确认（工具授权 / askUserQuestion 提问 / 退出 PLAN 计划审批）时弹出。
 *
 * 多会话聚合为一条通知；仅在 App 处于后台且开关开启时展示，回到前台或全部解决后立即撤销。
 * 与 [AgentLiveNotificationManager] 的静默常驻通知不同，本通知需要弹窗+声音提醒用户处理。
 */
@Singleton
class AgentApprovalNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApprovalNotificationSettingsRepository
) {
    /** 一个等待用户审批的会话在通知里的展示信息。 */
    data class PendingApproval(
        val sessionId: String,
        val sessionTitle: String,
        val label: String
    )

    private companion object {
        const val CHANNEL_ID = "agent_approval"
        const val NOTIFICATION_ID = 1003
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val approvals = MutableStateFlow<List<PendingApproval>>(emptyList())

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 回到前台：用户正在看，撤掉通知。
                scope.launch { render(emptyList()) }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { render(approvals.value) }
            }
        })
        scope.launch {
            approvals.collect { render(it) }
        }
    }

    /** 推入当前等待审批的会话列表；空列表等价于撤销。 */
    fun update(pending: List<PendingApproval>) {
        approvals.value = pending
    }

    /** 立即撤销（开关关闭、全部解决等）。 */
    fun dismiss() {
        approvals.value = emptyList()
        scope.launch { render(emptyList()) }
    }

    private suspend fun render(pending: List<PendingApproval>) {
        val manager = NotificationManagerCompat.from(context)
        if (pending.isEmpty() || !settings.isEnabled() || inForeground()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (pending.size == 1) {
            context.getString(R.string.notification_approval_title)
        } else {
            context.getString(R.string.notification_approval_multi_title, pending.size)
        }
        val body = pending.joinToString("\n") { "${it.sessionTitle} · ${it.label}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
