package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.domain.service.PromptFragmentInfo
import com.aicode.feature.settings.presentation.PromptRulesViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus

/** 官方提示词文档（与 App 内置文档同源）。 */
private const val OFFICIAL_PROMPT_DOCS_URL = "https://aicode.murk.top/guide/custom-prompts"

/**
 * 自定义提示词页：说明门槛 → 片段清单 → 编辑。
 *
 * 首次进入强制先读使用说明（读完才解锁编辑），因为自定义提示词会整体替换内置片段，
 * 改错会让 Agent 行为异常甚至丢掉安全边界；关键片段（身份、安全）只读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptRulesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PromptRulesViewModel = hiltViewModel()
) {
    val fragments by viewModel.fragments.collectAsStateWithLifecycle()
    val docsRead by viewModel.docsRead.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = editor != null) {
        if (editor?.isDirty == true) confirmDiscard = true else viewModel.closeEditor()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = editor?.let { state ->
                            if (state.isNew) stringResource(R.string.prompt_rules_new_title)
                            else state.fragment.title
                        } ?: stringResource(R.string.prompt_rules_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            editor?.isDirty == true -> confirmDiscard = true
                            editor != null -> viewModel.closeEditor()
                            else -> onNavigateBack()
                        }
                    }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                docsRead == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                docsRead == false -> PromptDocsGate(onConfirm = viewModel::confirmDocsRead)

                editor != null -> PromptFragmentEditor(
                    state = editor!!,
                    onContentChange = viewModel::updateContent,
                    onSave = viewModel::save,
                    onDeleteCustom = viewModel::deleteCustom
                )

                else -> PromptFragmentList(
                    fragments = fragments,
                    onOpen = viewModel::open,
                    onNew = viewModel::openNew
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.prompt_rules_discard_title)) },
            text = { Text(stringResource(R.string.prompt_rules_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.closeEditor()
                }) { Text(stringResource(R.string.prompt_rules_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 使用说明门槛：正文可滚动，确认按钮放在滚动内容末尾，必须读到底才能点到。 */
@Composable
private fun PromptDocsGate(onConfirm: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.prompt_rules_docs_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.prompt_rules_docs_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_PROMPT_DOCS_URL)))
            }
        }) { Text(stringResource(R.string.prompt_rules_open_official)) }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.prompt_rules_docs_confirm))
        }
    }
}

/** 片段清单：内置 9 个（标注是否已覆盖 / 是否关键）+ 自定义新增片段。 */
@Composable
private fun PromptFragmentList(
    fragments: List<PromptFragmentInfo>,
    onOpen: (PromptFragmentInfo) -> Unit,
    onNew: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.prompt_rules_list_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )
        SettingsGroup {
            fragments.forEachIndexed { index, fragment ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    title = "${String.format("%02d", fragment.number)} · ${fragment.title}",
                    subtitle = buildString {
                        append(fragment.subtitle)
                        if (fragment.isProtected) {
                            append(" · ")
                            append(stringResource(R.string.prompt_rules_protected_tag))
                        } else if (fragment.hasCustom) {
                            append(" · ")
                            append(stringResource(R.string.prompt_rules_custom_tag))
                        }
                    },
                    onClick = { onOpen(fragment) }
                )
            }
        }
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.prompt_rules_new),
                onClick = onNew
            )
        }
    }
}

/** 片段编辑器：内置片段可覆盖/恢复默认，新增片段填名称；关键片段只读。 */
@Composable
private fun PromptFragmentEditor(
    state: PromptRulesViewModel.EditorState,
    onContentChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDeleteCustom: () -> Unit
) {
    var name by rememberSaveable(state.fragment.number) { mutableStateOf("") }
    val protected = state.fragment.isProtected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = if (protected) {
                stringResource(R.string.prompt_rules_protected_hint)
            } else if (state.isNew) {
                stringResource(R.string.prompt_rules_new_hint, String.format("%02d", state.fragment.number))
            } else {
                stringResource(R.string.prompt_rules_override_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.isNew) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.prompt_rules_name_label)
            )
        }

        AppTextField(
            value = state.content,
            onValueChange = onContentChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            label = stringResource(R.string.prompt_rules_content_label),
            singleLine = false,
            readOnly = protected
        )

        if (!protected) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = { onSave(name) }, enabled = state.isDirty) {
                    Text(stringResource(R.string.prompt_rules_save))
                }
                if (state.fragment.hasCustom && !state.isNew) {
                    TextButton(onClick = onDeleteCustom) {
                        Text(stringResource(R.string.prompt_rules_restore_builtin))
                    }
                }
            }
        }
    }
}
