package com.aicode.feature.agent.domain.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 回放历史的摘要去重：库里可能残留多对 marker + 接手摘要（旧的要等下一次折叠才会被标
 * isCompacted），模型可见的那份历史只应带最新一对。
 *
 * 用 Robolectric 在 JVM 上跑真实 SQLite（与 [com.aicode.core.db.MigrationTest] 同一套环境约束）：
 * 容器/PRoot 里加载不了 Robolectric 的 native 库，直接跳过；跳过不算失败，CI 上真实执行。
 */
@RunWith(AndroidJUnit4::class)
class MessagePersistenceUseCaseHistoryTest {

    private lateinit var db: AgentDatabase
    private lateinit var useCase: MessagePersistenceUseCase

    companion object {
        private const val SESSION = "session-1"

        /** 没有「执行中」工具行时的占位前缀：不匹配任何真实内容即可。 */
        private const val NO_PENDING_MARKER = "__no_pending_tool__"

        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).build()
        useCase = MessagePersistenceUseCase(db.agentMessageDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun keepsOnlyLatestCompactionPair() = runTest {
        val dao = db.agentMessageDao()
        dao.insertAll(
            listOf(
                marker("m1", timestamp = 100),
                summary("s1", timestamp = 101, content = "旧摘要"),
                user("u1", timestamp = 150, content = "中间的话"),
                marker("m2", timestamp = 200),
                summary("s2", timestamp = 201, content = "新摘要"),
                user("u2", timestamp = 300, content = "最新的话")
            )
        )

        val history = useCase.buildHistory(SESSION, NO_PENDING_MARKER)

        // 去重只跳过多余的 marker/summary 对，不碰普通消息：
        // u1(ts=150) 排在 m2(ts=200) 之前，所以它在历史里、而且位于最前。
        assertEquals(
            listOf("中间的话", CONTEXT_COMPACTION_MARKER, "新摘要", "最新的话"),
            history.map { it.text() }
        )
        // 只读：库里的行一条不少，也没有任何行被标 isCompacted
        val rows = dao.getMessagesBySessionOnce(SESSION)
        assertEquals(6, rows.size)
        assertFalse(rows.any { it.isCompacted })
    }

    @Test
    fun keepsSinglePairWhenPairsShareTimestamps() = runTest {
        val dao = db.agentMessageDao()
        // 连续折叠的残留形态：两对的时间戳完全相同（同一保留区起点），按 ts 比不出先后
        dao.insertAll(
            listOf(
                marker("m1", timestamp = 100),
                summary("s1", timestamp = 101, content = "旧摘要"),
                marker("m2", timestamp = 100),
                summary("s2", timestamp = 101, content = "新摘要"),
                user("u1", timestamp = 200, content = "最新的话")
            )
        )

        val history = useCase.buildHistory(SESSION, NO_PENDING_MARKER)

        val texts = history.map { it.text() }
        assertEquals(1, texts.count { it == CONTEXT_COMPACTION_MARKER })
        assertEquals(1, texts.count { it == "旧摘要" || it == "新摘要" })
        assertEquals("最新的话", texts.last())
    }

    @Test
    fun synthesizesMarkerWhenLatestSummaryHasNoPairedMarker() = runTest {
        val dao = db.agentMessageDao()
        // 最新摘要的配对 marker 已被标 isCompacted（或缺失）时，回放仍要给它补一条无 id 的 marker
        dao.insertAll(
            listOf(
                marker("m1", timestamp = 100, compacted = true),
                summary("s1", timestamp = 101, content = "摘要"),
                user("u1", timestamp = 200, content = "最新的话")
            )
        )

        val history = useCase.buildHistory(SESSION, NO_PENDING_MARKER)

        assertEquals(
            listOf(CONTEXT_COMPACTION_MARKER, "摘要", "最新的话"),
            history.map { it.text() }
        )
    }

    private fun AgentMessage.text(): String = when (this) {
        is AgentMessage.UserMessage -> content
        is AgentMessage.AssistantMessage -> content
        is AgentMessage.ToolResultMessage -> result
    }

    private fun marker(id: String, timestamp: Long, compacted: Boolean = false) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.USER.name,
        content = CONTEXT_COMPACTION_MARKER,
        timestamp = timestamp,
        isCompactionMarker = true,
        isCompacted = compacted
    )

    private fun summary(id: String, timestamp: Long, content: String) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.ASSISTANT.name,
        content = content,
        timestamp = timestamp,
        isContextSummary = true
    )

    private fun user(id: String, timestamp: Long, content: String) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.USER.name,
        content = content,
        timestamp = timestamp
    )
}
