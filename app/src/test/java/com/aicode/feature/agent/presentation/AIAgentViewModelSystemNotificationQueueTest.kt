package com.aicode.feature.agent.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「待发送」面板只承载用户消息。
 *
 * 后台任务完成、子代理结束这类系统通知在会话忙碌时本来由 [AgentNotificationCenter] 排队、靠工具结果
 * 搭车或本轮收尾的 flush 送达；一旦中途经过 [AIAgentViewModel.enqueueAgentRequest] 的入队分支，就会
 * 出现在输入框上方的待发送面板里，冒充用户自己排队的消息（用户报告：系统通知撞到排队中的消息中）。
 *
 * 这里钉住三条入口语义：带 systemNotifications 的调用在忙碌时回投通知中心且不进面板；不带它的调用
 * （用户消息）忙碌时照旧进面板；后台完成事件经 deliverSystemEvent 进到同一条系统通知路径。
 *
 * 忙碌状态直接写 [AIAgentViewModel] 的 sessionJobs（放一个活跃 Job），而不是真的发一条消息起一轮：
 * 真实轮次会拉起 workflow / Room / 唤醒锁，在 JVM 单测里既跑不动也不稳定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAgentViewModelSystemNotificationQueueTest {

    private companion object {
        const val SID = "session-1"
        const val WORKSPACE = "/workspace"
    }

    private lateinit var viewModel: AIAgentViewModel
    private lateinit var notificationCenter: AgentNotificationCenter
    private val mainDispatcher = UnconfinedTestDispatcher()
    /** 后台命令完成事件流：真实的 [TabFinishedEvent] 从这里进 [AIAgentViewModel.handleBackgroundCommandFinished]。 */
    private val tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        // viewModelScope 走 Dispatchers.Main.immediate；Unconfined 让 stateIn 与入队路径同步生效，
        // 断言之间不需要等待。
        Dispatchers.setMain(mainDispatcher)
        notificationCenter = AgentNotificationCenter()
        viewModel = newViewModel()
        // queuedRequests 按 _currentSessionId 取队列，先切到被测会话。
        viewModel.selectSession(SID)
    }

    @After
    fun tearDown() {
        // viewModelScope 不随测试结束：不取消它，init 里的 collect / stateIn(Eagerly) 会挂到后续测试上。
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `会话忙碌时系统通知回投通知中心，不进待发送面板`() {
        markSessionBusy()
        val item = backgroundTaskNotification("term-1")

        viewModel.enqueueAgentRequest(
            request = AgentNotificationFormatter.buildMessage(listOf(item)),
            projectRoot = WORKSPACE,
            targetSessionId = SID,
            systemNotifications = listOf(item)
        )

        assertTrue("系统通知不能出现在待发送面板", viewModel.queuedRequests.value.isEmpty())
        assertEquals("通知必须留在通知中心等待搭车/flush 送达", 1, notificationCenter.pendingCount(SID))
        assertEquals("term-1", notificationCenter.peek(SID).single().sourceId)
    }

    @Test
    fun `会话忙碌时用户消息照旧进待发送面板`() {
        markSessionBusy()

        viewModel.enqueueAgentRequest(
            request = "帮我改一下这个函数",
            projectRoot = WORKSPACE,
            targetSessionId = SID
        )

        val queued = viewModel.queuedRequests.value
        assertEquals("用户消息在忙碌时必须进待发送面板", 1, queued.size)
        assertEquals("帮我改一下这个函数", queued.single().request)
        assertEquals("用户消息不该占用通知中心", 0, notificationCenter.pendingCount(SID))
    }

    @Test
    fun `后台命令完成通知在会话忙碌时进通知中心，不进待发送面板`() {
        markSessionBusy()

        tabFinishedEvents.tryEmit(
            TabFinishedEvent(
                tabId = "term-9",
                title = "后台构建",
                command = "./gradlew :app:assembleUniversalDebug",
                exitCode = 0,
                sourceSessionId = SID
            )
        )
        mainDispatcher.scheduler.runCurrent()

        assertTrue("系统通知不能出现在待发送面板", viewModel.queuedRequests.value.isEmpty())
        assertEquals(1, notificationCenter.pendingCount(SID))
        assertEquals("term-9", notificationCenter.peek(SID).single().sourceId)
    }

    private fun backgroundTaskNotification(tabId: String) = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = "后台构建",
        outcome = NotificationOutcome.COMPLETED,
        exitCode = 0
    )

    /** 只改一个私有映射：会话视为「有一轮正在跑」，但不启动任何真实轮次。 */
    private fun markSessionBusy() {
        val field = AIAgentViewModel::class.java.getDeclaredField("sessionJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(viewModel) as MutableMap<String, Job>
        jobs[SID] = Job()
    }

    /**
     * 其余构造依赖与本路径无关，一律 relaxed mock；只有依赖的 Flow 需要桩成会发值/能挂住的对象，
     * 否则 init 里的 collect 立刻空转或拿到 null。
     */
    private fun newViewModel(): AIAgentViewModel = AIAgentViewModel(
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
        terminalSessionProvider = mockk(relaxed = true) { every { tabFinishedEvents } returns tabFinishedEvents },
        slashCommandRegistry = mockk(relaxed = true) {
            // init 里 _currentWorkspace.collect 会立刻触发 refresh()：真实实现会扫技能目录。
            coEvery { refresh() } just runs
        },
        fileMentionRepository = mockk(relaxed = true),
        checkpointManager = mockk(relaxed = true),
        checkpointDao = mockk(relaxed = true),
        backupManager = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        agentSoundSettings = mockk(relaxed = true),
        generalSettingsRepository = mockk(relaxed = true),
        keepaliveSettings = mockk(relaxed = true),
        subAgentEventBus = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        agentNotificationCenter = notificationCenter,
        agentDefinitionRepository = mockk(relaxed = true),
        todoItemDao = mockk(relaxed = true),
        fileAccess = mockk<FileAccessProvider>(relaxed = true),
        fileChangeHub = mockk(relaxed = true),
        workspaceWriteSignal = mockk(relaxed = true) { every { writes } returns MutableSharedFlow() },
        contextUsageHolder = mockk(relaxed = true),
        containerSettingsRepository = mockk(relaxed = true) {
            every { activeProfileFlow } returns MutableStateFlow(
                ContainerProfile(
                    id = "builtin",
                    name = "Alpine",
                    rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
                    shellPath = null,
                    isBuiltin = true
                )
            )
        },
        containerOsDetector = mockk(relaxed = true) { every { osMap } returns MutableStateFlow(emptyMap()) },
        liveNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentLiveNotificationManager = mockk(relaxed = true),
        approvalNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentApprovalNotificationManager = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true)
    )
}
