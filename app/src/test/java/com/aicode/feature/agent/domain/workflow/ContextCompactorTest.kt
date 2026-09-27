package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 压缩的核心约束：任何路径都不能把「压完仍超窗」的列表原样交回上层，
 * 否则请求会硬撞模型窗口上限（用户看到「本次输入超出模型上下文窗口」）。
 *
 * 窗口元数据走 relaxed mock（contextTokens = 0）→ 回退 128k 兜底，档位为 GENEROUS：
 * 硬阈值 = min(85% × 128k, 128k − 20k) = 108000，发送前兜底预算 = 92% × 128k = 117760。
 */
class ContextCompactorTest {

    private val agentMessageDao = mockk<AgentMessageDao>(relaxed = true)
    private val modelMetadataService = mockk<ModelMetadataService>(relaxed = true)
    private val systemPromptProvider = mockk<SystemPromptProvider>(relaxed = true)
    private val llmCallRecordDao = mockk<LlmCallRecordDao>(relaxed = true)
    private val generalSettingsRepository = mockk<GeneralSettingsRepository>(relaxed = true)
    private val archive = mockk<CompactedHistoryArchive>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)

    private lateinit var compactor: ContextCompactor

    @Before
    fun setUp() {
        coEvery { generalSettingsRepository.compactionThresholdPercent() } returns 85
        coEvery { generalSettingsRepository.softCompactionThresholdPercent() } returns 60
        coEvery { systemPromptProvider.resolvePrompt(any()) } returns "压缩指令 {{INSTRUCTION}}"
        coEvery { archive.archive(any(), any()) } returns "/root/.aicode/compacted-history/session-1.md"
        compactor = ContextCompactor(
            agentMessageDao = agentMessageDao,
            modelMetadataService = modelMetadataService,
            systemPromptProvider = systemPromptProvider,
            llmCallRecordDao = llmCallRecordDao,
            generalSettingsRepository = generalSettingsRepository,
            compactedHistoryArchive = archive
        )
    }

    /** 消息少且没逼近窗口时按条数早退，返回的是副本而不是原列表。 */
    @Test
    fun smallConversation_returnsCopyWithoutCompacting() = runTest {
        val messages = listOf(
            AgentMessage.UserMessage(content = "你好"),
            AgentMessage.AssistantMessage(content = "你好，有什么可以帮你？")
        )

        val result = compactor.compactIfNeeded(messages, provider, sessionId = "s1")

        assertEquals(messages, result)
        assertNotSame(messages, result)
    }

    /** 单条消息本身就超过窗口：压缩动不了它（tail 至少留一条），必须由发送前兜底截断。 */
    @Test
    fun oversizedSingleMessage_isTruncatedBeforeSend() = runTest {
        val messages = listOf(AgentMessage.UserMessage(content = "测".repeat(200_000)))

        val result = compactor.compactIfNeeded(messages, provider, sessionId = "s1")

        val truncated = result.single() as AgentMessage.UserMessage
        assertTrue("单条超大消息应被截断", truncated.content.length < 200_000)
        assertTrue("截断处应留下可见标记", truncated.content.contains("已省略中间"))
        assertTrue(
            "兜底后估算应明显低于原值",
            TokenEstimator.estimateMessages(result) < 120_000
        )
    }

    /** 未达阈值但单条已超窗时，同样要在发出前截断。 */
    @Test
    fun belowThresholdButOverWindow_stillGuarded() = runTest {
        val messages = listOf(
            AgentMessage.UserMessage(content = "先做点小事"),
            AgentMessage.ToolResultMessage(toolName = "executeCommand", result = "x".repeat(600_000))
        )

        val result = compactor.compactIfNeeded(messages, provider, sessionId = "s1")

        assertTrue(
            "工具结果应被兜底截断",
            TokenEstimator.estimateMessages(result) <= 118_000
        )
    }

    /** 压缩模型调用失败时不能裸发原列表，要退回兜底截断。 */
    @Test
    fun compactionFailure_fallsBackToTruncation() = runTest {
        coEvery { provider.complete(any(), any(), any(), any()) } throws RuntimeException("boom")
        // 12 条 × 10000 中文字 ≈ 120000 tokens，已过硬阈值 108000。
        val messages = (1..12).map { index ->
            AgentMessage.UserMessage(id = "m$index", content = "测".repeat(10_000))
        }

        val result = compactor.compactIfNeeded(messages, provider, sessionId = "s1")

        assertTrue("失败后应兜底截断而不是原样返回", TokenEstimator.estimateMessages(result) <= 118_000)
    }
}
