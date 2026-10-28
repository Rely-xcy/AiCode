package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.ShapeClickBox
import com.aicode.feature.agent.domain.model.ReasoningEffort
import kotlin.math.abs
import kotlin.math.roundToInt

/** 输入栏入口：34dp 圆 + 2.4dp 描边 + 15dp 闪电。 */
private val RingSize = 34.dp
private val RingStroke = 2.4.dp
private val BoltSize = 15.dp

/**
 * 档位在「当前模型可用档位」里的下标；不在列表里（被降级 / 历史值）时落到序号最近的档位，
 * 保证滑轨不会因为一个对不上的值整段空白。
 */
internal fun levelIndex(levels: List<ReasoningEffort>, effort: ReasoningEffort?): Int {
    if (levels.isEmpty() || effort == null || effort == ReasoningEffort.NONE) return -1
    val exact = levels.indexOf(effort)
    if (exact >= 0) return exact
    return levels.indices.minByOrNull { abs(levels[it].ordinal - effort.ordinal) } ?: -1
}

/** 环弧长 = 档位下标 + 1 除以可用档位数，最高档正好画满。 */
internal fun levelFraction(levelCount: Int, index: Int): Float {
    if (levelCount <= 0 || index < 0) return 0f
    return (index + 1).toFloat() / levelCount
}

/** 档位短名直接取线上值的大写形式（max / medium / low …），关闭态固定 OFF。 */
internal fun ReasoningEffort.shortLabel(): String =
    if (this == ReasoningEffort.NONE) "OFF" else apiValue.uppercase()

/** 环与弧只在有具体档位时出现：关闭思考（OFF）或没有可用档位时整圈不画，只留闪电。 */
internal fun showsReasoningRing(levels: List<ReasoningEffort>, effort: ReasoningEffort?): Boolean =
    levelIndex(levels, effort) >= 0

/**
 * 输入栏的思考入口：打开时先画常驻底环，再按「当前档位 / 档位总数」叠一段从 12 点顺时针的进度弧；
 * 关闭思考（OFF）或一个档位都没有（模型只支持开 / 关或纯透传）时环与弧都不画，只留灰色闪电。
 */
@Composable
internal fun ReasoningRingBolt(
    levels: List<ReasoningEffort>,
    effort: ReasoningEffort,
    enabled: Boolean
) {
    val baseRing = MaterialTheme.colorScheme.outlineVariant
    val brand = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val index = levelIndex(levels, effort)
    val fraction = levelFraction(levels.size, index)
    val atTop = index >= 0 && index == levels.lastIndex
    Canvas(
        modifier = Modifier
            .size(RingSize)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        drawBoltRing(
            ringColor = if (showsReasoningRing(levels, effort)) baseRing else Color.Transparent,
            arcFraction = fraction,
            arcColor = brand,
            boltColor = if (index >= 0) brand else muted,
            boltFilled = atTop,
            ringStroke = RingStroke.toPx(),
            boltSide = BoltSize.toPx()
        )
    }
}

/**
 * 指针 x → 档位下标：两端直接钉死（x ≤ 0 取第 0 档、x ≥ 宽度取最后一档），
 * 中间按「档位数 - 1」等分四舍五入。宽度未知或只有一档时只有第 0 档。
 */
internal fun levelIndexAt(x: Float, width: Float, levelCount: Int): Int {
    if (width <= 0f || levelCount <= 1) return 0
    if (x <= 0f) return 0
    if (x >= width) return levelCount - 1
    return (x / width * (levelCount - 1)).roundToInt().coerceIn(0, levelCount - 1)
}

/** 档位在轨道上的静止位置：首档贴左端、末档贴右端，中间等分；只有一档时贴右端。 */
internal fun levelPosition(levelCount: Int, index: Int): Float {
    if (levelCount <= 0 || index < 0) return 0f
    if (levelCount == 1) return 1f
    return index.coerceIn(0, levelCount - 1).toFloat() / (levelCount - 1)
}

/**
 * 环与闪电的共用画法：底环整圈、进度弧从 12 点顺时针、闪电居中。
 * [ringColor] 透明时整圈不画（关闭思考或模型没有任何可用档位），只留闪电。
 */
private fun DrawScope.drawBoltRing(
    ringColor: Color,
    arcFraction: Float,
    arcColor: Color,
    boltColor: Color,
    boltFilled: Boolean,
    ringStroke: Float,
    boltSide: Float
) {
    val inset = ringStroke / 2f
    val ringSize = Size(size.minDimension - ringStroke, size.minDimension - ringStroke)
    val ringTopLeft = Offset(inset, inset)
    if (ringColor.alpha > 0f) {
        drawArc(
            color = ringColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = ringStroke)
        )
    }
    if (arcFraction > 0f) {
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = 360f * arcFraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = ringStroke, cap = StrokeCap.Round)
        )
    }
    drawBolt(
        center = Offset(size.width / 2f, size.height / 2f),
        side = boltSide,
        color = boltColor,
        filled = boltFilled
    )
}

/** 只画闪电本体（描边或实心），不画环；输入栏入口与弹层顶部按钮共用。 */
private fun DrawScope.drawBolt(center: Offset, side: Float, color: Color, filled: Boolean) {
    val path = featherBoltPath(center = center, side = side)
    val stroke = 2.4f * (side / 24f)
    if (filled) {
        drawPath(path = path, color = color)
        drawPath(path = path, color = color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    } else {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Feather「zap」的轮廓（24 视口：13,2 → 3,14 → 12,14 → 11,22 → 21,10 → 12,10 闭合）。
 * 图标库里只有描边版，最高档「实心」需要同一套几何直接填充，所以这里按原型把路径重画一遍。
 */
private fun featherBoltPath(center: Offset, side: Float): Path {
    val scale = side / 24f
    val left = center.x - side / 2f
    val top = center.y - side / 2f
    return Path().apply {
        moveTo(left + 13f * scale, top + 2f * scale)
        lineTo(left + 3f * scale, top + 14f * scale)
        lineTo(left + 12f * scale, top + 14f * scale)
        lineTo(left + 11f * scale, top + 22f * scale)
        lineTo(left + 21f * scale, top + 10f * scale)
        lineTo(left + 12f * scale, top + 10f * scale)
        close()
    }
}
