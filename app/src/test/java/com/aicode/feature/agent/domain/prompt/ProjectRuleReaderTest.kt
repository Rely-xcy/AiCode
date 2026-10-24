package com.aicode.feature.agent.domain.prompt

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ProjectRuleReader]：项目规则必须经 [FileAccessProvider] 以容器路径读取。
 *
 * 改动前的实现直接构造 `File(projectRoot)`，远程模式下那是服务器路径、本机不存在，
 * `AGENTS.md` / `CLAUDE.md` 永远读不到且不报错——项目规则在远程模式等于失效。
 * 这两个用例分别覆盖「本地宿主目录」与「远程服务器目录」两种口径。
 */
class ProjectRuleReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 本地模式：真实 LocalFileAccess + 真实路径映射 ----------

    @Test
    fun localWorkspace_readsGlobalAndProjectRules() {
        val ws = tmp.newFolder("ws")
        val aicode = tmp.newFolder("aicode")
        File(ws, "AGENTS.md").writeText("项目规则")
        File(aicode, "CLAUDE.md").writeText("全局规则")

        val rules = ProjectRuleReader(localFileAccess(ws, aicode)).read(hasProject = true)

        assertEquals("AGENTS.md 优先", "项目规则", rules?.project)
        assertEquals("全局用 CLAUDE.md 兜底", "全局规则", rules?.global)
    }

    @Test
    fun localWorkspace_agentsFileWinsOverClaudeFile() {
        val ws = tmp.newFolder("ws")
        val aicode = tmp.newFolder("aicode")
        File(ws, "AGENTS.md").writeText("agents")
        File(ws, "CLAUDE.md").writeText("claude")

        val rules = ProjectRuleReader(localFileAccess(ws, aicode)).read(hasProject = true)

        assertEquals("agents", rules?.project)
    }

    @Test
    fun noProject_onlyGlobalRulesRead() {
        val ws = tmp.newFolder("ws")
        val aicode = tmp.newFolder("aicode")
        File(ws, "AGENTS.md").writeText("项目规则")
        File(aicode, "AGENTS.md").writeText("全局规则")

        val rules = ProjectRuleReader(localFileAccess(ws, aicode)).read(hasProject = false)

        assertNull("没有工作区时不读工作区规则", rules?.project)
        assertEquals("全局规则", rules?.global)
    }

    // ---------- 远程模式：容器路径由服务器上的目录解析 ----------

    @Test
    fun remoteWorkspace_readsRulesFromServerPaths() {
        val serverHome = tmp.newFolder("server-home")
        val serverWs = tmp.newFolder("server-ws")
        File(serverHome, ".aicode").mkdirs()
        File(serverHome, ".aicode/AGENTS.md").writeText("服务器全局规则")
        File(serverWs, "AGENTS.md").writeText("服务器项目规则")
        val access = ServerFileAccess(serverHome.absolutePath, serverWs.absolutePath)

        val rules = ProjectRuleReader(access).read(hasProject = true)

        assertEquals("服务器项目规则", rules?.project)
        assertEquals("服务器全局规则", rules?.global)
        assertTrue(
            "读的必须是容器路径（远程由 SFTP 落到服务器），实际=${access.requested}",
            access.requested.all { it.startsWith("~/") }
        )
    }

    @Test
    fun remoteReadFailure_isNotCachedAsNoRules() {
        val serverHome = tmp.newFolder("server-home")
        val serverWs = tmp.newFolder("server-ws")
        File(serverHome, ".aicode").mkdirs()
        File(serverHome, ".aicode/AGENTS.md").writeText("服务器全局规则")
        File(serverWs, "AGENTS.md").writeText("服务器项目规则")
        val base = ServerFileAccess(serverHome.absolutePath, serverWs.absolutePath)
        var offline = true
        val flaky = object : FileAccessProvider by base {
            override fun isFile(path: String): Boolean {
                if (offline) throw IOException("模拟远程断线")
                return base.isFile(path)
            }
        }
        val reader = ProjectRuleReader(flaky)

        assertNull("读失败时降级为「本次没有规则可用」", reader.read(hasProject = true))

        offline = false
        assertEquals(
            "恢复连接后必须立刻能读到：失败不得被缓存成「没有规则」",
            "服务器项目规则",
            reader.read(hasProject = true)?.project
        )
    }

    @Test
    fun unchangedRules_areNotReReadFromBackend() {
        val serverHome = tmp.newFolder("server-home")
        val serverWs = tmp.newFolder("server-ws")
        File(serverHome, ".aicode").mkdirs()
        File(serverHome, ".aicode/AGENTS.md").writeText("服务器全局规则")
        File(serverWs, "AGENTS.md").writeText("服务器项目规则")
        val access = ServerFileAccess(serverHome.absolutePath, serverWs.absolutePath)
        val reader = ProjectRuleReader(access)

        reader.read(hasProject = true)
        val afterFirst = access.readCount
        reader.read(hasProject = true)

        assertEquals("签名未变时不应再整篇传输规则正文", afterFirst, access.readCount)
        assertEquals("第一轮只读两份规则", 2, afterFirst)
    }

    /** 本地模式：真实映射 + 真实 [LocalFileAccess]，宿主工作区与 AI 配置目录都在临时目录上。 */
    private fun localFileAccess(ws: File, aicode: File): FileAccessProvider {
        val workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.currentPathOrNull() } returns ws.absolutePath
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns aicode
        every { installer.rootfsDirFor(any()) } returns File(aicode, "rootfs")
        val settings = mockk<ContainerSettingsRepository>()
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { settings.customProfilesFlow } returns flowOf(emptyList())
        val home = mockk<PathHomeResolver>()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            val p = firstArg<String>()
            if (p.startsWith("~/")) "/root" + p.removePrefix("~") else p
        }
        return LocalFileAccess(WorkspacePathMapper(workspaceRepository, installer, settings, home))
    }

    /**
     * 模拟远程后端：容器路径 `~/.aicode/…`、`~/workspace/…` 落到服务器上的两个目录。
     * 未覆写的方法按容器路径直通本地实现——本用例涉及的读取只走 [isFile] / [lastModified] / [readFile]。
     */
    private class ServerFileAccess(
        private val serverHome: String,
        private val serverWorkspace: String
    ) : FileAccessProvider by TestFileAccessProvider() {

        private val local = TestFileAccessProvider()
        val requested = mutableListOf<String>()
        var readCount = 0
            private set

        private fun map(path: String): String = when {
            path == "~/.aicode" || path.startsWith("~/.aicode/") -> serverHome + path.removePrefix("~")
            path == "~/workspace" || path.startsWith("~/workspace/") -> serverWorkspace + path.removePrefix("~/workspace")
            else -> path
        }

        override fun isFile(path: String): Boolean {
            requested += path
            return local.isFile(map(path))
        }

        override fun lastModified(path: String): Long {
            requested += path
            return local.lastModified(map(path))
        }

        override fun readFile(path: String): String {
            requested += path
            readCount++
            return local.readFile(map(path))
        }
    }
}
