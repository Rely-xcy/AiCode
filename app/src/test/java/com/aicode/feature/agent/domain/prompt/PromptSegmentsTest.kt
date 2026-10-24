package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段级哈希与跨轮比对：哈希必须稳定（同样的段两次构建得到同样的值，否则日志里的 changed 全是噪声），
 * 且只有真正变了的段进 changed。
 */
class PromptSegmentsTest {

    private fun tracker(limit: Int = 32) = PromptSegmentTracker(limit)

    @Test
    fun shortHash_与_sha256_前八字节一致() {
        // 固定向量：任何实现漂移（换摘要算法、换编码）都会在这里暴露。
        assertEquals("ba7816bf8f01cfea", PromptSegmentTracker.shortHash("abc"))
        assertEquals("e3b0c44298fc1c14", PromptSegmentTracker.shortHash(""))
        assertEquals("99aea2f9131ad6da", PromptSegmentTracker.shortHash("技能"))
    }

    @Test
    fun shortHash_同一输入两次相同_不同输入不同() {
        val first = PromptSegmentTracker.shortHash("## 可用技能\n- a: b")
        val second = PromptSegmentTracker.shortHash("## 可用技能\n- a: b")
        assertEquals(first, second)
        assertFalse(first == PromptSegmentTracker.shortHash("## 可用技能\n- a: c"))
        assertEquals("短哈希定长 16 字符", 16, first.length)
    }

    @Test
    fun 首次构建_changed_为_all_且_reused_为零() {
        val diff = tracker().record(KEY, listOf(seg("var:skills", "A"), seg("var:tools", "B")))

        assertTrue(diff.firstBuild)
        assertEquals("all", diff.changedText())
        assertEquals(0, diff.reused)
        assertTrue(diff.changed.isEmpty())
    }

    @Test
    fun 段落与取值都不变_第二轮全部复用() {
        val subject = tracker()
        subject.record(KEY, listOf(seg("var:skills", "A"), seg("var:tools", "B")))

        val diff = subject.record(KEY, listOf(seg("var:skills", "A"), seg("var:tools", "B")))

        assertFalse(diff.firstBuild)
        assertTrue(diff.changed.isEmpty())
        assertEquals("none", diff.changedText())
        assertEquals(2, diff.reused)
    }

    @Test
    fun 单段变化_只有该段进_changed() {
        val subject = tracker()
        subject.record(
            KEY,
            listOf(seg("frag:00-identity", "身份"), seg("var:skills", "A"), seg("var:tools", "B"))
        )

        val diff = subject.record(
            KEY,
            listOf(seg("frag:00-identity", "身份"), seg("var:skills", "A2"), seg("var:tools", "B"))
        )

        assertEquals("只有技能那段变了", listOf("var:skills"), diff.changed)
        assertEquals("另外两段复用", 2, diff.reused)
    }

    @Test
    fun 段落消失_记为减号前缀_与改内容区分开() {
        val subject = tracker()
        subject.record(KEY, listOf(seg("var:skills", "A"), seg("var:tools", "B")))

        val diff = subject.record(KEY, listOf(seg("var:skills", "A")))

        assertEquals(listOf("-var:tools"), diff.changed)
        assertEquals(1, diff.reused)
    }

    @Test
    fun 不同会话互不影响_各自首次构建都是_all() {
        val subject = tracker()
        subject.record("s1@/root/workspace", listOf(seg("var:skills", "A")))

        val diff = subject.record("s2@/root/workspace", listOf(seg("var:skills", "A")))

        assertTrue("另一个会话不该复用上一个会话的快照", diff.firstBuild)
    }

    @Test
    fun 超过上限整体清空_避免长期累积() {
        val subject = tracker(limit = 1)
        subject.record("s1", listOf(seg("var:skills", "A")))
        subject.record("s2", listOf(seg("var:skills", "A")))

        val diff = subject.record("s3", listOf(seg("var:skills", "A")))

        assertTrue(diff.firstBuild)
        // 清空只影响「上一次快照」，不影响本次结果
        assertFalse(subject.record("s3", listOf(seg("var:skills", "A"))).firstBuild)
    }

    @Test
    fun 段哈希取内容_不看_volatile_标记() {
        // volatile 只影响日志归类，不影响比对：否则同一份内容会在两轮间被算成「变了」。
        val subject = tracker()
        subject.record(KEY, listOf(PromptSegment("var:date", "2026-10-24", volatile = true)))

        val diff = subject.record(KEY, listOf(PromptSegment("var:date", "2026-10-24", volatile = true)))

        assertEquals(1, diff.reused)
        assertTrue(diff.changed.isEmpty())
    }

    private fun seg(name: String, content: String) = PromptSegment(name, content)

    private companion object {
        const val KEY = "session-1@/root/workspace"
    }
}
