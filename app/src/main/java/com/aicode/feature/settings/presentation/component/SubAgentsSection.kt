package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Users
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 子代理二级页：与「技能」一致的折叠分组列表——「当前项目 / 全局」两组各自可折叠，
 * 每行一个子代理（图标 + 名称 + 描述 + 模型标签），左滑删除，点击行进入详情，长按整行拖拽排序（仅同组内）。
 * 新建入口在顶栏的「＋」，启用开关与编辑入口在详情页。
 */

/** 列表 item key 前缀：既标识身份，也用来判定分组，跨组拖拽会被拦下。 */
private const val SUB_AGENT_PROJECT_PREFIX = "sub_agent_project_"
private const val SUB_AGENT_GLOBAL_PREFIX = "sub_agent_global_"
private const val SUB_AGENT_HEADER_PROJECT_KEY = "sub_agents_header_project"
private const val SUB_AGENT_HEADER_GLOBAL_KEY = "sub_agents_header_global"
private const val SUB_AGENT_EMPTY_PROJECT_KEY = "sub_agents_empty_project"
private const val SUB_AGENT_EMPTY_GLOBAL_KEY = "sub_agents_empty_global"

private fun subAgentItemKey(scope: AgentDefinitionScope, name: String): String =
    listItemKey(if (scope == AgentDefinitionScope.PROJECT) SUB_AGENT_PROJECT_PREFIX else SUB_AGENT_GLOBAL_PREFIX, name)

@Composable
internal fun SubAgentsSection(
    projectName: String?,
    entries: List<SubAgentUiEntry>,
    onDelete: (SubAgentUiEntry) -> Unit,
    onOpenDetail: (SubAgentUiEntry) -> Unit
) {
    val projectAgents = entries.filter { it.scope == AgentDefinitionScope.PROJECT }
    val globalAgents = entries.filter { it.scope == AgentDefinitionScope.GLOBAL }

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
                        FeatherIcons.Users,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.subagents_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.subagents_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }

    val settingsViewModel = rememberSettingsViewModel()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val viewModel = settingsViewModel ?: return@rememberReorderableLazyListState
        // onMove 的 from/to 是含分组标题、空态提示行的全局下标，这里用带分组前缀的 item key 反查身份：
        // 前缀不一致就是跨组，直接不处理；组内下标交给 VM 在自己的列表里定位。
        val scope = when {
            itemIdOf(from.key, SUB_AGENT_PROJECT_PREFIX) != null -> AgentDefinitionScope.PROJECT
            itemIdOf(from.key, SUB_AGENT_GLOBAL_PREFIX) != null -> AgentDefinitionScope.GLOBAL
            else -> return@rememberReorderableLazyListState
        }
        val prefix =
            if (scope == AgentDefinitionScope.PROJECT) SUB_AGENT_PROJECT_PREFIX else SUB_AGENT_GLOBAL_PREFIX
        val moved = itemIdOf(from.key, prefix) ?: return@rememberReorderableLazyListState
        val target = itemIdOf(to.key, prefix) ?: return@rememberReorderableLazyListState
        viewModel.reorderSubAgents(scope, moved, target)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        // 分组标题/卡片之间的间距原本由 Column 的 spacedBy(sm) 提供，换成 LazyColumn 后按项补回来，
        // 同组内的行不加间距，才能保持「一组连成一块卡片」。
        item(key = SUB_AGENT_HEADER_PROJECT_KEY) {
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
            if (projectAgents.isEmpty()) {
                item(key = SUB_AGENT_EMPTY_PROJECT_KEY) {
                    SettingsGroup(modifier = Modifier.animateItem()) {
                        SubAgentEmptyHint(stringResource(R.string.subagents_no_project))
                    }
                }
            } else {
                itemsIndexed(
                    items = projectAgents,
                    key = { _, entry -> subAgentItemKey(AgentDefinitionScope.PROJECT, entry.name) }
                ) { index, entry ->
                    ReorderableCardRow(
                        state = reorderableState,
                        key = subAgentItemKey(AgentDefinitionScope.PROJECT, entry.name),
                        isFirst = index == 0,
                        isLast = index == projectAgents.lastIndex,
                        dragLabel = "subAgentProjectDrag"
                    ) { dragModifier ->
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) },
                            dragModifier = dragModifier
                        )
                    }
                }
            }
        }

        item(key = SUB_AGENT_HEADER_GLOBAL_KEY) {
            Box(modifier = Modifier.animateItem().padding(top = Spacing.sm, bottom = Spacing.sm)) {
                CollapsibleGroupHeader(
                    text = stringResource(R.string.perm_global),
                    expanded = globalExpanded,
                    onToggle = { globalExpanded = !globalExpanded }
                )
            }
        }
        if (globalExpanded) {
            if (globalAgents.isEmpty()) {
                item(key = SUB_AGENT_EMPTY_GLOBAL_KEY) {
                    SettingsGroup(modifier = Modifier.animateItem()) {
                        SubAgentEmptyHint(stringResource(R.string.subagents_no_global))
                    }
                }
            } else {
                itemsIndexed(
                    items = globalAgents,
                    key = { _, entry -> subAgentItemKey(AgentDefinitionScope.GLOBAL, entry.name) }
                ) { index, entry ->
                    ReorderableCardRow(
                        state = reorderableState,
                        key = subAgentItemKey(AgentDefinitionScope.GLOBAL, entry.name),
                        isFirst = index == 0,
                        isLast = index == globalAgents.lastIndex,
                        dragLabel = "subAgentGlobalDrag"
                    ) { dragModifier ->
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) },
                            dragModifier = dragModifier
                        )
                    }
                }
            }
        }
    }
}

/** 单个子代理行：图标 + 名称/描述 + 模型标签 + 右箭头；左滑删除，点击行进入详情。 */
@Composable
private fun SubAgentRow(
    entry: SubAgentUiEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    dragModifier: Modifier = Modifier
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
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
                    imageVector = FeatherIcons.Users,
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
                    entry.model?.let { model ->
                        McpPill(
                            text = model,
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }
                    // 只在禁用时挂标签：默认启用是常态，每行都挂一个反而护不住重点。
                    if (entry.disabled) {
                        McpPill(
                            text = stringResource(R.string.common_disabled),
                            textColor = MaterialTheme.colorScheme.outline,
                            backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    }
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
private fun SubAgentEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
