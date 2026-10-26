package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位解析：定义锁定 > 请求档位 > 均衡档 > 继承父会话。
 *
 * 背景：中转站常有上百个模型，主代理只判断任务难度并给出档位，具体用哪个模型由用户在设置里
 * 绑定。档位没配或提供商不可用时必须降级**并且讲清原因**，否则主代理会以为换了模型在等效果。
 */
class SubagentModelResolverTest {

    private val configuredLight = SubagentTierSelection("prov-light", "cheap-model")
    private val configuredStandard = SubagentTierSelection("prov-std", "balanced-model")
    private val configuredStrong = SubagentTierSelection("prov-strong", "strong-model")

    private val allConfigured = mapOf(
        SubagentModelTier.LIGHT to configuredLight,
        SubagentModelTier.STANDARD to configuredStandard,
        SubagentModelTier.STRONG to configuredStrong
    )

    private fun resolve(
        requestedTier: SubagentModelTier?,
        definitionModel: String? = null,
        definitionProviderId: String? = null,
        selections: Map<SubagentModelTier, SubagentTierSelection> = allConfigured,
        unavailableProviders: Set<String> = emptySet(),
        unlistedModels: Set<Pair<String, String>> = emptySet()
    ) = SubagentModelResolver.resolve(
        requestedTier = requestedTier,
        definitionModel = definitionModel,
        definitionProviderId = definitionProviderId,
        selections = selections,
        isProviderAvailable = { it !in unavailableProviders },
        isModelListed = { providerId, model -> (providerId to model) !in unlistedModels }
    )

    /** 一档都没配时仍然继承父会话（不得因新逻辑把未配置的档位用起来）。 */
    @Test
    fun noTierRequestedAndNoTierConfigured_inheritsParent() {
        val resolved = resolve(requestedTier = null, selections = emptyMap())

        assertTrue(resolved.inheritsParent)
        assertNull(resolved.tier)
        assertNull(resolved.fallback)
    }

