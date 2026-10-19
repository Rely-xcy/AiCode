package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 压缩进度的状态转移：界面只靠这几个事件决定「显示什么、什么时候收起来」。
 *
 * 起点 Idle → 开始/步数更新 → Done；失败也直接到 Done（失败提示由既有的失败卡片负责，
 * 不再叠一条进度）。与压缩无关的事件不能把状态改掉。
 */
class CompactionProgressTest {

    @Test
    fun `开始到步数更新再到结束`() {
        var state: CompactionProgress = CompactionProgress.Idle

        state = CompactionProgress.next(state, AgentEvent.CompactionStarted(estimatedTokens = 60_000))
        assertEquals(CompactionProgress.Running(step = 0, totalSteps = 0), state)

        state = CompactionProgress.next(state, AgentEvent.CompactionProgressChanged(step = 2, totalSteps = 5))
        assertEquals(CompactionProgress.Running(step = 2, totalSteps = 5), state)

        state = CompactionProgress.next(state, AgentEvent.CompactionFinished)
        assertEquals(CompactionProgress.Done, state)
    }

    @Test
    fun `失败也收敛到结束，不留在进行中`() {
        var state: CompactionProgress =
            CompactionProgress.next(CompactionProgress.Idle, AgentEvent.CompactionStarted(1_000))
        state = CompactionProgress.next(state, AgentEvent.CompactionProgressChanged(step = 3, totalSteps = 5))

        state = CompactionProgress.next(state, AgentEvent.CompactionFailed("摘要模型挂了"))

        assertEquals(CompactionProgress.Done, state)
    }

    @Test
    fun `无关事件不改变状态`() {
        val running = CompactionProgress.Running(step = 1, totalSteps = 2)

        val afterTools = CompactionProgress.next(running, AgentEvent.ToolCallStarted("t1", "readFile", "{}"))
        val afterDelta = CompactionProgress.next(running, AgentEvent.AssistantDelta("继续"))

        assertEquals(running, afterTools)
        assertEquals(running, afterDelta)
    }
}
