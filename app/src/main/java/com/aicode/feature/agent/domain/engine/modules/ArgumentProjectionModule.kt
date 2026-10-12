package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.engine.LlmCall
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.workflow.BULK_ARG_KEYS
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import com.aicode.feature.agent.domain.workflow.bulkArgumentExcerptOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 参数指针化模块：请求组装处对 [BULK_ARG_KEYS] 大字段做纯函数摘录（上下文压缩重设计 §9 阶段 0）。
 *
 * write/edit 把整个文件正文塞进 arguments，每一轮请求都原样再发一遍，是历史增长的另一大头。
 * 本模块在每次发请求前把它换成摘录（头尾逐字 + 省略说明 + readFile 回读指针），规则与软精简
 * 共用 [bulkArgumentExcerptOf]。
 *
 * 与软精简的关键差异——这里是**纯函数投影，不落库**：
 * - 每次组装请求时从原文（`ToolCall.arguments`）确定性重算，
 *   同一输入永远得到同一输出，逐字节稳定，不破坏前缀缓存；
 * - 不写 `modelArguments`（软精简的持久载体），`arguments` 原文一字不动——执行、UI、落库、
 *   TokenEstimator 的原文基线全部不受影响；
 * - 因此**没有「投影前沿」问题**：不落库意味着没有跨轮持久改动，每轮从原文重算，改哪儿都不会
 *   让历史前缀的字节变掉（历史里的原文本来就没变过）。
 *
 * 与旧软精简相反的取舍——摘录形态 + 执行边界守卫已拦住「摘录回写」。
 *
 * 挂在 CompactionModule（order 10）之后：先让压缩模块把历史折叠/精简定型，再对本轮
 * 最终要发送的消息做投影，避免折叠结果被再次加工或互相覆盖。
 */
@Singleton
class ArgumentProjectionModule @Inject constructor() : EngineModule {

    override val id = MODULE_ID

    // 压缩之后、其余模块之前：投影的是「最终要发送的消息」。
    override val order = ORDER

    override suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? {
        var changed = false
        var savedTokens = 0
        val projected = call.messages.map { message ->
            if (message !is AgentMessage.AssistantMessage || message.toolCalls.isEmpty()) return@map message
            val rebuiltCalls = message.toolCalls.map { tc ->
                val projectedArgs = tc.arguments.mapValues { (key, value) ->
                    val text = (value as? JsonPrimitive)?.contentOrNull
                        ?: return@mapValues value
                    if (key.lowercase() !in BULK_ARG_KEYS) return@mapValues value
                    if (text.length <= BULK_ARG_THRESHOLD_CHARS) return@mapValues value
                    val path = tc.arguments.entries
                        .firstOrNull { it.key.lowercase() in PATH_ARG_KEYS }
                        ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
                        ?.takeIf { it.isNotBlank() }
                    val excerpt = bulkArgumentExcerptOf(text, path, BULK_ARG_THRESHOLD_CHARS)
                        ?: return@mapValues value
                    changed = true
                    JsonPrimitive(excerpt)
                }
                if (projectedArgs == tc.arguments) tc else tc.copy(arguments = projectedArgs)
            }
            if (rebuiltCalls == message.toolCalls) message
            else {
                val projectedMessage = message.copy(toolCalls = rebuiltCalls)
                savedTokens += TokenEstimator.estimateMessage(message) -
                    TokenEstimator.estimateMessage(projectedMessage)
                projectedMessage
            }
        }
        if (!changed) return null
        if (savedTokens > 0) {
            FileLogger.i(
                TAG,
                "参数指针化 会话=${ctx.sessionId ?: "-"} " +
                    "大字段摘录节省约 $savedTokens tokens（纯函数投影，未落库、未写 modelArguments）"
            )
        }
        return call.copy(messages = projected)
    }

    private companion object {
        const val MODULE_ID = "argument-projection"
        const val TAG = "ArgumentProjection"

        /**
         * 排在 CompactionModule（order 10）之后：投影「最终要发送的消息」。
         * 取 20 而不是 11：给后续可能插在两者之间的模块留位。
         */
        const val ORDER = 20

        /**
         * 参数投影阈值（设计 §8：~8K 字符）。与工具结果指针化的 MAX_INLINE_CHARS（8K）同量级：
         * 超过这个量级的正文，模型当下用得上的就是头尾，中段需要时 readFile 回读。
         */
        const val BULK_ARG_THRESHOLD_CHARS = 8_000

        /** 参数里的目标文件路径字段，与 ContextCompactor 的 PATH_ARG_KEYS 同一套别名（避免两处漂移才重复列出）。 */
        val PATH_ARG_KEYS = setOf("path", "file_path", "filepath", "file")
    }
}
