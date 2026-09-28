package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.prompt.UserPrompt
import com.aicode.feature.agent.domain.prompt.UserPromptPosition
import com.aicode.feature.agent.domain.prompt.UserPromptScope
import com.aicode.feature.settings.presentation.PromptsUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText

/**
 * 自定义提示词页：顶部一句说明 + 固定内置片段（00）+ 全局/项目两组用户提示词。
 *
 * 用户提示词按「创建顺序」注入，分「最前 / 最后 / 关闭」三种位置，见 [UserPromptPosition]。
 */
@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    onOpenDefaultFragment: () -> Unit,
    onOpenPrompt: (UserPrompt, UserPromptScope) -> Unit,
    onDeletePrompt: (UserPrompt, UserPromptScope) -> Unit
) {
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
                EmptyHint(stringResource(R.string.prompts_empty))
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
                EmptyHint(
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

/** 用户提示词只能软删除（全局/项目都走同一条路）。 */
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
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.width(18.dp)
            )
        }
    }

    if (onDelete == null) {
        // 固定内置片段不可删：只做普通可点行
        androidx.compose.foundation.clickable(onClick = onClick) { row() }
    } else {
        SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) { row() }
    }
}

@Composable
private fun EmptyHint(text: String) {
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
