package com.aicode.feature.agent.domain.subagent

/**
 * 子代理模型档位：主代理派发时按任务难度选档，档位到「哪个提供商的哪个模型」的映射由用户在
 * 设置里配置。
 *
 * 为什么用档位而不是裸 model id：自建中转站常有上百个模型，让模型自己去猜 id 既容易拼错，
 * 也看不出哪个便宜。档位把「谁强谁弱」这个判断留在用户手里，AI 只判断任务难度。
 */
enum class SubagentModelTier(val apiValue: String) {
    /** 只读探索、批量定位、机械改名这类活。 */
    LIGHT("light"),

    /** 单文件修改、常规修复。 */
    STANDARD("standard"),

    /** 跨模块重构、疑难排查、需要慎重判断的方案设计。 */
    STRONG("strong");

    companion object {
        /** 宽松解析：忽略大小写与空白；无法识别时返回 null（当作没传）。 */
        fun fromToken(raw: String?): SubagentModelTier? {
            val token = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.apiValue == token }
        }
    }
}

/** 某档位绑定的模型；两个字段都非空才算「已配置」。 */
data class SubagentTierSelection(
    val providerId: String = "",
    val model: String = ""
) {
    val isConfigured: Boolean get() = providerId.isNotBlank() && model.isNotBlank()
}

/**
 * 子代理定义只写了 [AgentDefinition.model]、没写 provider 时的供应商归属判定。
 *
 * 同名模型在多个供应商下并存是常态（官方与中转站常有同一批模型 id）。只按模型名取第一个，
 * 就是把用户在别家选定的那份换成名字相同、实际不同的另一份；所以只有唯一命中才允许自动绑定。
 */
sealed interface PinnedModelProvider {
    /** 恰好一个已启用供应商声明提供该模型：可以确定地绑定它。 */
    data class Unique(val providerId: String) : PinnedModelProvider

    /** 多个已启用供应商都声明提供该模型：无法判断用户要哪一份，必须让用户明确指定。 */
    data class Ambiguous(val providerIds: List<String>) : PinnedModelProvider

    /** 没有任何已启用供应商声明提供该模型（模型列表未刷新时也常见），只能沿用父会话供应商。 */
    data object Unlisted : PinnedModelProvider
}

/**
 * 按「哪个供应商声明提供这个模型」定位定义锁定的模型该挂在谁名下。
 *
 * 只做精确等值比较：运行时（`resolveProviderConfig`）也是精确比对供应商的模型列表，
 * 这里放宽到忽略大小写的话，绑上的供应商在运行时仍会判定绑定失效并回退到全局默认供应商。
 */
object SubagentPinnedModelProvider {

    /**
     * @param providerModels provider id → 该供应商已添加的模型列表；调用方只传可用的供应商
     *   （已启用且已填 Key），不可用的绑上去也跑不了请求。
     */
    fun resolve(model: String, providerModels: Map<String, List<String>>): PinnedModelProvider {
        val owners = providerModels.filterValues { models -> models.any { it == model } }.keys.toList()
        return when (owners.size) {
            0 -> PinnedModelProvider.Unlisted
            1 -> PinnedModelProvider.Unique(owners.first())
            else -> PinnedModelProvider.Ambiguous(owners)
        }
    }
}

/** 派发时实际采用的模型绑定，以及是否需要向主代理解释「为什么跟你请求的不一样」。 */
data class ResolvedSubagentModel(
    /** 生效的 provider id；null 表示继承父会话。 */
    val providerId: String? = null,
    /** 生效的模型名；null 表示继承父会话。 */
    val model: String? = null,
    /** 实际生效的档位；定义锁定模型或最终继承父会话时为 null。 */
    val tier: SubagentModelTier? = null,
    /** 主代理请求的档位（原样回显，便于工具结果说明差异）。 */
    val requestedTier: SubagentModelTier? = null,
    val fallback: SubagentModelFallback? = null
) {
    /** 是否最终继承父会话模型。 */
    val inheritsParent: Boolean get() = model.isNullOrBlank()
}

/** 档位没按请求生效的原因，用于生成给主代理的说明文案。 */
enum class SubagentModelFallback {
    /** 请求的档位没配置，或它绑定的提供商已删除/停用；已退到「均衡」档或继承父会话。 */
    TIER_UNAVAILABLE,

