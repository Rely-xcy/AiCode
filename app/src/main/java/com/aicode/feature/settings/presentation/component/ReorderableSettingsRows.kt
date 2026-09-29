package com.aicode.feature.settings.presentation.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import com.aicode.core.theme.Radius
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.presentation.SettingsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/**
 * 取 MainActivity 在 Activity 作用域创建的那个 [SettingsViewModel]（见 MainActivity 的 settingsViewModel）。
 *
 * 设置页各 section 由 SettingsScreen 渲染，而 SettingsScreen 里的调用点不在本次改动范围内，
 * 组件拿不到排序回调；用 hiltViewModel() 会取到 nav-entry 级实例（与页面正在用的 Activity 级实例不是同一个，
 * 改了页面也不会刷新），所以这里按 Activity 的 ViewModelStore 取同一实例。
 * 后续若 SettingsScreen 愿意显式传回调，把调用点换成参数即可，此函数可直接删除。
 */
@Composable
internal fun rememberSettingsViewModel(): SettingsViewModel? {
    val context = LocalContext.current
    return remember(context) {
        val activity = context.findActivity() ?: return@remember null
        ViewModelProvider(activity)[SettingsViewModel::class.java]
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 列表 item key：`前缀 + 条目 id`。前缀标识分组（全局 / 项目），
 * 拖拽回调里靠它反查身份与分组——这比下标可靠，因为 onMove 的下标是含分组标题、空态提示行的全局下标。
 */
internal fun listItemKey(prefix: String, id: String): String = prefix + id

/** 从 item key 取出 id；key 不带该前缀（不是这一组的行 / 是分组标题等）时返回 null。 */
internal fun itemIdOf(key: Any?, prefix: String): String? {
    val raw = key as? String ?: return null
    return if (raw.startsWith(prefix)) raw.removePrefix(prefix) else null
}

/**
 * 设置页分组卡片里可拖拽的一行。
 *
 * 分组卡片在 [Column] 时代是一个 [SettingsGroup]，换成 LazyColumn 后每行都是独立 item，
 * 这里按行位置补圆角（首行圆上角、末行圆下角、中间不圆）让同组各行仍连成一块卡片，
 * 并自带行尾分隔线（末行不画）；拖拽中给 0.95 缩放 + 阴影提升 + 层级前置，避免盖住相邻卡片。
 *
 * @param isFirst 是否所在分组的首行（决定上圆角）。
 * @param isLast 是否所在分组的末行（决定下圆角与是否画分隔线）。
 * @param content 行内容；必须把传入的 `dragModifier` 贴到行内容上，否则这一行拖不动。
 *
 * 是 [LazyItemScope] 的扩展：库里的 ReorderableItem 就是 LazyItemScope 的扩展，
 * 必须在 LazyColumn 的 item/items 作用域里才能调用。
 */
@Composable
internal fun LazyItemScope.ReorderableCardRow(
    state: ReorderableLazyListState,
    key: Any,
    isFirst: Boolean,
    isLast: Boolean,
    dragLabel: String,
    content: @Composable (dragModifier: Modifier) -> Unit
) {
    ReorderableItem(state = state, key = key) { isDragging ->
        val hapticFeedback = LocalHapticFeedback.current
        val dragScale by animateFloatAsState(
            targetValue = if (isDragging) 0.95f else 1f,
            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
            label = "${dragLabel}Scale"
        )
        val dragElevation by animateDpAsState(
            targetValue = if (isDragging) 8.dp else 0.dp,
            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
            label = "${dragLabel}Elevation"
        )
        val shape = if (isDragging) {
            RoundedCornerShape(Radius.lg)
        } else {
            RoundedCornerShape(
                topStart = if (isFirst) Radius.lg else 0.dp,
                topEnd = if (isFirst) Radius.lg else 0.dp,
                bottomStart = if (isLast) Radius.lg else 0.dp,
                bottomEnd = if (isLast) Radius.lg else 0.dp
            )
        }
        Surface(
            shape = shape,
            color = MaterialTheme.semanticColors.cardSurface,
            shadowElevation = dragElevation,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer {
                    scaleX = dragScale
                    scaleY = dragScale
                }
        ) {
            Column {
                content(
                    Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragStopped = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                    )
                )
                if (!isLast && !isDragging) {
                    SettingsDivider()
                }
            }
        }
    }
}
