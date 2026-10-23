package com.aicode.feature.backup.presentation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.ExportStats
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.WorkspaceSkip
import com.aicode.feature.backup.domain.WorkspaceSkipReason
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.BackupTargetType
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Download
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Server
import compose.icons.feathericons.Upload
import compose.icons.feathericons.UploadCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与还原整页：底部悬浮 tab 分「本地备份 / 网络备份」两页。
 *
 * 本地页 = 导出数据范围配置 + 导出/导入；网络页 = 备份目标列表（卡片）+ 上传/恢复，新增入口在右上角。
 * 与「连接与同步」「Git」同款：Scaffold + TopAppBar + HorizontalPager + FloatingTabBar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportOptions by viewModel.exportOptions.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val localScrollState = rememberScrollState()
    val networkScrollState = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf { localScrollState.isScrollInProgress || networkScrollState.isScrollInProgress }
    }

    var password by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingExportOptions by remember { mutableStateOf(BackupOptions()) }

    var editingTarget by remember { mutableStateOf<BackupTarget?>(null) }
    var showTargetSheet by remember { mutableStateOf(false) }
    var targetToDelete by remember { mutableStateOf<BackupTarget?>(null) }
    var uploadTarget by remember { mutableStateOf<BackupTarget?>(null) }
    var uploadPassword by remember { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<BackupTarget?>(null) }
    var restoreName by remember { mutableStateOf<String?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var networkWorkingTextRes by remember { mutableStateOf(R.string.backup_net_loading) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val pw = pendingExportPassword
            val opts = pendingExportOptions
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    viewModel.export(pw, opts, os)
                } else {
                    Toast.makeText(context, context.getString(R.string.backup_write_failed, uri.toString()), Toast.LENGTH_LONG).show()
                    viewModel.reset()
                }
            }
        } else {
            viewModel.reset()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pendingAction = null
            return@rememberLauncherForActivityResult
        }
        pendingImportUri = uri
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.settings_backup)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = {
                            editingTarget = null
                            showTargetSheet = true
                        }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.backup_net_add_target))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page == 0) {
                    LocalBackupTab(
                        scrollState = localScrollState,
                        exportOptions = exportOptions,
                        onUpdateOptions = viewModel::updateExportOptions,
                        working = state is BackupState.Working,
                        onExport = { pendingAction = PendingAction.ExportPassword },
                        onImport = {
                            pendingAction = PendingAction.Import
                            importLauncher.launch(arrayOf("application/octet-stream", "application/gzip", "*/*"))
                        }
                    )
                } else {
                    NetworkBackupTab(
                        scrollState = networkScrollState,
                        targets = targets,
                        working = state is BackupState.Working || networkState is NetworkBackupState.Working,
                        onEdit = {
                            editingTarget = it
                            showTargetSheet = true
                        },
                        onDelete = { targetToDelete = it },
                        onUpload = {
                            uploadTarget = it
                            uploadPassword = ""
                        },
                        onRestore = {
                            networkWorkingTextRes = R.string.backup_net_loading
                            viewModel.loadRemoteBackups(it)
                        }
                    )
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Download, stringResource(R.string.backup_tab_local)),
                    FloatingTabItem(FeatherIcons.UploadCloud, stringResource(R.string.backup_net_section))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // 导出：输口令（可留空）
    if (pendingAction == PendingAction.ExportPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_set_password),
            subtitle = stringResource(R.string.backup_password_hint),
            confirmText = stringResource(R.string.backup_export_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                pendingExportPassword = password
                pendingExportOptions = exportOptions
                password = ""
                pendingAction = null
                exportLauncher.launch("aicode-backup-${System.currentTimeMillis()}.tar.gz")
            },
            onDismiss = {
                password = ""
                pendingAction = null
            }
        )
    }

    // 导入口令弹窗（SAF 选完文件后弹出）
    if (pendingAction == PendingAction.Import && pendingImportUri != null) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_input),
            subtitle = stringResource(R.string.backup_password_optional_hint),
            confirmText = stringResource(R.string.backup_import_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                val pw = password
                val uri = pendingImportUri
                password = ""
                pendingAction = null
                pendingImportUri = null
                if (uri != null) viewModel.import(uri, pw)
            },
            onDismiss = {
                password = ""
                pendingAction = null
                pendingImportUri = null
            }
        )
    }

    // 目标编辑底部弹窗
    if (showTargetSheet) {
        BackupTargetSheet(
            initial = editingTarget,
            onDismiss = { showTargetSheet = false },
            onSave = { viewModel.saveTarget(it); showTargetSheet = false },
            onTest = { target, callback -> viewModel.testTarget(target, callback) }
        )
    }

    // 目标删除确认
    targetToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { targetToDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.backup_net_delete_target_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTarget(target.id)
                    targetToDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { targetToDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // 上传口令
    uploadTarget?.let { target ->
        PasswordDialog(
            title = stringResource(R.string.backup_set_password),
            subtitle = stringResource(R.string.backup_password_hint),
            confirmText = stringResource(R.string.backup_net_upload),
            password = uploadPassword,
            onPasswordChange = { uploadPassword = it },
            onConfirm = {
                val t = uploadTarget
                val pw = uploadPassword
                uploadTarget = null
                uploadPassword = ""
                if (t != null) {
                    networkWorkingTextRes = R.string.backup_net_uploading
                    viewModel.uploadToTarget(t, pw)
                }
            },
            onDismiss = { uploadTarget = null; uploadPassword = "" }
        )
    }

    // 云端恢复口令
    if (restoreTarget != null && restoreName != null) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_input),
            subtitle = stringResource(R.string.backup_password_optional_hint),
            confirmText = stringResource(R.string.backup_import_btn),
            password = restorePassword,
            onPasswordChange = { restorePassword = it },
            onConfirm = {
                val t = restoreTarget
                val n = restoreName
                val pw = restorePassword
                restoreTarget = null
                restoreName = null
                restorePassword = ""
                if (t != null && n != null) viewModel.restoreFromRemote(t, n, pw)
            },
            onDismiss = { restoreTarget = null; restoreName = null; restorePassword = "" }
        )
    }

    // 上传完成 → 与本地导出同一份摘要（含工作区文件数与被跳过的工作区）
    val uploadDone = networkState as? NetworkBackupState.UploadDone
    if (uploadDone != null) {
        ResultDialog(
            title = stringResource(R.string.backup_net_upload_done, uploadDone.targetName),
            message = buildExportSummary(context, uploadDone.stats),
            onDismiss = { viewModel.dismissNetworkState() }
        )
    }

    if (networkState is NetworkBackupState.Working) {
        NetworkProgressDialog(networkWorkingTextRes)
    }

    val remoteList = networkState as? NetworkBackupState.RemoteList
    if (remoteList != null) {
        RemoteBackupDialog(
            targetName = remoteList.targetName,
            items = remoteList.items,
            onRestore = { item ->
                val target = targets.firstOrNull { it.id == remoteList.targetId }
                if (target != null) {
                    restoreTarget = target
                    restoreName = item.name
                    restorePassword = ""
                } else {
                    viewModel.dismissNetworkState()
                }
            },
            onDelete = { item ->
                val target = targets.firstOrNull { it.id == remoteList.targetId }
                if (target != null) {
                    networkWorkingTextRes = R.string.backup_net_loading
                    viewModel.deleteRemoteBackup(target, item.name)
                } else {
                    viewModel.dismissNetworkState()
                }
            },
            onDismiss = { viewModel.dismissNetworkState() }
        )
    }

    val netError = networkState as? NetworkBackupState.Error
    if (netError != null) {
        ResultDialog(
            title = stringResource(R.string.backup_operation_failed),
            message = netError.message,
            onDismiss = { viewModel.dismissNetworkState() }
        )
    }

    if (state is BackupState.Working) {
        ProgressDialog()
    }

    // 导出完成 → 弹摘要对话框（汇总 + 跳过的工作区），用户确认后复位
    when (val current = state) {
        is BackupState.ExportDone -> ResultDialog(
            title = stringResource(R.string.backup_exported),
            message = buildExportSummary(context, current.stats),
            onDismiss = { viewModel.reset() }
        )
        is BackupState.Error -> ResultDialog(
            title = stringResource(R.string.backup_operation_failed),
            message = current.message,
            onDismiss = { viewModel.reset() }
        )
        is BackupState.ImportSuccess -> ResultDialog(
            // 有本机解不开的密钥时标题不能只说「导入完成」：用户需要知道要去重填。
            title = stringResource(
                if (current.stats.undecryptableSecrets > 0) R.string.backup_import_done_secrets_need_refill
                else R.string.backup_import_done
            ),
            message = buildImportSummary(context, current.stats),
            onDismiss = { viewModel.reset() }
        )
        is BackupState.WorkspaceSelection -> WorkspaceRestoreDialog(
            workspaces = current.workspaces,
            onConfirm = { selected -> viewModel.confirmImportSelection(selected) },
            onDismiss = { viewModel.cancelImportSelection() }
        )
        else -> {}
    }
}

