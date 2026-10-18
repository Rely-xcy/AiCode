package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贴底校准的门槛：手指在屏幕上时绝不请求滚动。
 *
 * 回归点：手指落下到越过 touch slop 之间有一段窗口（慢滑时可长达几十帧），
 * 窗口里若还允许校准，逐帧的 `requestScrollToItem(最后一项, MAX)` 会把视口拽回底部，
 * 表现成「慢慢滑也会跳上去」。
 */
class ChatScrollFollowPolicyTest {

    @Test
    fun `手指已落屏但还没越过 slop 时不校准`() {
        assertFalse(
            "手指在屏幕上就不允许请求滚动，否则会把正在滑动的视口拽回底部",
            shouldCalibrateToAnchor(
                followBottom = true,
                userScrollGesture = false,
                pointerDown = true,
                canScrollForward = true
            )
        )
    }

    @Test
    fun `流式贴底：跟随中、手指不在、还有可滚空间时要校准`() {
        assertTrue(
            shouldCalibrateToAnchor(
                followBottom = true,
                userScrollGesture = false,
                pointerDown = false,
                canScrollForward = true
            )
        )
    }

    @Test
    fun `不跟随、手势中、已到底这三种情况都不校准`() {
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = false, userScrollGesture = false, pointerDown = false, canScrollForward = true
            )
        )
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = true, userScrollGesture = true, pointerDown = false, canScrollForward = true
            )
        )
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = true, userScrollGesture = false, pointerDown = false, canScrollForward = false
            )
        )
    }
}
