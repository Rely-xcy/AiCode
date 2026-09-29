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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.rememberSheetFlingFix
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.memory.MemorySource
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
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
    // 自定义治理周期输入弹窗
    var showCustomIntervalDialog by remember { mutableStateOf(false) }

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
            // 治理周期：三档预设 + 自定义，直接铺成胶囊分段控件，不再弹层
            // （弹层要“点行→选→关弹层”三步，而这里只有三四个互斥选项）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.semanticColors.cardSurface)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
                    // 自动沉淀关了就没什么可治理的：整块置灰、不响应点击。
                    // 否则开关明明是关的，周期却还写着「1 天」，看着像还在按周期跑。
                    .alpha(if (autoDistillEnabled) 1f else 0.45f)
            ) {
                Text(
                    text = stringResource(R.string.memory_curation_interval),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                SegmentedTabs(
                    selected = curationIntervalIndex(curationIntervalHours),
                    labels = listOf(
                        stringResource(R.string.memory_curation_interval_off_short),
                        stringResource(R.string.memory_curation_interval_1d),
                        stringResource(R.string.memory_curation_interval_7d),
                        stringResource(R.string.memory_curation_interval_custom)
                    ),
                    onSelect = { index ->
                        if (!autoDistillEnabled) return@SegmentedTabs
                        when (index) {
                            0 -> onSelectCurationInterval(0)
                            1 -> onSelectCurationInterval(24)
                            2 -> onSelectCurationInterval(168)
                            else -> showCustomIntervalDialog = true
                        }
                    }
                )
                // 自定义档位下把当前值写出来，否则「自定义」这枚胶囊看不出实际是多少
                val isCustom = curationIntervalIndex(curationIntervalHours) == CURATION_CUSTOM_INDEX
                if (autoDistillEnabled && isCustom) {
                    Text(
                        text = stringResource(R.string.memory_curation_interval_hours, curationIntervalHours),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
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

    if (showCustomIntervalDialog) {
        CustomCurationIntervalDialog(
            initialHours = curationIntervalHours,
            onConfirm = {
                onSelectCurationInterval(it)
                showCustomIntervalDialog = false
            },
            onDismiss = { showCustomIntervalDialog = false }
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
                    // 来源只给自动沉淀类挂：NOTE 全是「对话中记录」，每行都挂就是噪音
                    if (memory.kind == MemoryKind.PROFILE && memory.source.isNotBlank()) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        MemorySourcePill(memory = memory)
                    }
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
/**
 * 来源文案：自动沉淀 / 压缩前抽取 / 对话中记录。
 *
 * 老条目没写 source（那之前的版本只存 kind），按 kind 回退：PROFILE 一定是沉淀出来的，
 * 其余当作对话中记的。
 */
@Composable
private fun memorySourceText(memory: Memory): String = when (memory.source) {
    MemoryExtractor.SOURCE_AUTO_DISTILL -> stringResource(R.string.memory_source_auto)
    MemoryExtractor.SOURCE_PRE_FOLD -> stringResource(R.string.memory_source_pre_fold)
    MemorySource.SOURCE_MODEL_TOOL -> stringResource(R.string.memory_source_manual)
    else -> stringResource(
        if (memory.kind == MemoryKind.PROFILE) R.string.memory_source_auto
        else R.string.memory_source_manual
    )
}

/** 来源徐章：与作用域徐章同款 McpPill，靠文案区分。 */
@Composable
private fun MemorySourcePill(memory: Memory) {
    McpPill(
        text = memorySourceText(memory),
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
}

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
                    subtitle = memorySourceText(memory)
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

/** 治理周期预设档位（小时）：关闭 / 1 天 / 7 天；其余值一律算「自定义」。 */
private val CURATION_PRESET_HOURS = listOf(0, 24, 168)

/** 「自定义」胶囊的下标（排在三个预设之后）。 */
private const val CURATION_CUSTOM_INDEX = 3

/** 当前值对应哪一枚胶囊：命中预设就用它的下标，其余值（含旧的 6h/12h/3d）算自定义。 */
private fun curationIntervalIndex(hours: Int): Int =
    CURATION_PRESET_HOURS.indexOf(hours).takeIf { it >= 0 } ?: CURATION_CUSTOM_INDEX

/**
 * 自定义治理周期：输入小时数（0 = 关闭，上限沿用仓库的 720）。
 *
 * 不合法时禁用确认按钮——超范围的值写进 DataStore 会被静默夹紧，用户却以为已生效。
 */
@Composable
private fun CustomCurationIntervalDialog(
    initialHours: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(if (initialHours > 0) initialHours.toString() else "") }
    val hours = text.trim().toIntOrNull()
    val valid = hours != null && hours in
        MemorySettingsRepository.MIN_CURATION_INTERVAL_HOURS..MemorySettingsRepository.MAX_CURATION_INTERVAL_HOURS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_curation_interval_custom_title)) },
        text = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = stringResource(R.string.memory_curation_interval_custom_label)
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.memory_curation_interval_custom_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { hours?.let(onConfirm) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
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
