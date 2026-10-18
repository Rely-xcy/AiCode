package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 悬浮层高度量化：小于 1dp 的抖动必须落到同一个值上，否则每帧都会重组整块聊天面板。
 */
class FloatingLayerReserveTest {

    @Test
    fun `同一 dp 内的高度抖动量化到同一个值`() {
        val density = 3f
        assertEquals(
            "99px 恰为 33dp、100px 是 33.3dp，两者量化后必须相同，否则 1px 抖动就写一次状态",
            quantizeLayerHeightPx(99, density),
            quantizeLayerHeightPx(100, density)
        )
        assertEquals(99, quantizeLayerHeightPx(100, density))
    }

    @Test
    fun `量化结果与原值的误差不超过半 dp 加一像素`() {
        val density = 2.75f
        val tolerance = (density / 2f).toInt() + 1
        for (heightPx in listOf(1, 37, 120, 429, 1013)) {
            val diff = abs(quantizeLayerHeightPx(heightPx, density) - heightPx)
            assertTrue(
                "量化只允许四舍五入到整 dp：$heightPx px 误差 ${diff}px 越过 ${tolerance}px",
                diff <= tolerance
            )
        }
    }

    @Test
    fun `量化是幂等的`() {
        val density = 2.75f
        for (heightPx in listOf(7, 99, 512, 2048)) {
            val once = quantizeLayerHeightPx(heightPx, density)
            assertEquals(
                "二次量化必须落在同一个值上，否则状态值会在两个值之间来回跳",
                once,
                quantizeLayerHeightPx(once, density)
            )
        }
    }

    @Test
    fun `没测到有效高度时返回 0 交由兜底留白`() {
        assertEquals(0, quantizeLayerHeightPx(0, 2f))
        assertEquals(0, quantizeLayerHeightPx(-12, 2f))
        assertEquals("density 异常时不能除零", 0, quantizeLayerHeightPx(120, 0f))
    }
}
