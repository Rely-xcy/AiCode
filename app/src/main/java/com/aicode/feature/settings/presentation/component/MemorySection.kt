package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import com.aicode.feature.settings.presentation.SessionShortTermState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.FileText
import compose.icons.feathericons.User
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 列表里的固定项与条目 key。LazyColumn 要求同一列表 key 唯一，撞 key 会直接崩；
// 条目 key 只拼作用域前缀 + 名称，不在后面补下标，否则删掉一条后其余行的滑动位移状态会错位到别的条目上。
private const val MEMORY_HEADER_GLOBAL_KEY = "memory_header_global"
private const val MEMORY_HEADER_PROJECT_KEY = "memory_header_project"
private const val MEMORY_SENSING_KEY = "memory_sensing"
private const val MEMORY_EMPTY_KEY = "memory_empty"
private const val MEMORY_GLOBAL_PREFIX = "memory_global_"
private const val MEMORY_PROJECT_PREFIX = "memory_project_"
private const val MEMORY_SENSING_CONTENT_TYPE = "memory-sensing"
private const val MEMORY_HEADER_CONTENT_TYPE = "memory-header"
private const val MEMORY_CARD_CONTENT_TYPE = "memory-card"
private const val MEMORY_EMPTY_CONTENT_TYPE = "memory-empty"

// 微标签的纵向内边距：与 AppStatusPill 的 2dp 对齐，标签高度才不会比同一行的正文高出一截。
private val MicroTagVerticalPadding = 2.dp

private fun memoryItemKey(memory: Memory): String =
    (if (memory.scope == MemoryScope.GLOBAL) MEMORY_GLOBAL_PREFIX else MEMORY_PROJECT_PREFIX) + memory.name

/**
 * 记忆页：单页流式，自上而下是页内搜索、主动感知微卡、全局偏好组、项目约定组。
 *
 * 取消了两级 Tab：状态与清单原本各占一屏，看开关要切一次、看条目再切回来；现在感知开关、治理周期
 * 与会话暂存收进一张微卡，清单紧跟其后，一屏能同时看到两者，也不必再记「刚才在哪个 Tab」。
 *
 * 搜索框留在页内、固定在列表上方而不是顶栏放大镜：顶栏那枚入口会把页面推进二级搜索页，
 * 返回后滚动位置与展开态都丢；页内常驻后筛选只作用在当前列表上，敲字不读盘。
 *
 * 详情改成就地展开：底部弹层要盖住列表、关掉才能看下一条，而记忆正文通常只有几行，
 * 就地展开能边看正文边改。同一时刻只展开一条，避免多条长文本把列表撑成好几屏。
 *
 * 列表仍走 LazyColumn 逐项虚拟化：条目数不可控（实测过上百条），一次性组合全部行会让进页卡顿。
 *
 * @param memories 已按当前关键词过滤后的记忆（来自 MemoryViewModel.visibleMemories）；
 *   本页只负责渲染，不再做第二套匹配，避免两处过滤规则走偏。
 * @param searchQuery 搜索词放在 ViewModel 里，旋转屏幕或从编辑页返回时不丢。
 * @param shortTerm 本次会话的短期上下文规模；null 表示取不到，此时不画「本次会话暂存」那一行。
 */
