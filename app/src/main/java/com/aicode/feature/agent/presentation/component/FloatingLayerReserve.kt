package com.aicode.feature.agent.presentation.component

import kotlin.math.roundToInt

/**
 * 悬浮层实测高度按整 dp 量化后再写回状态。
 *
 * 这个高度是列表底部留白的输入：写一次就重组整块聊天面板、重建 LazyColumn 的 contentPadding。
 * 字体度量与间距取整会让实测 px 在相邻帧之间差一两个像素（键盘逐帧动画更是每帧都不同），
 * 未量化等于逐帧产生新状态值。量化到整 dp 后，小于 1dp 的抖动不再改变状态值。
 *
 * 返回 0 表示「还没测到有效高度」，调用方据此退回兜底留白。
 */
internal fun quantizeLayerHeightPx(heightPx: Int, densityScale: Float): Int {
    if (heightPx <= 0 || densityScale <= 0f) return 0
    val heightDp = heightPx / densityScale
    return (heightDp.roundToInt() * densityScale).roundToInt()
}
