package com.aicode.feature.agent.presentation.component

/**
 * 贴底校准的门槛：这一帧能不能请求「滚回锚点」。
 *
 * 三个「不要动视口」的状态缺一不可：
 * - [followBottom] 为假：用户在别处看历史，别把他拉回去；
 * - [userScrollGesture] 为真：他的手势已经产生滚动量，交给他；
 * - [pointerDown] 为真：手指已经落在列表上，**哪怕还没越过 touch slop**。
 *
 * 最后一条是补上的：手指落下到真正开始拖拽之间有一段窗口，长度 = touch slop 像素 ÷ 手指速度。
 * 快速一甩时它只有一帧，慢慢滑则是几十帧。窗口里手势标志（`userScrollGesture`/`followBottom`
 * 的置位都在「已产生滚动量」之后，拖拽标志则要越过 slop）还没建立，而流式增长照旧，
 * 逐帧校准就会把「我准备往上滑」当成「你还在底部」，每帧 `requestScrollToItem` 把视口拽回底部
 * ——表现就是慢慢滑也跳。
 *
 * 抽成纯函数是为了能用单测钉住这段窗口（本仓没有 Compose 测试设施，只能测纯逻辑）。
 */
internal fun shouldCalibrateToAnchor(
    followBottom: Boolean,
    userScrollGesture: Boolean,
    pointerDown: Boolean,
    canScrollForward: Boolean
): Boolean = followBottom && !userScrollGesture && !pointerDown && canScrollForward
