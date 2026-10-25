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
     * 技能清单预算。
     *
     * 清单只放用户排在最前面的少数几个技能，其余靠 `loadSkill(keyword=…)` 检索：技能装到几百个时，
     * 全量列表在固定预算下必然只剩前几条，被砍掉的技能名字也没了，模型既看不到也没法加载。
     * 1200 字符约合 300 token，按实测每条约 100 字符（名称 + 一行描述，不含末尾摘要行）能放 8~10 条，
     * 常见场景不必先检索；比改版前的 1600 更省，因为省下的位置不再花在注定被截掉的条目上。
     */
    const val SKILLS = 1200

    /**
     * 技能清单最多列这么多条。
     *
     * 预算按字符算，「描述写得很短」的技能能让几十条挤进同一笔预算里，清单又退回全量枚举；
     * 条数上限保证它始终只是「最常用的几个」，检索才是找到其余技能的正路。
     */
    const val SKILLS_MAX_ITEMS = 10

    /**
     * 子代理清单预算。子代理比技能少、描述更短，但仍要防「每个都写长描述」把尾部撑开；
     * 取 1200 字符，与技能清单同一量级——子代理没有检索兜底，删掉的条数就是真的看不到。
     */
    const val SUBAGENTS = 1200

    /**
     * 技能清单的摘要行：给出「还剩几项、共几项、怎么找回来」。
     *
     * 这里不再指路到技能目录：`loadSkill` 只认 frontmatter 里的 name，目录名未必等于技能名，
     * 照目录名猜必然落空。指路必须指向真正可用的检索入口，否则模型会把未列出的当成不存在。
     */
    fun skillsNote(omitted: Int, total: Int): String =
        "- 其余 $omitted 个未列出（共 $total 个技能，均可检索后按名加载）：用 `loadSkill(keyword=\"关键词\")` 按关键词检索"

    /** 子代理清单的摘要行：同上，另点明未展开的仍可派发。 */
    fun subAgentsNote(omitted: Int, total: Int): String =
        "- 其余 $omitted 个未展开（共 $total 个子代理，均可照常派发）：名称见 `~/.aicode/agents/` 或项目 `.aicode/agents/` 下的文件名"

    /**
     * 逐行填充到预算用完，再补一行摘要 [note]（参数为：未展开条数、总条数）。
     * 全部放得下时原样返回，不做任何改写——避免正常规模下的字节抖动打断缓存；
     * 也因此 [maxItems] 只在确定要截断时才生效：整份清单放得下就没有理由少列几条。
     */
    fun limit(
        lines: List<String>,
        budget: Int,
        note: (omitted: Int, total: Int) -> String,
        maxItems: Int = Int.MAX_VALUE
    ): String {
        if (lines.isEmpty()) return ""
        val full = lines.joinToString("\n")
        if (full.length <= budget) return full

        // 摘要行本身也占预算，先按最长形态预留，否则「截断后仍超长」。
        val reserve = note(lines.size, lines.size).length + 1
        val kept = ArrayList<String>()
        var used = 0
        for (line in lines) {
            if (kept.size >= maxItems) break
            if (used + line.length + 1 + reserve > budget) break
            kept += line
            used += line.length + 1
        }
        // 单条就超过预算：保住「有多少项、去哪看」，不返回空串。
        if (kept.isEmpty()) return note(lines.size, lines.size)
        return (kept + note(lines.size - kept.size, lines.size)).joinToString("\n")
    }
}
