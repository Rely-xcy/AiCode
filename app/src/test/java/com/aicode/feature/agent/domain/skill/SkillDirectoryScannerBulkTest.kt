package com.aicode.feature.agent.domain.skill

import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 技能扫描：批量读取通路（远程）与失败可区分（供上层决定要不要缓存）。 */
class SkillDirectoryScannerBulkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bulkOf(files: Map<String, String>?): BulkFileSource = object : BulkFileSource {
        override fun filesUnder(root: String): Map<String, String>? = files
    }

    @Test
    fun scan_usesBulkFilesWithoutTouchingProvider() {
        val root = File(tempFolder.root, "skills").absolutePath
        val provider = mockk<FileAccessProvider>()
        val bulk = bulkOf(
            mapOf(
                "SKILL.md" to "---\nname: root-skill\n---\n根正文",
                "nested/SKILL.md" to "---\nname: nested-skill\n---\n嵌套正文",
                "nested/CLAUDE.md" to "---\nname: claude-skill\n---\n不该被选中"
            )
        )

        val skills = SkillDirectoryScanner.scan(provider, root, bulk)

        assertEquals(listOf("nested-skill", "root-skill"), skills.map { it.name })
        // SKILL.md 优先于同目录的 CLAUDE.md，目录路径按容器视角拼出。
        assertEquals("$root/nested", skills[0].dirPath)
        assertEquals("嵌套正文", skills[0].instructions)
        assertEquals(root, skills[1].dirPath)
        verify(exactly = 0) { provider.listFilesRecursive(any(), any()) }
        verify(exactly = 0) { provider.readFile(any()) }
        verify(exactly = 0) { provider.listFiles(any()) }
    }

    @Test
    fun scan_emptyBulkMapMeansNoSkillsNotFallback() {
        val provider = mockk<FileAccessProvider>()

        assertTrue(SkillDirectoryScanner.scan(provider, "/home/u/.aicode/skills", bulkOf(emptyMap())).isEmpty())
        verify(exactly = 0) { provider.listFilesRecursive(any(), any()) }
    }

    @Test
    fun scan_fallsBackToProviderWhenBulkUnavailable() {
        val dir = tempFolder.newFolder("my-skill")
        File(dir, "SKILL.md").writeText("---\nname: my-skill\n---\n正文")
        val provider = TestFileAccessProvider()

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath, bulkOf(null))

        assertEquals(listOf("my-skill"), skills.map { it.name })
    }

    @Test
    fun scan_noLongerListsDirectoriesDuringProviderScan() {
        val provider = mockk<FileAccessProvider>()
        every { provider.listFilesRecursive(any(), any()) } returns listOf("foo/SKILL.md")
        every { provider.readFile("${tempFolder.root.absolutePath}/foo/SKILL.md") } returns "---\nname: foo\n---\n正文"

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(listOf("foo"), skills.map { it.name })
        verify(exactly = 0) { provider.listFiles(any()) }
    }

    @Test
    fun scanOrNull_returnsNullWhenProviderReadsFail() {
        val provider = mockk<FileAccessProvider>()
        every { provider.listFilesRecursive(any(), any()) } throws IOException("远端断开")

        assertNull(SkillDirectoryScanner.scanOrNull(provider, "/home/u/.aicode/skills"))
        // 只关心成败的调用方仍拿到空列表（保持降级行为）。
        assertTrue(SkillDirectoryScanner.scan(provider, "/home/u/.aicode/skills").isEmpty())
    }

    @Test
    fun scanOrNull_missingDirectoryIsEmptyNotFailure() {
        val provider = TestFileAccessProvider()

        val skills = SkillDirectoryScanner.scanOrNull(provider, File(tempFolder.root, "not-exists").absolutePath)

        assertTrue(skills != null && skills.isEmpty())
    }
}
