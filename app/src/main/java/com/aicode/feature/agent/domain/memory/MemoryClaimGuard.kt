package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.model.AgentMessage

/**
 * 「说记住了、其实没调用工具」的兜底判据。
 *
 * 背景（已实测发生）：模型直接在回复里写「我记住了你的口味」，但整轮没有任何 `memory` 工具调用，
 * 用户去记忆页找不到。这是提示词层的结构性缺陷——规则里要求「保存成功后在回复末尾说明」，
 * 模型是续写机器，看到要写那行说明就把说明写出来了，动作被跳过。提示词与
 * [com.aicode.feature.agent.domain.tool.memory.MemoryTool] 描述已经钉过一遍（改的是「倾向」），
 * 这里是可核验的「保证」：本轮回复声称写入、而本轮确实没有 `memory` 工具调用时，
 * 由调用方往对话里补一条系统提醒，让模型当场补调用。
 *
 * 代价与边界（刻意收窄，避免为兜底付不必要的钱）：
 * - 只在命中时才多一次模型往返，不命中零成本；每个用户请求最多提醒一次（由调用方的状态位兜住）。
 * - 判据是「声称已经写进去」的措辞，不是关键词分类器：漏判只是退回提示词层的行为（今天的样子），
 *   误判也只是多问模型一次——[REMINDER] 里写清了「其实没有值得记的就不必调用」。
 * - 只在自动沉淀开关打开、且本轮真的没有 `memory` 调用时才成立；正常讨论记忆功能的对话
 *   只要顺手 list/read 过一次就不会被误判。
 */
object MemoryClaimGuard {

    /** 记忆工具的注册名（协议字段，不随文案变）。 */
    const val TOOL_NAME = "memory"

    /**
     * 补调用提醒：作为一条 user 消息注入本轮对话，不落库、不上 UI（与「截断后续写」的注入方式一致）。
     */
    val REMINDER: String = """
        [系统提醒] 你上一段回复里声称已经记住了什么，但这一轮你并没有调用 memory 工具——那等于什么都没记，用户去记忆页会找不到。
        现在只做一件事：如果上一段里确实有值得长期保留的稳定结论，立刻调用 memory(action=save, ...) 写进去；如果其实没有，就不要调用。
        回复只写一行，不要重复上一段的内容。
    """.trimIndent()

    /**
     * 中文声称写入：「我/已经/会 记住（了）」「记下了」「已写入记忆/更新了记忆」。
     * 排除否定与回忆的说法（「没记住」「记不住」「不会记住」不算声称）。
     */
    private val CHINESE_CLAIM = Regex(
        "(?<!没)(?<!不)(?<!不会)(记住(?!不)|记下(?!不)|记好了)|" +
            "(已|已经)?(保存|存|写|记|收录|更新|添加)(进|入|到)?(了)?(长期)?记忆|" +
            "记忆(里|中)(已|已经)?(保存|存|写|记)"
    )

    /**
     * 英文声称写入：只认「已经写完 / 承诺写」的完成式与第一人称承诺，
     * 不认 "I remember you said ..." 这类回忆式说法（那是回忆，不是写入）。
     */
    private val ENGLISH_CLAIMS: List<Regex> = listOf(
        Regex("""\bI('ve| have|'ll| will| am going to)\s+(just\s+)?(remember|remembered|memorize|memorise|memorized|note|noted|save|saved|store|stored|record|recorded|keep)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(saved|stored|recorded|added|written|wrote|preserved)\b[^.\n]{0,40}\b(memory|memories)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(memorized|memorised)\b""", RegexOption.IGNORE_CASE),
        // 整行就是一句「记下了 / Noted.」：英文里最常见的空口承诺形态（用 \z 而非 $，避免与 Kotlin 模板语法混淆）
        Regex("""(^|\n)[ \t]*(noted|saved|memorized|记下了?|记住了?)[。.!]?[ \t]*(\n|\z)""", RegexOption.IGNORE_CASE)
    )

    /** 这段文本是否声称已经写入（或承诺写入）长期记忆。 */
    fun claimsMemorized(text: String): Boolean {
        if (text.isBlank()) return false
        if (CHINESE_CLAIM.containsMatchIn(text)) return true
        return ENGLISH_CLAIMS.any { it.containsMatchIn(text) }
    }

    /**
     * 是否需要在结束时补一次提醒：最后这段回复声称写入、但本轮没有任何 `memory` 工具调用。
     *
     * 只看「本轮」——范围从最后一条 user 消息之后算起，避免上一轮调过 memory 就把这一轮豁免掉。
     */
    fun needsReminder(messages: List<AgentMessage>, finalText: String): Boolean {
        if (!claimsMemorized(finalText)) return false
        return !hadMemoryToolCallThisTurn(messages)
    }

    /** 本轮（最后一条 user 消息之后）是否出现过 memory 工具调用结果。 */
    fun hadMemoryToolCallThisTurn(messages: List<AgentMessage>): Boolean {
        val turnStart = messages.indexOfLast { it is AgentMessage.UserMessage }
        for (index in (turnStart + 1) until messages.size) {
            val message = messages[index]
            if (message is AgentMessage.ToolResultMessage && message.toolName == TOOL_NAME) return true
        }
        return false
    }
}
