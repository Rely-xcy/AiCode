package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionBar
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SelectionActionIconButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.settings.presentation.SkillUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Trash2
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 技能二级页：与「工具授权」一致的折叠分组列表——「当前项目 / 全局」两组各自可折叠，
 * 每行一个技能（图标 + 名称 + 描述），左滑删除，点击行进入详情，长按整行拖拽排序（仅同组内）。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：可批量启用 / 停用 / 删除。
 * 全选覆盖两组（含折叠起来的组），避免「点了全选却漏掉折叠里的」。
 *
 * 搜索不在这页：顶栏只放一个放大镜入口，点进二级搜索页（[SkillSearchSection]）去搜。
 * 搜索框原先铺在列表上方，记忆页又塞在顶栏，两页两种位置；现在统一成「入口一个按钮、搜索一页」。
 */

/** 列表 item key 前缀：既标识身份，也用来判定分组，跨组拖拽会被拦下。 */
private const val SKILL_PROJECT_PREFIX = "skill_project_"
private const val SKILL_GLOBAL_PREFIX = "skill_global_"
private const val SKILL_HEADER_PROJECT_KEY = "skills_header_project"
private const val SKILL_HEADER_GLOBAL_KEY = "skills_header_global"
private const val SKILL_EMPTY_PROJECT_KEY = "skills_empty_project"
private const val SKILL_EMPTY_GLOBAL_KEY = "skills_empty_global"

private fun skillItemKey(scope: SkillScope, name: String): String =
    listItemKey(if (scope == SkillScope.PROJECT) SKILL_PROJECT_PREFIX else SKILL_GLOBAL_PREFIX, name)

