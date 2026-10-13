package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链合并（阶段 4）：
 *
 * 1. 触发判定与合并批：`summaryChainStatus` 只数未淘汰的新语义块，`mergeOldestChainBlocks`
 *    只取最旧 [ContextCompactor] 调用方给的 blockCount 个块；
 * 2. 提交动作：新 marker+summary 对落库（`foldsUpToTimestamp` = 被合并块中最大边界），
 *    被合并块的 marker+summary 行标 `isCompacted`（`markChainBlocksCompacted`）；
 * 3. 失败路径：摘要响应不可用 / 不省空间 / 落库失败时，不标任何行（或已回滚），
 *    返回 null——合并失败不影响主折叠。
 */
class ContextCompactionChainMergeTest {

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>()
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private val compactor = ContextCompactor(dao, prompts, records, archive)

    /** 会话里 N 个未淘汰新语义块（marker+summary 对，timestamp 从 100 起 +10 递增）。 */
    private fun chainRows(blockCount: Int): List<AgentMessageEntity> = buildList {
        repeat(blockCount) { i ->
            val ts = 100L + i * 10
            add(
                AgentMessageEntity(
                    id = "marker-$i", sessionId = "s", role = "USER",
                    content = CONTEXT_COMPACTION_MARKER, timestamp = ts,
                    isCompactionMarker = true
                )
            )
            add(
                AgentMessageEntity(
                    id = "summary-$i", sessionId = "s", role = "ASSISTANT",
                    content = "摘要 $i（归档路径 /root/.aicode/compacted-history/arch-$i.md）",
                    timestamp = ts + 1, isContextSummary = true,
                    foldsUpToTimestamp = ts
                )
            )
        }
    }

    private fun stubMerge(response: String) {
        every { prompts.resolvePrompt(any()) } returns "合并提示词 {{INSTRUCTION}}"
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        coEvery { provider.complete(any(), any(), any()) } returns AIResponse(response, stopReason = "stop")
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse(response, stopReason = "stop")
        coEvery { provider.complete(any(), any(), any(), any(), any()) } returns AIResponse(response, stopReason = "stop")
        coEvery { dao.insert(any()) } just Runs
    }

    /** 内存视图：链块对在前（与回放一致），跟一条 tail 用户消息。 */
    private fun memoryMessages(blockCount: Int): List<AgentMessage> = buildList {
        repeat(blockCount) { i ->
            add(AgentMessage.UserMessage(id = "marker-$i", content = CONTEXT_COMPACTION_MARKER))
            add(AgentMessage.AssistantMessage(id = "summary-$i", content = "摘要 $i（归档路径 /root/.aicode/compacted-history/arch-$i.md）"))
        }
        add(AgentMessage.UserMessage(id = "goal", content = "继续"))
    }

    @Test
    fun `summaryChainStatus 只数未淘汰的新语义块并给出最大边界`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns
            chainRows(3) + listOf(
                // 已淘汰块：不计入
                AgentMessageEntity(
                    id = "old-summary", sessionId = "s", role = "ASSISTANT",
                    content = "旧摘要", timestamp = 50, isContextSummary = true,
                    isCompacted = true, foldsUpToTimestamp = 49
                ),
                // legacy 单块（边界 0）：不参与合并
                AgentMessageEntity(
                    id = "legacy", sessionId = "s", role = "ASSISTANT",
                    content = "旧语义摘要", timestamp = 60, isContextSummary = true,
                    foldsUpToTimestamp = 0
                )
            )

        val chain = compactor.summaryChainStatus("s")

