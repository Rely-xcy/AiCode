package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystemLoopException
import java.nio.file.Files

/**
 * WorkspacePathMapper：容器路径 ↔ 宿主真实路径互转。
 * 固定环境：宿主工作区=/data/ws、AI 配置目录=/data/aicode、rootfs=/data/rootfs、容器 home=/root。
 * activeProfileIdFlow 用 emptyFlow()，让 init 协程的 collect 永不触发，避免真实 DataStore 调用
 * 与异步 profile 切换带来的竞态；只有额外挂载那一例显式传入 profile 并轮询等它就绪。
 * 链接解析与挂载用例跑在临时目录的真实文件系统上。
 */
class WorkspacePathMapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val workspaceRepository: WorkspaceRepository = mockk()
    private val containerInstaller: ContainerInstaller = mockk()
    private val containerSettingsRepository: ContainerSettingsRepository = mockk()
    private val pathHomeResolver: PathHomeResolver = mockk()

    private val wsRoot = "/data/ws"
    private val aicodeDir = "/data/aicode"
    private val rootfsDir = "/data/rootfs"
    private val home = "/root"

    private fun newMapper(profile: ContainerProfile? = null): WorkspacePathMapper {
        every { workspaceRepository.currentPathOrNull() } returns wsRoot
        every { containerInstaller.aicodeDir } returns File(aicodeDir)
        every { containerInstaller.rootfsDirFor(any()) } returns File(rootfsDir)
        // profile 只在构造时订阅一次，所以默认桩必须在 newMapper 内完成；传入 profile 时换成会立即发射的状态流。
        val activeProfileId: Flow<String> = if (profile == null) emptyFlow() else MutableStateFlow(profile.id)
        val customProfiles: List<ContainerProfile> = profile?.let { listOf(it) } ?: emptyList()
        every { containerSettingsRepository.activeProfileIdFlow } returns activeProfileId
        every { containerSettingsRepository.customProfilesFlow } returns flowOf(customProfiles)
        every { pathHomeResolver.home() } returns home
        // expandHome 保持真实展开语义，让 toHostFile 里的 ~ 前缀与生产行为一致
        every { pathHomeResolver.expandHome(any()) } answers {
            val p = firstArg<String>()
            when {
                p == "~" -> pathHomeResolver.home()
                p.startsWith("~/") -> pathHomeResolver.home().trimEnd('/') + p.removePrefix("~")
                else -> p
            }
        }
        return WorkspacePathMapper(workspaceRepository, containerInstaller, containerSettingsRepository, pathHomeResolver)
    }

    /** 三条根（工作区 / AI 配置 / rootfs）都放在临时目录上，链接用例才能真的落盘。 */
    private fun filesystemMapper(profile: ContainerProfile? = null): WorkspacePathMapper {
        val mapper = newMapper(profile)
        every { workspaceRepository.currentPathOrNull() } returns tmp.newFolder("workspace").absolutePath
        every { containerInstaller.aicodeDir } returns tmp.newFolder("aicode")
        every { containerInstaller.rootfsDirFor(any()) } returns tmp.newFolder("rootfs")
        return mapper
    }

    /** profile 由 init 里的协程异步生效，纯 JVM 下没有可等待的挂起点，只能轮询到它落定。 */
    private fun awaitMount(mapper: WorkspacePathMapper, expected: File) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { mapper.toHostFile("/mnt/sdcard/probe.txt") }.getOrNull() == expected) return
            Thread.sleep(10)
        }
        fail("额外挂载未在 5 秒内生效")
    }

    // ---------- toHostFile：容器路径 → 宿主文件 ----------

    @Test
    fun toHostFile_workspaceRootTilde_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace/"))
    }

    @Test
    fun toHostFile_workspaceRootExpandedForm_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace/"))
    }

    @Test
    fun toHostFile_workspaceChild_mapsUnderHostWorkspace() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("~/workspace/src/Main.kt"))
        assertEquals(File(wsRoot, "app/build.gradle.kts"), newMapper().toHostFile("$home/workspace/app/build.gradle.kts"))
    }

    @Test
    fun toHostFile_aicodeRoot_mapsToAicodeDir() {
        assertEquals(File(aicodeDir), newMapper().toHostFile("/root/.aicode"))
    }

    @Test
    fun toHostFile_aicodeChild_mapsUnderAicodeDir() {
        assertEquals(File(aicodeDir, "skills/x/SKILL.md"), newMapper().toHostFile("/root/.aicode/skills/x/SKILL.md"))
    }

    @Test
    fun toHostFile_otherAbsolutePath_mapsToRootfs() {
        assertEquals(File(rootfsDir, "etc/hosts"), newMapper().toHostFile("/etc/hosts"))
        // /root 本身不在工作区/配置目录前缀下，走通用绝对路径规则落到 rootfs
        assertEquals(File(rootfsDir, "root"), newMapper().toHostFile("/root"))
    }

    @Test
    fun toHostFile_relativePath_hangsUnderWorkspaceRoot() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("src/Main.kt"))
    }

    @Test
    fun toHostFile_inputIsTrimmed() {
        assertEquals(File(wsRoot), newMapper().toHostFile("  ~/workspace  "))
    }

    // ---------- toContainerPath：宿主路径 → 容器路径 ----------

    @Test
    fun toContainerPath_workspaceRoot_mapsToContainerRoot() {
        assertEquals("~/workspace", newMapper().toContainerPath(wsRoot))
    }

    @Test
    fun toContainerPath_workspaceChild_mapsToContainerForm() {
        assertEquals("~/workspace/src/Main.kt", newMapper().toContainerPath("$wsRoot/src/Main.kt"))
    }

    @Test
    fun toContainerPath_expandedHomeForm_mapsToContainerForm() {
        assertEquals("~/workspace", newMapper().toContainerPath("$home/workspace"))
        assertEquals("~/workspace/a.txt", newMapper().toContainerPath("$home/workspace/a.txt"))
    }

    @Test
    fun toContainerPath_aicode_mapsToAicodeRootForm() {
        assertEquals("/root/.aicode", newMapper().toContainerPath(aicodeDir))
        assertEquals("/root/.aicode/skills/x/SKILL.md", newMapper().toContainerPath("$aicodeDir/skills/x/SKILL.md"))
    }

    @Test
    fun toContainerPath_rootfs_mapsToContainerAbsolute() {
        assertEquals("/", newMapper().toContainerPath(rootfsDir))
        assertEquals("/etc/hosts", newMapper().toContainerPath("$rootfsDir/etc/hosts"))
    }

    @Test
    fun toContainerPath_unmappedPath_unchanged() {
        assertEquals("/somewhere/else", newMapper().toContainerPath("/somewhere/else"))
    }

    // ---------- resolveFile / resolveHostFile：容器符号链接 ----------

    @Test
    fun resolveFile_absoluteDirectoryLink_mapsIntoWorkspaceTarget() {
        val mapper = filesystemMapper()
        val target = mapper.toHostFile("target").apply { mkdirs() }
        File(target, "probe.txt").writeText("test")
        Files.createSymbolicLink(mapper.toHostFile("link").toPath(), File("/root/workspace/target").toPath())

        assertEquals(File(target, "probe.txt"), mapper.resolveFile("~/workspace/link/probe.txt"))
        assertEquals(File(target, "probe.txt"), mapper.resolveFile("link/probe.txt"))
        assertEquals("test", mapper.resolveFile("link/probe.txt").readText())
        // 不跟随末级链接时留下链接本身（删除/改名只该动链接）
        assertEquals(mapper.toHostFile("link"), mapper.resolveFile("link", followFinalLink = false))
    }

    @Test
    fun resolveFile_relativeLinkChainWithParentSegments_resolvesAfterLink() {
        val mapper = filesystemMapper()
        mapper.toHostFile("target/sub").mkdirs()
        Files.createSymbolicLink(mapper.toHostFile("relative").toPath(), File("target/sub").toPath())
        Files.createSymbolicLink(mapper.toHostFile("chain").toPath(), File("relative").toPath())

        assertEquals(mapper.toHostFile("target/probe.txt"), mapper.resolveFile("chain/../probe.txt"))
    }

    @Test
    fun resolveFile_containerAbsoluteLinks_mapRootfsAndAicodeDir() {
        val mapper = filesystemMapper()
        Files.createSymbolicLink(mapper.toHostFile("system").toPath(), File("/etc").toPath())
        Files.createSymbolicLink(mapper.toHostFile("global").toPath(), File("/root/.aicode").toPath())

        // 链接文本是容器绝对路径，必须映到容器内同一个文件，而不是宿主同名路径
        assertEquals(mapper.toHostFile("/etc/hosts"), mapper.resolveFile("system/hosts"))
        assertEquals(mapper.toHostFile("/root/.aicode/permissions.json"), mapper.resolveFile("global/permissions.json"))
    }

    @Test
    fun resolveFile_noFollowFinalLink_stillResolvesParentLinks() {
        val mapper = filesystemMapper()
        mapper.toHostFile("target").mkdirs()
        Files.createSymbolicLink(mapper.toHostFile("parent").toPath(), File("/root/workspace/target").toPath())
        Files.createSymbolicLink(mapper.toHostFile("target/leaf").toPath(), File("/missing").toPath())

        assertEquals(mapper.toHostFile("target/leaf"), mapper.resolveFile("parent/leaf", followFinalLink = false))
        assertEquals(mapper.toHostFile("/missing"), mapper.resolveFile("parent/leaf"))
    }

    @Test
    fun resolveHostFile_projectConfigLink_mapsToTargetInsideWorkspace() {
        val mapper = filesystemMapper()
        val projectRoot = mapper.toHostFile("project/.aicode").apply { mkdirs() }
        Files.createSymbolicLink(mapper.toHostFile(".aicode").toPath(), File("/root/workspace/project/.aicode").toPath())

        assertEquals(
            File(projectRoot, "permissions.json"),
            mapper.resolveHostFile(mapper.toHostFile(".aicode/permissions.json"))
        )
    }

    @Test
    fun resolveHostFile_unmappedHostPath_preservesOriginalFile() {
        val mapper = filesystemMapper()
        val file = tmp.newFile("outside.txt")
        assertEquals(file, mapper.resolveHostFile(file))
    }

    @Test
    fun resolveFile_linkCycle_throwsInsteadOfHanging() {
        val mapper = filesystemMapper()
        Files.createSymbolicLink(mapper.toHostFile("a").toPath(), File("b").toPath())
        Files.createSymbolicLink(mapper.toHostFile("b").toPath(), File("a").toPath())

        assertThrows(FileSystemLoopException::class.java) { mapper.resolveFile("a/file") }
        assertEquals(mapper.toHostFile("a"), mapper.resolveFile("a", followFinalLink = false))
    }

    @Test
    fun resolveFile_hostAbsoluteLinkText_staysInsideContainerRoots() {
        val mapper = filesystemMapper()
        val hostOnly = tmp.newFolder("host-only")
        Files.createSymbolicLink(mapper.toHostFile("leak").toPath(), hostOnly.toPath())

        val resolved = mapper.resolveFile("leak/secret.txt")

        // 宿主绝对路径的链接文本按容器路径解释，落到 rootfs 下，读不到宿主真实文件
        assertNotEquals(File(hostOnly, "secret.txt"), resolved)
        assertTrue(resolved.absolutePath.startsWith(mapper.toHostFile("/").absolutePath))
    }

    @Test
    fun resolveFile_lexicalEscape_rejectedEvenThroughLink() {
        val mapper = filesystemMapper()
        mapper.toHostFile("target").mkdirs()
        Files.createSymbolicLink(mapper.toHostFile("link").toPath(), File("target").toPath())

        // `..` 越界必须在链接解析之前就被拒绝，否则链接会把逃逸路径截回容器根，反而放行
        assertThrows(IllegalArgumentException::class.java) { mapper.resolveFile("../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { mapper.resolveFile("link/../../../outside.txt") }
    }

    @Test
    fun extraMounts_stillApplyAndLinksUnderMountResolveAsContainerPaths() {
        val mountSrc = tmp.newFolder("sdcard")
        val profile = ContainerProfile(
            id = "custom",
            name = "自定义",
            rootfsSource = RootfsSource.Asset("ignored"),
            shellPath = null,
            extraBindings = listOf("${mountSrc.absolutePath}:/mnt/sdcard"),
            isBuiltin = false
        )
        val mapper = filesystemMapper(profile)
        val expected = File(mountSrc, "probe.txt")
        awaitMount(mapper, expected)

        assertEquals(expected, mapper.toHostFile("/mnt/sdcard/probe.txt"))
        assertEquals(expected, mapper.resolveFile("/mnt/sdcard/probe.txt"))

        Files.createSymbolicLink(File(mountSrc, "link").toPath(), File("/etc").toPath())
        assertEquals(mapper.toHostFile("/etc/hosts"), mapper.resolveFile("/mnt/sdcard/link/hosts"))
    }
}
