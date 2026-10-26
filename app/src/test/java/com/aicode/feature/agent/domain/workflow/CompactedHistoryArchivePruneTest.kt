package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 归档保留策略：活跃摘要正文里引用的归档文件不能被 7 天 / 32MB / 30 个配额删掉；
 * 引用集合未知（读库失败、无会话）时整体跳过 prune，宁可多留文件。
 */
class CompactedHistoryArchivePruneTest {

    private lateinit var root: File
    private lateinit var archive: CompactedHistoryArchive

    @Before
    fun setUp() {
        root = Files.createTempDirectory("aicode-archive-test").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        archive = CompactedHistoryArchive(installer)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun archiveDir(): File =
        File(root, COMPACTED_HISTORY_DIR_NAME).apply { mkdirs() }

    private fun message(): List<AgentMessage> =
        listOf(AgentMessage.UserMessage(content = "hi"))

    @Test
    fun `prune 保留仍被活跃摘要引用的归档文件`() {
        val dir = archiveDir()
        val referenced = File(dir, "session-referenced.md").apply { writeText("keep") }
        referenced.setLastModified(1_000L)
        val old = (0 until 30).map { index ->
            File(dir, "old-$index.md").apply { writeText("x") }
                .also { it.setLastModified(2_000L + index) }
        }

        // 31 个旧文件 + 新归档 = 32，超过 30 个上限。最旧的 referenced 必须留下，
        // 删除顺序从其后的 old-0、old-1 开始。
        val path = archive.archive("s", message(), setOf(referenced.name))

        assertNotNull(path)
        assertTrue("被摘要引用的归档不能被删", referenced.exists())
        assertTrue(dir.listFiles()!!.size <= 30)
        assertFalse("最旧的未引用文件应被删", old.first().exists())
    }

    @Test
    fun `引用集合未知时不删任何文件`() {
        val dir = archiveDir()
        val files = (0 until 31).map { index ->
            File(dir, "old-$index.md").apply { writeText("x") }
                .also { it.setLastModified(1_000L + index) }
        }

        archive.archive("s", message(), null)

        assertTrue("引用未知时必须整体跳过 prune", files.all { it.exists() })
    }

    @Test
    fun `从摘要正文解析归档引用`() {
        val names = referencedArchiveFileNames(
            listOf(
                "摘要（被折叠的历史原文已存档：" +
                    "$COMPACTED_HISTORY_CONTAINER_DIR/s-20260101-120000.md ，需要核对细节时可读取）",
                "另一份 $COMPACTED_HISTORY_CONTAINER_DIR/s-20260102-120000.md"
            )
        )

        assertEquals(
            setOf("s-20260101-120000.md", "s-20260102-120000.md"),
            names
        )
    }
}
