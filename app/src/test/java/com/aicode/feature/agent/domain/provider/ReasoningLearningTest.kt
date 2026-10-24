package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从 400 文案里学习档位白名单：两种真实错误格式各有用例。 */
class ReasoningLearningTest {

    @After
    fun tearDown() = ReasoningLearning.clear()

    @Test
    fun parse_validLevelsFormat() {
        val message = """{"error":{"message":"level \"minimal\" not supported, valid levels: low, medium, high, xhigh, max"}}"""
        assertEquals(
            listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH, ReasoningEffort.MAX
            ),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_supportedValuesFormat() {
        val message = "Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. " +
            "Supported values are: 'low', 'medium', 'high', 'xhigh', 'max'"
        assertEquals(
            listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH, ReasoningEffort.MAX
            ),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_geminiStyleFormat() {
        assertEquals(
            listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH),
            ReasoningLearning.parseSupportedLevels("""level "low" not supported, valid levels: minimal, high""")
        )
    }

    @Test
    fun parse_unrelatedMessage_returnsNull() {
        assertNull(ReasoningLearning.parseSupportedLevels("401 invalid api key"))
        assertNull(ReasoningLearning.parseSupportedLevels(""))
        assertNull(ReasoningLearning.parseSupportedLevels(null))
    }

    @Test
    fun observe_narrowsLevelsAndKeepsDisableInfo() {
        val current = ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            canDisable = true,
            disableKind = ReasoningCapability.DisableKind.TOGGLE_FIELD,
            source = ReasoningCapability.Source.VENDOR_TABLE
        )
        ReasoningLearning.observe(
            "p1",
            "deepseek-v4-pro",
            IllegalStateException("""level "max" not supported, valid levels: low, high"""),
            current
        )

        val learned = ReasoningLearning.recall("p1", "deepseek-v4-pro")!!
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), learned.levels)
        // 错误文案里读不到「能否关闭」，沿用已知能力。
        assertTrue(learned.canDisable)
        assertEquals(ReasoningCapability.Source.LEARNED, learned.source)
    }

    @Test
    fun observe_isScopedPerProvider() {
        ReasoningLearning.observe(
            "p1", "gpt-5.6-sol",
            IllegalStateException("valid levels: low, high"), null
        )
        assertTrue(ReasoningLearning.recall("p1", "gpt-5.6-sol") != null)
        // 换渠道（providerId 不同）不能串台：同一模型在不同中转站白名单可能不同。
        assertNull(ReasoningLearning.recall("p2", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", "gpt-6-sol"))
    }

    @Test
    fun observe_unparsableError_learnsNothing() {
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("429 rate limited"), null)
        assertNull(ReasoningLearning.recall("p1", "gpt-5.6-sol"))
    }

    @Test
    fun observe_ignoresBlankKeys() {
        ReasoningLearning.observe("", "gpt-5.6-sol", IllegalStateException("valid levels: low"), null)
        ReasoningLearning.observe("p1", "", IllegalStateException("valid levels: low"), null)
        assertNull(ReasoningLearning.recall("", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", ""))
    }

    @Test
    fun clear_removesLearnedEntries() {
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low"), null)
        assertFalse(ReasoningLearning.recall("p1", "m") == null)
        ReasoningLearning.clear(providerId = "p1")
        assertNull(ReasoningLearning.recall("p1", "m"))
    }
}
