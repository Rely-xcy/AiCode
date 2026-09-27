package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.aicode.feature.agent.domain.prompt.SystemPromptMode
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.settings.domain.service.PromptFragmentInfo
import com.aicode.feature.settings.presentation.PromptRulesViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 官方提示词文档（页面里有完整说明，App 内也给出离线要点）。 */
private const val OFFICIAL_PROMPT_DOCS_URL = "https://aicode.murk.top/guide/custom-prompts"

/**
 * 提示词页：说明门槛 → 系统提示词编辑 → 高级（片段级自定义）。
 *
 * 主入口只开放「系统提示词」一项：它作为内置提示词之上的注入层生效，内置的 9 个静态片段
 * 一个都不暴露——避免用户覆盖掉 `60-tools-and-paths.md` 这类片段后，App 升级导致
 * AI 看到的工具定义与实际不一致。片段级自定义（有文件名规则、需要手动维护）收在高级页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptRulesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PromptRulesViewModel = hiltViewModel()
) {
    val fragments by viewModel.fragments.collectAsStateWithLifecycle()
    val docsRead by viewModel.docsRead.collectAsStateWithLifecycle()
    val docs by viewModel.docs.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 导入：超限在 ViewModel 里被拒（不截断）；读不出来只提示，不动当前内容。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            if (text == null) {
                viewModel.showMessage(context.getString(R.string.prompt_rules_sys_import_failed))
            } else {
                viewModel.importSystemPromptBody(text)
            }
        }
    }

    val editorDirty = editor?.isDirty == true
    val systemDirty = editor == null && systemPrompt?.isDirty == true

    // 未保存守卫：只有真的改了东西才拦；否则直接退。
    BackHandler(enabled = editor != null || showAdvanced || editorDirty || systemDirty) {
        when {
            editorDirty -> confirmDiscard = true
            editor != null -> viewModel.closeEditor()
            showAdvanced -> showAdvanced = false
            systemDirty -> confirmDiscard = true
            else -> onNavigateBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            editor != null -> if (editor!!.isNew) {
                                stringResource(R.string.prompt_rules_new_title)
                            } else {
                                editor!!.fragment.title
                            }

                            showAdvanced -> stringResource(R.string.prompt_rules_advanced)
                            else -> stringResource(R.string.prompt_rules_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            editorDirty -> confirmDiscard = true
                            editor != null -> viewModel.closeEditor()
                            showAdvanced -> showAdvanced = false
                            systemDirty -> confirmDiscard = true
                            else -> onNavigateBack()
                        }
                    }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 三条杠菜单只在主页面（系统提示词）出现；编辑片段/高级页用自己的返回逻辑
                    if (editor == null && !showAdvanced && docsRead == true && systemPrompt != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    FeatherIcons.Menu,
                                    contentDescription = stringResource(R.string.prompt_rules_menu)
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val state = systemPrompt!!
                                Text(
                                    text = stringResource(R.string.prompt_rules_sys_mode_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                )
                                listOf(
                                    SystemPromptMode.OFF,
                                    SystemPromptMode.PREPEND,
                                    SystemPromptMode.SUFFIX
                                ).forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(modeTitleRes(mode)),
                                                color = if (mode == state.mode) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setSystemPromptMode(mode)
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.prompt_rules_sys_import)) },
                                    onClick = {
                                        menuOpen = false
                                        importLauncher.launch("text/*")
                                    }
                                )
                                if (state.hasCustom) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.prompt_rules_sys_restore)) },
                                        onClick = {
                                            menuOpen = false
                                            confirmRestore = true
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.prompt_rules_advanced)) },
                                    onClick = {
                                        menuOpen = false
                                        showAdvanced = true
                                    }
                                )
                            }
                        }
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

                docsRead == false -> PromptDocsGate(
                    docs = docs,
                    onConfirm = viewModel::confirmDocsRead
                )

                editor != null -> PromptFragmentEditor(
                    state = editor!!,
                    onContentChange = viewModel::updateContent,
                    onSave = viewModel::save,
                    onDeleteCustom = viewModel::deleteCustom
                )

                showAdvanced -> PromptFragmentList(
                    fragments = fragments,
                    onOpen = viewModel::open,
                    onNew = viewModel::openNew
                )

                systemPrompt == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> SystemPromptEditor(
                    state = systemPrompt!!,
                    onBodyChange = viewModel::updateSystemPromptBody,
                    onSave = viewModel::saveSystemPrompt
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
                    if (editor != null) viewModel.closeEditor() else viewModel.loadSystemPrompt()
                }) { Text(stringResource(R.string.prompt_rules_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.prompt_rules_sys_restore_title)) },
            text = { Text(stringResource(R.string.prompt_rules_sys_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    viewModel.restoreSystemPrompt()
                }) { Text(stringResource(R.string.prompt_rules_sys_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 系统提示词编辑器：一级界面只留正文与保存，注入位置/导入/恢复默认/高级都收在右上角三条杠菜单里。 */