private enum class PendingAction { ExportPassword, Import }

/** 本地备份页：导出数据范围 + 导出/导入操作。 */
@Composable
private fun LocalBackupTab(
    scrollState: ScrollState,
    exportOptions: BackupOptions,
    onUpdateOptions: (BackupOptions) -> Unit,
    working: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = 96.dp)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.backup_select_data))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.common_ai_providers),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.providers,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(providers = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_remote),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.remoteConnections,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(remoteConnections = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_chat_history),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.chatHistory,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(chatHistory = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_mcp),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.mcpServers,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(mcpServers = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_permissions),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.permissionRules,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(permissionRules = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_app_settings),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.appSettings,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(appSettings = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_workspace),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.workspaceFiles,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(workspaceFiles = it)) }
                    )
                }
            )
            WorkspaceBackupHint()
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_memory),
                subtitle = stringResource(R.string.backup_data_memory_hint),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.memoryFiles,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(memoryFiles = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_session_briefs),
                subtitle = stringResource(R.string.backup_data_session_briefs_hint),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.sessionBriefs,
                        onCheckedChange = { onUpdateOptions(exportOptions.copy(sessionBriefs = it)) }
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.backup_actions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.backup_export_title),
                subtitle = stringResource(R.string.backup_export_subtitle),
                enabled = !working,
                onClick = onExport
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Upload,
                title = stringResource(R.string.backup_import_title),
                subtitle = stringResource(R.string.backup_import_subtitle),
                enabled = !working,
                onClick = onImport
            )
        }
    }
}

