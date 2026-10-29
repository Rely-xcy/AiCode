package com.aicode.feature.agent.domain.skill

/** 一次自然语言命中的结果。[atStart] 表示触发词出现在消息的第一个 token（优先级更高）；
 *  [deduped] 表示该技能已在本会话注入过正文，本轮只降级为一行提示。[hitWord] 返回**技能配置里的触发词原文**。 */
data class SkillTriggerHit(
    val skill: Skill,
    val hitWord: String,
    val atStart: Boolean,
    val deduped: Boolean = false
)

/**
 * 技能触发词匹配：**空格分词全等**（见 `技能关键词触发设计.md`）。
 *
 * 规则（定稿，不要再放宽）：
 * - 按空白分词（含全角空格）后，存在一个 token 与触发词**完全相等**（忽略大小写）即命中；
 * - 标点粘连、连写一律不命中（`逆向。`、`帮我逆向看看` 都不算）——单发触发词视为显式口令，
 *   宁可漏触发也不要在用户没这个意思时把整篇技能正文灌进上下文；
 * - 长度 < 2 的触发词忽略（避免 `a`、`我` 这类词误伤）；
 * - **触发词本身必须是单个词**（不含空白）：含空格的触发词按全等规则永远匹配不上，
 *   在解析层就丢弃（见 [sanitize]），不等到匹配时静默失效。
 *
 * 纯字符串逻辑、无 IO，可单测。
 */
object SkillTriggerMatcher {

    private const val MIN_TRIGGER_LENGTH = 2

    /**
     * 英文触发词最短 3 个字符（中文仍是 2）。
     *
     * 两个字母的英文词到处都是：`UI` 能命中任何一段技术文本，`PE` 同理。
     * 宁可在配置层要求写具体一点（`PEfile` 而不是 `PE`），不要让它变成全时噪声源。
     */
    private const val MIN_ASCII_TRIGGER_LENGTH = 3

    /**
     * 超过这么多个词的长文本不参与自然语言触发。
     *
     * 粘贴的报错日志、代码块、结构化任务描述里必然含有 test/review/build 这类词，
     * 但那些不是「用户主动说出一个触发词」。用户真要用某个能力，会在一句话里说。
     */
    const val MAX_TRIGGER_TOKENS = 40

    /** 短命令阈值：≤ 这么多个词且只命中一个技能时，省掉一次工具往返，直接给正文。 */
    const val SHORT_COMMAND_TOKENS = 10

    /** 候选上限：给模型太多选项反而挑不准。 */
    const val MAX_CANDIDATES = 3

    private val WHITESPACE = Regex("[\\s\\u3000]+")

    /** 按空白分词；空串返回空列表。 */
    internal fun tokenize(request: String): List<String> =
        request.split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * 触发词规范化：去首尾空白、丢掉含空白（多词）与长度 < 2 的项。
     * 解析器与编辑页保存前都应过这一道——多词触发词在 token 全等规则下不可能命中，
     * 留着只会让人以为配了没用是 bug。
     */
    fun sanitize(triggers: List<String>): List<String> =
        triggers.map { it.trim() }
            .filter { trigger ->
                if (trigger.length < MIN_TRIGGER_LENGTH) return@filter false
                if (WHITESPACE.containsMatchIn(trigger)) return@filter false
                // 纯 ASCII 的触发词要高一个字：两字母英文单词的误触面积太大。
                if (trigger.all { it.code < 128 } && trigger.length < MIN_ASCII_TRIGGER_LENGTH) return@filter false
                true
            }
            .distinct()

    /**
     * 匹配消息命中的技能，按优先级排序：句首命中 > 触发词更长 > 技能名。
     *
     * 同一技能只出一个结果：取它最优的那个触发词。[hitWord] 是技能配置的触发词原文（非消息原文）。
     */
    fun match(request: String, skills: List<Skill>): List<SkillTriggerHit> {
        val tokens = tokenize(request)
        if (tokens.isEmpty()) return emptyList()

        val hits = ArrayList<SkillTriggerHit>()
        for (skill in skills) {
            var best: SkillTriggerHit? = null
            for (trigger in sanitize(skill.triggers)) {
                val index = tokens.indexOfFirst { it.equals(trigger, ignoreCase = true) }
                if (index < 0) continue
                val candidate = SkillTriggerHit(skill = skill, hitWord = trigger, atStart = index == 0)
                if (best == null || isBetter(candidate, best)) best = candidate
            }
            best?.let { hits += it }
        }

        return hits.sortedWith(
            compareByDescending<SkillTriggerHit> { it.atStart }
                .thenByDescending { it.hitWord.length }
                .thenBy { it.skill.name }
        )
    }

    private fun isBetter(candidate: SkillTriggerHit, current: SkillTriggerHit): Boolean =
        when {
            candidate.atStart != current.atStart -> candidate.atStart
            else -> candidate.hitWord.length > current.hitWord.length
        }
}
