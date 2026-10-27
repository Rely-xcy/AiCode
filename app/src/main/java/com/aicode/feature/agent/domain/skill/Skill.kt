package com.aicode.feature.agent.domain.skill

/**
 * 解析后的单个 Skill 模型。
 *
 * @param name 技能名称（供大模型调用的唯一标识）
 * @param description 技能描述（何时使用该技能）
 * @param requiredTools 该技能所需的专属工具列表（可选）
 * @param triggers 自然语言触发词：用户消息中以**空格分隔的独立词**出现即自动注入正文（见 `技能关键词触发设计.md`）
 * @param dirPath 技能所在目录的容器路径（本地/远程统一视角，如 `~/workspace/.aicode/skills/foo`）
 * @param instructions 技能指令正文（剥离 Frontmatter 后的内容）
 */
data class Skill(
    val name: String,
    val description: String,
    val requiredTools: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    val dirPath: String? = null,
    val instructions: String
)

/**
 * 检索结果。
 *
 * @param matches 命中项（已按调用方给的条数上限截断）
 * @param hitCount 命中总数（未截断），用于「还有 N 条」
 * @param skillCount 参与检索的技能总数：一个都没命中时靠它说明「搜过了多少个」而不是让人自己猜
 * @param truncatedByBudget 时间预算用完、扫描被中断。此时 [hitCount] 与 [matches] 都只是「已扫到的那部分」
 *   的结果，调用方必须把它和「确实没有匹配」分开说，否则模型会误以为库里真没有这个能力。
 */
data class SkillSearchResult(
    val matches: List<Skill>,
    val hitCount: Int,
    val skillCount: Int,
    val truncatedByBudget: Boolean = false
)

/** 单行描述的长度上限：换行与连续空白压成一个空格，超长截断。 */
private const val MAX_ONE_LINE_DESC = 100

private val ONE_LINE_WHITESPACE = Regex("[\\s\u3000]+")

/**
 * 压成一行描述，供清单与检索结果使用。
 *
 * 为什么必须压：清单与检索结果都是「一行一条」，描述里带换行会把一条撑成多行，预算与条数都对不上；
 * 截断是因为 500 字的描述在选技能这一步没有额外价值，能判断要不要加载就够了。
 */
fun Skill.oneLineDescription(max: Int = MAX_ONE_LINE_DESC): String {
    val text = description.replace(ONE_LINE_WHITESPACE, " ").trim()
    if (text.isEmpty()) return "（无描述）"
    return if (text.length <= max) text else text.take(max) + "…"
}

/** 设置页新建/编辑技能时提交的表单快照。 */
data class SkillForm(
    val name: String,
    val description: String,
    val instructions: String,
    /** 手写技能里的 `required_tools`，编辑页不暴露但原样写回，避免保存后丢字段。 */
    val requiredTools: List<String> = emptyList(),
    /** 触发词（编辑页可改）；空表示该技能只能通过 `/技能名` 或 loadSkill 触发。 */
    val triggers: List<String> = emptyList()
)

/** 保存技能失败的原因。 */
enum class SkillSaveError {
    /** 名称为空、过长或含不能做目录名的字符。 */
    INVALID_NAME,

    /** 指令正文为空：这种技能加载出来对 AI 没有任何指导价值。 */
    EMPTY_INSTRUCTIONS,

    /** 同作用域已有同名技能。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}
