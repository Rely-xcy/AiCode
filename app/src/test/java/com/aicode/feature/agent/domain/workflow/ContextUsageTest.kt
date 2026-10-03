package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.domain.model.ModelContextPolicy
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 上下文占用的口径（[ContextUsage] / [ContextUsageHolder]）：界面显示的百分比与压缩触发判定
 * 必须来自同一份数，且用户要能分清显示的是真实 usage 还是本地估算。
 */
class ContextUsageTest {

    private val thresholds = ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 85)

    @Test
    fun `真实 usage 更大时用真实值并标注真实`() {
        val usage = ContextUsage.of("s1", realTokens = 300_000, estimatedTokens = 120_000, thresholds = thresholds)

        assertEquals(300_000, usage.currentTokens)
        assertEquals(ContextUsage.Source.REPORTED, usage.source)
        assertFalse(usage.isEstimated)
    }

    @Test
    fun `本轮新内容还没发出去时取估算并标注估算`() {
        val usage = ContextUsage.of("s1", realTokens = 200_000, estimatedTokens = 900_000, thresholds = thresholds)

        assertEquals(900_000, usage.currentTokens)
        assertEquals(ContextUsage.Source.ESTIMATE, usage.source)
        assertTrue(usage.isEstimated)
    }

    @Test
    fun `provider 没回 usage 时纯估算`() {
        val usage = ContextUsage.of("s1", realTokens = 0, estimatedTokens = 4_000, thresholds = thresholds)

        assertEquals(4_000, usage.currentTokens)
        assertTrue(usage.isEstimated)
    }

    @Test
    fun `百分比与实际生效阈值都取自同一份判定输入`() {
        val usage = ContextUsage.of("s1", realTokens = 500_000, estimatedTokens = 10, thresholds = thresholds)

        assertEquals(0.5f, usage.progress)
        assertEquals(1_000_000, usage.contextLimit)
        assertEquals(400_000, usage.softThreshold)
        assertEquals(850_000, usage.hardThreshold)
        assertTrue(usage.hardEnabled)
    }

    @Test
    fun `窗口为 0 时百分比为 0，不产生 NaN`() {
        val usage = ContextUsage.of("s1", 10, 10, ModelContextPolicy.thresholds(0, softPercent = 40, hardPercent = 85))

        assertEquals(0f, usage.progress)
    }

    @Test
    fun `负数按 0 处理`() {
        val usage = ContextUsage.of("s1", realTokens = -5, estimatedTokens = -5, thresholds = thresholds)

        assertEquals(0, usage.currentTokens)
        assertEquals(0f, usage.progress)
    }

    @Test
    fun `判定值与真实值分开读，界面能分清环长里多少是真发出去过的`() {
        val usage = ContextUsage.of("s1", realTokens = 200_000, estimatedTokens = 900_000, thresholds = thresholds)

        assertEquals(900_000, usage.currentTokens)
        assertEquals(200_000, usage.reportedTokens)
        assertEquals(0.9f, usage.progress)
        assertEquals(0.2f, usage.reportedProgress)
        assertTrue(usage.isEstimated)
    }

    @Test
    fun `holder 按会话各留一份快照，互不覆盖`() {
        val holder = ContextUsageHolder()
        assertTrue(holder.usage.value.isEmpty())

        val first = ContextUsage.of("s1", realTokens = 1, estimatedTokens = 1, thresholds = thresholds)
        holder.publish(first)
        assertEquals(first, holder.usage.value["s1"])

        // 并行跑的其它会话（子代理、后台会话）各存各的：界面按当前会话取，不会被别的会话顶掉
        val second = ContextUsage.of("s2", realTokens = 2, estimatedTokens = 2, thresholds = thresholds)
        holder.publish(second)
        assertEquals(second, holder.usage.value["s2"])
        assertEquals(first, holder.usage.value["s1"])

        // 会话删除后清掉，表里不留已不存在会话的数
        holder.remove("s1")
        assertNull(holder.usage.value["s1"])
        assertEquals(second, holder.usage.value["s2"])
    }

    @Test
    fun `没有会话 id 的判定不进快照表，避免所有会话都取到同一份数`() {
        val holder = ContextUsageHolder()
        holder.publish(ContextUsage.of(null, realTokens = 1, estimatedTokens = 1, thresholds = thresholds))

        assertTrue(holder.usage.value.isEmpty())
    }

    /**
     * 主会话与子会话并发发布时的丢失更新。
     *
     * 旧实现 publish 是「整表读出来（_usage.value）→ 复制 → 整表写回」：两次读之间别的会话写进来的快照
     * 会被这一次写回整表覆盖，而该会话本轮不会再发布第二次，被覆盖掉的快照要等到下一轮才可能自愈。
     * 用例让 16 个协程同时发布（CyclicBarrier 对齐每一轮），每轮所有人发布完立即检查「16 个会话全在」；
     * 失败时把丢快照的轮次列在断言消息里，便于区分「实现有问题」与「没撞上」。
     */
    @Test
    fun `并发发布不同会话时全部保留，不因整表读改写丢更新`() = runBlocking {
        val holder = ContextUsageHolder()
        // 会话数取 16（= ContextUsageHolder 的同时保留上限）：不触发淘汰，断言「全都在」才有意义。
        val ids = (1..16).map { "s$it" }
        val rounds = 200
        // 16 个发布者 + 1 个校验者：barrier 保证「断言时本轮 16 次发布都已发生」。
        val barrier = CyclicBarrier(ids.size + 1)
        val publishers = ids.map { id ->
            launch(Dispatchers.IO) {
                repeat(rounds) { round ->
                    holder.publish(
                        ContextUsage.of(
                            sessionId = id,
                            realTokens = round,
                            estimatedTokens = round,
                            thresholds = thresholds
                        )
                    )
                    barrier.await()
                }
            }
        }
        val lostRounds = mutableListOf<Int>()
        repeat(rounds) { round ->
            barrier.await()
            if (holder.usage.value.keys != ids.toSet()) lostRounds += round
        }
        publishers.forEach { it.join() }
        assertTrue(lostRounds.isEmpty(), "这些轮次丢了会话快照（缺失的会话不再出现在表里）: $lostRounds")
    }
}
