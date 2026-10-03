package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var globalDir: File
    private lateinit var projectDir: File

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
        // 严格 mock：本类只把 hub 交给仓库构造，startWatching() 从不会被调用，
        // 所以 hub 上没有任何方法会被调用（严格 mock 只在「被调用却没桩」时才失败）；
        // 与 SkillConfigRepositoryTest / AgentDefinitionTest 对同一类的用法一致。
        val fileChangeHub = mockk<FileChangeHub>()
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
}