    /** 子代理定义里写死了 model，请求的档位被忽略。 */
    DEFINITION_PINNED,

    /**
     * 主代理没指定档位（主代理常常不传），但用户在设置里配了「均衡」档：
     * 按该档执行并明说。否则用户为子代理配的模型永远不生效，主代理也会以为还在用父会话模型。
     */
    DEFAULT_TIER_APPLIED
}

/**
 * 把「请求的档位 + 子代理定义」解析成实际要用的 provider/model。
 *
 * 优先级（与用户可见文档一致）：
 * 1. 定义里写死了 `model` → 用定义（视为用户锁定该子代理的模型，忽略档位）；
 * 2. 请求的档位已配置且提供商可用 → 用该档位；
 * 3. 没请求档位（主代理没传）→ 用「均衡」档（若已配置且可用）；
 * 4. 退到「均衡」档（请求的档位不可用时）；
 * 5. 都不可用 → 继承父会话。
 *
 * 纯函数：提供商可用性由调用方以谓词传入，便于单测覆盖各种降级组合。
 */
object SubagentModelResolver {

    fun resolve(
        requestedTier: SubagentModelTier?,
        definitionModel: String?,
        definitionProviderId: String?,
        selections: Map<SubagentModelTier, SubagentTierSelection>,
        isProviderAvailable: (String) -> Boolean,
        /**
         * 该供应商的模型列表里是否确有此模型。绑定一个列表里没有的模型时，运行时会判定绑定失效并
         * 回退到全局默认供应商——同名模型于是被悄悄换成别家那份。默认恒真，只关心供应商可用性的
         * 调用方（单测）不必重写。
         */
        isModelListed: (providerId: String, model: String) -> Boolean = { _, _ -> true }
    ): ResolvedSubagentModel {
        fun usable(tier: SubagentModelTier): SubagentTierSelection? =
            selections[tier]?.takeIf {
                it.isConfigured &&
                    isProviderAvailable(it.providerId) &&
                    isModelListed(it.providerId, it.model)
            }

        // 1) 定义锁定模型：尊重用户对该子代理的显式配置，但要让主代理知道它的请求被忽略了。
        if (!definitionModel.isNullOrBlank()) {
            return ResolvedSubagentModel(
                providerId = definitionProviderId,
                model = definitionModel,
                requestedTier = requestedTier,
                fallback = if (requestedTier != null) SubagentModelFallback.DEFINITION_PINNED else null
            )
        }

        // 没请求档位：「均衡」档是档位体系的基础档（请求档不可用时也是退到它），用户配了就用它。
        // 主代理经常不传 tier，直接继承父会话会让用户为子代理配的模型永远不生效。
        // 一档都没配时仍然继承父会话，保持原有行为。
        if (requestedTier == null) {
            usable(SubagentModelTier.STANDARD)?.let { selection ->
                return ResolvedSubagentModel(
                    providerId = selection.providerId,
                    model = selection.model,
                    tier = SubagentModelTier.STANDARD,
                    fallback = SubagentModelFallback.DEFAULT_TIER_APPLIED
                )
            }
            return ResolvedSubagentModel()
        }

        usable(requestedTier)?.let { selection ->
            return ResolvedSubagentModel(
                providerId = selection.providerId,
                model = selection.model,
                tier = requestedTier,
                requestedTier = requestedTier
            )
        }

        // 2) 降级：请求档位不可用时退到「均衡」。
        if (requestedTier != SubagentModelTier.STANDARD) {
            usable(SubagentModelTier.STANDARD)?.let { selection ->
                return ResolvedSubagentModel(
                    providerId = selection.providerId,
                    model = selection.model,
                    tier = SubagentModelTier.STANDARD,
                    requestedTier = requestedTier,
                    fallback = SubagentModelFallback.TIER_UNAVAILABLE
                )
            }
        }

        // 3) 全都没配 → 继承父会话，并说明原因。
        return ResolvedSubagentModel(
            requestedTier = requestedTier,
            fallback = SubagentModelFallback.TIER_UNAVAILABLE
        )
    }
}
