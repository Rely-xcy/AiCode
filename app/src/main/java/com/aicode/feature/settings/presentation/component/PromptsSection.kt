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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.prompt.PromptFragmentRepository
import com.aicode.feature.agent.domain.prompt.UserPrompt
import com.aicode.feature.agent.domain.prompt.UserPromptPosition
import com.aicode.feature.agent.domain.prompt.UserPromptScope
import com.aicode.feature.settings.presentation.PromptsUiState
import com.aicode.feature.settings.presentation.PromptsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Info
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 编辑目标：区分三种入口。
 *
 * - [fragmentNumber] 非 null：编辑内置静态片段（「高级设置」里任意一条，或首页的 00）
 * - [prompt] = null：新建用户提示词
 * - 其余：编辑已有用户提示词
 */
internal data class PromptEditTarget(
    val prompt: UserPrompt? = null,
    val scope: UserPromptScope = UserPromptScope.GLOBAL,
    val fragmentNumber: Int? = null
)

/**
 * 自定义提示词页：顶部一句说明 + 固定内置片段（00）+ 全局/项目两组用户提示词。
 *
 * 用户提示词按「创建顺序」注入，分「最前 / 最后 / 关闭」三种位置，见 [UserPromptPosition]；
 * 长按整行可拖拽调整顺序（仅同组内），顺序表存 ListOrderStore，注入顺序同步跟随。
 */

/** 列表 item key 前缀：既标识身份，也用来判定分组，跨组拖拽会被拦下。 */
private const val PROMPT_GLOBAL_PREFIX = "prompt_global_"
private const val PROMPT_PROJECT_PREFIX = "prompt_project_"
private const val PROMPT_HINT_KEY = "prompts_hint"
private const val PROMPT_HEADER_DEFAULT_KEY = "prompts_header_default"
private const val PROMPT_DEFAULT_ROW_KEY = "prompts_default_row"
private const val PROMPT_HEADER_GLOBAL_KEY = "prompts_header_global"
private const val PROMPT_HEADER_PROJECT_KEY = "prompts_header_project"
private const val PROMPT_EMPTY_GLOBAL_KEY = "prompts_empty_global"
private const val PROMPT_EMPTY_PROJECT_KEY = "prompts_empty_project"

private fun promptItemKey(scope: UserPromptScope, id: String): String =
    listItemKey(if (scope == UserPromptScope.GLOBAL) PROMPT_GLOBAL_PREFIX else PROMPT_PROJECT_PREFIX, id)

