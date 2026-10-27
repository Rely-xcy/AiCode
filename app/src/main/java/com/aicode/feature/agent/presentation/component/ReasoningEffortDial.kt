package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.model.ReasoningEffort
import kotlin.math.abs
import kotlin.math.roundToInt

/** 输入栏入口：34dp 圆 + 2.4dp 描边 + 15dp 闪电。 */
private val RingSize = 34.dp
private val RingStroke = 2.4.dp
private val BoltSize = 15.dp

/** 弹层顶部「最高档」按钮：18dp 裸闪电（不画环），外面留 48dp 触摸区。 */
private val TopButtonBoltSize = 18.dp

/** 浓度条：容器 48dp（触摸下限）里居中放 14dp 轨道，指示条 3dp 宽、比轨道高 12dp，刻度点贴轨道下方。 */
private val TrackRowHeight = 48.dp
private val TrackHeight = 14.dp

/** 触摸目标下限：视觉尺寸更小的按钮靠外层 Box 补到这个尺寸。 */
private val MinTouchSize = 48.dp

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

/** 环弧长与滑轨填充的同一个比例：档位下标 + 1 除以可用档位数，最高档正好画满。 */
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

/** 弹层左上的最高档按钮：只有裸闪电，不画环（最高档时闪电本身高亮实心）。 */
@Composable
internal fun ReasoningTopLevelButton(
    atTop: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit
) {
    val brand = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(MinTouchSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(TopButtonBoltSize)) {
            drawBolt(
                center = Offset(size.width / 2f, size.height / 2f),
                side = TopButtonBoltSize.toPx(),
                color = if (atTop) brand else muted,
                filled = atTop
            )
        }
    }
}

/** 「思考开关」胶囊：整块（含文字）都是触摸区，开关本身只做展示，避免同一手势被处理两次。 */
@Composable
internal fun ReasoningThinkingPill(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.pill)
    val background = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    val borderColor = if (checked) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .height(MinTouchSize)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(shape)
                .background(background)
                .border(1.dp, borderColor, shape)
                .padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(Spacing.sm))
            AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * 浓度滑轨：分段数 = 可用档位数，点哪段选哪档；拖动期间只改本地段位，松手才回调 [onPick]，
 * 不关闭弹层。手势里不做会触发重组的写入，避免每挪一像素就带动整个弹层重画。
 */
@Composable
internal fun ReasoningLevelTrack(
    levelCount: Int,
    selectedIndex: Int,
    enabled: Boolean,
    description: String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val brand = MaterialTheme.colorScheme.primary
    val tipColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val trackBrush = remember(brand) {
        Brush.horizontalGradient(listOf(brand.copy(alpha = 0.12f), brand.copy(alpha = 0.32f)))
    }
    val fillBrush = remember(brand) {
        Brush.horizontalGradient(listOf(brand.copy(alpha = 0.32f), brand))
    }
    // 拖动中 / 松手后待上层确认的段位：-1 表示没有预览；只在绘制阶段读它，改它只触发重绘、不触发重组。
    val dragIndex = remember { mutableIntStateOf(-1) }
    // 上层档位来自数据库 Flow，松手后要过几帧才回来；预览等它真正生效再看齐，避免指示条先弹回旧档。
    LaunchedEffect(selectedIndex, levelCount) {
        dragIndex.intValue = -1
    }
    // pointerInput 不以 onPick 为 key（否则每次重组都会打断进行中的手势），改为始终取最新回调。
    val latestOnPick = rememberUpdatedState(onPick)
    Box(
        modifier = modifier
            .height(TrackRowHeight)
            .semantics { contentDescription = description }
            .pointerInput(levelCount, enabled) {
                if (!enabled || levelCount < 2) return@pointerInput
                fun commit() {
                    val picked = dragIndex.intValue
                    if (picked >= 0) latestOnPick.value(picked)
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragIndex.intValue = indexAt(offset.x, size.width, levelCount)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val index = indexAt(change.position.x, size.width, levelCount)
                        if (index != dragIndex.intValue) dragIndex.intValue = index
                    },
                    onDragEnd = { commit() },
                    onDragCancel = { commit() }
                )
            }
            .pointerInput(levelCount, enabled) {
                if (!enabled || levelCount < 2) return@pointerInput
                detectTapGestures { offset ->
                    latestOnPick.value(indexAt(offset.x, size.width, levelCount))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shownIndex = dragIndex.intValue.takeIf { it >= 0 } ?: selectedIndex
            val fraction = levelFraction(levelCount, shownIndex)
            val trackHeight = TrackHeight.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            val corner = CornerRadius(trackHeight / 2f)
            // 底层轨道整体是主色的低透明度渐变，填充段是同一渐变的高透明度版本，长度 = 当前档位比例。
            drawRoundRect(
                brush = trackBrush,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )
            val filledWidth = (size.width * fraction).coerceIn(0f, size.width)
            if (filledWidth > 0f) {
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset(0f, trackTop),
                    // 太短时按轨道高度收边，否则圆角会被压成尖角。
                    size = Size(filledWidth.coerceAtLeast(trackHeight), trackHeight),
                    cornerRadius = corner
                )
            }
            val tipWidth = 3.dp.toPx()
            val tipHeight = trackHeight + 12.dp.toPx()
            val tipLeft = (size.width * fraction - tipWidth / 2f).coerceIn(0f, (size.width - tipWidth).coerceAtLeast(0f))
            drawRoundRect(
                color = tipColor,
                topLeft = Offset(tipLeft, trackTop - 6.dp.toPx()),
                size = Size(tipWidth, tipHeight),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            if (levelCount >= 1) {
                val dotRadius = 1.5.dp.toPx()
                for (i in 0 until levelCount) {
                    val ratio = if (levelCount == 1) 0.5f else i.toFloat() / (levelCount - 1)
                    drawCircle(
                        color = tickColor,
                        radius = dotRadius,
                        center = Offset(
                            x = (size.width * ratio).coerceIn(dotRadius, (size.width - dotRadius).coerceAtLeast(dotRadius)),
                            y = trackTop + trackHeight + 6.dp.toPx()
                        )
                    )
                }
            }
        }
    }
}

/** 手指 x 坐标 → 最近的一段；宽度未知或只有一段时都落到第 0 段。 */
private fun indexAt(x: Float, width: Int, levelCount: Int): Int {
    if (width <= 0 || levelCount <= 1) return 0
    val ratio = (x / width).coerceIn(0f, 1f)
    return (ratio * (levelCount - 1)).roundToInt().coerceIn(0, levelCount - 1)
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
