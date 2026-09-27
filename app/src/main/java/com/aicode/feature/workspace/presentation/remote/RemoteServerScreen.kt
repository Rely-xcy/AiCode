package com.aicode.feature.workspace.presentation.remote

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteMount
import com.aicode.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Server
import compose.icons.feathericons.Settings
import compose.icons.feathericons.UploadCloud
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteServerScreen(
    viewModel: RemoteServerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    var showAddConnectionDialog by remember { mutableStateOf(false) }
    var showAddMountDialog by remember { mutableStateOf(false) }
    var showHostKeysScreen by remember { mutableStateOf(false) }
    var showSyncSettingsSheet by remember { mutableStateOf(false) }

    // 列表滚动状态提升到页面层：滚动时底部 tab 栏淡出（同 Git 页面）。
    val connScrollState = rememberScrollState()
    val mountScrollState = rememberScrollState()
    val ftpScrollState = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf {
            connScrollState.isScrollInProgress ||
                mountScrollState.isScrollInProgress ||
                ftpScrollState.isScrollInProgress
        }
    }
    var connectionToEdit by remember { mutableStateOf<RemoteConnection?>(null) }
    var mountToEdit by remember { mutableStateOf<RemoteMount?>(null) }
    var pendingDeleteConnection by remember { mutableStateOf<RemoteConnection?>(null) }
    var pendingDeleteMount by remember { mutableStateOf<RemoteMount?>(null) }

    val syncUseGitIgnore by viewModel.syncUseGitIgnore.collectAsStateWithLifecycle()
    val maxSyncBatchSize by viewModel.maxSyncBatchSize.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.remote_workspace_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (pagerState.currentPage == 0) {
                        IconButton(onClick = { showHostKeysScreen = true }) {
                            Icon(FeatherIcons.Key, contentDescription = stringResource(R.string.ssh_host_key_settings_title))
                        }
                        IconButton(onClick = { showSyncSettingsSheet = true }) {
                            Icon(FeatherIcons.Settings, contentDescription = stringResource(R.string.sync_settings_title))
                        }
                    }
                    if (pagerState.currentPage == 0 || pagerState.currentPage == 1) {
                        IconButton(onClick = {
                            if (pagerState.currentPage == 0) {
                                connectionToEdit = null
                                showAddConnectionDialog = true
                            } else {
                                mountToEdit = null
                                showAddMountDialog = true
                            }
                        }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.common_add))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                0 -> {
                    if (uiState.connections.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.remote_no_connections),
                            desc = stringResource(R.string.remote_no_connections_desc),
                            onAdd = {
                                connectionToEdit = null
                                showAddConnectionDialog = true
                            }
                        )
                    } else {
                        ConnectionsList(
                            connections = uiState.connections,
                            onReorder = viewModel::moveConnection,
                            onReorderEnd = viewModel::persistConnectionOrder,
                            onEdit = {
                                connectionToEdit = it
                                showAddConnectionDialog = true
                            },
                            onDelete = { pendingDeleteConnection = it }
                        )
                    }
                }
                1 -> {
                    if (uiState.mounts.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.remote_no_workspaces),
                            desc = stringResource(R.string.remote_no_workspaces_desc),
                            onAdd = {
                                mountToEdit = null
                                showAddMountDialog = true
                            }
                        )
                    } else {
                        MountsList(
                            mounts = uiState.mounts,
                            failedMountIds = uiState.failedMountIds,
                            onReorder = viewModel::moveMount,
                            onReorderEnd = viewModel::persistMountOrder,
                            onEdit = {
                                mountToEdit = it
                                showAddMountDialog = true
                            },
                            onDelete = { pendingDeleteMount = it },
                            onUpload = { viewModel.forceUploadMount(it.id) },
                            onDownload = { viewModel.forceDownloadMount(it.id) },
                            onConnect = { viewModel.connectMount(it.id) },
                            onDisconnect = { viewModel.disconnectMount(it.id) }
                        )
                    }
                }
                2 -> WiFiFtpServerSection(viewModel, ftpScrollState)
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Server, stringResource(R.string.remote_tab_connections)),
                    FloatingTabItem(FeatherIcons.Folder, stringResource(R.string.remote_tab_mounts)),
                    FloatingTabItem(FeatherIcons.UploadCloud, stringResource(R.string.remote_tab_ftp))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }

    if (showHostKeysScreen) {
        HostKeysScreen(
            hostKeys = uiState.hostKeys,
            loginKeys = uiState.loginKeys,
            onAddLoginKey = { uri -> viewModel.addLoginKey(uri) },
            onRemoveHostKey = { host, port -> viewModel.removeHostKey(host, port) },
            onRemoveLoginKey = { id -> viewModel.removeLoginKey(id) },
            onNavigateBack = { showHostKeysScreen = false }
        )
        return
    }

    if (showSyncSettingsSheet) {
        SyncSettingsSheet(
            useGitIgnore = syncUseGitIgnore,
            maxSyncBatchSize = maxSyncBatchSize,
            onUseGitIgnoreChange = { viewModel.setSyncUseGitIgnore(it) },
            onMaxSyncBatchSizeChange = { viewModel.setMaxSyncBatchSize(it) },
            onDismiss = { showSyncSettingsSheet = false }
        )
    }

    pendingDeleteConnection?.let { conn ->
        AlertDialog(
            onDismissRequest = { pendingDeleteConnection = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.remote_delete_connection_confirm, conn.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConnection(conn.id)
                    pendingDeleteConnection = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteConnection = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDeleteMount?.let { mount ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMount = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.remote_delete_mount_confirm,
                        mount.connection?.name ?: mount.localMountPath
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMount(mount.id)
                    pendingDeleteMount = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMount = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showAddConnectionDialog) {
        AddRemoteConnectionDialog(
            initialConnection = connectionToEdit,
            loginKeys = uiState.loginKeys,
            pendingHostKey = uiState.pendingHostKey,
            onConfirmHostKey = { viewModel.confirmHostKey() },
            onRejectHostKey = { viewModel.rejectHostKey() },
            onDismiss = { showAddConnectionDialog = false },
            onAdd = { name, host, port, username, auth, protocol ->
                val editing = connectionToEdit
                if (editing != null) {
                    viewModel.updateConnection(editing.id, name, host, port, username, auth, protocol)
                } else {
                    viewModel.addConnection(name, host, port, username, auth, protocol)
                }
                showAddConnectionDialog = false
            },
            onTestConnection = { host, port, username, auth, protocol, onResult ->
                viewModel.testConnection(host, port, username, auth, protocol, onResult)
            }
        )
    }

    if (showAddMountDialog) {
        if (uiState.connections.isEmpty()) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                onDismissRequest = { showAddMountDialog = false },
                title = { Text(stringResource(R.string.remote_hint_title)) },
                text = { Text(stringResource(R.string.remote_add_channel_first)) },
                confirmButton = {
                    TextButton(onClick = {
                        showAddMountDialog = false
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }) {
                        Text(stringResource(R.string.remote_go_add))
                    }
                }
            )
        } else {
            AddRemoteMountDialog(
                initialMount = mountToEdit,
                connections = uiState.connections,
                workspaces = uiState.workspaces,
                mounts = uiState.mounts,
                onDismiss = { showAddMountDialog = false },
                onAdd = { connectionId, remotePath, localWorkspacePath, autoConnect ->
                    val editing = mountToEdit
                    if (editing != null) {
                        viewModel.updateMount(editing.id, connectionId, remotePath, localWorkspacePath, autoConnect)
                    } else {
                        viewModel.addMount(connectionId, remotePath, localWorkspacePath, autoConnect)
                    }
                    showAddMountDialog = false
                },
                onListDirectories = { connectionId, path, onResult ->
                    viewModel.listRemoteDirectories(connectionId, path, onResult)
                }
            )
        }
    }
}

