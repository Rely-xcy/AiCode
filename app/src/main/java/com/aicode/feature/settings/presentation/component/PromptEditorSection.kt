package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.prompt.UserPromptPosition
import com.aicode.feature.agent.domain.prompt.UserPromptScope

/**
 * 添加 / 编辑用户提示词（照新建子代理页，但砍到只剩四样）：
 * 名称、作用域、注入位置、Agents 提示词正文。
 *
 * 没有用途描述、模型、工具集——用户提示词不需要这些。
 */
@Composable
internal fun PromptEditorSection(
    isNew: Boolean,
    initialName: String,
    initialScope: UserPromptScope,
    initialPosition: UserPromptPosition,
    initialContent: String,
    hasWorkspace: Boolean,
    onSave: (name: String, scope: UserPromptScope, position: UserPromptPosition, content: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var scope by remember { mutableStateOf(initialScope) }
    var position by remember { mutableStateOf(initialPosition) }
    var content by remember { mutableStateOf(initialContent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.prompts_field_basic))
        SettingsGroup {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.prompts_field_name),
                    placeholder = stringResource(R.string.prompts_field_name_placeholder)
                )
            }
            SettingsDivider()
            // 作用域：与「新建子代理」页同一种胶囊控件，保持全 App 一致
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.prompts_field_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = scope == UserPromptScope.PROJECT,
                    enabled = hasWorkspace,
                    onClick = { scope = UserPromptScope.PROJECT },
                    label = { Text(stringResource(R.string.subagent_scope_project)) }
                )
                FilterChip(
                    selected = scope == UserPromptScope.GLOBAL,
                    onClick = { scope = UserPromptScope.GLOBAL },
                    label = { Text(stringResource(R.string.subagent_scope_global)) }
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_field_position))
        SettingsGroup {
            // 注入位置：同样是胶囊控件（与作用域一致）
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                FilterChip(
                    selected = position == UserPromptPosition.BEFORE_ALL,
                    onClick = { position = UserPromptPosition.BEFORE_ALL },
                    label = { Text(stringResource(R.string.prompts_position_short_before_all)) }
                )
                FilterChip(
                    selected = position == UserPromptPosition.AFTER_SYSTEM,
                    onClick = { position = UserPromptPosition.AFTER_SYSTEM },
                    label = { Text(stringResource(R.string.prompts_position_short_after_system)) }
                )
                FilterChip(
                    selected = position == UserPromptPosition.OFF,
                    onClick = { position = UserPromptPosition.OFF },
                    label = { Text(stringResource(R.string.prompts_position_short_off)) }
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_field_content))
        SettingsGroup {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    singleLine = false,
                    label = stringResource(R.string.prompts_field_content),
                    placeholder = stringResource(R.string.prompts_field_content_placeholder)
                )
            }
        }

        Text(
            text = stringResource(R.string.prompts_save_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        // 保存动作放行内按钮，避免和外壳顶栏动作槽抢位置（与技能/子代理编辑页一致的做法）
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(if (isNew) R.string.prompts_action_add else R.string.prompts_action_save),
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(name.trim(), scope, position, content)
                }
            )
        }
    }
}

/**
 * 固定内置片段（默认 00 身份/总纲）的编辑页：只能改正文，可恢复内置默认。
 *
 * 修改落盘为 prompts.custom 下的覆盖文件，与手工放文件等价——内置文件本身永不被改。
 */
@Composable
internal fun FragmentEditorSection(
    initialContent: String,
    overridden: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.prompts_default_title))
        SettingsGroup {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    singleLine = false,
                    label = stringResource(R.string.prompts_field_content)
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_action_save),
                enabled = content.isNotBlank(),
                onClick = { onSave(content) }
            )
            if (overridden) {
                SettingsDivider()
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.prompts_action_reset),
                    subtitle = stringResource(R.string.prompts_action_reset_hint),
                    onClick = onReset
                )
            }
        }

        Text(
            text = stringResource(R.string.prompts_fragment_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.sm)
        )
    }
}
