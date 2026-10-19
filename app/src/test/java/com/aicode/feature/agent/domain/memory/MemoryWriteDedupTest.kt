package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 写入前查重：同 scope / 同 projectRoot 下，规范化 name 相同或正文 Jaccard 达到阈值时
 * 更新原条目（保留原 slug 与 created_at），不再多出一个文件。
 */
class MemoryWriteDedupTest {

    private class Harness(val repository: MemoryRepository, val memoryRoot: File)

    private fun harness(): Harness {
        val root = Files.createTempDirectory("write-dedup").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        val repository = MemoryRepository(
            GlobalMemorySource(installer),
            mockk<ExecutionModeHolder>(relaxed = true),
            installer,
            mockk<ProjectAicodeRoot>(relaxed = true),
            MemoryPendingQueue(installer)
        )
        return Harness(repository, File(root, "memory"))
    }

    private fun memoryFiles(root: File): List<File> =
        root.listFiles { file -> file.isFile && file.extension == "md" }?.toList().orEmpty()

    @Test
    fun `规范化名称相同则更新原条目而不是新建`() {
        val h = harness()

        assertTrue(
            h.repository.saveMemory(
                name = "alpha-note",
                description = "first",
                content = "first body",
                scope = MemoryScope.GLOBAL,
                projectRoot = null,
                createdAt = 1_000L
            )
        )
        assertTrue(
            h.repository.saveMemory(
                name = "ALPHA-NOTE",
                description = "second",
                content = "second body",
                scope = MemoryScope.GLOBAL,
                projectRoot = null
            )
        )

        val files = memoryFiles(h.memoryRoot)
        assertEquals(1, files.size)
        val parsed = MemoryParser.parse(files.single(), MemoryScope.GLOBAL)
        assertNotNull(parsed)
        assertEquals("alpha-note", parsed!!.name)
        assertEquals("second body", parsed.content)
        assertEquals(1_000L, parsed.createdAt)
    }

    @Test
    fun `正文相似度恰好达到阈值时更新原条目`() {
        val h = harness()

        h.repository.saveMemory(
            name = "alpha",
            description = "d",
            content = "alpha beta gamma delta",
            scope = MemoryScope.GLOBAL,
            projectRoot = null
        )
        h.repository.saveMemory(
            name = "beta",
            description = "d",
            content = "alpha beta gamma",
            scope = MemoryScope.GLOBAL,
            projectRoot = null
        )

        val files = memoryFiles(h.memoryRoot)
        assertEquals(1, files.size)
        val parsed = MemoryParser.parse(files.single(), MemoryScope.GLOBAL)
        assertNotNull(parsed)
        assertEquals("alpha", parsed!!.name)
        assertEquals("alpha beta gamma", parsed.content)
    }

    @Test
    fun `正文相似度低于阈值时新建条目`() {
        val h = harness()

        h.repository.saveMemory(
            name = "alpha",
            description = "d",
            content = "alpha beta gamma delta",
            scope = MemoryScope.GLOBAL,
            projectRoot = null
        )
        h.repository.saveMemory(
            name = "beta",
            description = "d",
            content = "alpha beta gamma epsilon",
            scope = MemoryScope.GLOBAL,
            projectRoot = null
        )

        assertEquals(2, memoryFiles(h.memoryRoot).size)
    }

    @Test
    fun `Jaccard 阈值边界：3of4 算重复，3of5 不算`() {
        val base = setOf("alpha", "beta", "gamma", "delta")
        val sameThreshold = setOf("alpha", "beta", "gamma")
        val belowThreshold = setOf("alpha", "beta", "gamma", "epsilon")

        assertEquals(0.75, MemoryRepository.jaccard(base, sameThreshold), 0.000001)
        assertEquals(0.6, MemoryRepository.jaccard(base, belowThreshold), 0.000001)
        assertTrue(MemoryRepository.isDuplicateContent("alpha beta gamma delta", "alpha beta gamma"))
        assertFalse(MemoryRepository.isDuplicateContent("alpha beta gamma delta", "alpha beta gamma epsilon"))
    }
}