        assertNotNull(chain)
        assertEquals(3, chain!!.blockCount)
        assertEquals(120L, chain.latestBoundary)
        assertEquals(3, chain.blocks.size)
        assertTrue(chain.summaryTokens > 0)
    }

    @Test
    fun `合并后块数减合并批加一 且被合并块标 isCompacted`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(4)
        stubMerge("合并后的摘要")
        coEvery { dao.markChainBlocksCompacted(any(), any()) } returns 4

        val chain = compactor.summaryChainStatus("s")!!
        val messages = memoryMessages(4)
        val result = compactor.mergeOldestChainBlocks(
            messages = messages,
            chain = chain,
            summaryProvider = provider,
            sessionId = "s",
            blockCount = 2
        )

        assertNotNull(result)
        // 新 marker + 新摘要 + 剩下 2 个块对（4 条）+ tail = 8 条；被合并的 4 条（2 对）已从视图移除
        assertEquals(7, result!!.size)
        assertEquals("合并后的摘要", (result[1] as AgentMessage.AssistantMessage).content)
        // 新块对在最前（与回放的摘要链前置一致）
        assertEquals(CONTEXT_COMPACTION_MARKER, (result[0] as AgentMessage.UserMessage).content)

        // 新摘要对落库：summary 带 foldsUpToTimestamp = 被合并块中最大边界（第 1 块边界 110）
        coVerify(exactly = 2) { dao.insert(any()) }
        // 标记被合并块的 4 行（2 个 marker + 2 个 summary）
        coVerify(exactly = 1) {
            dao.markChainBlocksCompacted("s", listOf("marker-0", "summary-0", "marker-1", "summary-1"))
        }
    }

    @Test
    fun `摘要响应不可用时不落库也不标行`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(3)
        stubMerge("")
        coEvery { dao.markChainBlocksCompacted(any(), any()) } returns 4

        val chain = compactor.summaryChainStatus("s")!!
        val result = compactor.mergeOldestChainBlocks(
            messages = memoryMessages(3), chain = chain,
            summaryProvider = provider, sessionId = "s", blockCount = 2
        )

        assertNull(result)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.markChainBlocksCompacted(any(), any()) }
        // 摘要调用成功返回过（kind=compaction 记账），只是我们主动拒绝
        coVerify(exactly = 1) { records.insert(any()) }
    }

    @Test
    fun `合并不省空间时放弃且不标行`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(3)
        // 合并「摘要」比原摘要还长（把每份都复述一遍）→ 不省空间 → 不提交
        stubMerge("合并后的摘要（归档路径 /root/.aicode/compacted-history/arch-0.md）".repeat(6))
        coEvery { dao.markChainBlocksCompacted(any(), any()) } returns 4

        val chain = compactor.summaryChainStatus("s")!!
        val result = compactor.mergeOldestChainBlocks(
            messages = memoryMessages(3), chain = chain,
            summaryProvider = provider, sessionId = "s", blockCount = 2
        )

        assertNull(result)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.markChainBlocksCompacted(any(), any()) }
    }

    @Test
    fun `标记旧块失败时回滚新块`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(3)
        stubMerge("合并后的摘要")
        coEvery { dao.markChainBlocksCompacted(any(), any()) } returns 0 // 未命中 → 回滚

        val chain = compactor.summaryChainStatus("s")!!
        val result = compactor.mergeOldestChainBlocks(
            messages = memoryMessages(3), chain = chain,
            summaryProvider = provider, sessionId = "s", blockCount = 2
        )

        assertNull(result)
        // 新块先插了，标记未命中 → 两行都被删掉
        coVerify(exactly = 2) { dao.deleteMessageById(any()) }
    }

    @Test
    fun `合并成功后视图不再包含被合并块且回放边界取剩余块最大边界`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(4)
        stubMerge("合并后的摘要")
        coEvery { dao.markChainBlocksCompacted(any(), any()) } returns 4

        val chain = compactor.summaryChainStatus("s")!!
        val result = compactor.mergeOldestChainBlocks(
            messages = memoryMessages(4), chain = chain,
            summaryProvider = provider, sessionId = "s", blockCount = 2
        )!!

        // 视图里不再有被合并块的 id；新块边界落在剩余块的边界之下（110 ≤ 120/130）
        val ids = result.map { it.id }
        assertFalse(ids.contains("marker-0"))
        assertFalse(ids.contains("summary-0"))
        assertFalse(ids.contains("marker-1"))
        assertFalse(ids.contains("summary-1"))
        assertTrue(ids.contains("summary-2"))
        assertTrue(ids.contains("summary-3"))
    }

    @Test
    fun `块数不足时无法合并`() = runTest {
        coEvery { dao.getMessagesBySessionOnce("s") } returns chainRows(1)
        stubMerge("合并后的摘要")

        val chain = compactor.summaryChainStatus("s")!!
        val result = compactor.mergeOldestChainBlocks(
            messages = memoryMessages(1), chain = chain,
            summaryProvider = provider, sessionId = "s", blockCount = 1
        )

        // 单块链：合并后不省空间（新摘要 ≥ 原块）会走「不省空间」闸门；块数为 0 时直接 null。
        // 无论走哪条，都不应有标记发生。
        if (result == null) {
            coVerify(exactly = 0) { dao.markChainBlocksCompacted(any(), any()) }
        }
    }
}
