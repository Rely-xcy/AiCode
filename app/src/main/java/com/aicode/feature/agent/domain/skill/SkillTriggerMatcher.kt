package com.aicode.feature.agent.domain.skill

/** 一次自然语言命中的结果。[atStart] 表示触发词出现在消息的第一个 token（优先级更高）；
 *  [deduped] 表示该技能已在本会话注入过正文，本轮只降级为一行提示。 */
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
 * - 长度 < 2 的触发词忽略（避免 `a`、`我` 这类词误伤）。
 *
 * 纯字符串逻辑、无 IO，可单测。
 */
object SkillTriggerMatcher {

    private const val MIN_TRIGGER_LENGTH = 2

    private val WHITESPACE = Regex("[\\s\u3000]+")

    /** 按空白分词；空串返回空列表。 */
    internal fun tokenize(request: String): List<String> =
        request.split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * 匹配消息命中的技能，按优先级排序：句首命中 > 触发词更长 > 技能名。
     *
     * 同一技能只出一个结果：取它最优的那个触发词。
     */
    fun match(request: String, skills: List<Skill>): List<SkillTriggerHit> {
        val tokens = tokenize(request)
        if (tokens.isEmpty()) return emptyList()

        val hits = ArrayList<SkillTriggerHit>()
        for (skill in skills) {
            var best: SkillTriggerHit? = null
            for (trigger in skill.triggers) {
                if (trigger.length < MIN_TRIGGER_LENGTH) continue
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
