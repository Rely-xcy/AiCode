package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保留区起点的自适应收敛（[convergeSplitIndex]）。
 *
 * 这几条钉住的是「超大会话也能真正压下来」：摘要窗口把 head 卡得很小时，tail 占掉几乎整段历史，
 * 折完不比原来小，最终判据会整体放弃本次压缩。收敛步骤负责把 tail 起点往后挪（多折、少留），
 * 同时不许跨过地板（最近这一轮必须留在原文里）。
 *
 * 全部断言只用本地估算，不碰模型、不碰 IO；消息一律用汉字构造（[TokenEstimator] 里 CJK 约 1 字 1 token），
 * 期望值尽量按 [TokenEstimator] 现算，不硬编码具体数值，避免估算口径微调就让用例失稳。
 */
class ContextSplitConvergenceTest {

    private fun big(tokens: Int): AgentMessage = AgentMessage.UserMessage(content = "汉".repeat(tokens))

    private fun fill(count: Int, tokens: Int): List<AgentMessage> = List(count) { big(tokens) }

    /** 按「tail 至少留 [MIN_TAIL_TOKENS]」自己再算一遍最靠后的合法切点，用来核对地板位置。 */
    private fun floorByTokens(messages: List<AgentMessage>): Int {
        var tokens = 0
        for (index in messages.indices.reversed()) {
            tokens += TokenEstimator.estimateMessage(messages[index])
            if (tokens >= MIN_TAIL_TOKENS) return index
        }
        return 0
    }

    @Test
    fun `head 已经装下大头时切点不动`() {
        // 10 条各约 2000 token，切在 7：head 约 14000、tail 约 6000，折完远小于原估算
        val messages = fill(10, 2_000)
        val estimated = TokenEstimator.estimateMessages(messages)
        val initialSplit = 7
        assertTrue(
            "用例前提：折完确实更小",
            projectedCompactedTokens(messages, initialSplit) < estimated
        )

        val shrink = convergeSplitIndex(messages, initialSplit, estimated)

        assertEquals("无需收缩时切点必须原样返回", initialSplit, shrink.splitIndex)
        assertEquals("无需收缩时不应计轮数", 0, shrink.rounds)
    }

    @Test
    fun `tail 占满历史时收缩一轮就够`() {
        // 头一条很小、后面每条约 2000 token：摘要窗口会把 head 卡到很小，tail 于是占了几乎全部历史
        val messages = listOf(big(100)) + fill(19, 2_000)
        val estimated = TokenEstimator.estimateMessages(messages)
        val initialSplit = 1
        assertTrue(
            "用例前提：不收缩就省不下空间",
            projectedCompactedTokens(messages, initialSplit) >= estimated
        )

        val shrink = convergeSplitIndex(messages, initialSplit, estimated)

        assertTrue("切点必须往后挪（多折）", shrink.splitIndex > initialSplit)
        assertEquals("按 token 差值一次挪到位，一轮即可", 1, shrink.rounds)
        assertTrue(
            "收缩后必须真的更小",
            projectedCompactedTokens(messages, shrink.splitIndex) < estimated
        )
    }

    @Test
    fun `撑不到更小就停在地板`() {
        // 每条约 100 token、总量约 1500：要省出摘要那 1600 token 就得把 tail 折光，地板不允许
        val messages = listOf(big(50)) + fill(14, 100)
        val estimated = TokenEstimator.estimateMessages(messages)
        val initialSplit = 1
        assertTrue(
            "用例前提：不收缩就省不下空间",
            projectedCompactedTokens(messages, initialSplit) >= estimated
        )

        val shrink = convergeSplitIndex(messages, initialSplit, estimated)

        assertEquals("必须停在地板处", floorByTokens(messages), shrink.splitIndex)
        assertTrue(
            "保留区不得少于地板 token",
            TokenEstimator.estimateMessages(messages.subList(shrink.splitIndex, messages.size)) >= MIN_TAIL_TOKENS
        )
        assertTrue(
            "折完仍然不更小时宁可不动，也不越过地板",
            projectedCompactedTokens(messages, shrink.splitIndex) >= estimated
        )
    }

    @Test
    fun `切点从不超过条数地板`() {
        // 小尾巴 + 需要多折：每条约 400 token，要省出摘要那 1600 token 得折掉四条，但条数地板只允许折到 2
        val messages = listOf(big(50)) + fill(5, 400)
        val estimated = TokenEstimator.estimateMessages(messages)
        assertTrue(
            "用例前提：不收缩就省不下空间",
            projectedCompactedTokens(messages, 1) >= estimated
        )

        val shrink = convergeSplitIndex(messages, 1, estimated)

        assertEquals("切点必须停在条数地板", messages.size - MIN_TAIL_MESSAGES, shrink.splitIndex)
        assertTrue("切点必须仍小于总条数", shrink.splitIndex < messages.size)
    }

    @Test
    fun `原估算越大切点越靠前（候选单调）`() {
        val messages = listOf(big(50)) + fill(29, 100)
        val initialSplit = 1
        val estimates = listOf(2_000, 2_500, 3_000, 3_500, 4_000, 6_000, 100_000)

        val picked = estimates.map { convergeSplitIndex(messages, initialSplit, it).splitIndex }

        // 原估算越大 = 收缩空间越小 = 越不需要多折，切点只能相同或更靠前
        picked.zipWithNext().forEach { (previous, next) ->
            assertTrue("切点随原估算增大必须单调不增：$picked", next <= previous)
        }
        assertEquals("估算远大于历史时不该收缩", initialSplit, picked.last())
    }
}
