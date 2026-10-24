package com.aicode.feature.agent.domain.tool.file

import com.aicode.core.watch.WorkspaceWriteSignal
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.agent.domain.tool.editor.EditFileTool
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.sha256Fingerprint
import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一次写只把同一文件完整读一遍。
 *
 * 改动前一次 `writeFile` 会把目标文件整篇读 3-4 次（存在性探测 + 两次指纹 + 旧内容 + 写后指纹），
 * 远程 SFTP 下每次都是一次 stat 加一次完整传输。这些读取本可以共用同一份内容，
 * 所以这里直接钉住「整篇读取次数」，避免以后又在链路上加一次回读。
 */
class FileWriteReadCountTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val versions = FileVersionRegistry()

    private fun context(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.absolutePath,
        language = "kotlin",
        sessionId = sessionId
    )

    private fun writeArgs(path: String, content: String): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "content" to JsonPrimitive(content)
    )

    private fun editArgs(path: String, old: String, new: String): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "edits" to JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "old_string" to JsonPrimitive(old),
                        "new_string" to JsonPrimitive(new)
                    )
                )
            )
        )
    )

    @Test
    fun writeFile_readsContentOnce() = runBlocking {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val access = CountingFileAccess()
        val tool = WriteFileTool(access, WorkspaceWriteSignal(), WorkspaceFileLock(), versions)

        val result = tool.executeWithContext(writeArgs(file.path, "v2\n"), context("s1"))

        assertTrue("实际=$result", result is ToolResult.Success)
        assertEquals("一次写只该完整读一次旧内容", 1, access.contentReads)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun writeFile_recordsWrittenContentAsNewVersion() = runBlocking {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val tool = WriteFileTool(CountingFileAccess(), WorkspaceWriteSignal(), WorkspaceFileLock(), versions)

        tool.executeWithContext(writeArgs(file.path, "v2\n"), context("s1"))

        // 写后登记不靠回读文件：登记的必须正是落盘内容的指纹，否则下一次写会被自己误判为过期写
        assertEquals(
            sha256Fingerprint("v2\n".toByteArray(Charsets.UTF_8)),
            versions.get("s1", file.path)
        )
        val second = tool.executeWithContext(writeArgs(file.path, "v3\n"), context("s1"))
        assertTrue("同一会话连续写不应被判为过期写，实际=$second", second is ToolResult.Success)
    }

    @Test
    fun writeFile_newFile_readsNothing() = runBlocking {
        val target = File(tmp.root, "new.kt")
        val access = CountingFileAccess()
        val tool = WriteFileTool(access, WorkspaceWriteSignal(), WorkspaceFileLock(), versions)

        val result = tool.executeWithContext(writeArgs(target.path, "fresh\n"), context("s1"))

        assertTrue("实际=$result", result is ToolResult.Success)
        assertEquals("新建文件没有旧内容可读", 0, access.contentReads)
    }

    @Test
    fun editFile_readsContentOnce() = runBlocking {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val access = CountingFileAccess()
        val tool = EditFileTool(access, WorkspaceWriteSignal(), WorkspaceFileLock(), versions)

        val result = tool.executeWithContext(editArgs(file.path, "val a = 1", "val a = 2"), context("s1"))

        assertTrue("实际=$result", result is ToolResult.Success)
        assertEquals("一次编辑只该完整读一次文件", 1, access.contentReads)
        assertEquals("val a = 2\n", file.readText())
        assertEquals(
            sha256Fingerprint("val a = 2\n".toByteArray(Charsets.UTF_8)),
            versions.get("s1", file.path)
        )
    }

    @Test
    fun editFile_staleWriteDoesNotWrite() = runBlocking {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val access = CountingFileAccess()
        val tool = EditFileTool(access, WorkspaceWriteSignal(), WorkspaceFileLock(), versions)
        versions.record("s1", file.path, sha256Fingerprint("v1\n".toByteArray(Charsets.UTF_8)))

        // 外部改动后指纹不再匹配：拒绝写入（同一个文件在锁内也只读一次）
        File(file.path).writeText("external\n")
        val result = tool.executeWithContext(editArgs(file.path, "external", "mine"), context("s1"))

        assertTrue("外部改动必须仍能被识别，实际=$result", result is ToolResult.Error)
        assertEquals("external\n", file.readText())
        assertEquals(1, access.contentReads)
    }

    /** 统计「整篇读取」次数：读正文、读字节、算指纹都算一次完整传输（失败的那次不计）。 */
    private class CountingFileAccess(
        private val inner: TestFileAccessProvider = TestFileAccessProvider()
    ) : FileAccessProvider by inner {

        var contentReads = 0
            private set

        override fun readFile(path: String): String {
            val text = inner.readFile(path)
            contentReads++
            return text
        }

        override fun readBytes(path: String): ByteArray {
            val bytes = inner.readBytes(path)
            contentReads++
            return bytes
        }

        override fun fingerprint(path: String): String? {
            val value = inner.fingerprint(path)
            contentReads++
            return value
        }
    }
}
