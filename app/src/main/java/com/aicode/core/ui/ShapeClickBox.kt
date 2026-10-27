package com.aicode.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * 点击区与形状一致的容器：内部固定按「先按 [shape] 裁切、再挂 clickable」的顺序装配，
 * 圆形与圆角图标点下去不会再铺出方形 ripple。
 *
 * 尺寸完全由 [modifier] 决定（`.size(24.dp)`、`.fillMaxSize()` 都行），默认不放大也不缩小触摸区：
 * 小于 48dp 的图标要不要补到触摸下限，取决于各自的布局，交给调用方决定（见 [touchTarget]）。
 *
 * 裁切只作用于绘制与波纹，命中判定仍是矩形的 [modifier] 尺寸（与手写 clip + clickable 一致）。
 *
 * @param onClick 点击回调
 * @param modifier 尺寸与位置；裁切挂在它之后，所以它就是视觉与波纹的边界。
 * 传了 [touchTarget] 时它只决定视觉内容，触摸区由外层补足，含外边距的位置修饰要放到调用处外层
 * @param shape 裁切形状，默认圆形；圆角方块传 `RoundedCornerShape(Radius.md)`
 * @param enabled 是否可点，关闭时点击与波纹一起失效；灰化程度由调用方自己决定
 * @param contentDescription 无障碍描述；为 null 时只挂点击语义（描述在子节点里或纯装饰）
 * @param role 无障碍角色，默认 [Role.Button]
 * @param onClickLabel 点击动作的无障碍标签（例如「预览图片」）
 * @param contentAlignment 内容对齐方式，默认居中
 * @param touchTarget 触摸区下限；非空时在视觉内容外层补足命中区，内容仍按 [modifier] 的尺寸绘制。
 * 补出来的空间算作父布局占位，只在周围留白能吸收时使用（紧凑行里硬补会把相邻元素挤开），
 * 受父约束限制时按父约束封顶。波纹跟随补足后的命中区，因此会比视觉尺寸大
 * @param content 内容；子图标的 contentDescription 应留空，描述统一由 [contentDescription] 提供
 */
@Composable
fun ShapeClickBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    contentDescription: String? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    touchTarget: Dp? = null,
    content: @Composable () -> Unit
) {
    val description = contentDescription
    val hitArea = Modifier
        .clip(shape)
        .then(
            if (description == null) {
                Modifier
            } else {
                Modifier.semantics { this.contentDescription = description }
            }
        )
        .clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick
        )
    if (touchTarget == null) {
        Box(
            modifier = modifier.then(hitArea),
            contentAlignment = contentAlignment
        ) {
            content()
        }
    } else {
        // 补足必须挂在最外层：命中区取的是节点自身尺寸，尺寸修饰在它内侧就只剩视觉尺寸的命中范围。
        Box(
            modifier = Modifier.touchTargetFloor(touchTarget).then(hitArea),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = modifier, contentAlignment = contentAlignment) {
                content()
            }
        }
    }
}

/**
 * 把节点报到至少 [target] 的尺寸，但不改子节点的视觉尺寸。
 *
 * 子节点按放宽了下限的约束测量：`size()` 会把请求尺寸夹进上级约束，直接抬下限会把视觉一起撑大。
 * 取不到空间时按父约束封顶，避免报出超过上级约束的尺寸。
 */
private fun Modifier.touchTargetFloor(target: Dp): Modifier = layout { measurable, constraints ->
    val targetPx = target.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = maxOf(placeable.width, targetPx).coerceAtMost(constraints.maxWidth)
    val height = maxOf(placeable.height, targetPx).coerceAtMost(constraints.maxHeight)
    layout(width, height) {
        placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
}
