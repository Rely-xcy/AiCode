package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.model.ReasoningWire
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningCatalogTest {

    private fun openAi(useResponses: Boolean): OpenAIAdapter =
        OpenAIAdapter(mockk<OpenAIApi>(relaxed = true)).apply {
            model = "gpt-5"
            useResponseApi = useResponses
        }

    @Test
    fun protocol_followsAdapterClassInsteadOfEndpointSwitch() {
        assertEquals(ReasoningProtocol.OPENAI_CHAT, reasoningProtocolOf(openAi(false)))
        assertEquals(ReasoningProtocol.OPENAI_RESPONSES, reasoningProtocolOf(openAi(true)))

        val anthropic = AnthropicAdapter(mockk<AnthropicApi>(relaxed = true)).apply { model = "claude-opus-4-7" }
        assertEquals(ReasoningProtocol.ANTHROPIC, reasoningProtocolOf(anthropic))

        // Gemini 的 Interactions 端点与 generateContent 共用同一套思考语义：切了端点也不能按 OpenAI 处理。
        val gemini = GeminiAdapter(mockk<GeminiApi>(relaxed = true)).apply {
            model = "gemini-3-flash-preview"
            useResponseApi = true
        }
        assertEquals(ReasoningProtocol.GEMINI, reasoningProtocolOf(gemini))
    }

    @Test
    fun planFor_prefersInjectedCapabilityOverVendorTable() {
        // 元数据声明支持 max，plan 就必须如实给 max；厂商兜底表会把它压成 high。
        val adapter = openAi(false).apply {
            reasoningCapability = ReasoningCapability(
                levels = listOf(
                    ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX
                ),
                canDisable = true,
                disableKind = ReasoningCapability.DisableKind.EFFORT_NONE,
                source = ReasoningCapability.Source.MODELS_DEV
            )
        }

        val plan = reasoningPlanFor(adapter, "max")
        assertEquals(ReasoningWire.Effort("max"), plan.wire)
        assertEquals(ReasoningPlan.Reason.NONE, plan.reason)
    }
}
