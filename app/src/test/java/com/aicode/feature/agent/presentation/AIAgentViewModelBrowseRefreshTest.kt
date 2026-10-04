package com.aicode.feature.agent.presentation

import androidx.lifecycle.viewModelScope
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 文件树「手动刷新进行中」这一帧不能丢。
 *
 * 远程模式下手动刷新要串行重列所有已展开目录，可能数秒；期间继续渲染上一份树（仅置 refreshing），
 * UI 在工具栏叠加进行中指示、并暂时禁用刷新按钮。若退回 Loading 或直接把 nodes 换掉，
 * 滚动位置与旧树内容都会闪一下。本用例钉住这两步的先后与「旧树未被替换」。
 *
 * 观察方式：browseState 是 StateFlow（会合并中间值），用一个 [Dispatchers.Unconfined] 收集器
 * 在每次 emit 处同步取帧；第二次列目录卡在一道闸门上，让「进行中」那帧必定能被观察到。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAgentViewModelBrowseRefreshTest {

    private lateinit var viewModel: AIAgentViewModel

    @Before
    fun setUp() {
        // viewModelScope 走 Dispatchers.Main.immediate。
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // viewModelScope 的生命周期不随测试结束：不取消它，ViewModel init 里起的 launch
        // 与 stateIn(Eagerly) 会继续挂在 Dispatchers.Main 上，泄漏到后面的测试。
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `手动刷新先发一帧旧树进行中态，重列完成后再发新树`() = runBlocking {
        val firstTree = listOf(FileEntry(name = "a.txt", isDirectory = false, size = 1, lastModified = 0))
        val secondTree = firstTree + FileEntry(name = "b.txt", isDirectory = false, size = 2, lastModified = 0)

        val fileAccess = mockk<FileAccessProvider>()
        var listCalls = 0
        val refreshBuilding = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles(any()) } answers {
            listCalls += 1
            if (listCalls == 1) {
                firstTree
            } else {
                // 第二次列目录就是手动刷新的重列：卡住它，让「进行中」那一帧能稳定被观察到。
                refreshBuilding.countDown()
                releaseRefresh.await()
                secondTree
            }
        }

        viewModel = newFileBrowseViewModel(fileAccess)
        val frames = ConcurrentLinkedQueue<FileBrowseState>()
        val collectJob = launch(Dispatchers.Unconfined) { viewModel.browseState.collect { frames += it } }

        val first = awaitSuccess(frames) { !it.refreshing }
        // 等刷新触发器订阅就绪：刷新信号经 drop(1) 丢掉订阅时的当前值，抢在订阅前发出会被吞掉。
        delay(200)
        viewModel.refreshBrowse()

        assertTrue("手动刷新应触发重列（第二次 listFiles）", refreshBuilding.await(5, TimeUnit.SECONDS))
        val refreshing = awaitSuccess(frames) { it.refreshing }
        assertSame("进行中帧必须原样复用上一份树，不能替换 nodes", first.nodes, refreshing.nodes)

        releaseRefresh.countDown()
        val updated = awaitSuccess(frames) { !it.refreshing && it.nodes.size == secondTree.size + 1 }
        assertEquals(secondTree.size + 1, updated.nodes.size)
        assertTrue("新树与旧树不是同一份", updated.nodes !== first.nodes)
        collectJob.cancel()
    }

    /** 取第一帧满足 [predicate] 的 [FileBrowseState.Success]；超时即失败。 */
    private suspend fun awaitSuccess(
        frames: ConcurrentLinkedQueue<FileBrowseState>,
        predicate: (FileBrowseState.Success) -> Boolean
    ): FileBrowseState.Success = withTimeout(5_000) {
        var match: FileBrowseState.Success? = null
        while (match == null) {
            match = frames.firstOrNull { it is FileBrowseState.Success && predicate(it) } as? FileBrowseState.Success
            if (match == null) delay(10)
        }
        match!!
    }

    /**
     * 只为文件树这一条路径起的 ViewModel：它只依赖 [FileAccessProvider] 与文件变更信号，
     * 其余构造依赖与本路径无关，一律 relaxed mock。
     */
    private fun newFileBrowseViewModel(fileAccess: FileAccessProvider): AIAgentViewModel = AIAgentViewModel(
        agentWorkflow = mockk(relaxed = true),
        toolRegistry = mockk(relaxed = true),
        agentEngine = mockk(relaxed = true),
        agentMessageDao = mockk(relaxed = true),
        agentDatabase = mockk(relaxed = true),
        chatSessionDao = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        modelCostCalculator = mockk(relaxed = true),
        aiProviderRepository = mockk(relaxed = true),
        defaultModelSettingsRepository = mockk(relaxed = true),
        modelReasoningEffortRepository = mockk(relaxed = true),
        toolPermissionManager = mockk(relaxed = true),
        askUserQuestionManager = mockk(relaxed = true),
        containerEngine = mockk(relaxed = true),
        sessionUseCase = mockk(relaxed = true),
        messagePersistenceUseCase = mockk(relaxed = true),
        planApprovalManager = mockk(relaxed = true),
        terminalSessionManager = mockk(relaxed = true) { every { tabFinishedEvents } returns MutableSharedFlow() },
        slashCommandRegistry = mockk(relaxed = true),
        checkpointManager = mockk(relaxed = true),
        checkpointDao = mockk(relaxed = true),
        backupManager = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        agentSoundSettings = mockk(relaxed = true),
        generalSettingsRepository = mockk(relaxed = true),
        keepaliveSettings = mockk(relaxed = true),
        subAgentEventBus = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        agentNotificationCenter = mockk(relaxed = true),
        agentDefinitionRepository = mockk(relaxed = true),
        todoItemDao = mockk(relaxed = true),
        fileAccess = fileAccess,
        fileChangeHub = mockk(relaxed = true),
        workspaceWriteSignal = mockk(relaxed = true),
        contextUsageHolder = mockk(relaxed = true),
        context = mockk(relaxed = true)
    )
}
