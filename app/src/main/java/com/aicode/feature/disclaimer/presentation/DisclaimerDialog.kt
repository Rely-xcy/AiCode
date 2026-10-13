package com.aicode.feature.disclaimer.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R

/** 免责声明弹窗的两种用途：首次启动必须同意，或从关于页只读查看。 */
enum class DisclaimerMode { Accept, View }

/**
 * 免责声明弹窗。[Accept] 模式下不可点外部关闭，必须点「我已阅读并同意」或「不同意」；
 * [View] 模式可随时关闭。
 */
@Composable
fun DisclaimerDialog(
    mode: DisclaimerMode,
    onAccept: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onDecline: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = { if (mode == DisclaimerMode.View) onDismiss() },
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = {
            Text(
                text = stringResource(R.string.disclaimer_body),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = if (mode == DisclaimerMode.Accept) onAccept else onDismiss) {
                Text(
                    stringResource(
                        if (mode == DisclaimerMode.Accept) R.string.disclaimer_accept
                        else R.string.common_close
                    )
                )
            }
        },
        dismissButton = if (mode == DisclaimerMode.Accept) {
            {
                TextButton(onClick = onDecline) {
                    Text(stringResource(R.string.disclaimer_decline))
                }
            }
        } else {
            null
        }
    )
}
