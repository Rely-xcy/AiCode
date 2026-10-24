package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆页（长期记忆）：主体是当前生效的长期记忆列表（点击看详情、长按弹编辑/删除、左滑删除），
 * 其后是主动记忆开关 + 治理周期（周期归零即关闭），最后是只读的「本次会话（短期）」卡片。
 *
 * 列表排在最前、两张设置/状态卡沉底，是按使用频率排的：列表才是用户来这一页要看的东西，
 * 设置与只读状态放前面会把列表挤到首屏折线以下。
 *
 * 搜索不在这页：顶栏只放一个放大镜入口，点进二级搜索页（[MemorySearchSection]）去搜。
 * 记忆一多只能靠滚动找人，而搜索框留在内容区会随列表一起滚走、塞进顶栏又会和「+」挤同一行。
 *
 * 短期与长期必须分开展示：短期上下文随会话结束就没了，长期记忆是写盘、跨会话注入提示词的另一套东西，
 * 混成一张清单会让人以为上下文也会被存下来。
 *
 * 按作用域分两栏（全局 / 项目），每条再带一个作用域徽章——项目记忆只在该工作区生效，
 * 和全局记忆混成一份清单会让人分不清哪条换项目就没了。
 *
 * @param shortTerm 本次会话（短期）卡片的数据；null 表示拿不到（如还没开过会话），此时整块不渲染。
 * @param memories 全部记忆；本页始终画完整列表，筛选只发生在搜索页，
 *   否则从搜索页返回后列表会被上一次的关键词继续筛着。
 */
