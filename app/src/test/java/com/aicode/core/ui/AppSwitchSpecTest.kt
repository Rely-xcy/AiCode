package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开关的几何不变量。
 *
 * 设计稿只给了一组数值，真正容易被改坏的是它们之间的关系：滑块必须始终落在轨道内、
 * 两端留白一致、开启态右缘不越界。这里把关系锁住，调数值时不会改出「滑块探出轨道」的观感 bug。
 *
 * 比较用 [androidx.compose.ui.unit.Dp.value]，不依赖 value class 的装箱相等。
 */
class AppSwitchSpecTest {

    @Test
    fun `轨道与滑块尺寸符合设计稿`() {
        assertEquals(50f, AppSwitchSpec.trackWidth.value, 0f)
        assertEquals(30f, AppSwitchSpec.trackHeight.value, 0f)
        assertEquals(26f, AppSwitchSpec.thumbSize.value, 0f)
        assertEquals(12f, AppSwitchSpec.trackCorner.value, 0f)
    }

    @Test
    fun `滑块竖直居中`() {
        val verticalGap = AppSwitchSpec.trackHeight.value - AppSwitchSpec.thumbSize.value
        assertEquals(AppSwitchSpec.thumbInset.value, verticalGap / 2f, 0f)
    }

    @Test
    fun `两端留白一致`() {
        val rightInset = AppSwitchSpec.trackWidth.value -
            (AppSwitchSpec.thumbOffsetOn.value + AppSwitchSpec.thumbSize.value)
        assertEquals(AppSwitchSpec.thumbOffsetOff.value, rightInset, 0f)
    }

    @Test
    fun `开启态滑块右缘不越界`() {
        val rightEdge = AppSwitchSpec.thumbOffsetOn.value + AppSwitchSpec.thumbSize.value
        assertTrue("滑块右缘 $rightEdge 超出轨道 ${AppSwitchSpec.trackWidth.value}", rightEdge <= AppSwitchSpec.trackWidth.value)
    }

    @Test
    fun `关闭态滑块左缘贴内边距`() {
        assertEquals(AppSwitchSpec.thumbInset.value, AppSwitchSpec.thumbOffsetOff.value, 0f)
    }

    @Test
    fun `动效参数与设计稿一致`() {
        assertEquals(200, AppSwitchSpec.trackColorDurationMillis)
        assertEquals(0.8f, AppSwitchSpec.thumbDampingRatio, 0.0001f)
    }
}
