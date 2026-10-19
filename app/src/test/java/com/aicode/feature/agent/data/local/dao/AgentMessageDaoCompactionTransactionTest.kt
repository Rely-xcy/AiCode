package com.aicode.feature.agent.data.local.dao

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 压缩落库的两个事务方法在真实 SQLite 上的原子性：
 *
 * 1. 折叠：marker + 摘要一次提交，两行都会在；
 * 2. 链合并：新块两行 + 旧块淘汰标记一次提交，成功时都生效；
 * 3. 链合并标记一行都没命中时抛异常，Room 回滚整个事务——新块不会残留成孤立行。
 *
 * 用 Robolectric 在 JVM 上跑真实 SQLite，环境约束与
 * [AgentMessageDaoSessionContextStatsTest] 相同：容器/PRoot 里加载不了 native 库，直接跳过。
 */
@RunWith(AndroidJUnit4::class)
class AgentMessageDaoCompactionTransactionTest {

    private lateinit var db: AgentDatabase
    private lateinit var dao: AgentMessageDao

    companion object {
        private const val SESSION = "session-tx"

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
    fun insertCompactionPairWritesMarkerAndSummaryTogether() = runBlocking {
        dao.insertCompactionPair(
            marker = markerRow("marker-1", timestamp = 100),
            summary = summaryRow("summary-1", timestamp = 101, boundary = 99)
        )

        val rows = dao.getMessagesBySessionOnce(SESSION)
        assertEquals(2, rows.size)
        assertEquals(listOf("marker-1", "summary-1"), rows.map { it.id })
        assertEquals(listOf(100L, 101L), rows.map { it.timestamp })
        assertTrue(rows[0].isCompactionMarker)
        assertTrue(rows[1].isContextSummary)
        assertEquals(99L, rows[1].foldsUpToTimestamp)
    }

    @Test
    fun insertChainMergePairInsertsNewBlockAndCompactsOldRows() = runBlocking {
        dao.insertAll(
            listOf(
                markerRow("marker-0", timestamp = 100),
                summaryRow("summary-0", timestamp = 101, boundary = 99),
                markerRow("marker-1", timestamp = 110),
                summaryRow("summary-1", timestamp = 111, boundary = 109)
            )
        )

        val marked = dao.insertChainMergePair(
            marker = markerRow("marker-new", timestamp = 99),
            summary = summaryRow("summary-new", timestamp = 100, boundary = 109),
            sessionId = SESSION,
            supersededIds = listOf("marker-0", "summary-0", "marker-1", "summary-1")
        )

        assertEquals(4, marked)
        val rows = dao.getMessagesBySessionOnce(SESSION).associateBy { it.id }
        assertEquals(6, rows.size)
        assertTrue(rows.getValue("marker-new").isCompactionMarker)
        assertEquals(99L, rows.getValue("marker-new").timestamp)
        assertTrue(rows.getValue("summary-new").isContextSummary)
        assertEquals(109L, rows.getValue("summary-new").foldsUpToTimestamp)
        listOf("marker-0", "summary-0", "marker-1", "summary-1").forEach { id ->
            assertTrue("旧块 $id 应标 isCompacted", rows.getValue(id).isCompacted)
        }
        assertTrue(!rows.getValue("marker-new").isCompacted)
        assertTrue(!rows.getValue("summary-new").isCompacted)
    }

    @Test
    fun insertChainMergePairWithNoMatchingRowsRollsBackNewBlock() = runBlocking {
        // 旧块行一条都不存在：事务内标记命中 0 行 → 抛异常 → 新块两行也必须回滚
        val failure = runCatching {
            dao.insertChainMergePair(
                marker = markerRow("marker-orphan", timestamp = 99),
                summary = summaryRow("summary-orphan", timestamp = 100, boundary = 109),
                sessionId = SESSION,
                supersededIds = listOf("missing-marker", "missing-summary")
            )
        }

        assertTrue(
            "应抛 CompactionChainBlocksNotMarkedException，实际：${failure.exceptionOrNull()}",
            failure.exceptionOrNull() is CompactionChainBlocksNotMarkedException
        )
        assertEquals(emptyList<AgentMessageEntity>(), dao.getMessagesBySessionOnce(SESSION))
    }

    private fun markerRow(id: String, timestamp: Long) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.USER.name,
        content = CONTEXT_COMPACTION_MARKER,
        timestamp = timestamp,
        isCompactionMarker = true
    )

    private fun summaryRow(id: String, timestamp: Long, boundary: Long) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.ASSISTANT.name,
        content = "摘要 $id",
        timestamp = timestamp,
        isContextSummary = true,
        foldsUpToTimestamp = boundary
    )
}
