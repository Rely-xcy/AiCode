package com.aicode.feature.agent.domain.container

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerInstallerExtractionSecurityTest {

    private val root = Files.createTempDirectory("rootfs-extract-").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val context = FakeContext(filesDir, cacheDir)
    private val installer = ContainerInstaller(context, ContainerOsDetector(context))

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun extractRootfs_hardlinkTargetOutsideRootIsSkippedAndCannotOverwriteSentinel() {
        val dest = File(root, "rootfs").apply { mkdirs() }
        val sentinel = File(root, "sentinel").apply { writeText("keep") }
        val archive = gzipTar(
            hardlink("probe", "../sentinel"),
            regular("probe", "changed")
        )

        installer.extractRootfsTo(dest, ByteArrayInputStream(archive))

        assertEquals("keep", sentinel.readText())
        assertEquals("changed", File(dest, "probe").readText())
    }

    @Test
    fun extractRootfs_hardlinkTargetThroughSymlinkOutsideRootIsSkipped() {
        val dest = File(root, "rootfs").apply { mkdirs() }
        val sentinel = File(root, "sentinel").apply { writeText("keep") }
        Files.createSymbolicLink(File(dest, "outside-link").toPath(), sentinel.toPath())
        val archive = gzipTar(hardlink("alias", "outside-link"))

        installer.extractRootfsTo(dest, ByteArrayInputStream(archive))

        assertEquals("keep", sentinel.readText())
        assertFalse(File(dest, "alias").exists())
    }

    @Test
    fun extractRootfs_hardlinkInsideRootStillWorks() {
        val dest = File(root, "rootfs").apply { mkdirs() }
        val archive = gzipTar(
            regular("target", "content"),
            hardlink("alias", "target")
        )

        installer.extractRootfsTo(dest, ByteArrayInputStream(archive))

        assertEquals("content", File(dest, "target").readText())
        assertEquals("content", File(dest, "alias").readText())
        assertTrue(Files.isSameFile(File(dest, "target").toPath(), File(dest, "alias").toPath()))
    }

    private fun gzipTar(vararg entries: Entry): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { entry ->
                    val tarEntry = if (entry.linkName == null) {
                        TarArchiveEntry(entry.name).apply { size = entry.bytes.size.toLong() }
                    } else {
                        TarArchiveEntry(entry.name, TarConstants.LF_LINK).apply {
                            linkName = entry.linkName
                            size = 0
                        }
                    }
                    tar.putArchiveEntry(tarEntry)
                    if (entry.bytes.isNotEmpty()) tar.write(entry.bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun regular(name: String, content: String) = Entry(name, content.toByteArray())
    private fun hardlink(name: String, target: String) = Entry(name, byteArrayOf(), target)

    private data class Entry(val name: String, val bytes: ByteArray, val linkName: String? = null)

    private class FakeContext(private val files: File, private val cache: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
    }
}
