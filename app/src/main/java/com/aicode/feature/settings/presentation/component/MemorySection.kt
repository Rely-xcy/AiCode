package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.rememberSheetFlingFix
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Trash2

/**
 * 记忆页：长期记忆自动沉淀开关 + 当前生效的记忆列表（点击看详情、长按弹编辑/删除、左滑删除）。
 *
 * 按作用域分两栏（全局 / 项目），每条再带一个作用域徽章——项目记忆只在该工作区生效，
 * 和全局记忆混成一份清单会让人分不清哪条换项目就没了。
 */
@Composable
internal fun MemorySection(
    memories: List<Memory>,
    autoDistillEnabled: Boolean,
    onToggleAutoDistill: (Boolean) -> Unit,
    curationIntervalHours: Int,
    onSelectCurationInterval: (Int) -> Unit,
    onOpenDetail: (Memory) -> Unit,
    onEdit: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    // 长按哪条就为哪条弹操作菜单；null 表示菜单未打开
    var actionMemory by remember { mutableStateOf<Memory?>(null) }
    // 治理周期选择弹层
    var showCurationIntervalSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.memory_auto_distill),
                subtitle = stringResource(R.string.memory_auto_distill_desc),
                trailing = {
                    AppSwitch(
                        checked = autoDistillEnabled,
                        onCheckedChange = onToggleAutoDistill
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.memory_curation_interval),
                // 自动沉淀关了就没什么可治理的：整行置灰、行尾显示「关闭」。
                // 否则开关明明是关的，周期却还写着「1 天」，看着像还在按周期跑。
                // enabled=false 会真正摘掉 clickable，弹层也打不开。
                enabled = autoDistillEnabled,
                onClick = { showCurationIntervalSheet = true },
                trailing = {
                    Text(
                        text = if (autoDistillEnabled) curationIntervalLabel(curationIntervalHours)
                        else stringResource(R.string.memory_curation_interval_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        if (memories.isEmpty()) {
            EmptyState()
        } else {
            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }
            if (globalMemories.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.memory_group_global))
                MemoryGroup(
                    memories = globalMemories,
                    onOpenDetail = onOpenDetail,
                    onLongClick = { actionMemory = it },
                    onDelete = onDelete
                )
            }
            if (projectMemories.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.memory_group_project))
                MemoryGroup(
                    memories = projectMemories,
                    onOpenDetail = onOpenDetail,
                    onLongClick = { actionMemory = it },
                    onDelete = onDelete
                )
            }
        }
    }

    actionMemory?.let { memory ->
        MemoryActionsSheet(
            memory = memory,
            onEdit = {
                actionMemory = null
                onEdit(memory)
            },
            onDelete = {
                actionMemory = null
                onDelete(memory)
            },
            onDismiss = { actionMemory = null }
        )
    }

    if (showCurationIntervalSheet) {
        CurationIntervalSheet(
            selectedHours = curationIntervalHours,
            onSelect = {
                onSelectCurationInterval(it)
                showCurationIntervalSheet = false
            },
            onDismiss = { showCurationIntervalSheet = false }
        )
    }
}