/** 网络备份页：备份目标卡片列表（新增在右上角）。 */
@Composable
private fun NetworkBackupTab(
    scrollState: ScrollState,
    targets: List<BackupTarget>,
    working: Boolean,
    onEdit: (BackupTarget) -> Unit,
    onDelete: (BackupTarget) -> Unit,
    onUpload: (BackupTarget) -> Unit,
    onRestore: (BackupTarget) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = 96.dp)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.backup_net_targets))
        SettingsGroup {
            if (targets.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.backup_net_no_target),
                    enabled = false
                )
            } else {
                targets.forEachIndexed { index, target ->
                    if (index > 0) SettingsDivider()
                    BackupTargetCard(
                        target = target,
                        working = working,
                        onEdit = { onEdit(target) },
                        onDelete = { onDelete(target) },
                        onUpload = { onUpload(target) },
                        onRestore = { onRestore(target) }
                    )
                }
            }
        }
    }
}

/** 备份目标卡片：图标方块 + 名称（类型徽章）+ 地址副标题 + 编辑箭头，底部上传/恢复操作，左滑删除。 */
@Composable
private fun BackupTargetCard(
    target: BackupTarget,
    working: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onRestore: () -> Unit
) {
    SwipeToDeleteRow(onDelete = onDelete, onClick = onEdit) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TargetIconBox(target.type)
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        TargetTypeBadge(target.type)
                    }
                    Text(
                        text = targetSubtitle(target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = FeatherIcons.ChevronRight,
                        contentDescription = stringResource(R.string.common_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onUpload,
                    enabled = !working,
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.backup_net_upload_short),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !working,
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.backup_net_remote_restore),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetIconBox(type: BackupTargetType) {
    val icon = when (type) {
        BackupTargetType.WEBDAV -> FeatherIcons.Server
        BackupTargetType.S3 -> FeatherIcons.UploadCloud
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).align(Alignment.Center)
        )
    }
}

