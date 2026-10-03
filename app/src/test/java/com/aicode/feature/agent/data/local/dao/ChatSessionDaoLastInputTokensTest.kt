package com.aicode.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
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
 * 换绑模型时把「上次请求的输入 token」清零。
 *
 * 判定侧取 `max(lastInputTokens, 本地估算)`（CompactionModule.beforeLlmCall）：换模型后旧值不再代表
 * 新上下文，留着会把下一轮直接顶过硬压缩线（白花一次摘要调用，失败时弹红卡片）。这条 SQL 一旦退回
 * 到「只写 providerId / model」，本用例即红。
 *
 * 用 Robolectric 在 JVM 上跑真实 SQLite（与 [AgentMessageDaoSessionContextStatsTest] 同一套环境约束）：
 * 容器/PRoot 里加载不了 Robolectric 的 native 库，直接跳过；跳过不算失败，CI 上真实执行。
 */
@RunWith(AndroidJUnit4::class)
class ChatSessionDaoLastInputTokensTest {

    private lateinit var db: AgentDatabase
    private lateinit var dao: ChatSessionDao

    companion object {
        private const val SESSION = "session-1"

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
        dao = db.chatSessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateProviderModel_换绑模型时清零上次输入用量() = runTest {
        dao.upsert(
            ChatSessionEntity(
                id = SESSION,
                title = "会话",
                createdAt = 1L,
                updatedAt = 1L,
                providerId = "old",
                model = "old-model",
                lastInputTokens = 120_000
            )
        )

        dao.updateProviderModel(SESSION, "new", "new-model")

        val after = dao.getById(SESSION)!!
        assertEquals("new", after.providerId)
        assertEquals("new-model", after.model)
        assertEquals(0, after.lastInputTokens)
    }
}