@Composable
internal fun MemorySection(
    memories: List<Memory>,
    loading: Boolean,
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
        // 列表放最前：这是本页最高频的内容，两张设置/状态卡沉底。
        // 空态跟技能页 / MCP 页一样直接排在页内，不套卡片：一张没有分组标题的卡片贴在顶栏下
        // 既没有归属，也跟下面带标题的卡片不是一套节奏。
        if (memories.isEmpty()) {
            // 首次读盘未完成时不要渲染空态：那会先闪一下「没有记忆」再变成列表。
            if (loading) MemoryLoadingPlaceholder() else MemoryEmptyState()
        } else {
            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }
            if (globalMemories.isNotEmpty()) {
                SettingsGroupHeader(
                    text = stringResource(R.string.memory_group_global_count, globalMemories.size)
                )
                MemoryGroup(
                    memories = globalMemories,
                    onOpenDetail = onOpenDetail,
                    onLongClick = { actionMemory = it },
                    onDelete = onDelete
                )
            }
            if (projectMemories.isNotEmpty()) {
                SettingsGroupHeader(
                    text = stringResource(R.string.memory_group_project_count, projectMemories.size)
                )
                MemoryGroup(
                    memories = projectMemories,
                    onOpenDetail = onOpenDetail,
                    onLongClick = { actionMemory = it },
                    onDelete = onDelete
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_long_term_header))
        SettingsGroup {
            // 长期 / 短期是两套东西：这页管的是写盘、跨会话注入提示词的长期记忆
            Text(
                text = stringResource(R.string.memory_long_term_desc),
                style = MaterialTheme.typography.bodySmall,
                // 说明句统一用 onSurfaceVariant（与各项副标题、页脚说明同色）；
                // subtleText 在本仓只用于取值与箭头这类弱提示
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.memory_active_memory),
                subtitle = stringResource(R.string.memory_active_memory_desc),
                subtitleMaxLines = 2,
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

/**
 * 记忆搜索二级页：顶栏那一行搜索框由 SettingsScreen 渲染（[SearchTopBarField]），这里只画结果。
 *
 * 结果行直接复用列表的 [MemoryGroup] / [MemoryRow]：图标、作用域徽章、置顶与来源标注全长在行里，
 * 另写一套搜索结果行，过一阵就会和列表长得不一样。分组也不拆：命中横跨全局与项目时，
 * 一个作用域小标题比每行再重复两遍徽章更好扫。
 *
 * 过滤不在这里做：进来的 [results] 已经是 MemoryViewModel.visibleMemories 的结果，
 * 只对已读进内存的列表做字符串匹配，敲字不会触发任何读盘。
 *
 * 点击行为只到详情：详情弹层里本来就有编辑与删除入口，搜索页再挂一套长按菜单只多一层；
 * 左滑删除保留——行组件自带这个手势，滑开了不回应反而像是坏了。
 *
 * @param results 已按当前关键词算好的命中列表（来自 MemoryViewModel.visibleMemories）
 * @param query 当前关键词：只用来判断「是否刚进页面」，空词时把滚动位置归零
 * @param loading 首次读盘未完成：此时 [results] 必然为空，不能拿「没搜到」冒充。
 */
@Composable
internal fun MemorySearchSection(
    results: List<Memory>,
    query: String,
    loading: Boolean,
    onEdit: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    // 详情与删除确认都留在这个页面上：从结果点进去、关掉弹层，还能回到同一份结果，
    // 而不是被带回列表再搜一次。
    var detailMemory by remember { mutableStateOf<Memory?>(null) }
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }

    val scrollState = rememberScrollState()
    // 空关键词=刚从入口进来（入口每次都会先清空）：把上一次留下的滚动位置归零，
    // 否则会停在全量列表的中间，看着像结果被截断了；有关键词时不动，从详情返回能回到原位置。
    LaunchedEffect(query.isBlank()) {
        if (query.isBlank()) scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            // 只补一点上边：结果从顶栏下方直接开始，不要在搜索框和第一行之间留一大片空白
            .padding(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        when {
            loading -> MemoryLoadingPlaceholder()
            results.isEmpty() -> SearchEmptyHint(stringResource(R.string.memory_search_empty))
            else -> {
                val globalMemories = results.filter { it.scope == MemoryScope.GLOBAL }
                val projectMemories = results.filter { it.scope == MemoryScope.PROJECT }
                if (globalMemories.isNotEmpty()) {
                    SettingsGroupHeader(
                        text = stringResource(R.string.memory_group_global_count, globalMemories.size)
                    )
                    MemoryGroup(
                        memories = globalMemories,
                        onOpenDetail = { detailMemory = it },
                        onLongClick = {},
                        onDelete = { deleteTarget = it }
                    )
                }
                if (projectMemories.isNotEmpty()) {
                    SettingsGroupHeader(
                        text = stringResource(R.string.memory_group_project_count, projectMemories.size)
                    )
                    MemoryGroup(
                        memories = projectMemories,
                        onOpenDetail = { detailMemory = it },
                        onLongClick = {},
                        onDelete = { deleteTarget = it }
                    )
                }
            }
        }
    }

    detailMemory?.let { memory ->
        MemoryDetailSheet(
            memory = memory,
            onDismiss = { detailMemory = null },
            onEdit = {
                detailMemory = null
                onEdit(memory)
            },
            onDelete = {
                detailMemory = null
                deleteTarget = memory
            }
        )
    }

    deleteTarget?.let { memory ->
        MemoryDeleteConfirmDialog(
            memory = memory,
            onConfirm = {
                onDelete(memory)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** 删除记忆的二次确认框：列表页与搜索页共用，条目名统一取 memory.name。 */
@Composable
internal fun MemoryDeleteConfirmDialog(
    memory: Memory,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_delete_confirm_title)) },
        text = { Text(stringResource(R.string.memory_delete_confirm_message, memory.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
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

/** 首次读盘时的占位：不是「没有记忆」，所以不套空态图标与引导文案。 */
@Composable
private fun MemoryLoadingPlaceholder() {
    Text(
        text = stringResource(R.string.memory_loading),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    )
}

/** 一条记忆都没有：页内空态，与技能页 / MCP 页同一套图标尺寸、文字层级与留白。 */
@Composable
private fun MemoryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 24 是给那条长提示的：不留白时它会顶到屏幕两侧
            .padding(horizontal = Spacing.xl, vertical = 48.dp),
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
                imageVector = FeatherIcons.FileText,
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

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onOpenDetail,
        onLongClick = onLongClick,
        // 圆角跟着滑动位移：记忆行在分组卡片里，卡片是 14dp 圆角，
        // 滑开时露出的是行自己的切边，不在这里裁圆角就是一条直角。
        contentShape = RoundedCornerShape(Radius.lg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    // 40dp 圆角块 + 8dp 圆角与 MCP / 技能 / 子代理 / 容器列表行一致，
                    // 刻意不用令牌：这几处必须像素对齐，改一个就会跟其它列表行错开
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // 画像与笔记是两种东西：自动沉淀出的用户画像要一眼认出来，
                    // 与名称旁的来源徽章一起表达“这条不是我在对话里让它记的”
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

            Spacer(modifier = Modifier.width(Spacing.sm))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
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
 * 记忆详情：名称当标题，下面一行徽章（作用域 / 置顶 / 来源），再是描述、元信息与完整正文，
 * 底部给编辑 / 删除两个入口。
 *
 * 注入到系统提示词的只有「名称 + 描述」，正文平时看不到，详情页才展开。
 * 编辑/删除除了长按菜单还在这里出现：刚看完正文往往正是要改或要删的时候，
 * 退回列表再长按多一步，也容易对错条目。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryDetailSheet(
    memory: Memory,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 弹层内容槽在部分分支给的是无界高度，verticalScroll 需要上界才知道能滚多少；
                // 不封顶时可见区＝内容高度、滚动范围恒为 0，长正文就“滑不动”。
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = memory.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm)
            )
            // 徽章行：与列表行同一套 pill，不另造视觉
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MemoryScopePill(scope = memory.scope)
                if (memory.pinned) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    McpPill(
                        text = stringResource(R.string.memory_pinned),
                        textColor = MaterialTheme.colorScheme.tertiary,
                        backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    )
                }
                // 来源与列表行同一条规则：只给自动沉淀类挂，NOTE 全是对话中记的
                if (memory.kind == MemoryKind.PROFILE) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    MemorySourcePill(memory = memory)
                }
            }
            SettingsGroup(modifier = Modifier.padding(top = Spacing.sm)) {
                SettingsRow(
                    icon = null,
                    title = memory.description.ifBlank { stringResource(R.string.mcp_no_description) }
                )
            }
            // 元信息：没有值的行整行不摆，沿用短期卡那条约定（不拿 0 或空占位凑数）
            val hasMeta = memory.hitCount > 0 || memory.lastHitAt > 0 || memory.createdAt > 0
            if (hasMeta) {
                SettingsGroupHeader(text = stringResource(R.string.memory_detail_meta))
                SettingsGroup {
                    if (memory.hitCount > 0) {
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.memory_detail_hits, memory.hitCount)
                        )
                    }
                    if (memory.lastHitAt > 0) {
                        if (memory.hitCount > 0) SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(
                                R.string.memory_detail_last_hit,
                                relativeTimeText(memory.lastHitAt)
                            )
                        )
                    }
                    if (memory.createdAt > 0) {
                        if (memory.hitCount > 0 || memory.lastHitAt > 0) SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(
                                R.string.memory_detail_created,
                                formatEpochDate(memory.createdAt)
                            )
                        )
                    }
                }
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
            SettingsGroup {
                MemoryDetailActionRow(
                    icon = FeatherIcons.Edit2,
                    title = stringResource(R.string.common_edit),
                    onClick = onEdit
                )
                SettingsDivider()
                MemoryDetailActionRow(
                    icon = FeatherIcons.Trash2,
                    title = stringResource(R.string.common_delete),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

/**
 * 详情弹层的动作行：尺寸与 [SettingsRow] 一致，只多一个标题色参数。
 *
 * 不复用 SettingsRow：它的标题色写死 onSurface，而共享组件不在本页改动范围内；
 * 删除行必须用 error 色才能与编辑行区分开。
 */
@Composable
private fun MemoryDetailActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = titleColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 「最后命中」的相对时间：超过 30 天回落到绝对日期——「87 天前」还得自己心算。
 */
@Composable
private fun relativeTimeText(epochMillis: Long): String {
    val elapsed = System.currentTimeMillis() - epochMillis
    return when {
        elapsed < 60_000L -> stringResource(R.string.memory_time_just_now)
        elapsed < 3_600_000L ->
            stringResource(R.string.memory_time_minutes_ago, (elapsed / 60_000L).toInt())
        elapsed < 86_400_000L ->
            stringResource(R.string.memory_time_hours_ago, (elapsed / 3_600_000L).toInt())
        elapsed < 30 * 86_400_000L ->
            stringResource(R.string.memory_time_days_ago, (elapsed / 86_400_000L).toInt())
        else -> formatEpochDate(epochMillis)
    }
}

/** 绝对日期：与调用统计页的时间格式同源（yyyy/M/d）。 */
private fun formatEpochDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(epochMillis))

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
        containerColor = settingsPageBackground()
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

