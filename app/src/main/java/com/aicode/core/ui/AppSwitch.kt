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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.semanticColors

/**
 * 开关的几何与动效参数集中在一处：设计稿给的是固定数值，散在 Composable 里既改不全、也没法在
 * 单测里锁住「滑块不会溢出轨道」这类不变量。
 */
internal object AppSwitchSpec {
    val trackWidth = 50.dp
    val trackHeight = 30.dp
    val trackCorner = 12.dp
    val thumbSize = 26.dp
    val thumbInset = 2.dp
    val thumbOffsetOff = thumbInset
    val thumbOffsetOn = 22.dp
    const val trackColorDurationMillis = 200
    const val thumbDampingRatio = 0.8f
}

/**
 * 全局统一的精致紧凑型开关组件：
 * - 50.dp × 30.dp 胶囊轨道 + 26.dp 圆形滑块，触控面积达标又不显笨重；
 * - 开启态取主题 primary（暖纸下即陶土红），关闭态取语义 mutedSurface，跟随主题切换；
 * - 颜色 tween(200) 渐变、位移 spring 回弹，与设置页其它微交互同节奏。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val targetTrackColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.semanticColors.mutedSurface
    }
    val animatedTrackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = tween(durationMillis = AppSwitchSpec.trackColorDurationMillis),
        label = "switchTrackColor"
    )

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
                .background(Color.White, CircleShape)
        )
    }
}