    /**
     * 主代理没传 tier（最常见的情况）时不能直接继承父会话：
     * 用户在设置里给子代理配的「均衡」档必须生效，否则「改了模型还是继承会话」。
     */
    @Test
    fun noTierRequested_usesConfiguredStandardTier() {
        val resolved = resolve(requestedTier = null)

        assertEquals("prov-std", resolved.providerId)
        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelFallback.DEFAULT_TIER_APPLIED, resolved.fallback)
    }

    /** 「均衡」档没配时才回落到继承：主代理不传档位不等于用户可以接受沉默退化。 */
    @Test
    fun noTierRequestedWithoutStandardTier_inheritsParent() {
        val resolved = resolve(
            requestedTier = null,
            selections = mapOf(
                SubagentModelTier.LIGHT to configuredLight,
                SubagentModelTier.STRONG to configuredStrong
            )
        )

        assertTrue(resolved.inheritsParent)
        assertNull(resolved.tier)
    }

    @Test
    fun requestedTierConfigured_usesItsProviderAndModel() {
        val resolved = resolve(SubagentModelTier.STRONG)

        assertEquals("prov-strong", resolved.providerId)
        assertEquals("strong-model", resolved.model)
        assertEquals(SubagentModelTier.STRONG, resolved.tier)
        assertNull(resolved.fallback)
    }

    /** 定义里写死 model 视为用户锁定：请求的档位被忽略，但要告诉主代理原因。 */
    @Test
    fun definitionModelWins_andReportsPinned() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.LIGHT,
            definitionModel = "pinned-model",
            definitionProviderId = "prov-pinned"
        )

        assertEquals("pinned-model", resolved.model)
        assertEquals("prov-pinned", resolved.providerId)
        assertNull(resolved.tier)
        assertEquals(SubagentModelFallback.DEFINITION_PINNED, resolved.fallback)
    }

    /** 定义锁定模型、主代理没请求档位时不算降级，不该多一条说明。 */
    @Test
    fun definitionModelWithoutTierRequest_hasNoFallbackNotice() {
        val resolved = resolve(requestedTier = null, definitionModel = "pinned-model")

        assertEquals("pinned-model", resolved.model)
        assertNull(resolved.fallback)
    }

    /** 请求的档位没配 → 退到「均衡」档，并标记降级。 */
    @Test
    fun unconfiguredTier_fallsBackToStandard() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            selections = mapOf(
                SubagentModelTier.LIGHT to SubagentTierSelection(),
                SubagentModelTier.STANDARD to configuredStandard,
                SubagentModelTier.STRONG to SubagentTierSelection()
            )
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelTier.STRONG, resolved.requestedTier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    /** 请求的就是「均衡」档且没配 → 没有下一档可退，直接继承父会话。 */
    @Test
    fun unconfiguredStandardTier_inheritsParent() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STANDARD,
            selections = mapOf(
                SubagentModelTier.LIGHT to configuredLight,
                SubagentModelTier.STANDARD to SubagentTierSelection(),
                SubagentModelTier.STRONG to configuredStrong
            )
        )

        assertTrue(resolved.inheritsParent)
        assertNull(resolved.tier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
        assertEquals(SubagentModelTier.STANDARD, resolved.requestedTier)
    }

    /** 档位绑定的提供商被删除/停用 → 等同于未配置，不得拿一个不可用的 provider 去起会话。 */
    @Test
    fun providerUnavailable_fallsBackLikeUnconfigured() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            unavailableProviders = setOf("prov-strong")
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    /** 只配了 half（有 provider 没 model）也按未配置处理，避免发出空模型名的请求。 */
    @Test
    fun partialSelection_isTreatedAsUnconfigured() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.LIGHT,
            selections = mapOf(
                SubagentModelTier.LIGHT to SubagentTierSelection("prov-light", ""),
                SubagentModelTier.STANDARD to configuredStandard,
                SubagentModelTier.STRONG to SubagentTierSelection()
            )
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    @Test
    fun tierTokenParsing_isLenient() {
        assertEquals(SubagentModelTier.LIGHT, SubagentModelTier.fromToken(" Light "))
        assertEquals(SubagentModelTier.STRONG, SubagentModelTier.fromToken("STRONG"))
        assertNull(SubagentModelTier.fromToken("turbo"))
        assertNull(SubagentModelTier.fromToken(""))
        assertNull(SubagentModelTier.fromToken(null))
    }

    // ------------------------------------------------- 同名模型：档位不能串供应商

    /**
     * 两个供应商有同名模型时，档位选的是哪家就必须用哪家。
     *
     * 这里不能用「模型名去重」的写法偷懒：同名模型是两个不同的可计费端点，
     * 用错供应商就是拿别家的额度和计费跑任务。
     */
    @Test
    fun sameModelNameInTwoProviders_keepsTheSelectedProvider() {
        val relay = SubagentTierSelection("prov-relay", "gpt-5.6-sol")
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            selections = mapOf(
                SubagentModelTier.STRONG to relay,
                SubagentModelTier.STANDARD to SubagentTierSelection("prov-official", "gpt-5.6-sol")
            )
        )

        assertEquals("prov-relay", resolved.providerId)
        assertEquals("gpt-5.6-sol", resolved.model)
        assertNull(resolved.fallback)
    }

    /** 档位绑定的模型不在该供应商的模型列表里：运行时仍会回退到默认供应商，所以按不可用降级。 */
    @Test
    fun tierModelMissingFromProviderList_fallsBackExplicitly() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            unlistedModels = setOf("prov-strong" to "strong-model")
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    // ------------------------------------------------- 定义锁定模型的供应商归属

    @Test
    fun pinnedModelProvider_uniqueOwnerIsResolved() {
        val resolution = SubagentPinnedModelProvider.resolve(
            model = "gpt-5.6-sol",
            providerModels = linkedMapOf(
                "prov-official" to listOf("gpt-5.6-sol"),
                "prov-relay" to listOf("other-model")
            )
        )

        assertEquals(PinnedModelProvider.Unique("prov-official"), resolution)
    }

    /** 同名模型同时存在于两家：不得挑第一个，必须让用户明确指定。 */
    @Test
    fun pinnedModelProvider_sameNameInTwoProvidersIsAmbiguous() {
        val resolution = SubagentPinnedModelProvider.resolve(
            model = "gpt-5.6-sol",
            providerModels = linkedMapOf(
                "prov-official" to listOf("gpt-5.6-sol"),
                "prov-relay" to listOf("gpt-5.6-sol")
            )
        )

        assertEquals(PinnedModelProvider.Ambiguous(listOf("prov-official", "prov-relay")), resolution)
    }

    @Test
    fun pinnedModelProvider_unknownModelIsUnlisted() {
        val resolution = SubagentPinnedModelProvider.resolve(
            model = "gpt-5.6-sol",
            providerModels = linkedMapOf("prov-official" to listOf("gpt-5.1"))
        )

        assertEquals(PinnedModelProvider.Unlisted, resolution)
    }

    /** 只做精确等值：运行时也是精确比对模型列表，忽略大小写绑上去照样会被判失效。 */
    @Test
    fun pinnedModelProvider_matchIsExact() {
        val resolution = SubagentPinnedModelProvider.resolve(
            model = "gpt-5.6-sol",
            providerModels = linkedMapOf("prov-relay" to listOf("GPT-5.6-SOL"))
        )

        assertEquals(PinnedModelProvider.Unlisted, resolution)
    }
}
