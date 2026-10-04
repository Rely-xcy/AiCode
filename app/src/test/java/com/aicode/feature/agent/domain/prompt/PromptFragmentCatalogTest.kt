package com.aicode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * 四级来源片段的解析/保存/删除/重排：编号身份、优先级、落盘层。
 *
 * 本地层（`prompts/`）与项目/全局一样按编号解析，不再要求文件名与内置同名；
 * 需要内置层时用 mock 的 assets 提供对应文件名。
 */
class PromptFragmentCatalogTest {

    private lateinit var root: File
    private lateinit var aicodeDir: File
    private lateinit var workspaceDir: File
    private lateinit var catalog: PromptFragmentCatalog
    private lateinit var assets: AssetManager

    private val globalDir get() = File(aicodeDir, "prompts.custom")
    private val localDir get() = File(aicodeDir, "prompts")
    private val projectDir get() = File(File(workspaceDir, ".aicode"), "prompts.custom")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("prompt-catalog-test").toFile()
        aicodeDir = File(root, "aicode").apply { mkdirs() }
        workspaceDir = File(root, "workspace").apply { mkdirs() }
        globalDir.mkdirs()
        localDir.mkdirs()

        val context = mockk<Context>()
        assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.list(any()) } returns arrayOf(BUILTIN_NAME)
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns aicodeDir
        val projectRoot = mockk<ProjectAicodeRoot>()
        every { projectRoot.forPath(any()) } returns File(workspaceDir, ".aicode")

        catalog = PromptFragmentCatalog(context, installer, projectRoot)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, content: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText(content) }
    }

    @Test
    fun list_同编号_取优先级最高的项目层() {
        write(globalDir, "10-g.md", "G")
        write(localDir, BUILTIN_NAME, "L")
        write(projectDir, "10-p.md", "P")

        val fragment = catalog.list(workspaceDir.path).first { it.number == 10 }

        assertEquals(PromptFragmentSource.PROJECT, fragment.source)
        assertEquals("P", fragment.content)
    }

    @Test
    fun list_无项目层_回退全局再回退本地() {
        write(localDir, BUILTIN_NAME, "L")
        write(globalDir, "10-g.md", "G")
        assertEquals(PromptFragmentSource.GLOBAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)

        globalDir.listFiles()!!.forEach { it.delete() }
        assertEquals(PromptFragmentSource.LOCAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)
    }

    @Test
    fun renderStatic_按编号升序拼接正文() {
        write(globalDir, "30-b.md", "BBB")
        write(globalDir, "10-a.md", "AAA")

        assertEquals("AAA\n\nBBB", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun saveOverride_写到指定层并清掉旧编号() {
        catalog.saveOverride(12, "x", "X", workspaceDir.path, target = PromptFragmentSource.GLOBAL)
        assertTrue(File(globalDir, "12-x.md").isFile)

        catalog.saveOverride(
            20, "x", "X", workspaceDir.path,
            target = PromptFragmentSource.GLOBAL, previousNumber = 12
        )
        assertFalse("旧编号应被清掉", File(globalDir, "12-x.md").exists())
        assertTrue(File(globalDir, "20-x.md").isFile)
    }

    @Test
    fun saveOverride_工作区未落定时项目层返回false且不写到全局层() {
        // 窗口期（currentPathOrNull 为空 → projectRoot 为 ""）：项目层解析不到，
        // 旧行为会兜到全局层，用户以为存的是项目级。现在一律拒写，由调用方提示。
        assertFalse(catalog.saveOverride(12, "x", "X", projectRoot = "", target = PromptFragmentSource.PROJECT))
        assertFalse("不应在任何层留下文件", File(globalDir, "12-x.md").exists())
        assertFalse(File(projectDir, "12-x.md").exists())

        assertFalse(catalog.saveOverride(12, "x", "X", projectRoot = null, target = PromptFragmentSource.PROJECT))
        assertFalse(File(globalDir, "12-x.md").exists())

        // 同一入口写全局层不受影响（本地模式/未选工作区仍可编辑全局提示词）
        assertTrue(catalog.saveOverride(12, "x", "X", projectRoot = "", target = PromptFragmentSource.GLOBAL))
        assertTrue(File(globalDir, "12-x.md").isFile)
    }

    @Test
    fun deleteOverride_删除可写覆盖() {
        write(globalDir, "12-x.md", "X")
        assertTrue(catalog.deleteOverride(12, workspaceDir.path, PromptFragmentSource.GLOBAL))
        assertFalse(File(globalDir, "12-x.md").exists())
    }

    @Test
    fun deleteOverride_删项目层不动全局层那份() {
        write(globalDir, "12-x.md", "G")
        write(projectDir, "12-x.md", "P")

        assertTrue(catalog.deleteOverride(12, workspaceDir.path, PromptFragmentSource.PROJECT))

        assertFalse(File(projectDir, "12-x.md").exists())
        assertEquals("删项目层应回退到全局层，全局层那份不动", "G", File(globalDir, "12-x.md").readText())
    }

    @Test
    fun deleteOverride_工作区未落定时项目层返回false且不删全局层() {
        // 窗口期（currentPathOrNull 为空 → projectRoot 为 ""）：项目层解析不到，
        // 旧行为按「生效层」推断会删掉全局层那份，而用户看着的是项目级那条。
        write(globalDir, "12-x.md", "G")
        write(projectDir, "12-x.md", "P")

        assertFalse(catalog.deleteOverride(12, projectRoot = "", target = PromptFragmentSource.PROJECT))
        assertEquals("全局层不该被动", "G", File(globalDir, "12-x.md").readText())
        assertEquals("项目层不该被动", "P", File(projectDir, "12-x.md").readText())

        assertFalse(catalog.deleteOverride(12, projectRoot = null, target = PromptFragmentSource.PROJECT))
        assertEquals("G", File(globalDir, "12-x.md").readText())

        // 同一入口删全局层不受影响（本地模式/未选工作区仍可删全局提示词）
        assertTrue(catalog.deleteOverride(12, projectRoot = "", target = PromptFragmentSource.GLOBAL))
        assertFalse(File(globalDir, "12-x.md").exists())
    }

    @Test
    fun reorder_按新顺序重新编号并写入可写层() {
        write(globalDir, "10-a.md", "A")
        write(globalDir, "30-b.md", "B")

        val original = catalog.list(workspaceDir.path)
        val reversed = original.reversed()
        assertTrue(catalog.reorder(reversed, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("B", after.first { it.number == 10 }.content)
        assertEquals("A", after.first { it.number == 30 }.content)
    }

    @Test
    fun reorder_拖动全局行_本地行仍是本地且内容不变() {
        // 旧实现把两行都写进可写层（有工作区时是项目层）：徽章全变 PROJECT，全局那份被删。
        every { assets.list(any()) } returns arrayOf("10-a.md", "20-b.md")
        write(localDir, "10-a.md", "L10")
        write(localDir, "20-b.md", "L20")
        write(globalDir, "20-g.md", "G20")

        val before = catalog.list(workspaceDir.path)
        assertEquals(PromptFragmentSource.LOCAL, before.first { it.number == 10 }.source)
        assertEquals(PromptFragmentSource.GLOBAL, before.first { it.number == 20 }.source)

        // 把全局那条拖到最前。
        val reordered = assignReorderNumbers(before.reversed())
        assertTrue(catalog.reorder(reordered, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("行数不变", 2, after.size)
        // 灵魂断言：来源徽章一个都不变，只是编号换了。
        assertEquals(PromptFragmentSource.GLOBAL, after.first { it.number == 10 }.source)
        assertEquals(PromptFragmentSource.LOCAL, after.first { it.number == 20 }.source)
        assertEquals("G20", after.first { it.number == 10 }.content)
        assertEquals("L10", after.first { it.number == 20 }.content)
    }

    @Test
    fun reorder_拖动本地行_全局行仍是全局且内容不变() {
        // 旧实现同样把三行全写进项目层：全局徽章丢失，globalDir 里那份也被删。
        every { assets.list(any()) } returns arrayOf("10-a.md", "20-b.md", "30-c.md")
        write(localDir, "10-a.md", "L10")
        write(localDir, "20-b.md", "L20")
        write(localDir, "30-c.md", "L30")
        write(globalDir, "20-g.md", "G20")

        val before = catalog.list(workspaceDir.path)
        // 新顺序：20(GLOBAL) 在前，30(LOCAL) 居中，10(LOCAL) 最后。
        val reordered = assignReorderNumbers(
            listOf(
                before.first { it.number == 20 },
                before.first { it.number == 30 },
                before.first { it.number == 10 }
            )
        )
        assertTrue(catalog.reorder(reordered, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals(3, after.size)
        val globalRows = after.filter { it.source == PromptFragmentSource.GLOBAL }
        assertEquals("全局行只有一条且内容不变", listOf("G20"), globalRows.map { it.content })
        assertEquals("全局行落到编号 10", 10, globalRows.single().number)
        assertEquals(PromptFragmentSource.LOCAL, after.first { it.number == 20 }.source)
        assertEquals("L30", after.first { it.number == 20 }.content)
        assertEquals(PromptFragmentSource.LOCAL, after.first { it.number == 30 }.source)
        assertEquals("L10", after.first { it.number == 30 }.content)
    }

    @Test
    fun reorder_内容逐字不变() {
        // 重排只改文件名里的编号，正文（含开头的摘要注释）原样搬运。
        every { assets.list(any()) } returns arrayOf("10-a.md", "20-b.md")
        val body = "<!-- 摘要 -->\n第一行\n\n第二行\n"
        write(localDir, "10-a.md", "L10")
        write(localDir, "20-b.md", "L20")
        write(globalDir, "20-g.md", body)

        val before = catalog.list(workspaceDir.path)
        assertTrue(catalog.reorder(assignReorderNumbers(before.reversed()), workspaceDir.path))

        assertEquals(body, File(globalDir, "10-g.md").readText())
        assertEquals("L10", File(localDir, "20-a.md").readText())
        val after = catalog.list(workspaceDir.path)
        assertEquals(PromptFragmentSource.GLOBAL, after.first { it.number == 10 }.source)
        assertEquals("摘要", after.first { it.number == 10 }.description)
    }

    @Test
    fun reorder_行数不变且清掉同编号的其它层文件() {
        // 编号 20 在项目层与全局层各有一份（全局那份本来就被项目层遮挡）。
        // 重排后只应留下当前归属层的文件，否则下一次刷新会多冒出旧内容。
        every { assets.list(any()) } returns arrayOf("10-a.md", "20-b.md")
        write(localDir, "10-a.md", "L10")
        write(localDir, "20-b.md", "L20")
        write(globalDir, "20-g.md", "G20")
        write(projectDir, "20-p.md", "P20")

        val before = catalog.list(workspaceDir.path)
        assertEquals(PromptFragmentSource.PROJECT, before.first { it.number == 20 }.source)

        val reordered = assignReorderNumbers(
            listOf(before.first { it.number == 20 }, before.first { it.number == 10 })
        )
        assertTrue(catalog.reorder(reordered, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("行数不变", 2, after.size)
        assertEquals("P20", after.first { it.number == 10 }.content)
        assertEquals(PromptFragmentSource.PROJECT, after.first { it.number == 10 }.source)
        assertEquals("L10", after.first { it.number == 20 }.content)
        assertEquals(PromptFragmentSource.LOCAL, after.first { it.number == 20 }.source)
        assertFalse("20 号上被遮挡的全局旧文件应清掉", File(globalDir, "20-g.md").exists())
    }

    @Test
    fun reorder_内置行跳过不写盘() {
        // 内置片段在 assets 里，文件名改不了：既不参与编号分配，也不会被写进任何层。
        // 旧实现会把它的内容一并抄进可写层，徽章从「内置」变成「项目」。
        every { assets.list(any()) } returns arrayOf("10-a.md", "20-b.md")
        every { assets.open("prompts/10-a.md") } returns ByteArrayInputStream("B10".toByteArray())
        write(localDir, "20-b.md", "L20")

        val before = catalog.list(workspaceDir.path)
        assertEquals(PromptFragmentSource.BUILTIN, before.first { it.number == 10 }.source)
        assertEquals(PromptFragmentSource.LOCAL, before.first { it.number == 20 }.source)

        // 试着把本地那条拖到内置那条前面。
        val reordered = assignReorderNumbers(
            listOf(before.first { it.number == 20 }, before.first { it.number == 10 })
        )
        assertTrue(catalog.reorder(reordered, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals(2, after.size)
        assertEquals(PromptFragmentSource.BUILTIN, after.first { it.number == 10 }.source)
        assertEquals("B10", after.first { it.number == 10 }.content)
        assertEquals(PromptFragmentSource.LOCAL, after.first { it.number == 20 }.source)
        assertFalse("内置片段不该在项目层留下文件", File(projectDir, "10-a.md").exists())
        assertFalse(File(globalDir, "10-a.md").exists())
    }

    @Test
    fun assignReorderNumbers_内置片段不参与编号分配() {
        // 旧实现按整份列表下标重编号，内置行也会被改号（10→30），进而写进可写层。
        val builtin = PromptFragment(10, "identity", PromptFragmentSource.BUILTIN, "B", null, "")
        val local = PromptFragment(20, "communication", PromptFragmentSource.LOCAL, "L", null, "")
        val global = PromptFragment(30, "mine", PromptFragmentSource.GLOBAL, "G", null, "")

        val result = assignReorderNumbers(listOf(global, local, builtin))

        assertEquals(listOf(10, 20, 30), result.map { it.number })
        assertEquals(PromptFragmentSource.BUILTIN, result.first { it.number == 10 }.source)
        assertEquals(PromptFragmentSource.GLOBAL, result.first { it.number == 20 }.source)
        assertEquals(PromptFragmentSource.LOCAL, result.first { it.number == 30 }.source)
    }

    private companion object {
        /** 让本地层可解析的内置文件名（编号 10）。 */
        const val BUILTIN_NAME = "10-builtin.md"
    }
}
