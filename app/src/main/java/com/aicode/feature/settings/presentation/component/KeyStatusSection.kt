package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.security.KeyRotationService
import com.aicode.core.security.KeyringStore
import com.aicode.feature.settings.presentation.KeyStatusViewModel
import kotlinx.coroutines.launch

/**
 * 密钥状态卡：显示设备密钥与自备密钥的状态，并提供手动更换入口。
 *
 * 自备密钥 90 天一轮换。自动轮换在启动时按到期策略跑，但用户也可以随时手动换一次——
 * 出了安全问题、或者想主动换把钥匙时用得上。
 *
 * 状态卡刻意做成独立 Composable 而不直接写进设置页：接入位置由宿主决定，
 * 这里只管内容与动作。
 */
@Composable
internal fun KeyStatusSection(
    modifier: Modifier = Modifier
) {
    val viewModel: KeyStatusViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    var rotating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KeyRotationService.Result?>(null) }
    // 换完钥匙后剩余天数变了，要重新读一次。
    var refreshKey by remember { mutableStateOf(0) }

    val entry = remember(refreshKey) { KeyringStore.activeEntry() }
    val remainingDays = remember(refreshKey) { KeyringStore.remainingDays() }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsGroupHeader(text = stringResource(R.string.key_status_section_title))

        SettingsGroup(modifier = Modifier.padding(top = Spacing.sm)) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.key_status_device_key),
                subtitle = stringResource(
                    if (entry != null) R.string.key_status_device_key_ok else R.string.key_status_device_key_missing
                )
            )
            SettingsDivider()

            SettingsRow(
                icon = null,
                title = stringResource(R.string.key_status_local_key),
                subtitle = when {
                    entry == null -> stringResource(R.string.key_status_local_key_missing)
                    remainingDays == null -> stringResource(R.string.key_status_local_key_unknown)
                    else -> stringResource(R.string.key_status_local_key_days, remainingDays)
                }
            )
            SettingsDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.key_status_rotate),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.key_status_rotate_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                TextButton(
                    enabled = !rotating,
                    onClick = {
                        rotating = true
                        scope.launch {
                            // 换密钥要读写全部凭据，放后台线程，别卡住界面。
                            result = withContextIo { viewModel.rotate() }
                            rotating = false
                            refreshKey++
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (rotating) R.string.key_status_rotating else R.string.key_status_rotate_action
                        )
                    )
                }
            }
        }

        result?.let { r ->
            val text = when {
                r.rotated && r.failure == null ->
                    stringResource(R.string.key_status_rotate_done, r.migratedItems)
                r.rotated -> stringResource(R.string.key_status_rotate_partial, r.migratedItems)
                r.failure != null -> stringResource(R.string.key_status_rotate_failed, r.failure)
                else -> stringResource(R.string.key_status_rotate_skipped)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.rotated && r.failure == null) {
                    MaterialTheme.semanticColors.subtleText
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.lg, end = Spacing.lg)
            )
        }
    }
}

/** 换密钥是磁盘与数据库操作，不能在主线程做。 */
private suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
