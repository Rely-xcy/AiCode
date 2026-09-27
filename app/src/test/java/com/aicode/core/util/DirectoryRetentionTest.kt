package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectoryRetentionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeFile(name: String, bytes: Int, ageDays: Long): File {
        val file = File(tempFolder.root, name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(System.currentTimeMillis() - ageDays * 24L * 60 * 60 * 1000)
        return file
    }

    @Test
    fun prune_withinLimits_keepsEverything() {
        writeFile("a.log", 100, 0)
        writeFile("b.log", 100, 1)

        val freed = DirectoryRetention.prune(tempFolder.root, maxAgeDays = 7, maxBytes = 10_000, maxFiles = 10)

        assertEquals(0L, freed)
        assertEquals(2, tempFolder.root.listFiles()?.size)
    }

    @Test
    fun prune_overFileCount_dropsOldestFirst() {
        writeFile("old.log", 100, 5)
        writeFile("mid.log", 100, 2)
        writeFile("new.log", 100, 0)

        DirectoryRetention.prune(tempFolder.root, maxAgeDays = 30, maxBytes = 10_000, maxFiles = 2)

        assertFalse("最旧的应被删除", File(tempFolder.root, "old.log").exists())
        assertTrue(File(tempFolder.root, "mid.log").exists())
        assertTrue(File(tempFolder.root, "new.log").exists())
    }

    @Test
    fun prune_overByteLimit_deletesUntilUnderLimit() {
        writeFile("old.log", 400, 5)
        writeFile("new.log", 400, 0)

        DirectoryRetention.prune(tempFolder.root, maxAgeDays = 30, maxBytes = 500, maxFiles = 10)

        assertFalse(File(tempFolder.root, "old.log").exists())
        assertTrue(File(tempFolder.root, "new.log").exists())
    }

    @Test
    fun prune_overAge_deletesExpiredOnly() {
        writeFile("expired.log", 100, 10)
        writeFile("fresh.log", 100, 1)

        DirectoryRetention.prune(tempFolder.root, maxAgeDays = 7, maxBytes = 10_000, maxFiles = 10)

        assertFalse(File(tempFolder.root, "expired.log").exists())
        assertTrue(File(tempFolder.root, "fresh.log").exists())
    }

    @Test
    fun prune_namePrefix_ignoresOtherFiles() {
        writeFile("keep.txt", 100, 30)
        writeFile("drop.log", 100, 30)

        DirectoryRetention.prune(
            tempFolder.root,
            maxAgeDays = 1,
            maxBytes = 10_000,
            maxFiles = 10,
            namePrefix = "drop"
        )

        assertTrue("前缀不匹配的文件不该被删", File(tempFolder.root, "keep.txt").exists())
        assertFalse(File(tempFolder.root, "drop.log").exists())
    }
}
