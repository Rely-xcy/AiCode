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
        animationSpec = tween(durationMillis = 200),
        label = "switchTrackColor"
    )

    // 轨道 50dp、滑块 26dp、内边距 2dp，位移范围 2dp -> 22dp。
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = 0.8f,
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
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(animatedTrackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(26.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}
