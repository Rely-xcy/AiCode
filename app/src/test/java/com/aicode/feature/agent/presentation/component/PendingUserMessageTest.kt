package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 乐观上屏（待落库用户气泡）的退场判据：id 相等，共三条——落库行同 id、VM 入了队、VM 把这条标为作废。
 *
 * 发送时预生成的 id 就是这条消息落库行的主键，所以「气泡退场」= 「库里出现了同 id 的行」/「这条被 VM 入了队
 * （队列面板接手）」/「VM 明说这条已经丢掉了」。这条性质只由 [resolvePendingUserMessages] 负责，
 * 用例直接锁它——回退成「文本 + 时间戳」的相似匹配（见 sameTextSentTwice_isMatchedByIdNotText）会红。
 */
class PendingUserMessageTest {

    private fun pending(id: String, text: String = "hi") = PendingUserMessage(id = id, text = text)

    @Test
    fun bubbleStaysUntilAnySignalArrives() {
        // 直发但还没落库：气泡必须还在，否则「乐观上屏」等于没做
        val resolving = listOf(pending("m1"))
        assertEquals(resolving, resolvePendingUserMessages(resolving, emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun directSend_bubbleRetiresWhenItsRowLands() {
        // 直发路径：落库行 id 与气泡 id 相同 → 气泡退场，由列表项承载（同一条不同时显示两遍）
        val resolving = listOf(pending("m1"))
        assertTrue(resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun queuedSend_bubbleRetiresToQueuePanel() {
        // 入队路径：VM 把它排进队列（气泡与队列面板不能同显同一条）→ 改由输入框上方的队列面板承载
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), setOf("m1"), emptySet()).isEmpty()
        )
    }

    @Test
    fun queueDelete_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径一（队列面板删除，AIAgentViewModel.removeQueuedRequest）：既不会落库、也不在队列里，
        // 只有「VM 明说这条作废」才能让它退场
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun interject_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径二（插话转通知，interjectQueuedRequest）：落库的是通知消息（另一个随机 id），原 id 作废
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun rewind_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径三（回退清空队列，executeRewindOption）
        val resolving = listOf(pending("m1"), pending("m2", "另一条"))
        val kept = resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1"))
        assertEquals(listOf("m2"), kept.map { it.id })
    }

    @Test
    fun stopAll_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径四（切换工作区停止全部，stopAllAgents）
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun normalSend_stillRetiresThroughTheSameThreeSignals() {
        // 正常发送仍然退场：作废台账没记它，但它会依次落到「入队 → 落库」两个信号上，不会被台账影响
        val resolving = listOf(pending("m1"))
        assertTrue(resolvePendingUserMessages(resolving, emptySet(), setOf("m1"), emptySet()).isEmpty())
        assertTrue(resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun sameTextSentTwice_isMatchedByIdNotText() {
        // 同一段文本连发两条：第一条已落库、第二条仍在 pending（或已入队）。
        // 按文本比对时第一条会把第二条一起退场（第二条就此消失），按 id 比对不会。
        val resolving = listOf(pending("m1", "再跑一次"), pending("m2", "再跑一次"))
        val kept = resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet())
        assertEquals(listOf("m2"), kept.map { it.id })
    }

    @Test
    fun queuedEntryClaimsOnlyItsOwnId() {
        // 队列里那条的 clientMessageId 只认领它自己，同文本的另一条照旧留在气泡里
        val resolving = listOf(pending("m1", "再跑一次"), pending("m2", "再跑一次"))
        val kept = resolvePendingUserMessages(resolving, emptySet(), setOf("m2"), emptySet())
        assertEquals(listOf("m1"), kept.map { it.id })
    }

    @Test
    fun emptyPending_isNoOp() {
        assertTrue(
            resolvePendingUserMessages(emptyList(), setOf("m1"), setOf("m2"), setOf("m3")).isEmpty()
        )
    }
}
