package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.data.local.entity.ProfileEntryEntity
import com.aicode.feature.agent.data.local.entity.ProfileStatus
import com.aicode.feature.settings.presentation.ProfileViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 用户画像页：按分节列出 AI 从历史对话里沉淀的结论，可逐条删除，也可展开回看被取代的旧结论。
 *
 * 只做「看 + 删」：画像由 ProfileModule 自动沉淀，人工新增没有意义，而删除是必需的出口
 * （记错了却删不掉，画像只会越跑越偏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSettingsScreen(
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val activeCount by viewModel.activeCount.collectAsStateWithLifecycle()
    val showHistory by viewModel.showHistory.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ProfileEntryEntity?>(null) }
    var detailEntry by remember { mutableStateOf<ProfileEntryEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 顶栏交给 SettingsScreen 外壳（与其它设置分区一致，不再自画一套）；
    // 删除结果的 snackbar 需要自己的 host，所以用 Box 浮在底部。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.profile_hint, activeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.profile_show_history),
                    subtitle = stringResource(R.string.profile_show_history_desc),
                    trailing = {
                        AppSwitch(checked = showHistory, onCheckedChange = { viewModel.setShowHistory(it) })
                    }
                )
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.profile_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            groups.forEach { group ->
                SettingsGroupHeader(text = group.section.title)
                SettingsGroup {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        // 行里只给简略信息，点开才看详细（从底部弹出，与 App 其它页一致）；左滑删除。
                        SwipeToDeleteRow(onDelete = { pendingDelete = entry }) {
                            SettingsRow(
                                title = entry.value,
                                subtitle = buildString {
                                    if (entry.status != ProfileStatus.ACTIVE) {
                                        append(stringResource(R.string.profile_superseded_tag))
                                        append(" · ")
                                    }
                                    if (entry.evidence.isNotBlank()) {
                                        append(entry.evidence)
                                    }
                                },
                                onClick = { detailEntry = entry }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 行里只放简略信息，点开才看详细——从底部弹出，与 App 其它页一致。
    detailEntry?.let { entry ->
        AdaptiveModalBottomSheet(onDismissRequest = { detailEntry = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SettingsGroupHeader(text = entry.value)
                SettingsGroup {
                    SettingsRow(
                        title = entry.evidence.ifBlank { entry.value },
                        subtitle = if (entry.status == ProfileStatus.ACTIVE) {
                            null
                        } else {
                            stringResource(R.string.profile_superseded_tag)
                        }
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body, entry.value)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(entry)
                }) { Text(stringResource(R.string.profile_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
