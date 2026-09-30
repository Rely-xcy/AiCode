package com.aicode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import com.aicode.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * 覆盖副本的读写：落盘路径与命名、覆盖优先于内置、恢复内置后回落、超长拒绝、不留临时文件。
 *
 * 只有内置片段（`assets/prompts/`）是 mock 出来的，其余全走真实文件系统，
 * 断言的也是「文件落在哪、读到什么内容」——覆盖副本这套机制的实质就在这里。
 */
class PromptFragmentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var aicodeDir: File

    /** 覆盖副本目录：与 [PromptFragmentRepository] 的约定一致。 */
    private val customDir: File
        get() = File(aicodeDir, "prompts.custom")

    /**
     * 造一个只认 [builtin] 那份内置片段的仓库（键为文件名，如 `00-identity.md`）。
     * 值为 null 表示该内置资源读不出来，用于验证读失败时的行为。
     */
    private fun repository(builtin: Map<String, String?>): PromptFragmentRepository {
        aicodeDir = tempFolder.newFolder("aicode")
        val assets = mockk<AssetManager>()
        every { assets.list("prompts") } returns builtin.keys.toTypedArray()
        builtin.forEach { (name, content) ->
            if (content == null) {
                every { assets.open("prompts/$name") } throws IOException("内置资源损坏")
            } else {
                // 用 answers 而不是 returns：同一个 InputStream 读第二次就空了，每次都得新建
                every { assets.open("prompts/$name") } answers { ByteArrayInputStream(content.toByteArray()) }
            }
        }
        val context = mockk<Context>()
        every { context.assets } returns assets
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns aicodeDir
        return PromptFragmentRepository(context, installer)
    }

    @Test
    fun saveOverride_内置片段_覆盖副本沿用内置文件名() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))

        assertTrue(repo.saveOverride(0, "identity", "我的身份"))

        // 文件名与内置片段一一对应：编号一眼可对上，同编号也不会留下第二个文件
        assertEquals(listOf("00-identity.md"), customDir.list()!!.toList())
        assertEquals("我的身份", File(customDir, "00-identity.md").readText())
    }

    @Test
    fun listFragments_覆盖副本优先于内置() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))
        customDir.mkdirs()
        File(customDir, "00-identity.md").writeText("我的身份")

        val fragment = repo.listFragments().single()

        assertEquals("我的身份", fragment.content)
        assertTrue(fragment.isOverridden)
        assertEquals("00-identity.md", fragment.overrideFile?.name)
    }

    @Test
    fun deleteOverride_恢复内置后回退() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))
        repo.saveOverride(0, "identity", "我的身份")
        assertTrue("先确认覆盖生效", repo.listFragments().single().isOverridden)

        assertTrue(repo.deleteOverride(0))

        val fragment = repo.listFragments().single()
        assertEquals("App 自带身份", fragment.content)
        assertFalse(fragment.isOverridden)
    }

    @Test
    fun saveOverride_重复保存_同编号只留一个副本且无临时文件残留() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))
        repo.saveOverride(0, "identity", "第一版")
        repo.saveOverride(0, "identity", "第二版")

        // 两份同编号文件并存时会按字典序取错那份；原子写的临时文件也不能留下
        assertEquals(listOf("00-identity.md"), customDir.list()!!.toList())
        assertEquals("第二版", repo.listFragments().single().content)
    }

    @Test
    fun saveOverride_超长_整体拒绝且不落盘() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))
        val tooLong = "x".repeat(PromptFragmentResolver.MAX_FRAGMENT_CHARS + 1)

        assertFalse("超长必须整体拒绝，不能静默截断", repo.saveOverride(0, "identity", tooLong))

        assertFalse("被拒绝就不该动磁盘", customDir.exists())
        assertEquals("App 自带身份", repo.listFragments().single().content)
    }

    @Test
    fun listFragments_覆盖路径被目录占据_不当作覆盖并回落内置() {
        val repo = repository(mapOf("00-identity.md" to "App 自带身份"))
        File(customDir, "00-identity.md").mkdirs()

        val fragment = repo.listFragments().single()

        // 编号扫描只认普通文件，目录不算覆盖；用户看到的仍是 App 自带内容，不是空白
        assertEquals("App 自带身份", fragment.content)
        assertFalse(fragment.isOverridden)
    }

    @Test
    fun listFragments_内置资源读不出来_片段不出现而不是留空正文() {
        val repo = repository(mapOf("00-identity.md" to null))

        assertTrue(
            "读不到内置正文就没有可用内容，宁可不在清单里出现，也不要一个空片段",
            repo.listFragments().isEmpty()
        )
    }
}
