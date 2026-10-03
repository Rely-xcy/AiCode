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
import java.io.File
import java.nio.file.Files

/**
 * 四级来源片段的解析/保存/删除/重排：编号身份、优先级、落盘层。
 *
 * 注意：本地层（`prompts/`）只在**内置存在对应编号**时才会被解析（见 [PromptFragmentCatalog.resolve]），
 * 故这里让 assets 暴露一个内置文件名 `10-builtin.md`，本地同名文件才会生效。
 */
class PromptFragmentCatalogTest {

    private lateinit var root: File
    private lateinit var aicodeDir: File
    private lateinit var workspaceDir: File
    private lateinit var catalog: PromptFragmentCatalog

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
        val assets = mockk<AssetManager>()
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

    private companion object {
        /** 让本地层可解析的内置文件名（编号 10）。 */
        const val BUILTIN_NAME = "10-builtin.md"
    }
}