@Composable
private fun TargetTypeBadge(type: BackupTargetType) {
    val (text, color) = when (type) {
        BackupTargetType.WEBDAV -> stringResource(R.string.backup_net_type_webdav) to MaterialTheme.colorScheme.primary
        BackupTargetType.S3 -> stringResource(R.string.backup_net_type_s3) to MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun WorkspaceBackupHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.backup_workspace_git_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkspaceRestoreDialog(
    workspaces: List<WorkspaceBackupMeta>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var checked by remember { mutableStateOf(workspaces.associate { it.name to true }) }
    val anyChecked = checked.values.any { it }
    val allChecked = workspaces.isNotEmpty() && checked.values.all { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_workspaces_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_restore_workspaces_warning),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(Spacing.md))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = allChecked,
                        onCheckedChange = { v -> checked = workspaces.associate { it.name to v } }
                    )
                    Text(
                        text = stringResource(R.string.backup_restore_workspaces_select_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                workspaces.forEach { ws ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = checked[ws.name] ?: false,
                            onCheckedChange = { v -> checked = checked + (ws.name to v) }
                        )
                        Text(
                            text = ws.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_workspaces_files, ws.fileCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(checked.filterValues { it }.keys) },
                enabled = anyChecked
            ) {
                Text(stringResource(R.string.backup_restore_workspaces_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    subtitle: String,
    confirmText: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.backup_password_label),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun ProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_processing)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.backup_processing_data))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun NetworkProgressDialog(messageRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_processing)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(messageRes))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun targetSubtitle(target: BackupTarget): String = when (target.type) {
    BackupTargetType.WEBDAV -> "${stringResource(R.string.backup_net_type_webdav)} · ${target.endpoint}"
    BackupTargetType.S3 ->
        "${stringResource(R.string.backup_net_type_s3)} · ${target.endpoint.trimEnd('/')}/${target.bucket}"
}

@Composable
private fun ResultDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
    )
}

/**
 * 导出完成摘要：把「真的写进去了多少」与「哪些工作区被跳过、为什么」分开讲。
 * 导出本身总是产出能拿到的部分（不会因为远程未连接而整包失败），所以这份摘要就是用户唯一的知情渠道。
 */
private fun buildExportSummary(context: android.content.Context, stats: ExportStats): String = buildString {
    if (!stats.workspaceFilesRequested) {
        append(context.getString(R.string.backup_export_summary_no_workspaces))
    } else {
        append(
            context.getString(
                R.string.backup_export_stat_workspace_files,
                stats.workspaceFiles,
                stats.exportedWorkspaces
            )
        )
        if (stats.skippedWorkspaces.isNotEmpty()) {
            appendLine()
            append(
                context.getString(
                    R.string.backup_export_stat_skipped_workspaces,
                    stats.skippedWorkspaces.size,
                    formatSkippedWorkspaces(context, stats.skippedWorkspaces)
                )
            )
        }
        // 远程工作区一个文件都没导出时，把「怎么才能拿到」写清楚：连接只是前提，仍需要重新导出一次。
        if (stats.remoteWorkspaceMissing) {
            appendLine()
            appendLine()
            append(context.getString(R.string.backup_export_remote_workspace_missing))
        }
    }
    // 解不开的密钥是按密文原样进的包：不说清的话，用户在新设备上只会看到一串解不开的密文。
    // 这一段不能放进上面的工作区分支，不勾工作区文件时也同样要提。
    if (stats.undecryptableSecrets > 0) {
        appendLine()
        appendLine()
        append(context.getString(R.string.backup_export_stat_undecryptable_secrets, stats.undecryptableSecrets))
    }
}

/** 跳过的工作区列表：每项「名字（原因）」，超过 [MAX_SKIPPED_WORKSPACE_NAMES] 个则截断为「…等 M 个」。 */
private fun formatSkippedWorkspaces(context: android.content.Context, skipped: List<WorkspaceSkip>): String {
    val separator = context.getString(R.string.backup_export_skipped_name_separator)
    val shown = skipped.take(MAX_SKIPPED_WORKSPACE_NAMES).joinToString(separator) { skip ->
        context.getString(
            R.string.backup_export_skipped_workspace_item,
            skip.name,
            context.getString(skip.reason.labelRes())
        )
    }
    return if (skipped.size > MAX_SKIPPED_WORKSPACE_NAMES) {
        context.getString(R.string.backup_export_skipped_workspaces_more, shown, skipped.size)
    } else {
        shown
    }
}

/** 跳过原因的文案资源：摘要只用这一处映射，新增原因时漏映射会直接编译失败。 */
private fun WorkspaceSkipReason.labelRes(): Int = when (this) {
    WorkspaceSkipReason.REMOTE_NOT_CONNECTED -> R.string.backup_export_skip_remote_not_connected
    WorkspaceSkipReason.REMOTE_UNSUPPORTED -> R.string.backup_export_skip_remote_unsupported
    WorkspaceSkipReason.HOST_DIR_MISSING -> R.string.backup_export_skip_host_dir_missing
    WorkspaceSkipReason.EMPTY -> R.string.backup_export_skip_empty
    WorkspaceSkipReason.READ_FAILED -> R.string.backup_export_skip_read_failed
}

