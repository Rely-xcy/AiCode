package com.aicode.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.semanticColors

/**
 * 开关的几何与动效参数集中在一处：散在 Composable 里既改不全、也没法在单测里锁住
 * 「滑块不会溢出轨道」这类不变量。
 */
internal object AppSwitchSpec {
    val trackWidth = 42.dp
    val trackHeight = 24.dp
    val trackCorner = 12.dp
    val thumbSize = 20.dp
    val thumbInset = 2.dp
    val thumbOffsetOff = thumbInset
    val thumbOffsetOn = 20.dp
    const val trackColorDurationMillis = 200
    const val thumbDampingRatio = 0.8f
}

/**
 * 全局统一的精致紧凑型开关组件：
 * - 紧凑尺寸（42.dp × 24.dp），告别 Material 3 默认 Switch 的笨重厚重感；
 * - 纯白微阴影圆形滑块 + 丝滑平移动画；
 * - 浅色模式为品牌蓝 / 柔和浅灰轨道；深色模式为亮蓝 / 暗灰蓝轨道（均取主题语义色，不写死色值）。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // 轨道颜色
    val targetTrackColor = when {
        checked -> MaterialTheme.colorScheme.primary
        isLight -> MaterialTheme.semanticColors.mutedSurface
        else -> MaterialTheme.semanticColors.capsuleSurface
    }
    val animatedTrackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = tween(durationMillis = AppSwitchSpec.trackColorDurationMillis),
        label = "switchTrackColor"
    )

    // 滑块颜色
    val thumbColor = MaterialTheme.colorScheme.surface

    // 滑块位移（轨道宽 42dp，内边距 2dp，滑块 20dp，位移范围 2dp -> 20dp）
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) AppSwitchSpec.thumbOffsetOn else AppSwitchSpec.thumbOffsetOff,
        animationSpec = spring(
            dampingRatio = AppSwitchSpec.thumbDampingRatio,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "switchThumbOffset"
    )

    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = onCheckedChange
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(toggleModifier)
            .size(width = AppSwitchSpec.trackWidth, height = AppSwitchSpec.trackHeight)
            .clip(RoundedCornerShape(AppSwitchSpec.trackCorner))
            .background(animatedTrackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(AppSwitchSpec.thumbSize)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(thumbColor, CircleShape)
        )
    }
}
