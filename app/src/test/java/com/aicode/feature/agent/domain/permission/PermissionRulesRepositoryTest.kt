package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.watch.ChangeDomain
import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PermissionRulesRepository.loadEffectiveForCurrentProject] 的「读不到 ≠ 没有规则」语义：
 * 工作区未落定、项目文件不存在、项目文件损坏三种情形必须能区分开——「文件不存在」= 确认没有项目级规则；
 * 「未落定 / 解析失败」= 读不到（projectRulesConfirmed=false），不得退化成「项目级没有规则」。
 */
class PermissionRulesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: PermissionRulesRepository
    private lateinit var fileChangeHub: FileChangeHub
    private lateinit var globalDir: File
    private lateinit var projectDir: File

    /** 测试里手动投递给仓库的变更批次（收集发生在仓库自己的 IO 协程里）。 */
    private val batches = MutableSharedFlow<FileChangeBatch>(replay = 1, extraBufferCapacity = 8)

    /** 当前工作区路径；null = 工作区未落定。 */
    private var currentPath: String? = null

    private val globalRule = PermissionRule("Bash", "git status", PermissionDecision.ALLOW)

    @Before
    fun setUp() {
        globalDir = tempFolder.newFolder("files")
        projectDir = tempFolder.newFolder("project-aicode")
        val context = mockk<Context>()
        every { context.filesDir } returns globalDir
        val workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.currentPathOrNull() } answers { currentPath }
        // 构造期求值的两个 val（currentProjectNameFlow / projectRulesFlow）都读 current，
        // 不桩就会在构造函数那一行抛 MockKException。
        every { workspaceRepository.current } returns MutableStateFlow<Workspace?>(null)
        val projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.forPath(any()) } returns projectDir
        // 严格 mock：本类只把 hub 交给仓库构造，只有「按域重读」那条用例会调 startWatching()。
        fileChangeHub = mockk<FileChangeHub>()
        every { fileChangeHub.watchAicode(any(), any(), any(), any(), any()) } returns batches
        every { fileChangeHub.watchWorkspace(any(), any(), any(), any(), any(), any()) } returns emptyFlow()
        repository = PermissionRulesRepository(
            context,
            workspaceRepository,
            projectAicodeRoot,
            fileChangeHub
        )
    }

    private fun projectFile(): File = File(projectDir, "permissions.json")

    /** 全局规则文件：`filesDir/aicode/permissions.json`。 */
    private fun globalPermissionsFile(): File = File(File(globalDir, "aicode"), "permissions.json")

    @Test
    fun unsettledWorkspace_onlyGlobalRules_andNotConfirmed() = runTest {
        repository.setGlobalRules(listOf(globalRule))
        currentPath = null

        val effective = repository.loadEffectiveForCurrentProject()

        assertEquals(listOf(globalRule), effective.rules)
        assertFalse(effective.projectRulesConfirmed)
    }

    @Test
    fun missingProjectFile_confirmedAsNoProjectRules() = runTest {
        repository.setGlobalRules(listOf(globalRule))
        currentPath = "/remote/ws"

        val effective = repository.loadEffectiveForCurrentProject()

        // 文件不存在 = 确认没有项目级规则，不是「读不到」
        assertEquals(listOf(globalRule), effective.rules)
        assertTrue(effective.projectRulesConfirmed)
    }

    @Test
    fun corruptedProjectFile_notConfirmed() = runTest {
        repository.setGlobalRules(listOf(globalRule))
        currentPath = "/remote/ws"
        projectFile().writeText("{broken json")

        val effective = repository.loadEffectiveForCurrentProject()

        // 解析失败 = 读不到，不能当成「项目级没有规则」
        assertEquals(listOf(globalRule), effective.rules)
        assertFalse(effective.projectRulesConfirmed)
    }

    @Test
    fun projectRulesRead_mergedBeforeGlobal_andConfirmed() = runTest {
        val projectDeny = PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.DENY)
        repository.setGlobalRules(listOf(globalRule))
        currentPath = "/remote/ws"
        projectFile().writeText("""{"permissions":{"deny":["Bash(rm -rf /tmp/build)"]}}""")

        val effective = repository.loadEffectiveForCurrentProject()

        // 项目级在前、全局在后；项目级 DENY 必须在快照里可见
        assertEquals(listOf(projectDeny, globalRule), effective.rules)
        assertTrue(effective.projectRulesConfirmed)
    }

    @Test
    fun corruptedGlobalFile_notConfirmed() = runTest {
        currentPath = "/remote/ws"
        globalPermissionsFile().parentFile?.mkdirs()
        globalPermissionsFile().writeText("{broken json")

        val effective = repository.loadEffectiveForCurrentProject()

        // 全局层与项目级同一套语义：解析失败 = 读不到，不能当成「全局没有规则」
        assertFalse(effective.globalRulesConfirmed)
    }

    @Test
    fun missingGlobalFile_confirmedAsNoGlobalRules() = runTest {
        currentPath = "/remote/ws"

        val effective = repository.loadEffectiveForCurrentProject()

        // 文件不存在 = 确认没有全局规则（默认情形，不应当成「读不到」）
        assertTrue(effective.globalRulesConfirmed)
        assertEquals(emptyList<PermissionRule>(), effective.rules)
    }

    @Test
    fun unsettledWorkspace_addProjectRule_skipped() = runTest {
        currentPath = null

        val written = repository.add(PermissionScope.PROJECT, globalRule)

        // 未落定写不进去：这也是引擎在「项目级规则读不到」时不允许「始终允许」记忆的原因
        assertFalse(written)
        assertFalse(projectFile().exists())
    }

    /**
     * 外部修改后按「变更域」重读，不按文件路径比对。
     *
     * 远端模式下事件带的是服务器路径（这里用 `/srv/app/.aicode/permissions.json` 模拟），与宿主配置路径
     * 完全对不上：改动前那套 `hostPath == 全局/项目配置文件` 的判据在远端永远命不中，改了配置不重载。
     * 域不对的批次（工作区里的普通文件）不得触发重读。
     */
    @Test
    fun `配置域变更触发重读，工作区域变更不触发`() = runBlocking {
        repository.setGlobalRules(listOf(globalRule))
        repository.startWatching()

        val external = PermissionRule("Bash", "git log", PermissionDecision.ALLOW)
        globalPermissionsFile().writeText("""{"permissions":{"allow":["Bash(git log)"]}}""")
        batches.emit(fileChanged("/srv/app/.aicode/permissions.json", ChangeDomain.AICODE_CONFIG))
        awaitUntil("服务器路径的配置域变更没被认领") {
            repository.loadEffectiveForCurrentProject().rules == listOf(external)
        }

        // 域不对：文件确实又变了，但缓存不得跟着变
        globalPermissionsFile().writeText("""{"permissions":{"allow":["Bash(git status)"]}}""")
        batches.emit(fileChanged("/srv/app/main.kt", ChangeDomain.WORKSPACE_FILE))
        delay(200)
        assertEquals(listOf(external), repository.loadEffectiveForCurrentProject().rules)
    }

    private fun fileChanged(hostPath: String, domain: ChangeDomain) = FileChangeBatch(
        listOf(
            FileChange(
                root = ChangeRoot.WORKSPACE,
                hostPath = hostPath,
                containerPath = "~/workspace/.aicode/permissions.json",
                kind = ChangeKind.MODIFIED,
                domain = domain
            )
        )
    )

    /** 重读发生在仓库自己的 IO 协程里，与测试线程异步；轮询等它落到缓存上（超时即失败）。 */
    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(20)
        assertTrue(what, condition())
    }
}