@Composable
internal fun MemorySection(
    memories: List<Memory>,
    loading: Boolean,
    shortTerm: SessionShortTermState?,
    activeMemoryEnabled: Boolean,
    onToggleActiveMemory: (Boolean) -> Unit,
    curationIntervalHours: Int,
    onSelectCurationInterval: (Int) -> Unit,
    onEdit: (Memory) -> Unit,
    onDelete: (Memory) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    // 展开态按条目 key 记：搜索过滤后重新排布时，展开的仍是同一条记忆，而不是同一下标。
    // 用 rememberSaveable 而不是 remember：进编辑器改完返回时，刚展开的那条应该还开着，
    // 否则回来面对的是一列全部收起的卡片，得重新找。
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 删除走二次确认：左滑与内联按钮都落到这里，确认后才真正回调上层。
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }
    var showCustomIntervalDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // 搜索词一变就把列表拉回顶部：筛选后的结果数量变少，留着旧偏移会直接落到列表末尾，
    // 看起来像「搜不到」。只在关键词变化时归零，不动用户的正常滚动。
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) listState.scrollToItem(0)
    }
    // 只在数据变化时分组，避免每次重组都产生两份新 List。
    val globalMemories = remember(memories) { memories.filter { it.scope == MemoryScope.GLOBAL } }
    val projectMemories = remember(memories) { memories.filter { it.scope == MemoryScope.PROJECT } }
    val searching = searchQuery.isNotBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框在列表外：清单上百条时它必须一直可见，跟着列表滚走就等于没有。
        MemorySearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.padding(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.sm
            )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = MEMORY_SENSING_KEY, contentType = MEMORY_SENSING_CONTENT_TYPE) {
                MemorySensingCard(
                    activeMemoryEnabled = activeMemoryEnabled,
                    onToggleActiveMemory = onToggleActiveMemory,
                    shortTerm = shortTerm,
                    curationIntervalHours = curationIntervalHours,
                    onSelectCurationInterval = onSelectCurationInterval,
                    onOpenCustomInterval = { showCustomIntervalDialog = true }
                )
            }

            if (memories.isEmpty()) {
                item(key = MEMORY_EMPTY_KEY, contentType = MEMORY_EMPTY_CONTENT_TYPE) {
                    when {
                        // 首次读盘未完成时列表必为空，那时不能拿「没搜到」冒充。
                        loading -> MemoryLoadingPlaceholder()
                        searching -> SearchEmptyHint(stringResource(R.string.memory_search_empty))
                        else -> MemoryEmptyState()
                    }
                }
            } else {
                if (globalMemories.isNotEmpty()) {
                    item(key = MEMORY_HEADER_GLOBAL_KEY, contentType = MEMORY_HEADER_CONTENT_TYPE) {
                        MemoryGroupHeader(
                            title = stringResource(R.string.memory_group_global_title),
                            count = globalMemories.size,
                            hint = stringResource(R.string.memory_group_global_hint)
                        )
                    }
                    items(
                        items = globalMemories,
                        key = { memory -> memoryItemKey(memory) },
                        contentType = { MEMORY_CARD_CONTENT_TYPE }
                    ) { memory ->
                        MemoryCard(
                            memory = memory,
                            expanded = expandedKey == memoryItemKey(memory),
                            onToggle = {
                                val key = memoryItemKey(memory)
                                expandedKey = if (expandedKey == key) null else key
                            },
                            onEdit = { onEdit(memory) },
                            onDeleteRequest = { deleteTarget = memory }
                        )
                    }
                }
                if (projectMemories.isNotEmpty()) {
                    item(key = MEMORY_HEADER_PROJECT_KEY, contentType = MEMORY_HEADER_CONTENT_TYPE) {
                        MemoryGroupHeader(
                            title = stringResource(R.string.memory_group_project_title),
                            count = projectMemories.size,
                            hint = stringResource(R.string.memory_group_project_hint)
                        )
                    }
                    items(
                        items = projectMemories,
                        key = { memory -> memoryItemKey(memory) },
                        contentType = { MEMORY_CARD_CONTENT_TYPE }
                    ) { memory ->
                        MemoryCard(
                            memory = memory,
                            expanded = expandedKey == memoryItemKey(memory),
                            onToggle = {
                                val key = memoryItemKey(memory)
                                expandedKey = if (expandedKey == key) null else key
                            },
                            onEdit = { onEdit(memory) },
                            onDeleteRequest = { deleteTarget = memory }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { memory ->
        MemoryDeleteConfirmDialog(
            memory = memory,
            onConfirm = {
                // 被删的那条如果正展开着，收起态没有承载物，顺手清掉，避免下一次同名条目一进来就是展开的。
                if (expandedKey == memoryItemKey(memory)) expandedKey = null
                onDelete(memory)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (showCustomIntervalDialog) {
        CustomCurationIntervalDialog(
            initialHours = curationIntervalHours,
            onConfirm = {
                onSelectCurationInterval(it)
                showCustomIntervalDialog = false
            },
            onDismiss = { showCustomIntervalDialog = false }
        )
    }
}

/** 页内搜索框：全 App 统一的胶囊搜索框，只额外挂一个清除按钮。 */
@Composable
private fun MemorySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val trailing: (@Composable () -> Unit)? = if (query.isEmpty()) {
        null
    } else {
        {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_clear),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.semanticColors.subtleText
                )
            }
        }
    }
    ModelSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.memory_search_hint),
        modifier = modifier,
        trailing = trailing
    )
}

