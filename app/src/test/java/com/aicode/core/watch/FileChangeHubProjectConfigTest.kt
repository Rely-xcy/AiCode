package com.aicode.core.watch

import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 远端模式下项目级配置（mcp / permissions / skills / agents.json）落在宿主私有目录
 * `filesDir/aicode/projects/<项目键>/`（见 [ProjectAicodeRoot]），那是 aicode 目录的**孙**层：
 * [FileChangeHub.watchAicode] 的宿主路由只到 aicode 目录的直系子项、远端路由又只在服务器上，
 * 两条都够不着——改动项目级配置因此不产生任何事件，四个按域认领的消费方都不刷新。
 *
 * 这两个用例钉住补上 `projects/` 路由之后的行为：改动该目录会产出一批
 * [ChangeDomain.AICODE_CONFIG] 变更，消费方据此刷新。旧实现下两条都会超时失败。
 *
 * 单测里 android.jar 是 mockable 的（`unitTests.isReturnDefaultValues`），FileObserver 不产生事件，
 * 事件只可能来自快照轮询（`fallbackPoll`，2s 一轮）；轮询按 mtime + size 比对，故每轮改写都换内容。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileChangeHubProjectConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 远端模式的宿主私有配置目录（= [ContainerInstaller.aicodeDir]）。 */
    private lateinit var aicodeDir: File

    /** 项目级配置目录：`<aicode 目录>/projects/<项目键>/`。 */
    private lateinit var projectDir: File

    private lateinit var workspaceRepository: WorkspaceRepository
    private lateinit var projectAicodeRoot: ProjectAicodeRoot
    private lateinit var hub: FileChangeHub

    @Before
    fun setUp() {
        // 观察器起停走 Dispatchers.Main；纯 JVM 单测里没有 Main Looper，必须先换成测试调度器，
        // 且必须在构造 FileChangeHub 之前（它的 mainScope 在构造期就取 Dispatchers.Main.immediate）。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val filesDir = tempFolder.newFolder("files")
        aicodeDir = File(filesDir, "aicode")
        projectDir = File(File(aicodeDir, "projects"), "ws-1a2b3c4d")
        projectDir.mkdirs()

        workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.current } returns MutableStateFlow<Workspace?>(null)
        every { workspaceRepository.currentPathOrNull() } returns REMOTE_WORKSPACE

        val containerInstaller = mockk<ContainerInstaller>()
        every { containerInstaller.aicodeDir } returns aicodeDir

        val pathMapper = mockk<WorkspacePathMapper>()
        every { pathMapper.toContainerPath(any()) } answers {
            val abs = File(firstArg<String>()).absolutePath
            val prefix = aicodeDir.absolutePath + File.separator
            if (abs.startsWith(prefix)) {
                FileChangeHub.AICODE_ROOT + "/" + abs.removePrefix(prefix)
            } else {
                abs
            }
        }

        val executionModeHolder = ExecutionModeHolder()
        executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)

        val remoteSshConnection = mockk<RemoteSshConnection>()
        every { remoteSshConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        // 远端 home 未探到 → 远程那条路由退化为空流，事件只能来自宿主这条（正是被测的路由）。
        every { remoteSshConnection.remoteHome } returns null

        val poller = mockk<RemoteFileWatchPoller>()
        every { poller.watch(any(), any(), any(), any(), any()) } returns emptyFlow()

        projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.forPath(any()) } returns projectDir
        every { projectAicodeRoot.currentOrNull() } returns projectDir

        hub = FileChangeHub(
            workspaceRepository,
            containerInstaller,
            pathMapper,
            executionModeHolder,
            remoteSshConnection,
            poller
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun projectConfigChange_under_host_private_projects_dir_is_labeled_as_aicode_config() = runBlocking {
        val configFile = projectFile("permissions.json")
        configFile.writeText(projectConfigJson(0))

        val batches = Channel<FileChangeBatch>(Channel.UNLIMITED)
        val collector = launch { hub.watchAicode().collect { batches.send(it) } }
        try {
            val batch = awaitChangeAfterRewrite(configFile) {
                withTimeoutOrNull(REWRITE_WAIT_MS) { batches.receive() }
            }

            assertNotNull("改写后未能等到 projects/ 下的变更：watchAicode 没覆盖宿主私有的项目级配置目录", batch)
            val received = batch!!
            assertTrue(
                "变更必须标成 AICODE_CONFIG 域，消费方才认领：${received.changes.map { it.domain }}",
                received.touches(ChangeDomain.AICODE_CONFIG)
            )
            val change = received.changes.firstOrNull { it.hostPath == configFile.absolutePath }
            assertNotNull("变更里应含被改写的配置文件：${received.changes.map { it.hostPath }}", change)
            assertEquals(
                "配置在 aicode 目录下，容器路径应还原成 /root/.aicode 形式",
                "${FileChangeHub.AICODE_ROOT}/${PROJECTS_DIR}/${projectDir.name}/permissions.json",
                change!!.containerPath
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun agentsConfigChange_under_projects_dir_notifies_consumer() = runBlocking {
        val configFile = projectFile("agents.json")
        configFile.writeText(agentsJson(0))

        val repository = AgentDefinitionConfigRepository(
            projectAicodeRoot,
            hub,
            mockk<FileAccessProvider>()
        )
        val refreshes = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch { repository.changes.collect { refreshes.send(Unit) } }
        try {
            // 改动之前不该有刷新信号，否则下面的断言没有区分度。
            assertNull(
                "订阅建立后、改动文件前不该有刷新信号",
                withTimeoutOrNull(1_000) { refreshes.receive() }
            )

            val notified = awaitChangeAfterRewrite(configFile) {
                withTimeoutOrNull(REWRITE_WAIT_MS) { refreshes.receive() }
            }

            assertNotNull("改写后消费方一直没刷新：projects/ 下的改动没能形成 AICODE_CONFIG 批次", notified)
        } finally {
            collector.cancel()
        }
    }

    private fun projectFile(name: String): File = File(projectDir, name)

    /**
     * 反复改写 [file]（每轮换内容，保证 mtime 与 size 至少变一个）直到 [await] 拿到结果。
     *
     * 订阅建立时的那次快照是基线、不产生事件，所以必须**在订阅之后**改写；每轮给快照轮询留够时间
     * （轮询 2s 一轮），轮与轮之间也顺带错开时间窗口。全部轮次都没等到就返回 null。
     */
    private suspend fun <T> awaitChangeAfterRewrite(file: File, await: suspend () -> T?): T? {
        delay(BASELINE_WAIT_MS)
        repeat(REWRITE_ROUNDS) { round ->
            file.writeText(projectConfigJson(round + 1))
            val result = await()
            if (result != null) return result
        }
        return null
    }

    /** 每次内容长度不同（尾部填充），确保快照里的 size 一定变化。 */
    private fun projectConfigJson(round: Int): String =
        "{\"permissions\":{\"allow\":[\"Bash(git status)\"],\"round\":$round}}\n" + " ".repeat(round)

    private fun agentsJson(round: Int): String =
        "{\"disabled\":[\"agent-$round\"]}\n" + " ".repeat(round)

    private companion object {
        const val REMOTE_WORKSPACE = "/remote/ws"
        const val PROJECTS_DIR = "projects"

        /** 订阅建立后先等快照基线；轮询周期 2s，基线一定在这之前拍完。 */
        const val BASELINE_WAIT_MS = 1_000L

        /** 每轮改写后等事件的时间；快照轮询 2s 一轮。 */
        const val REWRITE_WAIT_MS = 3_500L

        /** 最多改写几轮（旧实现下必全轮超时）。 */
        const val REWRITE_ROUNDS = 4
    }
}
