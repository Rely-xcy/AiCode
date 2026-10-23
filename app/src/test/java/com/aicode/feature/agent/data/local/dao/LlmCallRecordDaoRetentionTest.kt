package com.aicode.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmCallRecordDaoRetentionTest {

    private lateinit var db: AgentDatabase
    private lateinit var dao: LlmCallRecordDao

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境", !androidContainer)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).build()
        dao = db.llmCallRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun compactDetailsBefore_clearsOnlyOldDiagnosticFieldsAndKeepsStatistics() = runBlocking {
        val cutoff = 10_000L
        dao.insert(record(createdAt = cutoff - 1, sessionId = "old"))
        dao.insert(record(createdAt = cutoff, sessionId = "boundary"))
        dao.insert(record(createdAt = cutoff + 1, sessionId = "new"))

        assertEquals(1, dao.compactDetailsBefore(cutoff))

        val calls = dao.getRecentCalls(0, 10, 0).first().map { it.record }.sortedBy { it.createdAt }
        val old = calls[0]
        assertEquals(null, old.sessionId)
        assertEquals(null, old.reasoningEffort)
        assertEquals(null, old.errorMessage)
        assertEquals(null, old.stopReason)
        assertEquals("provider", old.providerId)
        assertEquals("model", old.model)
        assertEquals("chat", old.kind)
        assertEquals(100, old.inputTokens)
        assertEquals(20, old.outputTokens)
        assertEquals(10, old.cachedInputTokens)
        assertEquals(5, old.cacheCreationTokens)
        assertEquals(123, old.ttfbMillis)
        assertEquals(456, old.durationMillis)
        assertEquals("error", old.status)
        assertEquals(2, old.retryCount)
        assertEquals(cutoff - 1, old.createdAt)

        assertEquals("boundary", calls[1].sessionId)
        assertEquals("new", calls[2].sessionId)
        assertEquals(3, dao.getSummary(0).first().calls)
        assertEquals(300L, dao.getSummary(0).first().inputTokens)
        assertEquals(0, dao.compactDetailsBefore(cutoff))
    }

    private fun record(createdAt: Long, sessionId: String) = LlmCallRecordEntity(
        sessionId = sessionId,
        providerId = "provider",
        model = "model",
        reasoningEffort = "medium",
        kind = "chat",
        inputTokens = 100,
        outputTokens = 20,
        cachedInputTokens = 10,
        cacheCreationTokens = 5,
        ttfbMillis = 123,
        durationMillis = 456,
        status = "error",
        errorMessage = "diagnostic",
        stopReason = "stop",
        retryCount = 2,
        createdAt = createdAt
    )
}