@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    onMarkHelpRead: () -> Unit,
    onOpenFragment: (PromptFragmentRepository.Fragment) -> Unit,
    onOpenPrompt: (UserPrompt, UserPromptScope) -> Unit,
    onDeletePrompt: (UserPrompt, UserPromptScope) -> Unit
) {
    // 首次进入先读使用说明：读完（点确认）才放行，与「容器与镜像」页的说明门槛一致
    if (!state.helpRead) {
        PromptsHelpGate(onConfirm = onMarkHelpRead)
        return
    }

    val promptsViewModel: PromptsViewModel = hiltViewModel()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // onMove 的 from/to 是含说明文案、分组标题、内置片段行的全局下标，
        // 这里用带分组前缀的 item key 反查身份：前缀不一致就是跨组；组内下标交给 VM 在自己列表里定位。
        val scope = when {
            itemIdOf(from.key, PROMPT_GLOBAL_PREFIX) != null -> UserPromptScope.GLOBAL
            itemIdOf(from.key, PROMPT_PROJECT_PREFIX) != null -> UserPromptScope.PROJECT
            else -> return@rememberReorderableLazyListState
        }
        val prefix = if (scope == UserPromptScope.GLOBAL) PROMPT_GLOBAL_PREFIX else PROMPT_PROJECT_PREFIX
        val moved = itemIdOf(from.key, prefix) ?: return@rememberReorderableLazyListState
        val target = itemIdOf(to.key, prefix) ?: return@rememberReorderableLazyListState
        promptsViewModel.reorderPrompts(scope, moved, target)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        // 各组之间的间距原本由 Column 的 spacedBy(sm) 提供，换成 LazyColumn 后按项补回来，
        // 同组内的行不加间距，才能保持「一组连成一块卡片」。
        item(key = PROMPT_HINT_KEY) {
            Box(modifier = Modifier.animateItem().padding(bottom = Spacing.sm)) {
                Text(
                    text = stringResource(R.string.prompts_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
                )
            }
        }

        item(key = PROMPT_HEADER_DEFAULT_KEY) {
            Box(modifier = Modifier.animateItem().padding(top = Spacing.sm, bottom = Spacing.sm)) {
                SettingsGroupHeader(text = stringResource(R.string.prompts_group_default))
            }
        }
        // 固定行与「高级设置」的清单走同一个入口：都是打开片段编辑页，不再分两套。
        // 片段读不出来（内置资源缺失）时不展示这行，避免点进去是个空编辑器。
        state.defaultFragment?.let { fragment ->
            item(key = PROMPT_DEFAULT_ROW_KEY) {
                SettingsGroup(modifier = Modifier.animateItem()) {
                    PromptRow(
                        title = stringResource(R.string.prompts_default_title),
                        subtitle = stringResource(
                            if (fragment.isOverridden) R.string.prompts_state_overridden
                            else R.string.prompts_state_builtin
                        ),
                        onClick = { onOpenFragment(fragment) },
                        trailing = { PromptStateBadge(overridden = fragment.isOverridden) }
                    )
                }
            }
        }

        item(key = PROMPT_HEADER_GLOBAL_KEY) {
            Box(modifier = Modifier.animateItem().padding(top = Spacing.sm, bottom = Spacing.sm)) {
                SettingsGroupHeader(text = stringResource(R.string.perm_global))
            }
        }
        if (state.globalPrompts.isEmpty()) {
            item(key = PROMPT_EMPTY_GLOBAL_KEY) {
                SettingsGroup(modifier = Modifier.animateItem()) {
                    PromptEmptyHint(stringResource(R.string.prompts_empty))
                }
            }
        } else {
            itemsIndexed(
                items = state.globalPrompts,
                key = { _, prompt -> promptItemKey(UserPromptScope.GLOBAL, prompt.id) }
            ) { index, prompt ->
                ReorderableCardRow(
                    state = reorderableState,
                    key = promptItemKey(UserPromptScope.GLOBAL, prompt.id),
                    isFirst = index == 0,
                    isLast = index == state.globalPrompts.lastIndex,
                    dragLabel = "promptGlobalDrag"
                ) { dragModifier ->
                    PromptRow(
                        title = prompt.name,
                        subtitle = promptLabel(prompt),
                        onClick = { onOpenPrompt(prompt, UserPromptScope.GLOBAL) },
                        onDelete = { onDeletePrompt(prompt, UserPromptScope.GLOBAL) },
                        dragModifier = dragModifier
                    )
                }
            }
        }

        item(key = PROMPT_HEADER_PROJECT_KEY) {
            Box(modifier = Modifier.animateItem().padding(top = Spacing.sm, bottom = Spacing.sm)) {
                SettingsGroupHeader(text = stringResource(R.string.skills_scope_project))
            }
        }
        if (state.projectPrompts.isEmpty()) {
            item(key = PROMPT_EMPTY_PROJECT_KEY) {
                SettingsGroup(modifier = Modifier.animateItem()) {
                    PromptEmptyHint(
                        stringResource(
                            if (state.hasWorkspace) R.string.prompts_empty
                            else R.string.prompts_no_workspace
                        )
                    )
                }
            }
        } else {
            itemsIndexed(
                items = state.projectPrompts,
                key = { _, prompt -> promptItemKey(UserPromptScope.PROJECT, prompt.id) }
            ) { index, prompt ->
                ReorderableCardRow(
                    state = reorderableState,
                    key = promptItemKey(UserPromptScope.PROJECT, prompt.id),
                    isFirst = index == 0,
                    isLast = index == state.projectPrompts.lastIndex,
                    dragLabel = "promptProjectDrag"
                ) { dragModifier ->
                    PromptRow(
                        title = prompt.name,
                        subtitle = promptLabel(prompt),
                        onClick = { onOpenPrompt(prompt, UserPromptScope.PROJECT) },
                        onDelete = { onDeletePrompt(prompt, UserPromptScope.PROJECT) },
                        dragModifier = dragModifier
                    )
                }
            }
        }
    }
}

/**
 * 右上角「+」弹层：三项入口（添加提示词 / 高级设置 / 帮助）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptsAddSheet(
    onDismiss: () -> Unit,
    onAddPrompt: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.prompts_add_sheet_title))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.prompts_add_prompt),
                    subtitle = stringResource(R.string.prompts_add_prompt_hint),
                    onClick = onAddPrompt
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Sliders,
                    title = stringResource(R.string.prompts_advanced),
                    subtitle = stringResource(R.string.prompts_advanced_hint),
                    onClick = onAdvanced
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Info,
                    title = stringResource(R.string.prompts_help),
                    subtitle = stringResource(R.string.prompts_help_hint),
                    onClick = onHelp
                )
            }
        }
    }
}

/**
 * 高级设置：官方文档要点摘要 + 「完全禁用内置提示词」开关 + 内置片段清单。
 *
 * 清单里每一条都可点开编辑，改动落盘为 `prompts.custom/` 下的覆盖副本，App 自带文件永不被改。
 * 摘要是内置文本（不联网），官方文档更新后需随 App 发版更新。
 */
