package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search
import compose.icons.feathericons.X

/**
 * 设置页分组组件：浅灰背景 + 白色分组圆角卡片 + 组内缩进分隔线 + 黑色线条图标。
 *
 * 用法：
 * ```
 * SettingsGroupHeader("分组标题")
 * SettingsGroup {
 *     SettingsRow(icon, title, onClick = { ... })
 *     SettingsDivider()
 *     SettingsRow(icon, title, trailing = { AppSwitch(...) })
 * }
 * ```
 */

/** 当前是否浅色模式（据此切换浅灰/主题深色配色）。 */
@Composable
internal fun settingsLightMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() > 0.5f

/** 可折叠分组标题：点击展开/收起，右侧 chevron 指示状态。与工具授权页的分组一致。 */
@Composable
internal fun CollapsibleGroupHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            size = 16.dp,
            tint = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** 设置页背景：统一使用语义定义的 pageBackground。 */
@Composable
internal fun settingsPageBackground(): Color =
    MaterialTheme.semanticColors.pageBackground

/** 分组小标题：卡片上方灰色小字，左对齐。 */
@Composable
internal fun SettingsGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.lg, bottom = Spacing.sm)
    )
}

/** 白色/深色分组圆角卡片容器：内部按行排布，行间用 [SettingsDivider] 分隔。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

/**
 * 分组内单行：左侧线条图标 + 标题，右侧可选尾随组件（右箭头/开关/值）。
 *
 * @param icon 左侧图标，null 则标题与无图标行对齐。
 * @param trailing 右侧尾随内容（如 [com.aicode.core.ui.AppSwitch]、chevron）。
 * @param onClick null 表示无点击行为（如开关行）；非 null 时行尾自动显示右箭头。
 * @param subtitleMaxLines 副标题行数上限。默认不限（副标题本来该有多长就多长）；
 *   取值同时很长时传 1，副标题不再换行，否则换行后的第二行会与右侧取值视觉上连成一句。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null && enabled) it.clickable { onClick() } else it }
        .padding(horizontal = Spacing.lg, vertical = 11.dp)
    Row(
        modifier = baseModifier.alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke(this)
        if (onClick != null && enabled) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 分组内行间分隔线：左右两端均缩进对齐行内容，末行不显示。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        thickness = 0.5.dp,
        color = MaterialTheme.semanticColors.subtleBorder
    )
}

/** iOS 风格搜索框：浅灰胶囊背景、无边框，与设置页分组风格一致。
 *  [trailing] 为可选尾部控件（如清除按钮）。
 *  [focusRequester] 非空时挂到内部输入框上：搜索二级页要在进入时把焦点交给它，
 *  入口上的 Surface 不能聚焦，必须落到真正的 BasicTextField 节点。 */
@Composable
internal fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val isLight = settingsLightMode()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.semanticColors.capsuleSurface
    ) {
        Row(
            // 行高固定：尾部控件（清除按钮的 IconButton 自带 48dp 最小触摸目标）比正文行高更高，
            // 若由内容撑高，有无尾部控件时高度不一致，输入后整条搜索框会突然变高。
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = if (focusRequester == null) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().focusRequester(focusRequester)
                    }
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * 搜索二级页顶栏里的那行搜索框：进入页面即聚焦、软键盘自动弹出。
 *
 * 聚焦逻辑放在这里而不是各页面各写一遍：[SettingsScreen] 里记忆与技能两个搜索页共用这一枚输入框，
 * 两处各写一份容易出现一处漏了请求焦点、或改了清除按钮另一处没跟上。
 *
 * 不再套上下内边距：TopAppBar 的 title 槽本身只有 64dp，再垫一圈会把 40dp 的胶囊压扁，
 * 也会和返回箭头对不上垂直中线。
 */
@Composable
internal fun SearchTopBarField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    val focusRequester = remember { FocusRequester() }
    // 与聊天输入栏（ChatInputBar）同一套做法：组合完成后再请求焦点，此时输入框节点已挂上。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ModelSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        focusRequester = focusRequester,
        trailing = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
        }
    )
}

/**
 * 搜索无结果时的轻提示：半透明放大镜 + 一行次要色小字，顶部对齐。
 *
 * 不居中、不撑满整页：结果区就从顶栏下方开始排，居中会把「没搜到」画在屏幕腰上，
 * 和下面的大片空白连成一体，看着像页面还没加载完。
 *
 * 图标用 onSurfaceVariant 加 0.5 透明度，与 MCP 页的搜索空态同一套参数，
 * 各页的「没搜到」必须长得一样，否则用户会以为是两种不同的状态。
 */
@Composable
internal fun SearchEmptyHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
