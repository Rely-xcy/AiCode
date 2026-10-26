package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 记忆清单缓存：签名不变不重扫，增删与显式 invalidate 后必须重扫。
 *
 * 这里只钉「什么时候会重扫」——重扫出来的是不是同一批文件由 [MemoryFileCache] 与各数据源负责。
 */
class MemoryListCacheTest {

    private fun tempRoot(): File = Files.createTempDirectory("memory-list-cache").toFile()

    private fun memory(name: String) = Memory(
        name = name,
        description = "",
        scope = MemoryScope.GLOBAL,
        content = "body $name"
    )

    @Test
    fun `签名不变时第二次直接复用上次结果`() {
        val root = tempRoot()
        File(root, "a.md").writeText("a")
        var calls = 0
        val first = MemoryListCache.list(root) { calls++; listOf(memory("a")) }
        val second = MemoryListCache.list(root) { calls++; listOf(memory("a")) }

        assertEquals(1, calls)
        assertSame(first, second)
    }

    @Test
    fun `新增与删除记忆文件都会重扫`() {
        val root = tempRoot()
        File(root, "a.md").writeText("a")
        var calls = 0
        MemoryListCache.list(root) { calls++; listOf(memory("a")) }
        assertEquals(1, calls)

        File(root, "b.md").writeText("b")
        MemoryListCache.list(root) { calls++; listOf(memory("a"), memory("b")) }
        assertEquals("新增条目要重扫", 2, calls)

        File(root, "b.md").delete()
        MemoryListCache.list(root) { calls++; listOf(memory("a")) }
        assertEquals("删除条目要重扫", 3, calls)
    }

    @Test
    fun `目录不存在时缓存空结果，目录建好后重扫`() {
        val root = File(tempRoot(), "memory")
        var calls = 0
        assertEquals(emptyList<Memory>(), MemoryListCache.list(root) { calls++; emptyList() })
        assertEquals(emptyList<Memory>(), MemoryListCache.list(root) { calls++; emptyList() })
        assertEquals("目录不存在时不必反复扫", 1, calls)

        root.mkdirs()
        File(root, "a.md").writeText("a")
        val fresh = MemoryListCache.list(root) { calls++; listOf(memory("a")) }
        assertEquals("目录建好后要重扫", 2, calls)
        assertEquals(1, fresh.size)
    }

    /**
     * 原地改写（同一个 inode 上截断重写）不会改目录条目，签名看不出变化；
     * 本仓的写入路径在写完之后显式 [MemoryListCache.invalidate]，靠这一步兜住。
     */
    @Test
    fun `原地改写后 invalidate 能强制重扫`() {
        val root = tempRoot()
        val file = File(root, "a.md")
        file.writeText("old")
        var calls = 0
        MemoryListCache.list(root) { calls++; listOf(memory("a")) }

        file.writeText("new")
        MemoryListCache.invalidate(root)
        val reloaded = MemoryListCache.list(root) { calls++; listOf(memory("a")) }

        assertEquals(2, calls)
        assertEquals(1, reloaded.size)
    }
}