/**
 * 主动感知微卡：标题行（图标 + 说明 + 开关）、会话暂存行、治理周期三块合成一张卡。
 *
 * 治理周期原本在「状态」Tab 里独占一段，取消 Tab 后没地方放；单独再起一屏或一张卡都会把
 * 清单挤下去，所以收进这张卡：三者都是「引擎当前怎么跑」的状态，天然是一组。
 */
@Composable
private fun MemorySensingCard(
    activeMemoryEnabled: Boolean,
    onToggleActiveMemory: (Boolean) -> Unit,
    shortTerm: SessionShortTermState?,
    curationIntervalHours: Int,
    onSelectCurationInterval: (Int) -> Unit,
    onOpenCustomInterval: () -> Unit
) {
    val shape = RoundedCornerShape(Radius.xl)
    val iconShape = RoundedCornerShape(Radius.sm)
    val curationLabel = when (curationIntervalHours) {
        0 -> stringResource(R.string.memory_curation_interval_off_short)
        24 -> stringResource(R.string.memory_curation_interval_1d)
        168 -> stringResource(R.string.memory_curation_interval_7d)
        else -> stringResource(R.string.memory_curation_interval_hours, curationIntervalHours)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // 卡底用 cardSurface + 描边，跟本页其它分组卡同一套语言；
            // 不用页面底色：卡内的 SegmentedTabs 轨道是 mutedSurface，卡底一旦同色，
            // 整枚分段控件就只剩一个白滑块，看不出「这是一组可选项」。
            .background(MaterialTheme.semanticColors.cardSurface)
            .border(1.dp, MaterialTheme.semanticColors.subtleBorder, shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(Spacing.xxl)
                    // 图标块反过来用 mutedSurface：卡底是白的，这里再填白就看不出是个图标格。
                    .background(MaterialTheme.semanticColors.mutedSurface, iconShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Activity,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.memory_sensing_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.memory_sensing_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            AppSwitch(
                checked = activeMemoryEnabled,
                onCheckedChange = onToggleActiveMemory
            )
        }

        if (shortTerm != null) {
            HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.memory_sensing_session_hint,
                        shortTerm.retainedMessages
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                MemoryQuietTag(text = stringResource(R.string.memory_sensing_non_persistent))
            }
        }

        HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.memory_curation_interval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = curationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        SegmentedTabs(
            selected = curationIntervalIndex(curationIntervalHours),
            labels = listOf(
                stringResource(R.string.memory_curation_interval_off_short),
                stringResource(R.string.memory_curation_interval_1d),
                stringResource(R.string.memory_curation_interval_7d),
                stringResource(R.string.memory_curation_interval_custom)
            ),
            onSelect = { index ->
                when (index) {
                    0 -> onSelectCurationInterval(0)
                    1 -> onSelectCurationInterval(24)
                    2 -> onSelectCurationInterval(168)
                    else -> onOpenCustomInterval()
                }
            }
        )
    }
}

/** 分组头：标题 + 计数徽章 + 右侧作用域说明，说明用灰字，不与标题抢注意力。 */
@Composable
private fun MemoryGroupHeader(title: String, count: Int, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(Spacing.xs))
        MemoryCountBadge(count = count)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 计数徽章：跟着分组标题走，让「这一组有几条」不必靠数行数。 */
@Composable
private fun MemoryCountBadge(count: Int) {
    MemoryMicroTag(
        text = count.toString(),
        textColor = MaterialTheme.semanticColors.subtleText,
        backgroundColor = MaterialTheme.semanticColors.mutedSurface
    )
}

