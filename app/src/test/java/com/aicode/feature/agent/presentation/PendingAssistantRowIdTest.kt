package com.aicode.feature.agent.presentation

import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ViewModel 侧「本轮助手行 id」登记语义（AIAgentViewModel.currentSessionPendingAssistantRowId）。
 *
 * 一轮以 [AgentEvent.AssistantText] 落库助手行时：仅当该行「有可见正文或有 reasoning」才登记其行 id
 * （R2：空白工具行会被 messagesState 过滤，登记了界面也认不到）；每条新的可见 AssistantText 覆盖上一条
 * （R1：目标恒指最近一次）；stopAllAgents / deleteSession 清理该会话条目。
 *
 * 观察方式：把 workflow 的事件流换成测试持有的 [MutableSharedFlow]，逐条 emit 后在每条处理处读
 * StateFlow（Main 用 Unconfined，collector 内联处理，emit 返回即已落值）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingAssistantRowIdTest {

    private lateinit var viewModel: AIAgentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // viewModelScope 不随测试结束：不取消它，init 里的 launch 与 stateIn(Eagerly) 会泄漏到后续测试。
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun visibleText(content: String = "可见正文") = AgentEvent.AssistantText(content = content)

    private fun workflowReturning(events: MutableSharedFlow<AgentEvent>): AgentWorkflow =
        mockk(relaxed = true) { every { executeEvents(any(), any(), any()) } returns events }

    /** 等 workflow 事件流被 job 订阅上（job 前置有一段 withContext(Dispatchers.IO)，订阅会晚于 launch 返回）。 */
    private suspend fun awaitSubscription(events: MutableSharedFlow<AgentEvent>) {
        withTimeout(5_000) { events.subscriptionCount.first { it > 0 } }
    }

    /**
     * 只为这条路径起的 ViewModel：其余构造依赖与本路径无关，一律 relaxed mock。
     * agentWorkflow 传入带事件流桩的 mock。
     */
    private fun newViewModel(
        workflow: AgentWorkflow,
        sessionUseCase: SessionUseCase = mockk(relaxed = true)
    ): AIAgentViewModel = AIAgentViewModel(
        agentWorkflow = workflow,
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
        sessionUseCase = sessionUseCase,
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
        fileAccess = mockk<FileAccessProvider>(relaxed = true),
        fileChangeHub = mockk(relaxed = true),
        workspaceWriteSignal = mockk(relaxed = true),
        contextUsageHolder = mockk(relaxed = true),
        context = mockk(relaxed = true)
    )

    /** 有可见正文的 AssistantText 落库 → 登记该行 id。 */
    @Test
    fun `有可见正文的 AssistantText 登记该行 id`() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>()
        val vm = newViewModel(workflowReturning(events))
        viewModel = vm
        vm.selectSession("s1")

        val job = vm.executeAgentRequestStream("hi", targetSessionId = "s1")
        awaitSubscription(events)
        events.emit(visibleText())

        assertNotNull("有可见正文的助手行应登记 pending id", vm.currentSessionPendingAssistantRowId.value)
        job.cancel()
    }

    /** 无可见正文且无 reasoning（纯工具行）→ 不登记（R2）。 */
    @Test
    fun `无可见正文且无 reasoning 不登记`() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>()
        val vm = newViewModel(workflowReturning(events))
        viewModel = vm
        vm.selectSession("s1")

        val job = vm.executeAgentRequestStream("hi", targetSessionId = "s1")
        awaitSubscription(events)
        events.emit(AgentEvent.AssistantText(content = "", reasoning = ""))

        assertNull("空白工具行不登记 pending id", vm.currentSessionPendingAssistantRowId.value)
        job.cancel()
    }

    /** 连续两条可见正文 → 目标覆盖为后者（R1）。 */
    @Test
    fun `连续两条可见正文取后者`() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>()
        val vm = newViewModel(workflowReturning(events))
        viewModel = vm
        vm.selectSession("s1")

        val job = vm.executeAgentRequestStream("hi", targetSessionId = "s1")
        awaitSubscription(events)
        events.emit(visibleText("第一条"))
        val first = vm.currentSessionPendingAssistantRowId.value
        assertNotNull(first)
        events.emit(visibleText("第二条"))
        val second = vm.currentSessionPendingAssistantRowId.value
        assertNotNull(second)
        assertNotEquals("第二条可见正文应覆盖第一条的 pending id", first, second)
        job.cancel()
    }

    /** stopAllAgents 清空所有会话的 pending id。 */
    @Test
    fun `stopAllAgents 清空 pending`() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>()
        val vm = newViewModel(workflowReturning(events))
        viewModel = vm
        vm.selectSession("s1")

        val job = vm.executeAgentRequestStream("hi", targetSessionId = "s1")
        awaitSubscription(events)
        events.emit(visibleText())
        assertNotNull(vm.currentSessionPendingAssistantRowId.value)

        vm.stopAllAgents()

        assertNull("stopAllAgents 应清空 pending id", vm.currentSessionPendingAssistantRowId.value)
        job.cancel()
    }

    /** deleteSession 移除被删会话的 pending id。 */
    @Test
    fun `deleteSession 移除该会话的 pending`() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>()
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true) {
            coEvery { deleteSession("s1") } returns listOf("s1")
        }
        val vm = newViewModel(workflowReturning(events), sessionUseCase = sessionUseCase)
        viewModel = vm
        vm.selectSession("s1")

        val job = vm.executeAgentRequestStream("hi", targetSessionId = "s1")
        awaitSubscription(events)
        events.emit(visibleText())
        assertNotNull(vm.currentSessionPendingAssistantRowId.value)

        vm.deleteSession("s1").join()
        job.cancel()
        // deleteSession 会把当前会话切走；切回 s1 确认它的条目已被移除。
        vm.selectSession("s1")

        assertNull("被删会话的 pending id 应被移除", vm.currentSessionPendingAssistantRowId.value)
    }
}
