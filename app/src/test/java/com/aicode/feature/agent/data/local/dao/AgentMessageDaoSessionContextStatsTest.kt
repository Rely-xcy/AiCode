package com.aicode.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 记忆页「本次会话（短期）」卡片的取数：数出仍参与上下文回放的行，以及已折叠（接手摘要）次数。
 *
 * 用 Robolectric 在 JVM 上跑真实 SQLite（与 [com.aicode.core.db.MigrationTest] 同一套环境约束）：
 * 容器/PRoot 里加载不了 Robolectric 的 native 库，直接跳过；跳过不算失败，CI 上真实执行。
 */
@RunWith(AndroidJUnit4::class)
class AgentMessageDaoSessionContextStatsTest {

    private lateinit var db: AgentDatabase
    private lateinit var dao: AgentMessageDao

    companion object {
        private const val SESSION = "session-1"
        private const val OTHER_SESSION = "session-2"

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
        dao = db.agentMessageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun countsOnlyRowsStillInContext() = runTest {
        dao.insertAll(
            listOf(
                row("a", timestamp = 1),
                row("b", timestamp = 2),
                // 被折叠的历史仍在库里（留给 UI 与存档），但回放时会被滤掉，不该算进短期上下文
                row("c", timestamp = 3, compacted = true)
            )
        )

        val stats = dao.sessionContextStats(SESSION)

        assertEquals(2, stats.retainedMessages)
        assertEquals(0, stats.foldCount)
    }

    @Test
    fun countsEachContextSummaryAsOneFold() = runTest {
        dao.insertAll(
            listOf(
                row("first", timestamp = 1, compacted = true),
                row("marker-1", timestamp = 2),
                row("summary-1", timestamp = 3, summary = true),
                row("tail-1", timestamp = 4, compacted = true),
                row("marker-2", timestamp = 5),
                row("summary-2", timestamp = 6, summary = true),
                row("tail-2", timestamp = 7)
            )
        )

        val stats = dao.sessionContextStats(SESSION)

        // 折叠两次后：两对 marker+summary 加最后一段未折叠的历史仍在上下文里
        assertEquals(5, stats.retainedMessages)
        assertEquals(2, stats.foldCount)
    }

    @Test
    fun emptySessionReportsZeros() = runTest {
        val stats = dao.sessionContextStats(SESSION)

        assertEquals(0, stats.retainedMessages)
        assertEquals(0, stats.foldCount)
    }

    @Test
    fun ignoresOtherSessions() = runTest {
        dao.insertAll(
            listOf(
                row("mine", timestamp = 1),
                row("theirs", sessionId = OTHER_SESSION, timestamp = 2),
                row("theirs-summary", sessionId = OTHER_SESSION, timestamp = 3, summary = true)
            )
        )

        val stats = dao.sessionContextStats(SESSION)

        assertEquals(1, stats.retainedMessages)
        assertEquals(0, stats.foldCount)
    }

    private fun row(
        id: String,
        sessionId: String = SESSION,
        timestamp: Long,
        compacted: Boolean = false,
        summary: Boolean = false
    ) = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        role = MessageRole.USER.name,
        content = "content-$id",
        timestamp = timestamp,
        isCompacted = compacted,
        isContextSummary = summary
    )
}