/** 「非持久」小标签：会话暂存只是标签，不承载点击，所以不做成胶囊按钮。 */
@Composable
private fun MemoryQuietTag(text: String) {
    MemoryMicroTag(
        text = text,
        textColor = MaterialTheme.semanticColors.subtleText,
        backgroundColor = MaterialTheme.semanticColors.cardSurface,
        borderColor = MaterialTheme.semanticColors.subtleBorder
    )
}

/** 「置顶」小标签：品牌色系，与条目标题同色，一眼能看出这条被钉住了。 */
@Composable
private fun MemoryPinnedTag() {
    MemoryMicroTag(
        text = stringResource(R.string.memory_pinned),
        textColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    )
}

/** 微标签统一形状：4dp 小圆角，与卡片 16dp / 内块 8dp 分档，不跟卡片抢圆角。 */
@Composable
private fun MemoryMicroTag(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color? = null
) {
    val shape = RoundedCornerShape(Radius.xs)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderColor == null) {
                    Modifier
                } else {
                    Modifier.border(1.dp, borderColor, shape)
                }
            )
            .padding(horizontal = Spacing.xs, vertical = MicroTagVerticalPadding)
    )
}

/**
 * 单条记忆卡：左侧类型图标 + 名称/摘要，右侧置顶标签与折叠箭头；点击就地展开正文与编辑/删除。
 *
 * 展开态不弹层，是因为详情弹层要盖住整个列表：看完一条想对照下一条得先关掉再找位置，
 * 而记忆正文通常只有几行，就地展开的阅读成本更低。
 */