@Composable
private fun SystemPromptEditor(
    state: PromptRulesViewModel.SystemPromptState,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.prompt_rules_sys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.prompt_rules_sys_mode_title) + "：" +
                stringResource(modeTitleRes(state.mode)) +
                "（" + stringResource(modeDescRes(state.mode)) + "）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AppTextField(
            value = state.body,
            onValueChange = onBodyChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            label = stringResource(R.string.prompt_rules_sys_body_label),
            singleLine = false
        )

        if (state.isOverLimit) {
            Text(
                text = stringResource(
                    R.string.prompt_rules_sys_over_limit,
                    com.aicode.feature.agent.domain.prompt.CustomSystemPromptStore.BODY_CHAR_LIMIT
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = onSave,
            enabled = state.isDirty && !state.isOverLimit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.prompt_rules_sys_save))
        }
    }
}

private fun modeTitleRes(mode: SystemPromptMode): Int = when (mode) {
    SystemPromptMode.OFF -> R.string.prompt_rules_sys_mode_off
    SystemPromptMode.PREPEND -> R.string.prompt_rules_sys_mode_prepend
    SystemPromptMode.SUFFIX -> R.string.prompt_rules_sys_mode_suffix
}

private fun modeDescRes(mode: SystemPromptMode): Int = when (mode) {
    SystemPromptMode.OFF -> R.string.prompt_rules_sys_mode_off_desc
    SystemPromptMode.PREPEND -> R.string.prompt_rules_sys_mode_prepend_desc
    SystemPromptMode.SUFFIX -> R.string.prompt_rules_sys_mode_suffix_desc
}

/** 使用说明门槛：正文是 App 内置的官方文档，可滚动；确认按钮在滚动内容末尾，必须读到底才能点到。 */
@Composable
private fun PromptDocsGate(
    docs: PromptRulesViewModel.DocsUiState,
    onConfirm: () -> Unit
) {
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

        when (docs) {
            PromptRulesViewModel.DocsUiState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is PromptRulesViewModel.DocsUiState.Ready -> {
                MarkdownContent(
                    text = docs.text,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            PromptRulesViewModel.DocsUiState.Unavailable -> {
                Text(
                    text = stringResource(R.string.prompt_rules_docs_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.prompt_rules_docs_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

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

/** 高级页片段清单：内置 9 个（标注是否已覆盖）+ 自定义新增片段。 */
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
                        if (fragment.hasCustom) {
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

/** 片段编辑器：内置片段可覆盖/恢复默认，新增片段填名称。 */
@Composable
private fun PromptFragmentEditor(
    state: PromptRulesViewModel.EditorState,
    onContentChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDeleteCustom: () -> Unit
) {
    var name by rememberSaveable(state.fragment.number) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = if (state.isNew) {
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
            singleLine = false
        )

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
