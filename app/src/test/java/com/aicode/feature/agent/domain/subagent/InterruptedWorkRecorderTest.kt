package com.aicode.feature.agent.domain.subagent

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 半成品备份：复制哪些文件、manifest 里记什么、提示怎么交给下一个代理。
 *
 * 备份根在测试里指到临时目录——真实路径是用户工作区里的 `build/interrupted/`，
 * 单测不能往那里写东西。
 */
class InterruptedWorkRecorderTest {

    private val recorder = InterruptedWorkRecorder(TestFileAccessProvider())

    @Test
    fun copiesWrittenFilesAndPublishesNoticeOnce() = runTest {
        val root = tempDir()
        val source = File(root, "src/Foo.kt").apply {
            parentFile?.mkdirs()
            writeText("val a = 1\n")
        }
        val backupRoot = File(root, "backup").absolutePath

        recorder.recordNow(
            sessionId = SESSION,
            projectRoot = "/ws",
            reason = "被停止或取消",
            title = "改登录",
            // 第二个文件已经不在（代理删掉了 / 从没写成）：不进清单
            paths = listOf(source.absolutePath, File(root, "已删除.kt").absolutePath),
            backupRoot = backupRoot
        )

        val sessionDir = File("$backupRoot/$SESSION")
        val manifest = Json.parseToJsonElement(File(sessionDir, "manifest.json").readText()).jsonObject
        assertEquals("改登录", manifest["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("被停止或取消", manifest["reason"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1", manifest["copiedCount"]?.jsonPrimitive?.contentOrNull)
        assertEquals("0", manifest["registeredOnlyCount"]?.jsonPrimitive?.contentOrNull)
        val files = manifest["files"]!!.jsonArray
        assertEquals(1, files.size)
        val entry = files[0].jsonObject
        assertEquals("true", entry["copied"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, entry["sha256"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(
            "val a = 1\n",
            File(sessionDir, entry["backup"]!!.jsonPrimitive.content).readText()
        )

        val notice = recorder.takeNotice("/ws").orEmpty()
        assertTrue(notice.contains("[半成品提示]"))
        assertTrue(notice.contains("改登录"))
        assertTrue(notice.contains(source.absolutePath))
        assertNull("提示取走即消费", recorder.takeNotice("/ws"))
    }

    @Test
    fun writesNothingWhenTheWrittenFilesAreGone() = runTest {
        val root = tempDir()
        val backupRoot = File(root, "backup").absolutePath

        recorder.recordNow(
            sessionId = SESSION,
            projectRoot = "/ws",
            reason = "运行失败",
            title = "改登录",
            paths = listOf(File(root, "缺失.kt").absolutePath),
            backupRoot = backupRoot
        )

        // 没有现存文件时不落盘、不发提示：正常流程里看不到它的存在
        assertFalse(File(backupRoot).exists())
        assertNull(recorder.takeNotice("/ws"))
    }

    @Test
    fun oversizedFileIsRegisteredButNotCopied() = runTest {
        val root = tempDir()
        val big = File(root, "big.bin").apply { writeBytes(ByteArray(2 * 1024 * 1024 + 1)) }
        val backupRoot = File(root, "backup").absolutePath

        recorder.recordNow(
            sessionId = SESSION,
            projectRoot = "/ws",
            reason = "运行失败",
            title = "改大文件",
            paths = listOf(big.absolutePath),
            backupRoot = backupRoot
        )

        val sessionDir = File("$backupRoot/$SESSION")
        val manifest = Json.parseToJsonElement(File(sessionDir, "manifest.json").readText()).jsonObject
        assertEquals("0", manifest["copiedCount"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1", manifest["registeredOnlyCount"]?.jsonPrimitive?.contentOrNull)
        val entry = manifest["files"]!!.jsonArray[0].jsonObject
        assertEquals("false", entry["copied"]?.jsonPrimitive?.contentOrNull)
        assertEquals("", entry["sha256"]?.jsonPrimitive?.contentOrNull)
        assertFalse(File(sessionDir, "_abs${big.absolutePath}").exists())
        assertTrue(recorder.takeNotice("/ws").orEmpty().contains("只登记，未复制"))
    }

    @Test
    fun noticesFromSeveralRunsAreDeliveredTogether() = runTest {
        val root = tempDir()
        val backupRoot = File(root, "backup").absolutePath
        val first = File(root, "a.kt").apply { writeText("a") }
        val second = File(root, "b.kt").apply { writeText("b") }

        recorder.recordNow("session-1", "/ws", "运行失败", "第一个", listOf(first.absolutePath), backupRoot)
        recorder.recordNow("session-2", "/ws", "被停止或取消", "第二个", listOf(second.absolutePath), backupRoot)

        val notice = recorder.takeNotice("/ws").orEmpty()
        assertTrue(notice.contains("第一个"))
        assertTrue(notice.contains("第二个"))
    }

    private fun tempDir(): File = Files.createTempDirectory("interrupted-work-test").toFile()

    private companion object {
        const val SESSION = "session-1"
    }
}
