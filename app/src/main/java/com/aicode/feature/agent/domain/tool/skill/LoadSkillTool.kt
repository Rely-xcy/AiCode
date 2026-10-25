package com.aicode.feature.agent.domain.tool.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.oneLineDescription
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 技能的两条通道：按关键词检索（只列名字与描述）与按名字加载完整指令正文。
 *
 * 为什么合成一个工具而不是两个：系统提示里的技能清单只放得下最常用的几条，模型需要先「查」再「读」，
 * 两步紧邻发生（查完立刻读），多一个工具就多一份工具清单与一次选择成本，且两者的能力边界完全一样。
 *
 * 加载沿用原来按名字全等匹配的语义：AI 拿到名字后调用本工具取回 SKILL.md 正文（可能含
 * 「先 apk add python3，再 python ~/.aicode/skills/<name>/x.py」之类的执行步骤），随后按正文行事。
 * 技能目录在当前执行环境为 `~/.aicode/skills/<name>/`。
 */
class LoadSkillTool @Inject constructor(
    private val skillRepository: SkillRepository
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"

        /**
         * 检索最多回这么多条：清单本来就只放得下几条，检索结果的用途是「认出正确的名字」，
         * 再多也只是一串名字，换更具体的关键词比翻页更有效。
         */
        const val MAX_SEARCH_HITS = 20
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "检索技能或加载技能正文。传 keyword 按关键词检索（匹配名称、描述与正文，大小写不敏感），" +
            "返回名字与一行描述；传 skill_name 加载该技能的完整指令。"

    override val useWhen =
        "需要某类能力但技能清单里没有对应条目时先按关键词检索（中英文各试一次），拿到准确名字后再加载正文"
    override val avoidWhen =
        "已在本轮看到技能清单或正文时不要重复检索；触发词命中的技能正文已注入，不必再加载"
    override val successCriteria =
        "检索返回名字与一行描述（或明确说没有匹配）；加载返回技能正文本身"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            // 按名字全等匹配，目录名不算数：说清楚，模型才不会拿目录名硬试。
            description = "要加载的技能名称，须与检索结果或技能清单里的名字完全一致（不是目录名）",
            required = false
        ),
        "keyword" to ToolParameter(
            name = "keyword",
            type = ParameterType.STRING,
            description = "检索关键词（只列不加载）：匹配名称、描述与正文，大小写不敏感；不确定确切名字时先用它",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        // 两个参数都给时优先加载：模型已经认定要哪个技能，再检索一遍只是浪费一轮往返。
        if (!skillName.isNullOrEmpty()) return loadByName(skillName)

        val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull?.trim()
        if (keyword.isNullOrEmpty()) {
            return ToolResult.Error(
                "缺少参数：用 keyword 按关键词检索技能（返回名字与一行描述），或用 skill_name 加载指定技能。",
                "MISSING_SKILL_ARGUMENT"
            )
        }
        return searchByKeyword(keyword)
    }

    /** 按名字加载正文；找不到时不回吐全量技能名——几百个名字塞进错误信息比空结果更占上下文。 */
    private fun loadByName(skillName: String): ToolResult {
        val instructions = skillRepository.loadInstructions(skillName)
        if (instructions == null) {
            FileLogger.w(TAG, "load_skill 未找到: $skillName")
            return ToolResult.Error(
                "未找到技能「$skillName」（按名字全等匹配）。先用 `loadSkill(keyword=\"关键词\")` " +
                    "检索出准确名字，再按名字加载。",
                "SKILL_NOT_FOUND"
            )
        }

        FileLogger.d(TAG, "load_skill 加载成功: $skillName (${instructions.length} 字符)")
        return ToolResult.Success(JsonPrimitive(instructions))
    }

    /** 按关键词检索：命中时给名字与一行描述，零命中时明确说没有，不给空数组让模型猜。 */
    private fun searchByKeyword(keyword: String): ToolResult {
        val result = skillRepository.searchSkills(keyword, MAX_SEARCH_HITS)
        if (result.matches.isEmpty()) {
            FileLogger.d(TAG, "检索技能 $keyword 零命中（共 ${result.skillCount} 个技能）")
            return ToolResult.Success(
                JsonPrimitive(
                    "没有匹配的技能：关键词「$keyword」在 ${result.skillCount} 个技能的名称、描述与正文里都没有命中。" +
                        "换更短或更常见的词（中文与英文各试一次）再检索；确认没有对应能力就别硬加载不存在的名字。"
                )
            )
        }

        val omitted = result.hitCount - result.matches.size
        val text = buildString {
            append("关键词「").append(keyword).append("」命中 ").append(result.hitCount)
            append(" 个技能（共 ").append(result.skillCount).append(" 个）：\n")
            append(result.matches.joinToString("\n") { "- ${it.name}: ${it.oneLineDescription()}" })
            if (omitted > 0) {
                append("\n（还有 ").append(omitted).append(" 个命中未列出，换更具体的关键词再看）")
            }
            append("\n用 loadSkill(skill_name=\"<名字>\") 加载正文。")
        }
        FileLogger.d(TAG, "检索技能 $keyword 命中 ${result.hitCount} 个，返回 ${result.matches.size} 个")
        return ToolResult.Success(JsonPrimitive(text))
    }
}
