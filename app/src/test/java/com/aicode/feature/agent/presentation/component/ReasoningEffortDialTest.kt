package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度控件的纯逻辑：档位 → 滑轨段位 / 环弧长的换算，以及档位短名。
 * 分段数必须跟着当前模型可用档位走（写死 4 档会让 3 档或 6 档的模型画错）。
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
}
