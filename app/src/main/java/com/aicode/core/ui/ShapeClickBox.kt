package com.aicode.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 点击区与形状一致的容器：内部固定按「先按 [shape] 裁切、再挂 clickable」的顺序装配，
 * 圆形与圆角图标点下去不会再铺出方形 ripple。
 *
 * 尺寸完全由 [modifier] 决定（`.size(24.dp)`、`.fillMaxSize()` 都行），本组件不放大也不缩小触摸区：
 * 小于 48dp 的图标要不要补到触摸下限，取决于各自的布局，交给调用方决定。
 *
 * 裁切只作用于绘制与波纹，命中判定仍是矩形的 [modifier] 尺寸（与手写 clip + clickable 一致）。
 *
 * @param onClick 点击回调
 * @param modifier 尺寸与位置；裁切挂在它之后，所以它就是视觉与波纹的边界
 * @param shape 裁切形状，默认圆形；圆角方块传 `RoundedCornerShape(Radius.md)`
 * @param enabled 是否可点，关闭时点击与波纹一起失效；灰化程度由调用方自己决定
 * @param contentDescription 无障碍描述；为 null 时只挂点击语义（描述在子节点里或纯装饰）
 * @param role 无障碍角色，默认 [Role.Button]
 * @param onClickLabel 点击动作的无障碍标签（例如「预览图片」）
 * @param contentAlignment 内容对齐方式，默认居中
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
    content: @Composable () -> Unit
) {
    val description = contentDescription
    Box(
        modifier = modifier
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
            ),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}