/** 摘要里最多逐个列出的跳过工作区数，其余折叠成「等 M 个」。 */
private const val MAX_SKIPPED_WORKSPACE_NAMES = 3

internal fun buildImportSummary(context: android.content.Context, stats: RestoreStats): String = buildString {
    appendLine(context.getString(R.string.backup_restored_data))
    append(buildRestoreStatsLines(context, stats))
    // 密钥行不管有没有问题都要出现：否则用户分不清「全部可用」与「压根没检查过」。
    // 放在成功摘要而不是 buildRestoreStatsLines 里，是为了不把「已全部恢复」带进部分失败的提示。
    if (stats.undecryptableSecrets > 0) {
        appendLine(context.getString(R.string.backup_stat_undecryptable_secrets, stats.undecryptableSecrets))
    } else {
        appendLine(context.getString(R.string.backup_stat_secrets_all_restored))
    }
    // 跳过的工作区文件不计入已恢复数，单独一行明示：否则「工作区文件 0 个」看起来就像全丢了一样。
    // 只放在成功摘要里，不进 buildRestoreStatsLines——那行说明的是「已写入」的数据，跳过项不属于该口径。
    if (stats.skippedWorkspaceFiles > 0) {
        appendLine(context.getString(R.string.backup_stat_skipped_workspace_files, stats.skippedWorkspaceFiles))
    }
    // 远程相关的提醒只在真跟远程有关系时出现：要么这次把模式切回了远程、要么备份里带了远程连接/挂载。
    // 本地模式且备份不带远程凭据的用户不该被无用的提示打扰。
    if (stats.remoteModeRestored || stats.remoteConnections > 0 || stats.remoteMounts > 0) {
        appendLine()
        appendLine(context.getString(R.string.backup_restore_remote_hint))
    }
    append(context.getString(R.string.backup_settings_covered))
}

/** 逐行列出还原统计；导入成功摘要与「部分写入」失败提示共用，避免两处口径不一。 */
internal fun buildRestoreStatsLines(context: android.content.Context, stats: RestoreStats): String = buildString {
    if (stats.providers > 0) appendLine(context.getString(R.string.backup_stat_providers, stats.providers))
    if (stats.remoteConnections > 0) appendLine(context.getString(R.string.backup_stat_remote_connections, stats.remoteConnections))
    if (stats.remoteMounts > 0) appendLine(context.getString(R.string.backup_stat_remote_mounts, stats.remoteMounts))
    if (stats.chatSessions > 0) appendLine(context.getString(R.string.backup_stat_chat_sessions, stats.chatSessions))
    if (stats.agentMessages > 0) appendLine(context.getString(R.string.backup_stat_chat_messages, stats.agentMessages))
    if (stats.todoItems > 0) appendLine(context.getString(R.string.backup_stat_todo_items, stats.todoItems))
    if (stats.sessionBriefs > 0) appendLine(context.getString(R.string.backup_stat_session_briefs, stats.sessionBriefs))
    if (stats.mcpServers > 0) appendLine(context.getString(R.string.backup_stat_mcp_servers, stats.mcpServers))
    if (stats.globalPermissionRules > 0) appendLine(context.getString(R.string.backup_stat_permission_rules, stats.globalPermissionRules))
    if (stats.workspaceFiles > 0) appendLine(context.getString(R.string.backup_stat_workspace_files, stats.workspaceFiles))
    if (stats.memoryFiles > 0) appendLine(context.getString(R.string.backup_stat_memory_files, stats.memoryFiles))
    if (stats.skillFiles > 0) appendLine(context.getString(R.string.backup_stat_skill_files, stats.skillFiles))
    if (stats.subagentFiles > 0) appendLine(context.getString(R.string.backup_stat_subagent_files, stats.subagentFiles))
    if (stats.panelScriptFiles > 0) appendLine(context.getString(R.string.backup_stat_panel_scripts, stats.panelScriptFiles))
    if (stats.skippedSecurityEntries > 0) {
        appendLine(context.getString(R.string.backup_stat_skipped_security, stats.skippedSecurityEntries))
    }
}
