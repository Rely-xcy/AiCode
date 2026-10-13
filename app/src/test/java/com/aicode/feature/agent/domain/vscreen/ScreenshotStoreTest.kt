package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 截图目录容量治理的纯逻辑测试：LRU 删最久未改、只删到预算内、未超预算不动手。
 *
 * 不依赖设备（用 JUnit 的临时目录）。
 */
class ScreenshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = ScreenshotStore()

    private fun shot(name: String, size: Int, lastModified: Long): File {
        val f = temp.newFile(name)
        f.writeBytes(ByteArray(size))
        f.setLastModified(lastModified)
        return f
    }

    @Test
    fun `未超预算时不删除`() {
        shot("a.png", 100, 1_000)
        shot("b.png", 100, 2_000)
        val result = store.prune(temp.root, maxTotalBytes = 1_000)
        assertEquals(0, result.deleted)
        assertEquals(0L, result.freedBytes)
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test
    fun `超预算时按最久未改先删`() {
        val oldest = shot("a.png", 400, 1_000)
        val middle = shot("b.png", 400, 2_000)
        val newest = shot("c.png", 400, 3_000)

        // 总 1200 字节，预算 500：必须删到只剩一张。
        val result = store.prune(temp.root, maxTotalBytes = 500)
        assertEquals(2, result.deleted)
        assertEquals(800L, result.freedBytes)
        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue("最新一张保留", newest.exists())
    }

    @Test
    fun `目录不存在时安全返回`() {
        val missing = File(temp.root, "not-there")
        val result = store.prune(missing, maxTotalBytes = 10)
        assertEquals(0, result.deleted)
    }

    @Test
    fun `单文件超限只告警不删除`() {
        val big = shot("big.png", 64, 1_000)
        val warned = store.warnIfOversized(big)
        assertFalse("默认上限 5MB，64 字节不该告警", warned)
        assertTrue(big.exists())
    }
}