/** 居中空态：标题 + 描述 + 添加按钮，样式与容器镜像空态一致。 */
@Composable
private fun EmptyState(
    title: String,
    desc: String,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg)
        )
        Button(onClick = onAdd) {
            Text(stringResource(R.string.common_add))
        }
    }
}

/** 连接列表：长按拖拽排序（与提供商列表一致的 reorderable 交互）。 */
@Composable
private fun ConnectionsList(
    connections: List<RemoteConnection>,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onReorderEnd: () -> Unit,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onReorder(from.index, to.index)
    }
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = 70.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items = connections, key = { _, c -> c.id }) { _, conn ->
            ReorderableItem(state = reorderableState, key = conn.id) { isDragging ->
                DraggableCard(
                    isDragging = isDragging,
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragStopped = {
                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            onReorderEnd()
                        }
                    )
                ) {
                    RemoteConnectionCard(
                        conn = conn,
                        onEdit = onEdit,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

/** 挂载（工作区）列表：同样支持长按拖拽排序。 */
@Composable
private fun MountsList(
    mounts: List<RemoteMount>,
    failedMountIds: Set<String>,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onReorderEnd: () -> Unit,
    onEdit: (RemoteMount) -> Unit,
    onDelete: (RemoteMount) -> Unit,
    onUpload: (RemoteMount) -> Unit,
    onDownload: (RemoteMount) -> Unit,
    onConnect: (RemoteMount) -> Unit,
    onDisconnect: (RemoteMount) -> Unit
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onReorder(from.index, to.index)
    }
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = 70.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items = mounts, key = { _, m -> m.id }) { _, mount ->
            ReorderableItem(state = reorderableState, key = mount.id) { isDragging ->
                DraggableCard(
                    isDragging = isDragging,
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragStopped = {
                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            onReorderEnd()
                        }
                    )
                ) {
                    RemoteMountCard(
                        mount = mount,
                        isFailed = mount.id in failedMountIds,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onUpload = onUpload,
                        onDownload = onDownload,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect
                    )
                }
            }
        }
    }
}

/** 拖拽中的卡片包装：缩放 + 阴影 + 层级提升 + 长按拖拽把手 + 触感反馈（对齐提供商列表）。 */
@Composable
private fun DraggableCard(
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 0.97f else 1f,
        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
        label = "remoteDragScale"
    )
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
        label = "remoteDragElevation"
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = elevation,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(dragHandleModifier)
    ) {
        content()
    }
}
