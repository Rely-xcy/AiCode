package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 命中记账的会话级去重：同一会话同一记忆只记一次；去重表记满时淘汰最旧的一条。
 *
 * 这里只用真实的 [MemoryRepository.recordHits]（它不碰任何注入依赖，只读 memory.file 走文件 IO），
 * 四个构造依赖给 mockk 保持构造可行。
 */
class MemoryHitDedupTest {

    private fun repository() = MemoryRepository(
        mockk<GlobalMemorySource>(relaxed = true),
        mockk<ExecutionModeHolder>(relaxed = true),
        mockk<ContainerInstaller>(relaxed = true),
        mockk<ProjectAicodeRoot>(relaxed = true)
    )

    private fun tempRoot(tag: String): File = Files.createTempDirectory(tag).toFile()

    /** 落一个 hitCount=0 的记忆文件，返回 (文件, 内存态 Memory)。 */
    private fun newMemory(root: File, name: String): Pair<File, Memory> {
        val file = MemorySource.resolveMemoryFile(root, name)
        file.writeText(
            MemoryParser.format(
                name = name,
                description = "d",
                content = "body",
                kind = MemoryKind.PROFILE,
                source = "model-tool",
                createdAt = 1_700_000_000_000L
            )
        )
        return file to Memory(name = name, description = "d", scope = MemoryScope.GLOBAL, file = file, content = "body")
    }

    private fun hitCountOf(file: File): Int? = MemoryParser.parse(file, MemoryScope.GLOBAL)?.hitCount

    @Test
    fun `同一会话重复注入只记一次命中`() {
        val root = tempRoot("hit-dedup")
        val (file, memory) = newMemory(root, "m1")
        val repository = repository()

        repository.recordHits(listOf(memory), "s1")
        repository.recordHits(listOf(memory), "s1")

        val parsed = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(parsed)
        assertEquals(1, parsed.hitCount)
    }

    @Test
    fun `去重表记满时淘汰最旧的一条而不是整表清空`() {
        val root = tempRoot("hit-dedup-full")
        val repository = repository()
        val session = "s1"
        // 500 是去重表上限；第 501 个 key 落表时触发淘汰
        val names = (0..500).map { "m$it" }
        val files = names.associateWith { newMemory(root, it) }

        names.forEach { name ->
            val (_, memory) = files.getValue(name)
            repository.recordHits(listOf(memory), session)
        }

        // 最近记过的 key 必须还在表里：整表清空的话这里会被当成首次命中，hitCount 变 2
        val (recentFile, recentMemory) = files.getValue("m500")
        repository.recordHits(listOf(recentMemory), session)
        assertEquals(1, hitCountOf(recentFile))

        // 被淘汰的必须是最旧的那条：m0 再记一次才重新计数
        val (oldestFile, oldestMemory) = files.getValue("m0")
        repository.recordHits(listOf(oldestMemory), session)
        assertEquals(2, hitCountOf(oldestFile))
    }
}
