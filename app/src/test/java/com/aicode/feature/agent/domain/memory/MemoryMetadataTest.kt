package com.aicode.feature.agent.domain.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMetadataTest {

    private fun tempRoot(): File = Files.createTempDirectory("memory-test").toFile()

    @Test
    fun `新元数据能落盘并回读`() {
        val root = tempRoot()
        val file = MemorySource.resolveMemoryFile(root, "build-env")
        file.writeText(
            MemoryParser.format(
                name = "build-env",
                description = "构建环境",
                content = "正文",
                kind = MemoryKind.PROFILE,
                source = "auto-distill",
                createdAt = 1_700_000_000_000L
            )
        )

        val parsed = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(parsed)
        assertEquals(MemoryKind.PROFILE, parsed.kind)
        assertEquals("auto-distill", parsed.source)
        assertEquals(1_700_000_000_000L, parsed.createdAt)
        assertEquals("正文", parsed.content)
    }

    @Test
    fun `旧文件缺新字段时按默认值回读`() {
        val root = tempRoot()
        val file = File(root, "legacy.md")
        file.writeText("---\nname: legacy\ndescription: 老格式\n---\n老正文")

        val parsed = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(parsed)
        assertEquals(MemoryKind.NOTE, parsed.kind)
        assertEquals("", parsed.source)
        assertEquals(0L, parsed.createdAt)
    }

    @Test
    fun `NOTE 且无元数据时不写多余行`() {
        // 历史记忆文件必须保持字节不变，不能因为新增元数据把所有旧文件重写一遍
        assertEquals(
            "---\nname: n\ndescription: d\n---\nbody",
            MemoryParser.format("n", "d", "body", MemoryKind.NOTE)
        )
    }

    @Test
    fun `覆盖前把旧版本归档到 superseded`() {
        val root = tempRoot()
        val file = MemorySource.resolveMemoryFile(root, "note")
        file.writeText(MemoryParser.format("note", "旧描述", "旧正文"))

        val archived = MemorySource.archiveBeforeOverwrite(root, file)

        assertNotNull(archived)
        assertTrue(archived.isFile)
        assertEquals(file.readText(), archived.readText())
        assertTrue(File(root, MemorySource.SUPERSEDED_DIR).isDirectory)
    }

    @Test
    fun `没有旧文件时不做归档`() {
        val root = tempRoot()
        assertNull(MemorySource.archiveBeforeOverwrite(root, MemorySource.resolveMemoryFile(root, "missing")))
    }
}
