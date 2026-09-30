package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/** 记忆编辑器目标：[memory] 为 null 表示新建一条；null 目标（整个值为 null）表示编辑器未打开。 */
internal data class MemoryEditorTarget(val memory: Memory? = null)

/**
 * 记忆新建/编辑页（全屏）：自带顶栏（与 [SubAgentEditorScreen] 一致，不能嵌进设置页的 Scaffold）。
 *
 * 保存动作在顶栏右上角，表单按「基本信息 / 内容」分两张卡片。
 *
 * @param memory 编辑目标；null 表示新建一条。
 * @param onSave 保存回调，参数已 trim（正文保留原样，Markdown 里的首尾空行有意义）。
 * @param onNavigateBack 返回上一页；有未保存修改时由本页先弹确认，确认后才回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryEditorScreen(
    memory: Memory?,
    onSave: (name: String, description: String, content: String, scope: MemoryScope) -> Unit,
    onNavigateBack: () -> Unit
) {
    val isNew = memory == null
    val initialName = memory?.name.orEmpty()
    val initialDescription = memory?.description.orEmpty()
    val initialContent = memory?.content.orEmpty()
    val initialScope = memory?.scope ?: MemoryScope.GLOBAL

    // 换编辑目标（改完 A 再改 B）时整页会重建，但保险起见仍按 memory 取值重置表单。
    var name by remember(memory) { mutableStateOf(initialName) }
    var description by remember(memory) { mutableStateOf(initialDescription) }
    var content by remember(memory) { mutableStateOf(initialContent) }
    // 作用域决定记忆文件落在哪个目录，改已有条目的作用域等于搬家，只允许新建时选（与子代理编辑页同一套规则）。
    var scope by remember(memory) { mutableStateOf(initialScope) }

    var showDiscardDialog by remember { mutableStateOf(false) }

    val isDirty = name.trim() != initialName ||
        description != initialDescription ||
        content != initialContent ||
        scope != initialScope

    // 顶栏返回箭头与系统返回键共用这一份判定，不再各写一套（与设置页返回目标那处同一个理由）。
    val requestBack: () -> Unit = {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler { requestBack() }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (isNew) R.string.memory_add else R.string.common_edit))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 名称是记忆的唯一标识（文件名），空名保存会写出一个没有名字的条目
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = { onSave(name.trim(), description.trim(), content, scope) }
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.memory_editor_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        readOnly = !isNew,
                        label = stringResource(R.string.common_name),
                        placeholder = stringResource(R.string.memory_field_name_placeholder),
                        singleLine = true
                    )
                    if (!isNew) {
                        Text(
                            text = stringResource(R.string.memory_field_name_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.memory_field_description),
                        placeholder = stringResource(R.string.memory_field_description_placeholder),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            text = stringResource(R.string.memory_field_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 分段控件没有禁用态：编辑时置灰并吞掉点击，再补一行说明，
                        // 否则用户点了没反应，会以为控件坏了。
                        SegmentedTabs(
                            selected = if (scope == MemoryScope.GLOBAL) 0 else 1,
                            labels = listOf(
                                stringResource(R.string.memory_scope_global),
                                stringResource(R.string.memory_scope_project)
                            ),
                            onSelect = { index ->
                                if (isNew) {
                                    scope = if (index == 0) MemoryScope.GLOBAL else MemoryScope.PROJECT
                                }
                            },
                            modifier = Modifier.alpha(if (isNew) 1f else 0.5f)
                        )
                        if (!isNew) {
                            Text(
                                text = stringResource(R.string.memory_editor_scope_locked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.subtleText
                            )
                        }
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.memory_detail_content))
            SettingsGroup {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = stringResource(R.string.memory_detail_content),
                    singleLine = false,
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.memory_editor_discard_title)) },
            text = { Text(stringResource(R.string.memory_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.memory_editor_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
