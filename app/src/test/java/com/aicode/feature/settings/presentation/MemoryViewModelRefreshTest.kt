package com.aicode.feature.settings.presentation

import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.SessionContextStats
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 记忆页首屏顺序：清单读完就放开「正在读取」，短期卡片（查库）后到。
 *
 * 旧实现把两件事串在同一个 try 里、只在 finally 置 `loading = false`：短期卡片要查两次库，
 * 期间界面一直停在「正在读取」，明明清单早就到手了。这里卡住短期查询来钉这个顺序——
 * 查询不返回时 `loading` 也必须是 false，列表也必须已经可读。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelRefreshTest {

    private lateinit var viewModel: MemoryViewModel

    @Before
    fun setUp() {
        // viewModelScope 走 Dispatchers.Main.immediate。
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // stateIn(Eagerly) 与 init 里的 refresh 挂在 viewModelScope 上，不取消会泄漏到后续测试。
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        repository: MemoryRepository,
        sessionUseCase: SessionUseCase,
        agentMessageDao: AgentMessageDao
    ): MemoryViewModel {
        val settings = mockk<MemorySettingsRepository>(relaxed = true)
        every { settings.activeMemoryEnabledFlow } returns MutableStateFlow(false)
        every { settings.curationIntervalHoursFlow } returns MutableStateFlow(24)
        val workspace = mockk<WorkspaceRepository>(relaxed = true)
        every { workspace.currentPathOrNull() } returns WORKSPACE
        return MemoryViewModel(repository, workspace, settings, sessionUseCase, agentMessageDao)
    }

    @Test
    fun `清单到手即放开正在读取，短期卡片随后到达`() = runBlocking {
        val listing = CountDownLatch(1)
        val shortTermStarted = CountDownLatch(1)
        val releaseShortTerm = CountDownLatch(1)

        val repository = mockk<MemoryRepository>(relaxed = true)
        every { repository.listMemories(WORKSPACE) } answers {
            listing.countDown()
            listOf(
                Memory(
                    name = "alpha",
                    description = "d",
                    scope = MemoryScope.GLOBAL,
                    content = "body alpha"
                )
            )
        }
        val session = mockk<ChatSessionEntity>(relaxed = true)
        every { session.id } returns "s1"
        every { session.title } returns "会话一"
        every { session.lastInputTokens } returns 512
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getMostRecentSessionOfWorkspace(WORKSPACE) } coAnswers {
            shortTermStarted.countDown()
            releaseShortTerm.await(5, TimeUnit.SECONDS)
            session
        }
        val dao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { dao.sessionContextStats("s1") } returns SessionContextStats(
            retainedMessages = 3,
            foldCount = 1
        )

        viewModel = newViewModel(repository, sessionUseCase, dao)

        assertTrue("init 应当触发读盘", listing.await(5, TimeUnit.SECONDS))
        // 旧实现：短期查询不返回 → loading 一直 true，这一句会超时。
        awaitTrue("清单到手后必须放开「正在读取」") { !viewModel.loading.value }
        assertEquals(1, viewModel.memories.value.size)

        assertTrue("清单之后应当接着读短期卡片", shortTermStarted.await(5, TimeUnit.SECONDS))
        assertNull("短期查询还没返回，卡片就还不该有数据", viewModel.shortTermSession.value)

        releaseShortTerm.countDown()
        awaitTrue("短期卡片应当随后到位") { viewModel.shortTermSession.value != null }
        val shortTerm = requireNotNull(viewModel.shortTermSession.value)
        assertEquals("会话一", shortTerm.title)
        assertEquals(3, shortTerm.retainedMessages)
        assertEquals(1, shortTerm.foldCount)
        assertEquals(512, shortTerm.lastInputTokens)
    }

    /** 一直等到条件成立；超时即测试失败（附带说明是哪一步没等到）。 */
    private suspend fun awaitTrue(message: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(5_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue(message, met == true)
    }

    private companion object {
        const val WORKSPACE = "/ws"
    }
}
