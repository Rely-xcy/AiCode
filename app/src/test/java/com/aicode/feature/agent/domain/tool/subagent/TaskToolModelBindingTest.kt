package com.aicode.feature.agent.domain.tool.subagent

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.schedule.WriteLeaseRegistry
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubagentModelTier
import com.aicode.feature.agent.domain.subagent.SubagentTierSelection
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * `task(action="create")` 的模型绑定：同名模型绝不串供应商、用户配置的模型不被静默跳过。
 *
 * 两条现场：
 * - 官方与中转站都有 `gpt-5.6-sol`，用户在中转站上配了它，跑起来却用了官方那份；
 * - 用户在设置里改了子代理用的模型，主代理没传 `tier`，子代理还是继承父会话。
 *
 * `newSubSessionEntity` 被 mock，所以断言点就是 TaskTool 交给它的 providerId/model——
 * 这正是子会话落库后运行时解析供应商的唯一依据。
 */
class TaskToolModelBindingTest {

    private companion object {
        const val PARENT = "parent-session"
        const val ROOT = "~/workspace"
        const val HOST_ROOT = "/host/ws"
        const val PINNED_MODEL = "gpt-5.6-sol"
        const val OFFICIAL = "prov-official"
        const val RELAY = "prov-relay"
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

    /** 最近一次交给 newSubSessionEntity 的绑定，断言点。 */
    private var boundProviderId: String? = null
    private var boundModel: String? = null

    @Before
    fun stubStrings() {
        every { appContext.getString(any()) } answers { labelOf(firstArg()) }
        every { appContext.getString(any(), *anyVararg()) } answers {
            val template = labelOf(firstArg())
            val values = args.drop(1).flatMap { value ->
                if (value is Array<*>) value.toList() else listOf(value)
            }
            String.format(Locale.ROOT, template, *values.toTypedArray())
        }
    }

    // ------------------------------------------------------------ 同名模型

    /**
     * 两个供应商都有同名模型时，档位选的是哪家就用哪家。
     *
     * 这是用户最早报的那条：选中转站，跑出来是官方。旧实现里档位本来就带 providerId，
     * 但只要哪一环退回「按模型名找供应商」，这条就会红。
     */
    @Test
    fun tierSelection_sameModelInTwoProviders_keepsSelectedProvider() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", PINNED_MODEL),
            provider(RELAY, "中转站", PINNED_MODEL)
        )
        stubDefinitions()
        coEvery { tierSettings.snapshot() } returns mapOf(
            SubagentModelTier.STRONG to SubagentTierSelection(RELAY, PINNED_MODEL)
        )
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("改 A 文件"), "description" to str("改 A"), "tier" to str("strong")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertEquals("选定的是中转站，不得换成官方的同名模型", RELAY, boundProviderId)
        assertEquals(PINNED_MODEL, boundModel)
    }

    /** 档位绑定的供应商没填 Key：运行时会被回退到默认供应商，所以按不可用降级并说明。 */
    @Test
    fun tierSelection_providerWithoutKey_isTreatedUnavailable() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", PINNED_MODEL),
            provider(RELAY, "中转站", PINNED_MODEL, apiKey = "")
        )
        stubDefinitions()
        coEvery { tierSettings.snapshot() } returns mapOf(
            SubagentModelTier.STRONG to SubagentTierSelection(RELAY, PINNED_MODEL),
            SubagentModelTier.STANDARD to SubagentTierSelection(RELAY, PINNED_MODEL)
        )
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("改 A 文件"), "description" to str("改 A"), "tier" to str("strong")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertNull("不可用的供应商不得被绑上", boundProviderId)
        assertNull("档位不可用 → 继承父会话", boundModel)
        assertTrue(
            "应说明档位降级：${warningOf(result)}",
            warningOf(result)?.contains("tier-unavailable") == true
        )
    }

    // ------------------------------------------------------------ 定义锁定模型

    /** 定义只写 model、两家都有同名模型：拒绝派发，列出候选供应商，不猜。 */
    @Test
    fun definitionPinsModel_onlyModel_sameNameInTwoProviders_refusesDispatch() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", PINNED_MODEL),
            provider(RELAY, "中转站", PINNED_MODEL)
        )
        stubDefinitions(definition(model = PINNED_MODEL))
        coEvery { tierSettings.snapshot() } returns emptyMap()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("审查代码"), "agent" to str("reviewer")),
            context
        )

        assertTrue("同名模型未指定供应商时必须报错，实际：$result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("SUBAGENT_PROVIDER_AMBIGUOUS", error.code)
        assertTrue("错误里要出现模型名：${error.message}", error.message.contains(PINNED_MODEL))
        assertTrue("错误里要出现供应商名：${error.message}", error.message.contains("官方"))
        assertTrue("错误里要出现供应商名：${error.message}", error.message.contains("中转站"))
        coVerify(exactly = 0) { sessionUseCase.upsertSession(any()) }
        assertTrue("不应有子代理被启动：${bus.activeSubSessionIds.value}", bus.activeSubSessionIds.value.isEmpty())
        assertTrue("预留名额应已归还：${bus.activeCount}", bus.activeCount == 0)
    }

    /** 只有一家声明提供该模型：确定地绑它，并告知主代理解析结果。 */
    @Test
    fun definitionPinsModel_uniqueOwner_isBound() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", "gpt-5.1"),
            provider(RELAY, "中转站", PINNED_MODEL)
        )
        stubDefinitions(definition(model = PINNED_MODEL))
        coEvery { tierSettings.snapshot() } returns emptyMap()
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("审查代码"), "agent" to str("reviewer")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertEquals(RELAY, boundProviderId)
        assertEquals(PINNED_MODEL, boundModel)
        assertTrue(
            "应说明绑定来源：${warningOf(result)}",
            warningOf(result)?.contains("pinned-provider-resolved") == true
        )
        assertTrue(
            "说明里要带供应商名：${warningOf(result)}",
            warningOf(result)?.contains("中转站") == true
        )
    }

    /** 定义里写明了 provider：同名模型也不影响绑定（DEFINITION_PINNED 语义不变）。 */
    @Test
    fun definitionPinsModelAndProvider_honorsProvider() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", PINNED_MODEL),
            provider(RELAY, "中转站", PINNED_MODEL)
        )
        stubDefinitions(definition(providerId = RELAY, model = PINNED_MODEL))
        coEvery { tierSettings.snapshot() } returns emptyMap()
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("审查代码"), "agent" to str("reviewer"), "tier" to str("strong")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertEquals(RELAY, boundProviderId)
        assertEquals(PINNED_MODEL, boundModel)
    }

    /** 没有任何供应商声明提供该模型：沿用父会话供应商（现状），但必须明说，不能沉默。 */
    @Test
    fun definitionPinsModel_noProviderListsIt_warnsInsteadOfSilentlyInheriting() = runBlocking {
        stubParent()
        stubProviders(provider(OFFICIAL, "官方", "gpt-5.1"))
        stubDefinitions(definition(model = PINNED_MODEL))
        coEvery { tierSettings.snapshot() } returns emptyMap()
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("审查代码"), "agent" to str("reviewer")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertNull("无法确定供应商时沿用父会话", boundProviderId)
        assertEquals(PINNED_MODEL, boundModel)
        assertTrue(
            "应说明无法定位供应商：${warningOf(result)}",
            warningOf(result)?.contains("pinned-provider-unlisted") == true
        )
    }

    // ------------------------------------------------------------ 未传档位

    /** 主代理没传 tier：用户配置的「均衡」档必须生效，不能直接继承父会话。 */
    @Test
    fun noTierRequested_appliesConfiguredStandardTier() = runBlocking {
        stubParent()
        stubProviders(
            provider(OFFICIAL, "官方", PINNED_MODEL),
            provider(RELAY, "中转站", PINNED_MODEL)
        )
        stubDefinitions()
        coEvery { tierSettings.snapshot() } returns mapOf(
            SubagentModelTier.STANDARD to SubagentTierSelection(RELAY, PINNED_MODEL)
        )
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("改 A 文件"), "description" to str("改 A")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertEquals(RELAY, boundProviderId)
        assertEquals(PINNED_MODEL, boundModel)
        val tier = (result as ToolResult.Success).data.jsonObject["tier"]?.jsonPrimitive?.contentOrNull
        assertEquals("standard", tier)
        assertTrue(
            "应说明按默认档执行：${warningOf(result)}",
            warningOf(result)?.contains("tier-default-applied") == true
        )
    }

    /** 一档都没配：仍然继承父会话（原有行为，不得因新逻辑改变）。 */
    @Test
    fun noTierRequestedAndNothingConfigured_inheritsParent() = runBlocking {
        stubParent()
        stubProviders(provider(OFFICIAL, "官方", PINNED_MODEL))
        stubDefinitions()
        coEvery { tierSettings.snapshot() } returns emptyMap()
        stubSessionCreation()

        val result = tool.executeWithContext(
            taskArgs("prompt" to str("改 A 文件"), "description" to str("改 A")),
            context
        )

        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        assertNull(boundProviderId)
        assertNull(boundModel)
        assertNull("没有降级/默认档说明才对：${warningOf(result)}", warningOf(result))
    }

    // ------------------------------------------------------------ 辅助

    /** 工具结果里的 warning 字段（只有非空时才写入）。 */
    private fun warningOf(result: ToolResult): String? =
        (result as? ToolResult.Success)
            ?.data?.jsonObject?.get("warning")
            ?.jsonPrimitive?.contentOrNull

    /**
     * 资源 id → 模板：`getString` 桩按它拼出可断言的文本。
     *
     * 断言锚点是模板里的语义前缀（如 `pinned-provider-resolved`）而不是资源 id 数值——
     * 单元测试里 id 的具体数值无关紧要，文案内容才对得上代码路径。
     */
    private fun labelOf(id: Int): String = stringTemplates[id] ?: "res-$id"

    private val stringTemplates = mapOf(
        R.string.settings_subagent_tier_light to "light-tier",
        R.string.settings_subagent_tier_standard to "standard-tier",
        R.string.settings_subagent_tier_strong to "strong-tier",
        R.string.tool_subagent_tier_pinned to "tier-pinned %1\$s %2\$s",
        R.string.tool_subagent_tier_fallback to "tier-fallback %1\$s %2\$s %3\$s",
        R.string.tool_subagent_tier_unavailable to "tier-unavailable %1\$s",
        R.string.tool_subagent_tier_default_applied to "tier-default-applied %1\$s %2\$s",
        R.string.tool_subagent_provider_unresolved to "provider-unresolved %1\$s %2\$s",
        R.string.tool_subagent_provider_ambiguous to "provider-ambiguous %1\$s %2\$s %3\$s",
        R.string.tool_subagent_pinned_provider_resolved to "pinned-provider-resolved %1\$s %2\$s %3\$s",
        R.string.tool_subagent_pinned_provider_unlisted to "pinned-provider-unlisted %1\$s %2\$s",
        R.string.tool_subagent_plan_readonly_warning to "plan-readonly"
    )

    private fun provider(id: String, name: String, model: String, apiKey: String = "sk-test") =
        AIProviderConfig(
            id = id,
            name = name,
            type = ProviderType.OPENAI,
            apiKey = apiKey,
            baseUrl = "https://example.invalid",
            defaultModel = model,
            models = listOf(model),
            isEnabled = true
        )

    private fun definition(providerId: String? = null, model: String? = null) = AgentDefinition(
        name = "reviewer",
        description = "审查代码",
        providerId = providerId,
        model = model,
        prompt = "审查并报告"
    )

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

    private fun stubParent() {
        coEvery { sessionUseCase.getSessionById(PARENT) } returns ChatSessionEntity(
            id = PARENT,
            title = "父会话",
            createdAt = 0L,
            updatedAt = 0L,
            providerId = OFFICIAL,
            model = PINNED_MODEL
        )
    }

    private fun stubProviders(vararg providers: AIProviderConfig) {
        every { aiProviderRepository.getAllProviders() } returns flowOf(providers.toList())
    }

    private fun stubDefinitions(vararg definitions: AgentDefinition) {
        every { agentDefinitionRepository.listEnabled() } returns definitions.map {
            AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL)
        }
    }

    /** 记录 TaskTool 交给会话实体的绑定。 */
    private fun stubSessionCreation() {
        coEvery { sessionUseCase.upsertSession(any()) } just Runs
        every {
            sessionUseCase.newSubSessionEntity(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            boundProviderId = arg(4)
            boundModel = arg(5)
            ChatSessionEntity(
                id = "sub-1",
                title = firstArg(),
                createdAt = 0L,
                updatedAt = 0L,
                parentId = PARENT
            )
        }
    }

    private fun taskArgs(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun str(value: String): JsonElement = JsonPrimitive(value)
}
