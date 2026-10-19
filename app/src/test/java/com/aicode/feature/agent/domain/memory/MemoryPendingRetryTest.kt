package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 写入失败的重试队列：抽取失败入队、重试成功移出、同内容幂等丢弃、失败累计后放弃。
 */
class MemoryPendingRetryTest {

    private class Harness(
        val repository: MemoryRepository,
        val global: GlobalMemorySource,
        val queue: MemoryPendingQueue
    )

    private fun harness(saveResult: Boolean = true, existing: List<Memory> = emptyList()): Harness {
        val root = Files.createTempDirectory("pending-retry").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        val global = mockk<GlobalMemorySource>(relaxed = true)
        every { global.listMemories() } returns existing
        every {
            global.saveMemory(any(), any(), any(), any(), any(), any(), any())
        } returns saveResult
        val queue = MemoryPendingQueue(installer)
        val repository = MemoryRepository(
            global,
            mockk<ExecutionModeHolder>(relaxed = true),
            installer,
            mockk<ProjectAicodeRoot>(relaxed = true),
            queue
        )
        return Harness(repository, global, queue)
    }

    @Test
    fun `抽取写盘失败进入重试队列且不计入写入条数`() = runTest {
        val repository = mockk<MemoryRepository>(relaxed = true)
        every { repository.listMemories(any()) } returns emptyList()
        every {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns false
        val queue = mockk<MemoryPendingQueue>(relaxed = true)
        val extractor = MemoryExtractor(repository, queue)
        val output = """
            name: alpha
            description: first
            content: body alpha
        """.trimIndent()

        val written = extractor.extract(
            projectRoot = "/ws",
            history = listOf(AgentMessage.UserMessage(content = "hello")),
            source = MemoryExtractor.SOURCE_PRE_FOLD,
            complete = { output }
        )

        assertEquals(0, written)
        val pending = slot<PendingMemoryEntry>()
        verify(exactly = 1) { queue.enqueue(capture(pending)) }
        val entry = pending.captured
        assertEquals("alpha", entry.name)
        assertEquals("first", entry.description)
        assertEquals("body alpha", entry.content)
        assertEquals(MemoryScope.GLOBAL.name, entry.scope)
        assertEquals("/ws", entry.projectRoot)
        assertEquals(MemoryKind.PROFILE.name, entry.kind)
        assertEquals(MemoryExtractor.SOURCE_PRE_FOLD, entry.source)
    }

    @Test
    fun `重试成功从队列移除`() {
        val h = harness(saveResult = true)
        h.queue.enqueue(PendingMemoryEntry(name = "alpha", description = "d", content = "body alpha"))
        assertEquals(1, h.queue.load().size)

        assertEquals(1, h.repository.retryPendingMemories())

        assertTrue(h.queue.load().isEmpty())
        verify(exactly = 1) {
            h.global.saveMemory("alpha", "d", "body alpha", any(), any(), any(), any())
        }
    }

    @Test
    fun `已存在同内容条目时 pending 项被幂等丢弃`() {
        val existing = Memory(
            name = "alpha",
            description = "d",
            scope = MemoryScope.GLOBAL,
            content = "body alpha"
        )
        val h = harness(saveResult = true, existing = listOf(existing))
        h.queue.enqueue(PendingMemoryEntry(name = "alpha", content = "body alpha"))

        assertEquals(0, h.repository.retryPendingMemories())

        assertTrue(h.queue.load().isEmpty())
        verify(exactly = 0) { h.global.saveMemory(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `重试失败累计 attempts 达到上限后放弃`() {
        val h = harness(saveResult = false)
        h.queue.enqueue(PendingMemoryEntry(name = "alpha", content = "body alpha"))

        assertEquals(0, h.repository.retryPendingMemories())
        assertEquals(1, h.queue.load().single().attempts)

        repeat(3) { h.repository.retryPendingMemories() }
        assertEquals(4, h.queue.load().single().attempts)

        // 第 5 次仍失败：达到上限，放弃并移出队列
        assertEquals(0, h.repository.retryPendingMemories())
        assertTrue(h.queue.load().isEmpty())
    }
}
