package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.model.ReasoningEffort
import kotlin.math.abs
import kotlin.math.roundToInt

/** 输入栏入口：34dp 圆 + 2.4dp 描边 + 15dp 闪电。 */
private val RingSize = 34.dp
private val RingStroke = 2.4.dp
private val BoltSize = 15.dp

/**
 * 弹层顶部「最高档」按钮：23dp 裸闪电（不画环），外面留 48dp 触摸区。
 * 去掉 32dp 环之后原来 18dp 的闪电在 48dp 空框里显小，按去掉环之前「闪电 / 环」的观感补到 23dp。
 */
private val TopButtonBoltSize = 23.dp

/** 浓度条：容器 48dp（触摸下限）里居中放 14dp 轨道，指示头 3dp 宽、比轨道高 12dp。 */
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

/**
 * 「思考开关」胶囊：只有开关本体可点，胶囊外壳与文字都不接收触摸、不画 ripple，
 * 避免点文字时整条胶囊跟着变色。
 */
@Composable
internal fun ReasoningThinkingPill(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    val background = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    val borderColor = if (checked) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .padding(start = Spacing.md, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(Spacing.sm))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 浓度滑轨：指针 x 把轨道宽度按「档位数 - 1」等分，落在哪一段就取哪档，首档贴左端、末档贴右端，
 * 所以从最左（外一点点）按下去也能取到最低档。
 *
 * 拖动期间只把指针 x 写进 [previewX]（只在绘制阶段读，不触发重组、不做动画插值），指示头逐帧跟着手指；
 * 松手才回调一次 [onPick]，弹层不会被横向拖动带关。
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
    val trackBrush = remember(brand) {
        Brush.horizontalGradient(listOf(brand.copy(alpha = 0.12f), brand.copy(alpha = 0.32f)))
    }
    val fillBrush = remember(brand) {
        Brush.horizontalGradient(listOf(brand.copy(alpha = 0.32f), brand))
    }
    // 指针 x（px，已夹到 0..宽度）：null 表示没在按，指示头画当前档位的静止位置。
    val previewX = remember { mutableStateOf<Float?>(null) }
    val pressing = remember { mutableStateOf(false) }
    // pointerInput 不以 onPick / selectedIndex 为 key（否则每次重组都会打断进行中的手势），改为始终取最新值。
    val latestOnPick = rememberUpdatedState(onPick)
    val latestSelected = rememberUpdatedState(selectedIndex)
    // 上层档位存在数据库里，松手后要过几帧才回灌；预览先压在手指位置，等新档位生效再让指示头归位。
    LaunchedEffect(selectedIndex, levelCount) {
        if (!pressing.value) previewX.value = null
    }
    Box(
        modifier = modifier
            .height(TrackRowHeight)
            .semantics { contentDescription = description }
            .pointerInput(levelCount, enabled) {
                if (!enabled || levelCount < 2) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    var picked = levelIndexAt(startX, width, levelCount)
                    // 按下即对齐：预览从按下点直算，不经过 touchSlop，端点也不会被起始位移吃掉。
                    previewX.value = startX.coerceIn(0f, width)
                    pressing.value = true
                    var horizontal = false
                    var moved = false
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        val dx = change.position.x - startX
                        val dy = change.position.y - down.position.y
                        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                        if (!horizontal && abs(dx) > touchSlop && abs(dx) >= abs(dy)) horizontal = true
                        if (horizontal) {
                            // 横拖自己消费掉，弹层不会把它当成下拉关闭；位置一律取绝对坐标，不累加增量。
                            change.consume()
                            previewX.value = change.position.x.coerceIn(0f, width)
                            picked = levelIndexAt(change.position.x, width, levelCount)
                        }
                    }
                    pressing.value = false
                    if (released && (horizontal || !moved)) {
                        latestOnPick.value(picked)
                        // 档位没变就没有回灌可等，直接让指示头归位。
                        if (picked == latestSelected.value) previewX.value = null
                    } else {
                        previewX.value = null
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            if (width <= 0f) return@Canvas
            // 拖动中用指针位置；没在按时用档位的静止位置。两者都是同一个 0..1 比例，填充与指示头共用。
            val fraction = previewX.value
                ?.let { (it / width).coerceIn(0f, 1f) }
                ?: levelPosition(levelCount, selectedIndex)
            val trackHeight = TrackHeight.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            val corner = CornerRadius(trackHeight / 2f)
            // 底层轨道整体是主色的低透明度渐变，填充段是同一渐变的高透明度版本，长度 = 当前档位位置。
            drawRoundRect(
                brush = trackBrush,
                topLeft = Offset(0f, trackTop),
                size = Size(width, trackHeight),
                cornerRadius = corner
            )
            val filledWidth = width * fraction
            if (filledWidth > 0f) {
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset(0f, trackTop),
                    // 太短时按轨道高度收边，否则圆角会被压成尖角。
                    size = Size(filledWidth.coerceAtLeast(trackHeight), trackHeight),
                    cornerRadius = corner
                )
            }
            // 指示头：竖条比轨道高，贴在当前档位（拖动时贴手指）上；不再画固定的档位刻度点。
            val headWidth = 3.dp.toPx()
            val headHeight = trackHeight + 12.dp.toPx()
            val headLeft = (width * fraction - headWidth / 2f).coerceIn(0f, (width - headWidth).coerceAtLeast(0f))
            drawRoundRect(
                color = tipColor,
                topLeft = Offset(headLeft, trackTop - 6.dp.toPx()),
                size = Size(headWidth, headHeight),
                cornerRadius = CornerRadius(headWidth / 2f)
            )
        }
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