/** 一栏记忆（全局或项目）：同一个分组里逐行渲染，行间加分隔线。 */
@Composable
private fun MemoryGroup(
    memories: List<Memory>,
    onOpenDetail: (Memory) -> Unit,
    onLongClick: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    SettingsGroup {
        memories.forEachIndexed { index, memory ->
            // 必须带 key：SwipeToDeleteRow 的滑开位移是行内 remember 的位置状态，
            // 不带 key 时 Compose 按位置匹配，删掉一条后剩下的行会继承上一条的滑开状态，
            // 用户以为在删 A、实际删掉的是 B
            key(memory.name) {
                if (index > 0) SettingsDivider()
                MemoryRow(
                    memory = memory,
                    onOpenDetail = { onOpenDetail(memory) },
                    onLongClick = { onLongClick(memory) },
                    onDelete = { onDelete(memory) }
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.FileText,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.memory_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.memory_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 单条记忆行：图标 + 名称/描述，点击看详情，长按弹编辑/删除，左滑删除。 */
@Composable
private fun MemoryRow(
    memory: Memory,
    onOpenDetail: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    SwipeToDeleteRow(onDelete = onDelete, onClick = onOpenDetail, onLongClick = onLongClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.lg, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = memory.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    MemoryScopePill(scope = memory.scope)
                }
                Text(
                    text = memory.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 作用域徽章：全局 / 项目。
 *
 * 配色与 MCP 页的 pill 保持一致（项目级用主色，全局用中性色），复用同一个 [McpPill]。
 */
@Composable
private fun MemoryScopePill(scope: MemoryScope) {
    val isProject = scope == MemoryScope.PROJECT
    McpPill(
        text = stringResource(
            if (isProject) R.string.memory_group_project else R.string.memory_group_global
        ),
        textColor = if (isProject) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        backgroundColor = if (isProject) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        }
    )
}

/**
 * 记忆详情：名称、来源、描述与完整正文。
 *
 * 注入到系统提示词的只有「名称 + 描述」，正文平时看不到，详情页才展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryDetailSheet(
    memory: Memory,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = memory.name)
            SettingsGroup {
                SettingsRow(
                    icon = null,
                    title = memory.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    subtitle = stringResource(
                        if (memory.kind == MemoryKind.PROFILE) R.string.memory_source_auto
                        else R.string.memory_source_manual
                    )
                )
            }
            SettingsGroupHeader(text = stringResource(R.string.memory_detail_content))
            SettingsGroup {
                Text(
                    text = memory.content.ifBlank { stringResource(R.string.memory_detail_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Spacing.lg)
                )
            }
        }
    }
}

/**
 * 长按记忆弹出的操作菜单：编辑 / 删除。
 *
 * 删除沿用列表左滑的同一条回调（是否二次确认由上层决定），编辑进编辑器弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryActionsSheet(
    memory: Memory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = memory.name)
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Edit2,
                    title = stringResource(R.string.common_edit),
                    onClick = onEdit
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Trash2,
                    title = stringResource(R.string.common_delete),
                    onClick = onDelete
                )
            }
        }
    }
}

/** 治理周期可选值（小时）：0 = 关闭。与设置页展示的六项一一对应。 */
private val CURATION_INTERVAL_OPTIONS = listOf(0, 6, 12, 24, 72, 168)

/** 周期展示文案：六个预设值各自一条；其它值（手改过 DataStore）按「每 N 小时」显示。 */
@Composable
private fun curationIntervalLabel(hours: Int): String = when (hours) {
    0 -> stringResource(R.string.memory_curation_interval_off)
    6 -> stringResource(R.string.memory_curation_interval_6h)
    12 -> stringResource(R.string.memory_curation_interval_12h)
    24 -> stringResource(R.string.memory_curation_interval_1d)
    72 -> stringResource(R.string.memory_curation_interval_3d)
    168 -> stringResource(R.string.memory_curation_interval_7d)
    else -> stringResource(R.string.memory_curation_interval_hours, hours)
}

/** 治理周期选择弹层：关闭 / 6 小时 / 12 小时 / 1 天 / 3 天 / 7 天。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurationIntervalSheet(
    selectedHours: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.memory_curation_interval),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            CURATION_INTERVAL_OPTIONS.forEach { hours ->
                val isSelected = hours == selectedHours
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(hours) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = curationIntervalLabel(hours),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 记忆编辑器目标：[memory] 为 null 表示新建一条。 */
internal data class MemoryEditorTarget(val memory: Memory? = null)

/**
 * 记忆编辑器弹层：名称、描述、正文三段。
 *
 * 新建时名称可填；编辑时名称只读——它是记忆的唯一标识（文件名），改了就是另一条记忆。
 * 注入系统提示词的只有名称 + 描述，正文平时不展示，所以正文放在最后一段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryEditorSheet(
    memory: Memory?,
    onSave: (name: String, description: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    val isNew = memory == null
    var name by remember(memory) { mutableStateOf(memory?.name.orEmpty()) }
    var description by remember(memory) { mutableStateOf(memory?.description.orEmpty()) }
    var content by remember(memory) { mutableStateOf(memory?.content.orEmpty()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val flingFix = rememberSheetFlingFix(sheetState)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.85f)
                .imePadding()
                .navigationBarsPadding()
                .nestedScroll(flingFix)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(if (isNew) R.string.memory_add else R.string.common_edit),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )

            SettingsGroup {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        readOnly = !isNew,
                        label = stringResource(R.string.common_name),
                        placeholder = stringResource(R.string.memory_field_name_placeholder)
                    )
                    if (!isNew) {
                        Text(
                            text = stringResource(R.string.memory_field_name_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xs)
                        )
                    }
                }
                SettingsDivider()
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.memory_field_description),
                        placeholder = stringResource(R.string.memory_field_description_placeholder)
                    )
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.memory_detail_content))
            SettingsGroup {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    AppTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        singleLine = false,
                        label = stringResource(R.string.memory_detail_content)
                    )
                }
            }

            // 保存动作放行内按钮，避免和外壳顶栏动作槽抢位置（与提示词/技能编辑页一致的做法）
            SettingsGroup {
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.common_save),
                    enabled = name.isNotBlank(),
                    onClick = { onSave(name.trim(), description.trim(), content) }
                )
            }
        }
    }
}