@Composable
internal fun PromptsAdvancedSection(
    builtinDisabled: Boolean,
    fragments: List<PromptFragmentRepository.Fragment>,
    onToggleBuiltinDisabled: (Boolean) -> Unit,
    onOpenFragment: (PromptFragmentRepository.Fragment) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.prompts_advanced_doc_title))
        SettingsGroup {
            Text(
                text = stringResource(R.string.prompts_advanced_doc_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.lg)
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_advanced_switch_title))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_disable_builtin),
                subtitle = stringResource(R.string.prompts_disable_builtin_hint),
                trailing = {
                    AppSwitch(checked = builtinDisabled, onCheckedChange = onToggleBuiltinDisabled)
                }
            )
        }

        // 内置片段清单：每一条都可编辑。用户可改的不再只有固定的 00，而是全部片段。
        SettingsGroupHeader(text = stringResource(R.string.prompts_builtin_list_title))
        Text(
            text = stringResource(R.string.prompts_builtin_list_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
        SettingsGroup {
            fragments.forEachIndexed { index, fragment ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = null,
                    title = "%02d · %s".format(fragment.number, fragment.title),
                    subtitle = stringResource(
                        if (fragment.isOverridden) R.string.prompts_fragment_from_override
                        else R.string.prompts_fragment_from_builtin
                    ),
                    onClick = { onOpenFragment(fragment) },
                    trailing = { PromptStateBadge(overridden = fragment.isOverridden) }
                )
            }
        }
    }
}

/**
 * 片段状态徽章：一眼看出这段内容是不是 App 自带的。
 *
 * 用颜色 + 文字双重区分（不靠颜色单独承载信息），色值取自语义色板，不硬编码。
 */
@Composable
internal fun PromptStateBadge(overridden: Boolean) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (overridden) {
            MaterialTheme.semanticColors.warningContainer
        } else {
            MaterialTheme.semanticColors.mutedSurface
        }
    ) {
        Text(
            text = stringResource(
                if (overridden) R.string.prompts_badge_overridden else R.string.prompts_badge_builtin
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (overridden) {
                MaterialTheme.semanticColors.onWarningContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
        )
    }
}

/** 帮助：内置的简短说明（不联网）。 */
@Composable
internal fun PromptsHelpSection() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.prompts_help))
        SettingsGroup {
            Text(
                text = stringResource(R.string.prompts_help_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.lg)
            )
        }
    }
}

/** 首次进入的使用说明门槛：正文可滚动，读到底后点确认才放行。 */
@Composable
private fun PromptsHelpGate(onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.prompts_help_gate_title))
        SettingsGroup {
            Text(
                text = stringResource(R.string.prompts_help_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.lg)
            )
        }
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_help_gate_confirm),
                onClick = onConfirm
            )
        }
    }
}

/** 单条提示词行：名称 + 注入位置，点击编辑，左滑删除。 */
@Composable
private fun PromptRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    dragModifier: Modifier = Modifier
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.semanticColors.cardSurface)
                .then(dragModifier)
                .padding(start = Spacing.lg, end = Spacing.lg, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            trailing?.invoke()
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (onDelete == null) {
        // 固定内置片段不可删：只做普通可点行（clickable 是 Modifier 扩展，不能当组件用）
        Box(modifier = Modifier.clickable(onClick = onClick)) { row() }
    } else {
        SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) { row() }
    }
}

/** 空态提示（名字带 Prompt 前缀：同包已有 EmptyHint）。 */
@Composable
private fun PromptEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

/** 行副标题：关闭时直说「已关闭」，否则显示注入位置。 */
@Composable
private fun promptLabel(prompt: UserPrompt): String =
    if (!prompt.enabled) {
        stringResource(R.string.prompts_position_off)
    } else {
        when (prompt.position) {
            UserPromptPosition.BEFORE_ALL -> stringResource(R.string.prompts_position_before_all)
            UserPromptPosition.AFTER_SYSTEM -> stringResource(R.string.prompts_position_after_system)
            UserPromptPosition.OFF -> stringResource(R.string.prompts_position_off)
        }
    }
