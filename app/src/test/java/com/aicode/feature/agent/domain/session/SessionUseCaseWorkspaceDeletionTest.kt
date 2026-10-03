package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.engine.AgentEngine
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 删除工作区时按 workspacePath 级联清理会话与消息的行为。 */
class SessionUseCaseWorkspaceDeletionTest {

    /** 空模块集的引擎：本测试只验证会话/消息删除，引擎侧钩子无副作用。 */
    private fun emptyEngine(): Lazy<AgentEngine> =
        Lazy { AgentEngine(emptySet(), CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    /** 历史缓存与这两个用例无关：删除路径上只要求它被调用，行为由 MessagePersistenceUseCase 自测。 */
    private fun noopPersistence(): MessagePersistenceUseCase = mockk<MessagePersistenceUseCase>(relaxed = true)

    private fun session(id: String, workspacePath: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = workspacePath,
        parentId = parentId
    )

    @Test
    fun deleteSessionsByWorkspace_deletesMessagesThenSessions() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/a") } returns listOf(
            session("root", "/ws/a"),
            session("sub", "/ws/a", parentId = "root")
        )

        val useCase = SessionUseCase(chatDao, messageDao, noopPersistence(), emptyEngine())
        val deleted = useCase.deleteSessionsByWorkspace("/ws/a")

        assertEquals(2, deleted)
        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.deleteByWorkspace("/ws/a") }
    }

    @Test
    fun deleteSessionsByWorkspace_noSessions_skipsDeletion() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/empty") } returns emptyList()

        val useCase = SessionUseCase(chatDao, messageDao, noopPersistence(), emptyEngine())
        val deleted = useCase.deleteSessionsByWorkspace("/ws/empty")

        assertEquals(0, deleted)
        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { chatDao.deleteByWorkspace(any()) }
    }
}