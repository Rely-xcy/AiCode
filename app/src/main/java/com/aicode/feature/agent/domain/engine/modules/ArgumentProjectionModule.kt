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
 * 参数指针化模块：请求组装处的历史改写入口，现已**关闭**（[RUNTIME_PROJECTION_ENABLED] = false）。
 *
 * 保留原因：模块仍注册在引擎里，关掉只能让它返回「不变更」，不能把它从顺序中摘掉，
 * 否则引擎模块顺序变化会影响其它模块的观测结果。摘录逻辑也留在这里，便于出问题时快速对照。
 *
 * 关闭原因：请求时按当前上下文重算摘录，会让同一条历史消息在不同轮次得到不同形态
 * （例如同一字段这轮超阈值、下轮因为上下文变化不再超），推出去的历史前缀字节随之变化，
 * 服务商的前缀缓存从第一个变字的位置起全部失效。现在改为**写入时一次写定**：
 * [com.aicode.feature.agent.domain.model.excerptToolCallsForHistory] 在消息进入内存历史与落库时执行，
 * 写进去的形态就是以后每轮发送的形态。
 *
 * 因此本模块的 [beforeLlmCall] 直接返回 null，历史一字不改。
 */
@Singleton
class ArgumentProjectionModule @Inject constructor() : EngineModule {

    override val id = MODULE_ID

    // 压缩之后、其余模块之前：保留原顺序位，模块本身不再改动消息。
    override val order = ORDER

    override suspend fun beforeLlmCall(ctx: EngineContext, call: LlmCall): LlmCall? {
        if (!RUNTIME_PROJECTION_ENABLED) return null
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
                    "大字段摘录节省约 $savedTokens tokens（纯函数投影，未落库）"
            )
        }
        return call.copy(messages = projected)
    }

    private companion object {
        const val MODULE_ID = "argument-projection"
        const val TAG = "ArgumentProjection"

        /**
         * 运行时投影开关：必须为 false。
         *
         * 历史里的工具参数已在写入时摘录（见 [com.aicode.feature.agent.domain.model.excerptToolCallsForHistory]），
         * 请求时再改一次会让「发出去的字节」与「存下来的字节」分叉，前缀缓存从改动处失效。
         * 留作紧急回滚开关：打开后本模块恢复旧行为，但同时必须接受缓存命中率下降。
         */
        const val RUNTIME_PROJECTION_ENABLED = false

        /**
         * 排在 CompactionModule（order 10）之后：投影「最终要发送的消息」。
         * 取 20 而不是 11：给后续可能插在两者之间的模块留位。
         */
        const val ORDER = 20

        /**
         * 旧投影阈值（字符）。模块关闭后不再参与请求组装，仅作为回滚时的对照值；
         * 写入时摘录用 [com.aicode.feature.agent.domain.model.HISTORY_TOOL_ARG_THRESHOLD_CHARS]（同为 8_000）。
         */
        const val BULK_ARG_THRESHOLD_CHARS = 8_000

        /** 参数里的目标文件路径字段，与 ContextCompactor 的 PATH_ARG_KEYS 同一套别名（避免两处漂移才重复列出）。 */
        val PATH_ARG_KEYS = setOf("path", "file_path", "filepath", "file")
    }
}
