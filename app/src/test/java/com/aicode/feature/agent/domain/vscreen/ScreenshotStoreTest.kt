package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 截图目录容量治理的纯逻辑测试：LRU 删最久未改、只删到预算内、未超预算不动手，
 * 以及**只删自己写的截图**——截图目录由调用方指定，误删会把用户的文件删掉。
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
        shot("vscreen-8-1000.png", 100, 1_000)
        shot("vscreen-8-2000.png", 100, 2_000)
        val result = store.prune(temp.root, maxTotalBytes = 1_000)
        assertEquals(0, result.deleted)
        assertEquals(0L, result.freedBytes)
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test
    fun `超预算时按最久未改先删`() {
        val oldest = shot("vscreen-8-1000.png", 400, 1_000)
        val middle = shot("vscreen-8-2000.png", 400, 2_000)
        val newest = shot("vscreen-8-3000.png", 400, 3_000)

        // 自己写的截图总 1200 字节，预算 500：必须删到只剩一张。
        val result = store.prune(temp.root, maxTotalBytes = 500)
        assertEquals(2, result.deleted)
        assertEquals(800L, result.freedBytes)
        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue("最新一张保留", newest.exists())
    }

    @Test
    fun `只删自己的截图，目录里其它文件一个都不动`() {
        // 用户自己的文件最老、体积最大：按纯 LRU 会被先删，但命名不属于本应用，必须留着。
        val userFile = shot("notes.png", 4_000, 500)
        val similarButNotOurs = shot("vscreen-notes.png", 1_000, 600)
        val ownOld = shot("vscreen-8-1000.png", 400, 1_000)
        val ownNew = shot("vscreen-8-2000.png", 400, 2_000)

        val result = store.prune(temp.root, maxTotalBytes = 500)

        assertEquals("只该删自己最老的一张", 1, result.deleted)
        assertEquals(400L, result.freedBytes)
        assertFalse(ownOld.exists())
        assertTrue(ownNew.exists())
        assertTrue("别人的文件不能被当成截图清理", userFile.exists())
        assertTrue("前缀像但命名不符的也不能删", similarButNotOurs.exists())
    }

    @Test
    fun `目录不存在时安全返回`() {
        val missing = File(temp.root, "not-there")
        val result = store.prune(missing, maxTotalBytes = 10)
        assertEquals(0, result.deleted)
    }

    @Test
    fun `单文件超限只告警不删除`() {
        val big = shot("vscreen-8-1000.png", 64, 1_000)
        val warned = store.warnIfOversized(big)
        assertFalse("默认上限 5MB，64 字节不该告警", warned)
        assertTrue(big.exists())
    }

    @Test
    fun `写入方与清理方共用同一份命名规则`() {
        val file = store.newScreenshotFile(temp.root, displayId = 8, nowMs = 1_700_000_000_000)
        assertTrue("新截图必须落在自己的命名规则里，否则永远清不掉", store.isOwnScreenshot(file.name))
        assertEquals(8, file.name.removePrefix(ScreenshotStore.FILE_PREFIX).substringBefore('-').toInt())
    }

    @Test
    fun `命名规则不接纳别的文件`() {
        assertFalse(store.isOwnScreenshot(null))
        assertFalse(store.isOwnScreenshot("notes.png"))
        assertFalse(store.isOwnScreenshot("vscreen-8-1000.jpg"))
        assertFalse(store.isOwnScreenshot("vscreen--1000.png"))
        assertFalse(store.isOwnScreenshot("vscreen-8-1000.png.bak"))
        assertTrue(store.isOwnScreenshot("vscreen-8-1000.png"))
    }
}
