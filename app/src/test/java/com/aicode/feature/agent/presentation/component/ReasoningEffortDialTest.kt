package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度控件的纯逻辑：指针 x → 档位下标、档位 → 滑轨位置 / 环弧长的换算，以及档位短名。
 * 档位数必须跟着当前模型可用档位走（写死 4 档会让 3 档或 6 档的模型画错）。
 */
class ReasoningEffortDialTest {

    private val threeLevels = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH
    )

    @Test
    fun levelIndex_matchesAvailableLevel() {
        assertEquals(0, levelIndex(threeLevels, ReasoningEffort.LOW))
        assertEquals(1, levelIndex(threeLevels, ReasoningEffort.MEDIUM))
        assertEquals(2, levelIndex(threeLevels, ReasoningEffort.HIGH))
    }

    @Test
    fun levelIndex_fallsBackToNearestWhenLevelUnavailable() {
        // 只支持到 high 的模型请求 max / xhigh：滑轨落在最后一段，而不是整段空白。
        assertEquals(2, levelIndex(threeLevels, ReasoningEffort.XHIGH))
        assertEquals(2, levelIndex(threeLevels, ReasoningEffort.MAX))
        // 请求低于模型最低档：落在第一段。
        assertEquals(0, levelIndex(threeLevels, ReasoningEffort.MINIMAL))
    }

    @Test
    fun levelIndex_offAndEmptyHaveNoPosition() {
        assertEquals(-1, levelIndex(threeLevels, ReasoningEffort.NONE))
        assertEquals(-1, levelIndex(threeLevels, null))
        assertEquals(-1, levelIndex(emptyList(), ReasoningEffort.HIGH))
    }

    @Test
    fun levelFraction_scalesWithActualLevelCount() {
        assertEquals(1f / 3f, levelFraction(3, 0), 0.0001f)
        assertEquals(2f / 3f, levelFraction(3, 1), 0.0001f)
        assertEquals(1f, levelFraction(3, 2), 0.0001f)
        // 六档平台的最高档同样画满，最低一段只占 1/6。
        assertEquals(1f / 6f, levelFraction(6, 0), 0.0001f)
        assertEquals(1f, levelFraction(6, 5), 0.0001f)
    }

    @Test
    fun levelFraction_zeroWhenOffOrNoLevel() {
        assertEquals(0f, levelFraction(3, -1), 0.0001f)
        assertEquals(0f, levelFraction(0, 0), 0.0001f)
    }

    @Test
    fun shortLabel_usesWireValueAndOff() {
        assertEquals("OFF", ReasoningEffort.NONE.shortLabel())
        assertEquals("MINIMAL", ReasoningEffort.MINIMAL.shortLabel())
        assertEquals("MEDIUM", ReasoningEffort.MEDIUM.shortLabel())
        assertEquals("XHIGH", ReasoningEffort.XHIGH.shortLabel())
        assertEquals("MAX", ReasoningEffort.MAX.shortLabel())
    }

    @Test
    fun ring_hiddenWhenOffOrNoLevels() {
        // 关闭思考时输入栏闪电外面必须什么都不剩：环与弧都不画，只留灰色闪电。
        assertFalse(showsReasoningRing(threeLevels, ReasoningEffort.NONE))
        assertFalse(showsReasoningRing(threeLevels, null))
        assertFalse(showsReasoningRing(emptyList(), ReasoningEffort.HIGH))
        // 打开时才有环；对不上的历史档位也可按最近档位画环。
        assertTrue(showsReasoningRing(threeLevels, ReasoningEffort.LOW))
        assertTrue(showsReasoningRing(threeLevels, ReasoningEffort.MAX))
    }

    @Test
    fun levelIndexAt_reachesBothEnds() {
        val width = 300f
        // 最左边缘、以及滑到轨道外一点点，都必须能取到第一档。
        assertEquals(0, levelIndexAt(0f, width, 3))
        assertEquals(0, levelIndexAt(-24f, width, 3))
        // 最右边缘与轨道外右侧同理，必须能取到最后一档。
        assertEquals(2, levelIndexAt(width, width, 3))
        assertEquals(2, levelIndexAt(width + 24f, width, 3))
        // 六档模型两头同样能取到。
        assertEquals(0, levelIndexAt(0f, width, 6))
        assertEquals(5, levelIndexAt(width, width, 6))
    }

    @Test
    fun levelIndexAt_splitsTrackEvenly() {
        val width = 300f
        // 三档：0 / 150 / 300；中点两侧按四舍五入归到较近的一档。
        assertEquals(0, levelIndexAt(74f, width, 3))
        assertEquals(1, levelIndexAt(76f, width, 3))
        assertEquals(1, levelIndexAt(150f, width, 3))
        assertEquals(1, levelIndexAt(224f, width, 3))
        assertEquals(2, levelIndexAt(226f, width, 3))
        // 六档：每段 60px。
        assertEquals(0, levelIndexAt(29f, width, 6))
        assertEquals(1, levelIndexAt(31f, width, 6))
        assertEquals(4, levelIndexAt(269f, width, 6))
        assertEquals(5, levelIndexAt(271f, width, 6))
    }

    @Test
    fun levelIndexAt_handlesDegenerateGeometry() {
        assertEquals(0, levelIndexAt(120f, 0f, 4))
        assertEquals(0, levelIndexAt(120f, 300f, 1))
        assertEquals(0, levelIndexAt(120f, 300f, 0))
    }

    @Test
    fun levelPosition_putsFirstAtLeftAndLastAtRight() {
        assertEquals(0f, levelPosition(3, 0), 0.0001f)
        assertEquals(0.5f, levelPosition(3, 1), 0.0001f)
        assertEquals(1f, levelPosition(3, 2), 0.0001f)
        assertEquals(0f, levelPosition(6, 0), 0.0001f)
        assertEquals(1f, levelPosition(6, 5), 0.0001f)
        assertEquals(0f, levelPosition(3, -1), 0.0001f)
    }

    @Test
    fun levelPosition_singleLevelSitsWhereTheHeadSits() {
        // 只有一档时轨道不可拖，刻度点与指示头放同一位置，不会出现「点在中间、头在最右」两个矛盾的标记。
        assertEquals(1f, levelPosition(1, 0), 0.0001f)
        assertEquals(0, levelIndexAt(300f, 300f, 1))
    }

    @Test
    fun levelPosition_roundTripsThroughLevelIndexAt() {
        val width = 360f
        // 静止位置再换算回档位必须还是同一档，否则松手后指示头会归到别的档位。
        for (n in 2..6) {
            for (i in 0 until n) {
                assertEquals(i, levelIndexAt(levelPosition(n, i) * width, width, n))
            }
        }
    }
}
