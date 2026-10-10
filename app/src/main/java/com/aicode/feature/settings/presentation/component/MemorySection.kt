package com.aicode.feature.settings.presentation.component

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
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicode.feature.settings.presentation.SessionShortTermState
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
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.memory.MemorySource
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.User

/**
 * 记忆页（长期记忆）：顶部一张只读的「本次会话（短期）」卡片，下面才是主动记忆开关 +
 * 治理周期（周期归零即关闭）+ 当前生效的长期记忆列表（点击看详情、长按弹编辑/删除、左滑删除）。
 *
 * 短期与长期必须分开展示：短期上下文随会话结束就没了，长期记忆是写盘、跨会话注入提示词的另一套东西，
 * 混成一张清单会让人以为上下文也会被存下来。
 *
 * 按作用域分两栏（全局 / 项目），每条再带一个作用域徽章——项目记忆只在该工作区生效，
 * 和全局记忆混成一份清单会让人分不清哪条换项目就没了。
 *
 * 单页自上而下：短期卡片、跨会话记忆组（开关 + 治理周期），
 * 最后是按作用域分组的记忆清单（PROFILE 与 NOTE 混排，PROFILE 在前：自动沉淀的
 * 长期结论更稳定，排前面便于一眼看到；kind 用行图标区分）。
 */
@Composable
internal fun MemorySection(
    memories: List<Memory>,
    shortTerm: SessionShortTermState?,
    activeMemoryEnabled: Boolean,
    onToggleActiveMemory: (Boolean) -> Unit,
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
        // 本次会话（短期）：只读，不给编辑入口——上下文不是用户能直接改的东西。
        // 拿不到会话数据就整块不渲染：摆一张空卡片比没有更让人困惑。
        if (shortTerm != null) {
            SettingsGroupHeader(text = stringResource(R.string.memory_session_card_title))
                SettingsGroup {
                    SettingsRow(
                        icon = null,
                        title = shortTerm.title,
                        subtitle = stringResource(R.string.memory_session_card_desc)
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = null,
                        title = stringResource(R.string.memory_session_context_label),
                        trailing = {
                            SessionValueText(
                                stringResource(R.string.memory_session_context_value, shortTerm.retainedMessages)
                            )
                        }
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = null,
                        title = stringResource(R.string.memory_session_fold_label),
                        trailing = {
                            SessionValueText(
                                if (shortTerm.foldCount == 0) {
                                    stringResource(R.string.memory_session_no_fold)
                                } else {
                                    stringResource(R.string.memory_session_fold_value, shortTerm.foldCount)
                                }
                            )
                        }
                    )
                    // 没跑过请求的会话没有输入 token 可报，这一行直接不显示，不摆一个 0
                    if (shortTerm.lastInputTokens > 0) {
                        SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.memory_session_input_label),
                            trailing = {
                                SessionValueText(
                                    // 这个数是 provider 回传的真实值（上次请求的输入 token），顺手标明来源，
                                    // 与聊天页指示器的「估算/真实」用同一对文案。
                                    stringResource(
                                        R.string.memory_session_input_value,
                                        shortTerm.lastInputTokens,
                                        stringResource(R.string.common_token_source_reported)
                                    )
                                )
                            }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.memory_long_term_header))
            SettingsGroup {
                // 长期 / 短期是两套东西：这页管的是写盘、跨会话注入提示词的长期记忆
                Text(
                    text = stringResource(R.string.memory_long_term_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
                SettingsDivider()
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.memory_active_memory),
                    subtitle = stringResource(R.string.memory_active_memory_desc),
                    trailing = {
                        AppSwitch(
                            checked = activeMemoryEnabled,
                            onCheckedChange = onToggleActiveMemory
                        )
                    }
                )
                SettingsDivider()
                // 治理周期：三档预设 + 自定义，直接铺成胶囊分段控件，不再弹层
                // （弹层要“点行→选→关弹层”三步，而这里只有三四个互斥选项）。
                // 与主动记忆开关无关：周期 = 0 就是关闭治理，所以始终可点、不置灰。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.semanticColors.cardSurface)
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.memory_curation_interval),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.memory_curation_interval_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    if (isCustom) {
                        Text(
                            text = stringResource(R.string.memory_curation_interval_hours, curationIntervalHours),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.semanticColors.subtleText,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }
            }
        }

        if (memories.isEmpty()) {
            EmptyState(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.memory_empty),
                hint = stringResource(R.string.memory_empty_hint)
            )
        } else {
            // 单一清单里 PROFILE 与 NOTE 混排：同组内 PROFILE 在前（自动沉淀的长期结论更稳定，排前面便于一眼看到）
            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
                .sortedByDescending { it.kind == MemoryKind.PROFILE }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }
                .sortedByDescending { it.kind == MemoryKind.PROFILE }
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

/** 短期卡片右侧取值：右对齐单行，配色与设置页其它取值行一致。 */
@Composable
private fun SessionValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = Spacing.sm)
    )
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

/** 空态：图标 + 标题 + 说明。画像与记忆两个 tab 各用自己的文案与图标。 */
@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String
) {
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
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = hint,
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
                        shape = RoundedCornerShape(Radius.sm)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // 行图标按类型区分：画像用 User，记忆用 FileText，与顶部 tab 一致
                    imageVector = if (memory.kind == MemoryKind.PROFILE) {
                        FeatherIcons.User
                    } else {
                        FeatherIcons.FileText
                    },
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
                    // 置顶标识：与作用域/来源同一套 McpPill，不另造视觉
                    if (memory.pinned) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        McpPill(
                            text = stringResource(R.string.memory_pinned),
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }
                    // 来源只给自动沉淀类挂：NOTE 全是「对话中记录」，每行都挂就是噪音。
                    // 不要求 source 非空：旧条目（source 字段是后来才加的）会按 kind 回退成「自动沉淀」，
                    // 否则升级上来的用户会看到这些条目一个来源都没有。
                    if (memory.kind == MemoryKind.PROFILE) {
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

/** 来源徐章：配色与作用域徐章同一套规则（主色=自动沉淀、次色=压缩前抽取），靠文案区分。 */
@Composable
private fun MemorySourcePill(memory: Memory) {
    val accent = if (memory.source == MemoryExtractor.SOURCE_PRE_FOLD) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    McpPill(
        text = memorySourceText(memory),
        textColor = accent,
        backgroundColor = accent.copy(alpha = 0.12f)
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

