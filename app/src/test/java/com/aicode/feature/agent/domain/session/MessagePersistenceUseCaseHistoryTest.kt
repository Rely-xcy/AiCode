package com.aicode.feature.agent.domain.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.presentation.MessageRole
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    private val json = Json

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
            // 读侧顺序来自上游 orderHistoryEntities（marker/summary 提到最前）；写侧会把
            // marker 之前的所有行标成 isCompacted，回放时滤掉，故正常路径下与时间序等价。
            listOf(CONTEXT_COMPACTION_MARKER, "新摘要", "中间的话", "最新的话"),
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

    @Test
    fun contextStatsMatchWhatReplayActuallySends() = runTest {
        val dao = db.agentMessageDao()
        dao.insertAll(
            listOf(
                user("u1", timestamp = 100, content = "第一句"),
                // 迁移 58 给升级前 /usage 统计行标的形状：isContextExcluded=1、isCompacted=0（从不进对话）
                usageRow("usage", timestamp = 150),
                user("u2", timestamp = 200, content = "第二句"),
                // 被折叠的历史：留在库里（聊天页看得见）但不参与回放
                user("folded", timestamp = 50, content = "被折叠的历史").copy(isCompacted = true)
            )
        )

        // 记忆页「短期上下文」的条数取自 AgentMessageDao.sessionContextStats，必须等于回放真的
        // 带上模型的消息数：两边判据一旦分叉（统计只看 isCompacted、回放还看 isContextExcluded），
        // 这条断言即红。
        val stats = dao.sessionContextStats(SESSION)
        val history = useCase.buildHistory(SESSION, NO_PENDING_MARKER)

        assertEquals(2, history.size)
        assertEquals(history.size, stats.retainedMessages)
    }

    /**
     * 软精简投影落库后跨轮回放：带出的是投影版（modelResult / modelArguments），
     * 而原文（result / arguments）一字未动——“模型可见那份 / 落库那份”分离在回放侧仍成立。
     *
     * 同时钉住“投影前沿跨轮次保留”的前提：回放拿到的必须是投影版，否则下一轮会把已落库的
     * 投影当新内容重算。
     */
    @Test
    fun softTrimProjectionPersistsAndSurvivesReplay() = runTest {
        val dao = db.agentMessageDao()
        val callId = "call-1"
        val bigText = "a".repeat(5_000)
        dao.insertAll(
            listOf(
                AgentMessageEntity(
                    id = "assistant-1",
                    sessionId = SESSION,
                    role = MessageRole.ASSISTANT.name,
                    content = "",
                    timestamp = 100,
                    toolCallsJson = json.encodeToString(
                        listOf(
                            ToolCall(
                                id = callId,
                                name = "writeFile",
                                arguments = mapOf(
                                    "path" to JsonPrimitive("a.txt"),
                                    "content" to JsonPrimitive(bigText)
                                )
                            )
                        )
                    )
                ),
                AgentMessageEntity(
                    id = "tool_$callId",
                    sessionId = SESSION,
                    role = MessageRole.TOOL.name,
                    content = bigText,
                    timestamp = 101,
                    toolCallId = callId,
                    toolName = "writeFile"
                ),
                // 三条用户消息把上面两条推到最近三轮保护线之外，软精简才会动它们。
                user("u1", timestamp = 200, content = "一"),
                user("u2", timestamp = 300, content = "二"),
                user("u3", timestamp = 400, content = "三")
            )
        )

        val history = useCase.buildHistory(SESSION, NO_PENDING_MARKER)
        val compactor = ContextCompactor(
            agentMessageDao = dao,
            systemPromptProvider = mockk(relaxed = true),
            llmCallRecordDao = mockk(relaxed = true),
            compactedHistoryArchive = mockk(relaxed = true)
        )
        // targetTokens=1 逼出软精简：保护线之前的两条（工具参数与工具结果）都会被削并写回库。
        // 落库走的是非挂起 DAO（生产路径在 Dispatchers.Default 上），测试要切到 IO 才不撞
        // Room 的主线程检查。
        val trimmed = withContext(Dispatchers.IO) { compactor.softTrim(history, targetTokens = 1) }
        assertTrue(trimmed !== history)

        // 换一个实例绕过 buildHistory 的内存缓存，读回库里的最新形态。
        val replayed = MessagePersistenceUseCase(dao, db).buildHistory(SESSION, NO_PENDING_MARKER)

        val tool = replayed.filterIsInstance<AgentMessage.ToolResultMessage>().single()
        assertNotNull("落库后回放应带出 modelResult 投影", tool.modelResult)
        assertEquals(bigText, tool.result)

        val call = replayed.filterIsInstance<AgentMessage.AssistantMessage>().single().toolCalls.single()
        assertNotNull("落库后回放应带出 modelArguments 投影", call.modelArguments)
        assertEquals(bigText, (call.arguments["content"] as JsonPrimitive).content)
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

    /** 迁移 58 给历史 /usage 统计行标的形状：排除出上下文（isContextExcluded=1）且未压缩。 */
    private fun usageRow(id: String, timestamp: Long) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = MessageRole.ASSISTANT.name,
        content = "| 项目 | 今日 | 累计 |",
        timestamp = timestamp,
        isContextExcluded = true
    )
}
