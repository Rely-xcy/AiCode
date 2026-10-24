package com.aicode.feature.agent.domain.prompt

/**
 * 清单类注入块（技能、子代理）的长度上限。
 *
 * 为什么按字符预算而不是按条数：条数与单条长度都不可控（装一个技能就多一条，描述长度由作者决定），
 * 按条数限制挡不住「10 条各 800 字」。
 *
 * 为什么保留前若干条原文而不是统一压成一行：清单里的名称与描述正是模型判断「要不要加载」的依据，
 * 全压成计数等于把可用能力藏起来；截断只对超长清单生效，正常规模下输出与截断前逐字相同。
 *
 * 截断时必须同时给出「有多少项」与「剩下的去哪看」——否则模型会以为清单就这些，把「只能加载清单里的」
 * 这条约束读成「只能加载展出的这些」。
 */
internal object PromptListBudget {

    /**
     * 技能清单预算。实测每条约 60~160 字符；1600 字符约合 400 token，能留下 12~20 条仍有描述的主力清单，
     * 又不会让「装了上百个技能」的机器把系统提示词的前缀拖长到十几 KB。
     */
    const val SKILLS = 1600

    /**
     * 子代理清单预算。子代理比技能少、描述更短，但仍要防「每个都写长描述」把尾部撑开，
     * 取 1200 字符，约为技能预算的四分之三。
     */
    const val SUBAGENTS = 1200

    /**
     * 技能清单的摘要行：必须同时给出「还剩几项、共几项、去哪看」。
     * 「均可照常加载」不是客套：别处规定了「只能加载清单里的技能」，不写清楚，模型会把未展开的当成不存在。
     */
    fun skillsNote(omitted: Int, total: Int): String =
        "- 其余 $omitted 个未展开（共 $total 个技能，均可照常加载）：名称见 `~/.aicode/skills/` 或项目 `.aicode/skills/` 下的目录名"

    /** 子代理清单的摘要行：同上，另点明未展开的仍可派发。 */
    fun subAgentsNote(omitted: Int, total: Int): String =
        "- 其余 $omitted 个未展开（共 $total 个子代理，均可照常派发）：名称见 `~/.aicode/agents/` 或项目 `.aicode/agents/` 下的文件名"

    /**
     * 逐行填充到预算用完，再补一行摘要 [note]（参数为：未展开条数、总条数）。
     * 全部放得下时原样返回，不做任何改写——避免正常规模下的字节抖动打断缓存。
     */
    fun limit(lines: List<String>, budget: Int, note: (omitted: Int, total: Int) -> String): String {
        if (lines.isEmpty()) return ""
        val full = lines.joinToString("\n")
        if (full.length <= budget) return full

        // 摘要行本身也占预算，先按最长形态预留，否则「截断后仍超长」。
        val reserve = note(lines.size, lines.size).length + 1
        val kept = ArrayList<String>()
        var used = 0
        for (line in lines) {
            if (used + line.length + 1 + reserve > budget) break
            kept += line
            used += line.length + 1
        }
        // 单条就超过预算：保住「有多少项、去哪看」，不返回空串。
        if (kept.isEmpty()) return note(lines.size, lines.size)
        return (kept + note(lines.size - kept.size, lines.size)).joinToString("\n")
    }
}
