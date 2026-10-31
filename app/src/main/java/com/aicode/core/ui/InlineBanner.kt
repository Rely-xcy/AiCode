package com.aicode.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.X

/**
 * 内联提示条的语义级别：只表达「这条提示有多严重」，配色由 [InlineBanner] 统一映射，
 * 调用方不用自己挑 container / onContainer 组合。
 */
enum class InlineBannerLevel {
    /** 普通说明。 */
    Info,

    /** 警告、需要注意但功能仍可用。 */
    Warning,

    /** 错误、失败。 */
    Error
}

/**
 * 单条内联提示：圆角容器 + 可选图标 + 一行正文 + 可选第二行说明 + 可选关闭按钮。
 *
 * 只服务「一条提示」这一种形态。带操作按钮、可展开正文、进度圈或自定义布局的提示不要套它——
 * 那些是面板或卡片，套进来只会逼着这里长出一堆一次性参数。
 *
 * @param text 主提示，必须是一句话。
 * @param level 语义级别，决定 container/onContainer 与描边色。
 * @param detail 可选的第二行说明（比主提示弱一档）。
 * @param icon 可选的左侧图标；为 null 时不占位。
 * @param onDismiss 可选的关闭回调；非空时右侧显示关闭按钮。
 * @param contentPadding 内容内边距，默认与设置页一致。
 * @param textStyle 主提示文字样式。
 * @param maxLines 主提示最多显示几行，超出用省略号。
 */
@Composable
fun InlineBanner(
    text: String,
    level: InlineBannerLevel,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE
) {
    val colors = level.bannerColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = colors.container,
        border = BorderStroke(0.5.dp, colors.accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = textStyle,
                    color = colors.content,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = textStyle,
                        color = colors.content.copy(alpha = 0.85f)
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        tint = colors.content.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** 级别 → (容器色, 内容色, 强调色)。 */
@Composable
private fun InlineBannerLevel.bannerColors(): BannerColors = when (this) {
    InlineBannerLevel.Info -> BannerColors(
        container = MaterialTheme.semanticColors.infoContainer,
        content = MaterialTheme.semanticColors.onInfoContainer,
        accent = MaterialTheme.semanticColors.info
    )

    InlineBannerLevel.Warning -> BannerColors(
        container = MaterialTheme.semanticColors.warningContainer,
        content = MaterialTheme.semanticColors.onWarningContainer,
        accent = MaterialTheme.semanticColors.warning
    )

    InlineBannerLevel.Error -> BannerColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        accent = MaterialTheme.colorScheme.error
    )
}

private data class BannerColors(val container: Color, val content: Color, val accent: Color)
