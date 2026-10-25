package com.aicode.feature.agent.domain.tool.subagent

import android.content.Context
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.schedule.WriteLeaseRegistry
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `task(action="create")` 的写范围接线：参数解析、启动前 claim、冲突整批回滚。
 *
 * 这里守的是「冲突必须在子代理启动前暴露」：claim 一旦落到 upsertSession 或 emit(SPAWNED) 之后，
 * 子代理已经开始跑，再返回 Error 只是收拾残局。所以冲突用例同时断言三件事——
 * 没落库、没 emit（活跃集合为空）、本批已登记的租约已释放。
 */
class TaskToolWriteScopeTest {

    private companion object {
        const val PARENT = "parent-session"
        const val ROOT = "~/workspace"
        const val HOST_ROOT = "/host/ws"
    }

    private val sessionUseCase = mockk<SessionUseCase>()
    private val chatSessionDao = mockk<ChatSessionDao>()
    private val agentMessageDao = mockk<AgentMessageDao>()
    private val bus = SubAgentEventBus()
    private val agentDefinitionRepository = mockk<AgentDefinitionRepository>()
    private val aiProviderRepository = mockk<AIProviderRepository>()
    private val tierSettings = mockk<SubagentTierSettingsRepository>()
    private val appContext = mockk<Context>()
    private val registry = WriteLeaseRegistry(fakeMapper())
    private val tool = TaskTool(
        sessionUseCase,
        chatSessionDao,
        agentMessageDao,
        bus,
        agentDefinitionRepository,
        aiProviderRepository,
        tierSettings,
        registry,
        appContext
    )

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = ROOT,
        language = null,
        sessionId = PARENT
    )

    // ------------------------------------------------------------ schema

    @Test
    fun parametersExposeWriteScopeAsStringArray() {
        val param = tool.parameters["writeScope"]
        assertNotNull("task 的 schema 必须声明 writeScope，否则模型传了也会被静默丢掉", param)
        assertEquals(ParameterType.ARRAY, param!!.type)
        assertEquals(mapOf("type" to "string"), param.itemsSchema)
    }

    // ------------------------------------------------------------ 参数解析

    @Test
    fun create_singleTaskReadsWriteScopeFromArgs() = runBlocking {
        stubHappyDeps(listOf("sub-1"))

        val result = tool.executeWithContext(
            args(
                "prompt" to str("改 A 文件"),
                "description" to str("改 A"),
                "writeScope" to JsonArray(listOf(str("app/src/main/java/A.kt")))
            ),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        // 租约的 pattern 就是入参原文，能证明 writeScope 解析后落到了 spec 上
        assertEquals(
            listOf("app/src/main/java/A.kt"),
            registry.leasesOf("sub-1").map { it.pattern }
        )
        coVerify(exactly = 1) { sessionUseCase.upsertSession(match { it.id == "sub-1" }) }
    }

    @Test
    fun create_batchTasksReadWriteScopePerElement() = runBlocking {
        stubHappyDeps(listOf("sub-1", "sub-2"))

        val tasks = JsonArray(
            listOf(
                buildJsonObject {
                    put("prompt", "改 A")
                    put("description", "A")
                    put("writeScope", buildJsonArray { add("app/src/a") })
                },
                buildJsonObject {
                    put("prompt", "改 B")
                    put("description", "B")
                    put("writeScope", buildJsonArray { add("app/src/b") })
                }
            )
        )

        val result = tool.executeWithContext(args("tasks" to tasks), context)

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(2, data["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("app/src/a"), registry.leasesOf("sub-1").map { it.pattern })
        assertEquals(listOf("app/src/b"), registry.leasesOf("sub-2").map { it.pattern })
    }

    // ------------------------------------------------------------ 冲突

    @Test
    fun create_conflictReturnsErrorWithoutEmittingOrPersisting() = runBlocking {
        stubHappyDeps(listOf("sub-1"))
        assertTrue(
            "预置租约本身不应冲突",
            registry.claim("other", "另一个子代理", listOf("app/src"), ROOT).isEmpty()
        )

        val result = tool.executeWithContext(
            args(
                "prompt" to str("改 A 文件"),
                "description" to str("改 A"),
                "writeScope" to JsonArray(listOf(str("app/src/main/A.kt")))
            ),
            context
        )

        assertTrue("期望写范围冲突，实际：$result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("WRITE_SCOPE_CONFLICT", error.code)
        assertTrue("冲突提示应点名占用者：${error.message}", error.message.contains("另一个子代理"))
        assertTrue("冲突提示应带上被拒的范围：${error.message}", error.message.contains("app/src/main/A.kt"))
        // emit(SPAWNED) 会把子会话加进活跃集合，集合为空即证明没有启动任何子代理
        assertTrue("不应有子代理被启动：${bus.activeSubSessionIds.value}", bus.activeSubSessionIds.value.isEmpty())
        assertTrue("预留名额应已归还", bus.activeCount == 0)
        coVerify(exactly = 0) { sessionUseCase.upsertSession(any()) }
        assertTrue("既有租约不应被清掉", registry.leasesOf("other").isNotEmpty())
    }

    @Test
    fun create_batchConflictRollsBackEarlierClaims() = runBlocking {
        stubHappyDeps(listOf("sub-1", "sub-2"))
        assertTrue(
            "预置租约本身不应冲突",
            registry.claim("other", "另一个子代理", listOf("app/src/b"), ROOT).isEmpty()
        )

        val tasks = JsonArray(
            listOf(
                buildJsonObject {
                    put("prompt", "改 A")
                    put("description", "A")
                    put("writeScope", buildJsonArray { add("app/src/a") })
                },
                buildJsonObject {
                    put("prompt", "改 B")
                    put("description", "B")
                    put("writeScope", buildJsonArray { add("app/src/b/main.kt") })
                }
            )
        )

        val result = tool.executeWithContext(args("tasks" to tasks), context)

        assertTrue("期望写范围冲突，实际：$result", result is ToolResult.Error)
        assertEquals("WRITE_SCOPE_CONFLICT", (result as ToolResult.Error).code)
        // 不做部分成功：先 claim 成功的 sub-1 也要整批释放
        assertTrue("本批已登记的租约应被回滚：${registry.leasesOf("sub-1")}", registry.leasesOf("sub-1").isEmpty())
        assertTrue(registry.leasesOf("sub-2").isEmpty())
        assertTrue("别人的租约不受影响", registry.leasesOf("other").isNotEmpty())
        coVerify(exactly = 0) { sessionUseCase.upsertSession(any()) }
        assertTrue("不应有子代理被启动：${bus.activeSubSessionIds.value}", bus.activeSubSessionIds.value.isEmpty())
        assertTrue("预留名额应已归还", bus.activeCount == 0)
    }

    // ------------------------------------------------------------ 转交

    @Test
    fun create_handsOverOverlappingParentScopeToChild() = runBlocking {
        stubHappyDeps(listOf("sub-1"))
        assertTrue(
            "父会话预置租约不应冲突",
            registry.claim(PARENT, "主会话", listOf("app/src"), ROOT).isEmpty()
        )

        val result = tool.executeWithContext(
            args(
                "prompt" to str("改 A 文件"),
                "description" to str("改 A"),
                "writeScope" to JsonArray(listOf(str("app/src/main/A.kt")))
            ),
            context
        )

        assertTrue("父会话自己的范围应放行子代理，实际：$result", result is ToolResult.Success)
        assertTrue("重叠部分应从父会话转交给子代理", registry.leasesOf(PARENT).isEmpty())
        assertEquals(listOf("app/src/main/A.kt"), registry.leasesOf("sub-1").map { it.pattern })
    }

    // ------------------------------------------------------------ 辅助

    /** 复刻 WorkspacePathMapper 的关键语义：`~/workspace/...`、相对路径、绝对路径归一到同一份宿主路径。 */
    private fun fakeMapper(): WorkspacePathMapper = mockk<WorkspacePathMapper>().apply {
        every { toHostFile(any()) } answers {
            val p = firstArg<String>().trim().replace('\\', '/')
            when {
                p.startsWith("/host/") || p == "/host" -> File(p)
                p.startsWith("~/workspace") -> File(HOST_ROOT, p.removePrefix("~/workspace").trimStart('/'))
                p.startsWith("/") -> File(HOST_ROOT, p.trimStart('/'))
                else -> File(HOST_ROOT, p)
            }
        }
    }

    /** 只保留与写范围无关的依赖桩：模型解析走「未指定档位 → 继承」。 */
    private fun stubHappyDeps(subIds: List<String>) {
        coEvery { sessionUseCase.getSessionById(PARENT) } returns ChatSessionEntity(
            id = PARENT,
            title = "父会话",
            createdAt = 0L,
            updatedAt = 0L
        )
        every { agentDefinitionRepository.listEnabled() } returns emptyList()
        every { aiProviderRepository.getAllProviders() } returns emptyFlow()
        coEvery { tierSettings.snapshot() } returns emptyMap()
        coEvery { sessionUseCase.upsertSession(any()) } just Runs
        var next = 0
        every {
            sessionUseCase.newSubSessionEntity(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val id = subIds.getOrElse(next) { "unexpected-$next" }
            next++
            ChatSessionEntity(
                id = id,
                title = firstArg<String>(),
                createdAt = 0L,
                updatedAt = 0L,
                parentId = PARENT
            )
        }
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun str(value: String): JsonElement = JsonPrimitive(value)
}