@Composable
private fun MemoryCard(
    memory: Memory,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val cardShape = RoundedCornerShape(Radius.xl)
    val innerShape = RoundedCornerShape(Radius.sm)
    val iconShape = RoundedCornerShape(Radius.sm)
    val borderColor = when {
        expanded -> MaterialTheme.colorScheme.primary
        // 置顶只加一层品牌色描边：整卡铺色会把「可点区域」和「选中态」混在一起。
        memory.pinned -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.semanticColors.subtleBorder
    }

    SwipeToDeleteRow(
        onDelete = onDeleteRequest,
        onClick = onToggle,
        contentShape = cardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, cardShape)
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, iconShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        // 画像与笔记是两种东西：自动沉淀出的用户画像要一眼认出来。
                        imageVector = if (memory.kind == MemoryKind.PROFILE) {
                            FeatherIcons.User
                        } else {
                            FeatherIcons.FileText
                        },
                        contentDescription = null,
                        tint = if (memory.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.sm))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = memory.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (memory.pinned) {
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            MemoryPinnedTag()
                        }
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        ExpandableChevronIcon(
                            expanded = expanded,
                            style = ChevronRotationStyle.RIGHT_DOWN,
                            size = 16.dp,
                            tint = if (expanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.semanticColors.subtleText
                            }
                        )
                    }
                    Text(
                        text = memory.description.ifBlank {
                            stringResource(R.string.mcp_no_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        // 展开时摘要转成正文前的引子：提一档对比度，并放开单行截断。
                        color = if (expanded) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.semanticColors.subtleText
                        },
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 200),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 150)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 180),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Column(
                    modifier = Modifier.padding(top = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(innerShape)
                            .background(MaterialTheme.semanticColors.pageBackground)
                            .border(1.dp, MaterialTheme.semanticColors.subtleBorder, innerShape)
                            .padding(horizontal = Spacing.md, vertical = Spacing.md)
                    ) {
                        Text(
                            text = memory.content.ifBlank {
                                stringResource(R.string.memory_detail_empty)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = memoryUpdatedAtText(memory),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // 行尾两个文字按钮收紧左右内边距：默认 12dp 会在 320dp 窄屏上
                        // 把「更新于 …」压成省略号，而这两颗按钮的文字只有两个字。
                        TextButton(
                            onClick = onEdit,
                            contentPadding = PaddingValues(horizontal = Spacing.sm)
                        ) {
                            Text(
                                text = stringResource(R.string.common_edit),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(
                            onClick = onDeleteRequest,
                            contentPadding = PaddingValues(horizontal = Spacing.sm)
                        ) {
                            Text(
                                text = stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「更新于 X · 全局/项目」：正文看过之后最想知道的就是它有多新、在哪个作用域生效。 */
@Composable
private fun memoryUpdatedAtText(memory: Memory): String {
    val updatedAt = maxOf(memory.lastHitAt, memory.createdAt)
    val scopeText = stringResource(
        if (memory.scope == MemoryScope.PROJECT) {
            R.string.memory_scope_project
        } else {
            R.string.memory_scope_global
        }
    )
    return if (updatedAt > 0) {
        stringResource(R.string.memory_detail_updated_at, relativeTimeText(updatedAt), scopeText)
    } else {
        scopeText
    }
}

/** 删除记忆的二次确认框：左滑与内联按钮共用，条目名统一取 memory.name。 */
@Composable
private fun MemoryDeleteConfirmDialog(
    memory: Memory,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_delete_confirm_title)) },
        text = { Text(stringResource(R.string.memory_delete_confirm_message, memory.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 首次读盘时的占位：不是「没有记忆」，所以不套空态图标与引导文案。 */
@Composable
private fun MemoryLoadingPlaceholder() {
    Text(
        text = stringResource(R.string.memory_loading),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    )
}

/** 一条记忆都没有：页内空态，与技能页 / MCP 页同一套图标尺寸、文字层级与留白。 */
@Composable
private fun MemoryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 24 是给那条长提示的：不留白时它会顶到屏幕两侧
            .padding(horizontal = Spacing.xl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.xl)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.FileText,
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

/**
 * 「最后命中」的相对时间：超过 30 天回落到绝对日期——「87 天前」还得自己心算。
 */
@Composable
private fun relativeTimeText(epochMillis: Long): String {
    val elapsed = System.currentTimeMillis() - epochMillis
    return when {
        elapsed < 60_000L -> stringResource(R.string.memory_time_just_now)
        elapsed < 3_600_000L ->
            stringResource(R.string.memory_time_minutes_ago, (elapsed / 60_000L).toInt())
        elapsed < 86_400_000L ->
            stringResource(R.string.memory_time_hours_ago, (elapsed / 3_600_000L).toInt())
        elapsed < 30 * 86_400_000L ->
            stringResource(R.string.memory_time_days_ago, (elapsed / 86_400_000L).toInt())
        else -> formatEpochDate(epochMillis)
    }
}

/** 绝对日期：与调用统计页的时间格式同源（yyyy/M/d）。 */
private fun formatEpochDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(epochMillis))

/** 治理周期的三档预设：关闭 / 1 天 / 7 天；其余值一律算「自定义」。 */
private val CURATION_PRESET_HOURS = listOf(0, 24, 168)

/** 「自定义」胶囊的下标（排在三个预设之后）。 */
private const val CURATION_CUSTOM_INDEX = 3

/** 当前值对应哪一枚胶囊：命中预设就用它的下标，其余值（含旧的 6h/12h/3d）算自定义。 */
private fun curationIntervalIndex(hours: Int): Int =
    CURATION_PRESET_HOURS.indexOf(hours).takeIf { it >= 0 } ?: CURATION_CUSTOM_INDEX

/**
 * 自定义治理周期：输入小时数（0 = 关闭，上限沿用仓库的 720）。
 *
 * 不合法时禁用确认按钮——超范围的值写进 DataStore 会被静默夹紧，用户却以为已生效。
 */
@Composable
private fun CustomCurationIntervalDialog(
    initialHours: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(if (initialHours > 0) initialHours.toString() else "") }
    val hours = text.trim().toIntOrNull()
    val valid = hours != null && hours in
        MemorySettingsRepository.MIN_CURATION_INTERVAL_HOURS..MemorySettingsRepository.MAX_CURATION_INTERVAL_HOURS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_curation_interval_custom_title)) },
        text = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = stringResource(R.string.memory_curation_interval_custom_label)
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.memory_curation_interval_custom_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { hours?.let(onConfirm) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
