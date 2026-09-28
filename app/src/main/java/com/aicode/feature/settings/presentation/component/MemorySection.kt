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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText

/**
 * 记忆页：长期记忆自动沉淀开关 + 当前生效的记忆列表（左滑删除）。
 *
 * 全局与项目记忆合并成一份清单、不分栏——用户看到的只是「AI 记住了什么」，
 * 记忆存在哪一侧是实现细节。
 */
@Composable
internal fun MemorySection(
    memories: List<Memory>,
    autoDistillEnabled: Boolean,
    onToggleAutoDistill: (Boolean) -> Unit,
    onOpenDetail: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.memory_auto_distill),
                subtitle = stringResource(R.string.memory_auto_distill_desc),
                trailing = {
                    AppSwitch(
                        checked = autoDistillEnabled,
                        onCheckedChange = onToggleAutoDistill
                    )
                }
            )
        }

        if (memories.isEmpty()) {
            EmptyState()
        } else {
            SettingsGroup {
                memories.forEachIndexed { index, memory ->
                    if (index > 0) SettingsDivider()
                    MemoryRow(memory = memory, onOpenDetail = { onOpenDetail(memory) }, onDelete = { onDelete(memory) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
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
                FeatherIcons.FileText,
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

/** 单条记忆行：图标 + 名称/描述，点击看详情，左滑删除。 */
@Composable
private fun MemoryRow(
    memory: Memory,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    SwipeToDeleteRow(onDelete = onDelete, onClick = onOpenDetail) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.lg, top = 11.dp, bottom = 11.dp),
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
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = memory.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 记忆详情：名称、来源、描述与完整正文。
 *
 * 注入到系统提示词的只有「名称 + 描述」，正文平时看不到，详情页才展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryDetailSheet(
    memory: Memory,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = memory.name)
            SettingsGroup {
                SettingsRow(
                    icon = null,
                    title = memory.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    subtitle = stringResource(
                        if (memory.kind == MemoryKind.PROFILE) R.string.memory_source_auto
                        else R.string.memory_source_manual
                    )
                )
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
        }
    }
}
