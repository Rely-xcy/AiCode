package com.aicode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.semanticColors

/**
 * 状态徽标的语义基调：只表达「这是什么状态」，具体配色由 [AppStatusPill] 统一映射到
 * semanticColors / colorScheme，新调用点不必再自己挑色。
 */
enum class AppStatusTone {
    /** 成功、已完成、可用。 */
    Success,

    /** 警告、即将到期、需要留意但尚未失败。 */
    Warning,

    /** 失败、已失效、不可用。 */
    Error,

    /** 提示性信息标注。 */
    Info,

    /** 品牌主色强调：项目级作用域、当前选中项等。 */
    Brand,

    /** 中性标注（类型、来源、未激活）：文字走 onSurfaceVariant，底色走浅 surfaceVariant。 */
    Neutral
}

/**
 * 全局统一的状态徽标：胶囊底色 + 单行小字，状态 / 类型 / 来源 / 摘要标注都用它。
 *
 * 形制固定为胶囊圆角（[Radius.pill]）、水平 8.dp / 垂直 2.dp 内边距、单行省略号。
 * 几何参数刻意不开放——各页各写一套 padding 与圆角正是要收敛掉的问题。
 *
 * @param text 徽标文案，单行显示，超长省略。
 * @param modifier 外部修饰符；行内需要占位时由调用方传 `Modifier.weight(...)`。
 * @param tone 语义基调，决定默认文字色与底色；显式传入的 [textColor] / [backgroundColor] 优先。
 * @param textColor 文字色，null 时取 [tone] 映射。
 * @param backgroundColor 底色，null 时取 [tone] 映射：语义色 12% 透明度，Neutral 为 surfaceVariant 60%。
 * @param borderColor 描边色，null 时不描边；深色底上需要勾出边界时用。
 * @param borderWidth 描边宽度，仅在 [borderColor] 非空时生效。
 * @param leadingDotColor 非空时在文字前加同色 6.dp 圆点（日志等级等靠色点区分的位置）。
 * @param width 固定宽度，用于需要列对齐的窄位；null 时按内容自适应。
 * @param textStyle 文字样式，默认与既有 pill 一致（bodySmall 覆盖为 12.sp）。
 */
@Composable
fun AppStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppStatusTone = AppStatusTone.Neutral,
    textColor: Color? = null,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = 0.5.dp,
    leadingDotColor: Color? = null,
    width: Dp? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
) {
    val toneColors = tone.statusColors()
    val resolvedTextColor = textColor ?: toneColors.first
    val resolvedBackgroundColor = backgroundColor ?: toneColors.second
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .background(resolvedBackgroundColor, shape)
            .then(if (borderColor != null) Modifier.border(borderWidth, borderColor, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(leadingDotColor, shape)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = textStyle,
            color = resolvedTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** [AppStatusTone] → (文字色, 底色)。中性基调沿用全仓既有观感，不走 12% 语义色。 */
@Composable
private fun AppStatusTone.statusColors(): Pair<Color, Color> {
    val accent = when (this) {
        AppStatusTone.Success -> MaterialTheme.semanticColors.success
        AppStatusTone.Warning -> MaterialTheme.semanticColors.warning
        AppStatusTone.Error -> MaterialTheme.colorScheme.error
        AppStatusTone.Info -> MaterialTheme.semanticColors.info
        AppStatusTone.Brand -> MaterialTheme.colorScheme.primary
        AppStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container = if (this == AppStatusTone.Neutral) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        accent.copy(alpha = 0.12f)
    }
    return accent to container
}
