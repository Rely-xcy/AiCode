package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.workflow.TokenEstimator
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级的整段消息估算缓存。
 *
 * 为什么要有：判定与折叠会反复估算同一段历史（模块判定一次、压缩器切块前再算、折叠后再算一次），
 * 而 [TokenEstimator] 的每条消息缓存仍要为每条消息重建缓存键——键里带整段内容的 hashCode，
 * 等于把全文再扫一遍。这里按整段列表复用结果，每次只要每个元素一次字段长度读取与引用比较。
 *
 * 精确性：命中要求指纹相同**且**元素逐个是同一对象。消息都是不可变 data class，引用相同即内容相同，
 * 所以命中值与当场调用 [TokenEstimator.estimateMessages] 逐值相等（误差 0）。
 * 指纹（元素数、字段长度之和、末条 id）只用于快速排除：任一项不同就一定是另一份列表；
 * 但指纹相同不能证明没变——用户编辑历史消息后字数可以一模一样，所以最终以元素同一性为准。
 *
 * @param estimateTokens 估算函数，默认就是生产路径；单测换成计数桩来断言「有没有真的重算」。
 */
internal class MessageEstimateCache(
    private val estimateTokens: (List<AgentMessage>) -> Int = TokenEstimator::estimateMessages
) {

    private class Entry(val tokens: Int, val messages: List<AgentMessage>)

    private data class Fingerprint(val size: Int, val totalLength: Int, val lastId: String?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 取 [messages] 的估算值：命中缓存就返回旧值，否则重算并记下这份列表。 */
    fun estimate(sessionId: String?, messages: List<AgentMessage>): Int {
        // 没有会话 id 就没有隔离维度（并行子代理、手动压缩的临时请求），不缓存。
        if (sessionId == null) return estimateTokens(messages)
        val cached = entries[sessionId]
        if (cached != null && sameMessages(cached.messages, messages)) return cached.tokens
        val tokens = estimateTokens(messages)
        entries[sessionId] = Entry(tokens, messages)
        return tokens
    }

    /** 会话删除时清掉它的条目，否则会一直留着一份已经没人看的历史。 */
    fun remove(sessionId: String) {
        entries.remove(sessionId)
    }

    private fun sameMessages(cached: List<AgentMessage>, current: List<AgentMessage>): Boolean {
        if (cached === current) return true
        if (cached.size != current.size) return false
        if (fingerprint(cached) != fingerprint(current)) return false
        for (index in cached.indices) {
            if (cached[index] !== current[index]) return false
        }
        return true
    }

    private fun fingerprint(messages: List<AgentMessage>): Fingerprint =
        Fingerprint(
            size = messages.size,
            totalLength = messages.sumOf { it.fingerprintLength() },
            lastId = messages.lastOrNull()?.id
        )
}

/**
 * 指纹用的字段长度和：只读 length，不拼接也不哈希内容。
 *
 * 覆盖估算会读到的每一类字段，另有只影响「是否被判成另一份列表」的字段也一并算上——
 * 多算一个长度只是几次字符串读取，漏算却会让指纹失去早退能力。
 */
private fun AgentMessage.fingerprintLength(): Int = when (this) {
    is AgentMessage.UserMessage ->
        content.length + (modelReminder?.length ?: 0) + images.fingerprintLength()

    is AgentMessage.AssistantMessage ->
        content.length + reasoning.length + signature.length + thinkingBlocksJson.length +
            toolCalls.sumOf { it.name.length + it.arguments.size } + images.fingerprintLength()

    is AgentMessage.ToolResultMessage ->
        toolName.length + result.length + images.fingerprintLength()
}

private fun List<AgentImage>.fingerprintLength(): Int =
    sumOf { it.mimeType.length + it.base64Data.length + it.path.length }
