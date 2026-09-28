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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
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
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Info
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders

/**
 * 编辑目标：区分三种入口。
 *
 * - [isDefaultFragment] = true：编辑固定的内置片段（00），只有正文可改
 * - [prompt] = null：新建用户提示词
 * - 其余：编辑已有用户提示词
 */
internal data class PromptEditTarget(
    val prompt: UserPrompt? = null,
    val scope: UserPromptScope = UserPromptScope.GLOBAL,
    val isDefaultFragment: Boolean = false
)

/**
 * 自定义提示词页：顶部一句说明 + 固定内置片段（00）+ 全局/项目两组用户提示词。
 *
 * 用户提示词按「创建顺序」注入，分「最前 / 最后 / 关闭」三种位置，见 [UserPromptPosition]。
 */
@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    onMarkHelpRead: () -> Unit,
    onOpenDefaultFragment: () -> Unit,
    onOpenPrompt: (UserPrompt, UserPromptScope) -> Unit,
    onDeletePrompt: (UserPrompt, UserPromptScope) -> Unit
) {
    // 首次进入先读使用说明：读完（点确认）才放行，与「容器与镜像」页的说明门槛一致
    if (!state.helpRead) {
        PromptsHelpGate(onConfirm = onMarkHelpRead)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.prompts_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
        )

        SettingsGroupHeader(text = stringResource(R.string.prompts_group_default))
        SettingsGroup {
            PromptRow(
                title = stringResource(R.string.prompts_default_title),
                subtitle = stringResource(
                    if (state.defaultFragmentOverridden) R.string.prompts_state_overridden
                    else R.string.prompts_state_builtin
                ),
                onClick = onOpenDefaultFragment
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.perm_global))
        SettingsGroup {
            if (state.globalPrompts.isEmpty()) {
                PromptEmptyHint(stringResource(R.string.prompts_empty))
            } else {
                state.globalPrompts.forEachIndexed { index, prompt ->
                    if (index > 0) SettingsDivider()
                    PromptRow(
                        title = prompt.name,
                        subtitle = positionLabel(prompt.position),
                        onClick = { onOpenPrompt(prompt, UserPromptScope.GLOBAL) },
                        onDelete = { onDeletePrompt(prompt, UserPromptScope.GLOBAL) }
                    )
                }
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.skills_scope_project))
        SettingsGroup {
            if (state.projectPrompts.isEmpty()) {
                PromptEmptyHint(
                    stringResource(
                        if (state.hasWorkspace) R.string.prompts_empty
                        else R.string.prompts_no_workspace
                    )
                )
            } else {
                state.projectPrompts.forEachIndexed { index, prompt ->
                    if (index > 0) SettingsDivider()
                    PromptRow(
                        title = prompt.name,
                        subtitle = positionLabel(prompt.position),
                        onClick = { onOpenPrompt(prompt, UserPromptScope.PROJECT) },
                        onDelete = { onDeletePrompt(prompt, UserPromptScope.PROJECT) }
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
 * 高级设置：官方文档要点摘要 + 「完全禁用内置提示词」开关。
 *
 * 摘要是内置文本（不联网），官方文档更新后需随 App 发版更新。
 */
@Composable
internal fun PromptsAdvancedSection(
    builtinDisabled: Boolean,
    fragments: List<PromptFragmentRepository.Fragment>,
    onToggleBuiltinDisabled: (Boolean) -> Unit
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

        // 内置片段清单：只读展示。用户可改的只有固定的 00（在上一页），其余放这里供查阅。
        SettingsGroupHeader(text = stringResource(R.string.prompts_builtin_list_title))
        SettingsGroup {
            fragments.forEachIndexed { index, fragment ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = null,
                    title = "%02d · %s".format(fragment.number, fragment.title),
                    subtitle = stringResource(
                        if (fragment.isOverridden) R.string.prompts_state_overridden
                        else R.string.prompts_state_builtin
                    )
                )
            }
        }
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

/** 单条用户提示词行：名称 + 注入位置，点击编辑，左滑删除。 */
@Composable
private fun PromptRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.semanticColors.cardSurface)
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

@Composable
private fun positionLabel(position: UserPromptPosition): String = when (position) {
    UserPromptPosition.BEFORE_ALL -> stringResource(R.string.prompts_position_before_all)
    UserPromptPosition.AFTER_SYSTEM -> stringResource(R.string.prompts_position_after_system)
    UserPromptPosition.OFF -> stringResource(R.string.prompts_position_off)
}
