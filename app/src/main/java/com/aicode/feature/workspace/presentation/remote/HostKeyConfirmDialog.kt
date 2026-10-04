package com.aicode.feature.workspace.presentation.remote

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicode.R

/**
 * SSH 主机密钥确认弹窗（首次连接或已保存指纹变化）。
 *
 * 「连接配置」页的测试连通性、聊天页「重试连接」两处入口共用这一个组件：指纹、标题与按钮文案
 * 必须一字不差。两处各写一套的后果不是样式不一致，而是同一台服务器在两个入口弹出不同的指纹说明，
 * 用户无法判断该信哪个。
 *
 * 确认与拒绝之后做什么由调用方决定：连接配置页确认后拿表单里的 host/port 重测连通性，
 * 聊天页确认后直接再走一次重连链。
 *
 * @param pendingHostKey 待确认详情（含指纹与是否为指纹变化），由调用方从连接层取出。
 * @param onConfirm 用户点了「信任并连接」；调用方需先落盘指纹再继续自己的流程。
 * @param onReject 用户点了「拒绝」或点弹窗外部关闭；指纹不落盘。
 */
@Composable
fun HostKeyConfirmDialog(
    pendingHostKey: PendingHostKeyConfirmation,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (pendingHostKey.changed) R.string.ssh_host_key_changed_title
                    else R.string.ssh_host_key_confirm_title
                )
            )
        },
        text = {
            Text(
                "${pendingHostKey.host}:${pendingHostKey.port}\n${pendingHostKey.keyType}\n" +
                    stringResource(R.string.ssh_host_key_fingerprint_value, pendingHostKey.fingerprint)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ssh_host_key_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.ssh_host_key_reject))
            }
        }
    )
}
