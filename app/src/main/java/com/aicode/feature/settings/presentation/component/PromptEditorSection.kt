package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
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
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_field_scope))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_scope_global),
                subtitle = stringResource(R.string.prompts_scope_global_hint),
                trailing = {
                    AppSwitch(checked = scope == UserPromptScope.GLOBAL, onCheckedChange = { scope = UserPromptScope.GLOBAL })
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_scope_project),
                subtitle = stringResource(
                    if (hasWorkspace) R.string.prompts_scope_project_hint
                    else R.string.prompts_no_workspace
                ),
                trailing = {
                    AppSwitch(
                        checked = scope == UserPromptScope.PROJECT,
                        onCheckedChange = { if (hasWorkspace) scope = UserPromptScope.PROJECT }
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_field_position))
        SettingsGroup {
            PositionRow(
                label = stringResource(R.string.prompts_position_before_all),
                hint = stringResource(R.string.prompts_position_before_all_hint),
                selected = position == UserPromptPosition.BEFORE_ALL,
                onSelect = { position = UserPromptPosition.BEFORE_ALL }
            )
            SettingsDivider()
            PositionRow(
                label = stringResource(R.string.prompts_position_after_system),
                hint = stringResource(R.string.prompts_position_after_system_hint),
                selected = position == UserPromptPosition.AFTER_SYSTEM,
                onSelect = { position = UserPromptPosition.AFTER_SYSTEM }
            )
            SettingsDivider()
            PositionRow(
                label = stringResource(R.string.prompts_position_off),
                hint = stringResource(R.string.prompts_position_off_hint),
                selected = position == UserPromptPosition.OFF,
                onSelect = { position = UserPromptPosition.OFF }
            )
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

@Composable
private fun PositionRow(
    label: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    SettingsRow(
        icon = null,
        title = label,
        subtitle = hint,
        trailing = {
            AppSwitch(checked = selected, onCheckedChange = { onSelect() })
        }
    )
}