@Composable
internal fun SkillsSection(
    projectName: String?,
    entries: List<SkillUiEntry>,
    selection: ListSelectionState,
    deletingInProgress: Boolean,
    onDelete: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit,
    onBatchDelete: (List<SkillUiEntry>) -> Unit,
    onBatchSetEnabled: (List<SkillUiEntry>, Boolean) -> Unit
) {
    // 本页总是画完整列表，不再做关键词过滤：筛选已经移到搜索页，否则从搜索页返回后
    // 列表会被上一次的关键词继续筛着，看起来像技能丢了一堆。
    // 只在数据变化时分组，避免每次重组都产生两份新 List。
    val (projectSkills, globalSkills) = remember(entries) {
        entries.filter { it.scope == SkillScope.PROJECT } to entries.filter { it.scope == SkillScope.GLOBAL }
    }

    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 多选只能作用在列表里的条目上：全选覆盖两组（含折叠的），不包含任何被筛掉的。
    val allKeys = remember(entries) { entries.map { skillSelectionKey(it) } }
    val keyToEntry = remember(entries) { entries.associateBy { skillSelectionKey(it) } }

    // 列表刷新后剔除已消失的选中项；全空了就退出多选。
    LaunchedEffect(allKeys, selection.isActive) {
        selection.retainOnly(allKeys.toSet())
        if (selection.isActive && entries.isEmpty()) selection.exit()
    }

    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    val allSelected = selection.selectedCount > 0 && selection.selectedCount == allKeys.size
    val selectedEntries: List<SkillUiEntry> = selection.selectedKeys.mapNotNull { keyToEntry[it] }

    val settingsViewModel = rememberSettingsViewModel()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val viewModel = settingsViewModel ?: return@rememberReorderableLazyListState
        // onMove 的 from/to 是含分组标题、空态提示行的全局下标，这里用带分组前缀的 item key 反查身份：
        // 前缀不一致就是跨组，直接不处理；组内下标交给 VM 在自己的列表里定位。
        val scope = when {
            itemIdOf(from.key, SKILL_PROJECT_PREFIX) != null -> SkillScope.PROJECT
            itemIdOf(from.key, SKILL_GLOBAL_PREFIX) != null -> SkillScope.GLOBAL
            else -> return@rememberReorderableLazyListState
        }
        val prefix = if (scope == SkillScope.PROJECT) SKILL_PROJECT_PREFIX else SKILL_GLOBAL_PREFIX
        val moved = itemIdOf(from.key, prefix) ?: return@rememberReorderableLazyListState
        val target = itemIdOf(to.key, prefix) ?: return@rememberReorderableLazyListState
        viewModel.reorderSkills(scope, moved, target)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            val enabled = selection.selectedCount > 0
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = allSelected,
                onToggleSelectAll = {
                    if (allSelected) selection.clear() else selection.replaceAll(allKeys)
                },
                onExit = { selection.exit() }
            ) {
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_enable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selectedEntries, true) }
                )
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_disable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selectedEntries, false) }
                )
                SelectionActionIconButton(
                    icon = FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_batch_delete),
                    enabled = enabled,
                    onClick = { showBatchDeleteConfirm = true },
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // 批量删除期间给一行文字提示：远程模式下逐条 SFTP 递归删可能持续数秒，没有反馈会以为没生效。
        if (deletingInProgress) {
            Text(
                text = stringResource(R.string.common_batch_deleting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = Spacing.lg, bottom = Spacing.xs)
            )
        }

        // 一条技能都没有时给整页引导（图标 + 标题 + 一句提示）：这种空是「还没装」，
        // 和分组里「这一组没有」不是一回事，不能只给一行灰字。
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xl, vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
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
                            FeatherIcons.Book,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.skills_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.skills_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                // 分组标题/卡片之间的间距原本由 Column 的 spacedBy(sm) 提供，换成 LazyColumn 后按项补回来，
                // 同组内的行不加间距，才能保持「一组连成一块卡片」。
                item(key = SKILL_HEADER_PROJECT_KEY) {
                    Box(modifier = Modifier.animateItem().padding(bottom = Spacing.sm)) {
                        CollapsibleGroupHeader(
                            text = if (projectName != null) {
                                stringResource(R.string.perm_current_project, projectName)
                            } else {
                                stringResource(R.string.perm_current_project_none)
                            },
                            expanded = projectExpanded,
                            onToggle = { projectExpanded = !projectExpanded }
                        )
                    }
                }
                if (projectExpanded) {
                    if (projectSkills.isEmpty()) {
                        item(key = SKILL_EMPTY_PROJECT_KEY) {
                            SettingsGroup(modifier = Modifier.animateItem()) {
                                SkillEmptyHint(stringResource(R.string.skills_no_project_skills))
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = projectSkills,
                            key = { _, entry -> skillItemKey(SkillScope.PROJECT, entry.name) },
                            contentType = { _, _ -> "skill-row" }
                        ) { index, entry ->
                            ReorderableCardRow(
                                state = reorderableState,
                                key = skillItemKey(SkillScope.PROJECT, entry.name),
                                isFirst = index == 0,
                                isLast = index == projectSkills.lastIndex,
                                dragLabel = "skillProjectDrag"
                            ) { dragModifier ->
                                SkillRow(
                                    entry = entry,
                                    onDelete = { onDelete(entry) },
                                    onClick = { onOpenDetail(entry) },
                                    // 多选态下不给拖拽手柄：否则长按整行会拉走拖拽，与「整行点击即勾选」抢手势。
                                    dragModifier = if (selection.isActive) Modifier else dragModifier,
                                    selectionMode = selection.isActive,
                                    selected = selection.isSelected(skillSelectionKey(entry)),
                                    onToggleSelect = { selection.toggle(skillSelectionKey(entry)) }
                                )
                            }
                        }
                    }
                }

                item(key = SKILL_HEADER_GLOBAL_KEY) {
                    Box(modifier = Modifier.animateItem().padding(top = Spacing.sm, bottom = Spacing.sm)) {
                        CollapsibleGroupHeader(
                            text = stringResource(R.string.perm_global),
                            expanded = globalExpanded,
                            onToggle = { globalExpanded = !globalExpanded }
                        )
                    }
                }
                if (globalExpanded) {
                    if (globalSkills.isEmpty()) {
                        item(key = SKILL_EMPTY_GLOBAL_KEY) {
                            SettingsGroup(modifier = Modifier.animateItem()) {
                                SkillEmptyHint(stringResource(R.string.skills_no_global_skills))
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = globalSkills,
                            key = { _, entry -> skillItemKey(SkillScope.GLOBAL, entry.name) },
                            contentType = { _, _ -> "skill-row" }
                        ) { index, entry ->
                            ReorderableCardRow(
                                state = reorderableState,
                                key = skillItemKey(SkillScope.GLOBAL, entry.name),
                                isFirst = index == 0,
                                isLast = index == globalSkills.lastIndex,
                                dragLabel = "skillGlobalDrag"
                            ) { dragModifier ->
                                SkillRow(
                                    entry = entry,
                                    onDelete = { onDelete(entry) },
                                    onClick = { onOpenDetail(entry) },
                                    dragModifier = if (selection.isActive) Modifier else dragModifier,
                                    selectionMode = selection.isActive,
                                    selected = selection.isSelected(skillSelectionKey(entry)),
                                    onToggleSelect = { selection.toggle(skillSelectionKey(entry)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBatchDeleteConfirm) {
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.skills_batch_delete_title),
            message = stringResource(R.string.skills_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(selectedEntries)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/** 多选 key：同名技能可能同时存在于项目层与全局层，必须带作用域前缀。 */
private fun skillSelectionKey(entry: SkillUiEntry): String = "${entry.scope.name}:${entry.name}"

/** 单个技能行：图标 + 名称/描述 + 右箭头；左滑删除，点击行进入详情。 */
@Composable
private fun SkillRow(
    entry: SkillUiEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    dragModifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    // 行背景由 SwipeToDeleteRow 提供，这里不再重复画一层，省一次整行绘制。
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(dragModifier)
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
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
                    imageVector = FeatherIcons.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    McpPill(
                        text = stringResource(if (entry.disabled) R.string.common_disabled else R.string.common_enabled),
                        textColor = if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary,
                        backgroundColor = (if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary).copy(alpha = 0.12f)
                    )
                }
                Text(
                    text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
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

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun SkillEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

/**
 * 技能搜索二级页：顶栏那一行搜索框由 SettingsScreen 渲染（[SearchTopBarField]），这里只画结果。
 *
 * 结果行直接复用列表的 [SkillRow]：图标、启用状态徽章、右箭头都在行里，
 * 点击进详情、左滑删除与列表页同一套动作，不另造一种结果行。
 *
 * 同名技能可以同时存在于项目层与全局层，所以结果仍按作用域分组：行里没有作用域标识，
 * 混成一份清单就分不清点开的是哪一个。
 *
 * 过滤口径（名称 / 描述 / 正文，大小写不敏感）只作用于已经加载到内存的 [entries]，
 * 每敲一个字都不会重新扫盘。不搜触发词与 required_tools：那两个是给引擎判断是否注入用的，
 * 按工具名搜技能只会搜出一堆无关结果。
 */
@Composable
internal fun SkillSearchSection(
    projectName: String?,
    entries: List<SkillUiEntry>,
    keyword: String,
    onOpenDetail: (SkillUiEntry) -> Unit,
    onDelete: (SkillUiEntry) -> Unit
) {
    val results = remember(entries, keyword) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.name.contains(trimmed, ignoreCase = true) ||
                    entry.description.contains(trimmed, ignoreCase = true) ||
                    entry.instructions.contains(trimmed, ignoreCase = true)
            }
        }
    }
    val projectSkills = remember(results) { results.filter { it.scope == SkillScope.PROJECT } }
    val globalSkills = remember(results) { results.filter { it.scope == SkillScope.GLOBAL } }

    val scrollState = rememberScrollState()
    // 空关键词=刚从入口进来（入口每次都会先清空）：把上一次留下的滚动位置归零，
    // 否则会停在全量列表的中间，看着像结果被截断了；有关键词时不动，从详情返回能回到原位置。
    LaunchedEffect(keyword.isBlank()) {
        if (keyword.isBlank()) scrollState.scrollTo(0)
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
        if (results.isEmpty()) {
            SearchEmptyHint(text = stringResource(R.string.skills_search_empty))
        } else {
            if (projectSkills.isNotEmpty()) {
                SettingsGroupHeader(
                    text = if (projectName != null) {
                        stringResource(R.string.perm_current_project, projectName)
                    } else {
                        stringResource(R.string.perm_current_project_none)
                    }
                )
                SettingsGroup {
                    projectSkills.forEachIndexed { index, entry ->
                        // 带 key：滑开位移是行内 remember 的位置状态，删掉一条后不带 key 会继承上一条的滑开状态
                        key(skillSelectionKey(entry)) {
                            if (index > 0) SettingsDivider()
                            SkillRow(
                                entry = entry,
                                onDelete = { onDelete(entry) },
                                onClick = { onOpenDetail(entry) }
                            )
                        }
                    }
                }
            }
            if (globalSkills.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.perm_global))
                SettingsGroup {
                    globalSkills.forEachIndexed { index, entry ->
                        key(skillSelectionKey(entry)) {
                            if (index > 0) SettingsDivider()
                            SkillRow(
                                entry = entry,
                                onDelete = { onDelete(entry) },
                                onClick = { onOpenDetail(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}
