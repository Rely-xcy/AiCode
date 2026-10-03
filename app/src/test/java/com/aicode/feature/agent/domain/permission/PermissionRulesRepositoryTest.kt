package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import java.io.File
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
    private lateinit var projectDir: File

    /** 当前工作区路径；null = 工作区未落定。 */
    private var currentPath: String? = null

    private val globalRule = PermissionRule("Bash", "git status", PermissionDecision.ALLOW)

    @Before
    fun setUp() {
        val globalDir = tempFolder.newFolder("files")
        projectDir = tempFolder.newFolder("project-aicode")
        val context = mockk<Context>()
        every { context.filesDir } returns globalDir
        val workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.currentPathOrNull() } answers { currentPath }
        val projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.forPath(any()) } returns projectDir
        repository = PermissionRulesRepository(
            context,
            workspaceRepository,
            projectAicodeRoot,
            mockk<FileChangeHub>(relaxed = true)
        )
    }

    private fun projectFile(): File = File(projectDir, "permissions.json")

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
    fun unsettledWorkspace_addProjectRule_skipped() = runTest {
        currentPath = null

        val written = repository.add(PermissionScope.PROJECT, globalRule)

        // 未落定写不进去：这也是引擎在「项目级规则读不到」时不允许「始终允许」记忆的原因
        assertFalse(written)
        assertFalse(projectFile().exists())
    }
}
