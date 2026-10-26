package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耗时与用量合并成一次轮次切分（[computeTurnStats]）后的取值口径。
 *
 * 两个旧入口 [computeTaskDurations] / [computeTurnUsage] 改为直接取 [TurnStats] 的两半，
 * 这里钉住合并前后结果不变：只结算已收工的轮、耗时取轮末助手行减轮首用户行、用量取轮内求和。
 */
class TurnStatsTest {

    private fun user(id: String, timestamp: Long) =
        AgentUIMessage(id = id, role = MessageRole.USER, content = "hi", timestamp = timestamp)

    private fun assistant(
        id: String,
        timestamp: Long,
        input: Int = 0,
        output: Int = 0,
        cached: Int = 0,
    ) = AgentUIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "ok",
        timestamp = timestamp,
        inputTokens = input,
        outputTokens = output,
        cachedInputTokens = cached,
    )

    @Test
    fun finishedTurn_settlesOnLastAssistantRowWithTurnTotalUsage() {
        val stats = computeTurnStats(
            messages = listOf(
                user("u1", 1_000),
                assistant("a1", 1_400, input = 10, output = 2),
                assistant("a2", 4_000, input = 3, output = 7, cached = 1),
            ),
            lastTurnFinished = true,
        )

        assertEquals(mapOf("a2" to 3_000L), stats.taskDurations)
        assertEquals(mapOf("a2" to TurnUsage(inputTokens = 13, outputTokens = 9, cachedInputTokens = 1)), stats.turnUsages)
    }

    @Test
    fun unfinishedTurn_settlesNothing() {
        val stats = computeTurnStats(
            messages = listOf(user("u1", 1_000), assistant("a1", 4_000, input = 5)),
            lastTurnFinished = false,
        )

        assertTrue(stats.taskDurations.isEmpty())
        assertTrue(stats.turnUsages.isEmpty())
    }

    @Test
    fun runningTurnAnchor_skipsThatTurnAndTheOnesAfterIt() {
        val stats = computeTurnStats(
            messages = listOf(
                user("u1", 1_000),
                assistant("a1", 3_000, input = 4),
                user("u2", 5_000),
                assistant("a2", 7_000, input = 6),
            ),
            lastTurnFinished = true,
            runningTurnStartId = "u2",
        )

        assertEquals(mapOf("a1" to 2_000L), stats.taskDurations)
        assertEquals(mapOf("a1" to TurnUsage(inputTokens = 4)), stats.turnUsages)
    }
}
