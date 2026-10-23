package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/** 子代理定义扫描的批量读取通路：只认顶层 `*.md`，不碰 provider。 */
class AgentDefinitionBulkScanTest {

    private val bulk = object : BulkFileSource {
        override fun filesUnder(root: String): Map<String, String>? = mapOf(
            "helper.md" to "---\nname: helper\n---\n正文",
            "CLAUDE.md" to "---\nname: claude\n---\n正文",
            "notes.txt" to "不是定义",
            "nested/inner.md" to "---\nname: inner\n---\n正文"
        )
    }

    @Test
    fun scan_takesOnlyTopLevelMarkdownFromBulk() {
        val provider = mockk<FileAccessProvider>()

        val defs = AgentDefinitionDirectoryScanner.scan(provider, "/home/u/.aicode/agents", bulk)

        assertEquals(listOf("claude", "helper"), defs.map { it.name })
        assertEquals("/home/u/.aicode/agents/helper.md", defs[1].filePath)
        verify(exactly = 0) { provider.listFiles(any()) }
        verify(exactly = 0) { provider.readFile(any()) }
    }
}
