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
import kotlinx.serialization.json.intOrNull
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
        /** 一次最多列几条。列多了模型反而不会挑，只看前两三条就决定。 */
        const val MAX_SEARCH_HITS = 8

        /**
         * 正文单页字符上限。
         *
         * 定 9000 而不是更大：工具结果统一有 10000 字符的内联截断（超过就只留头 8000 + 尾 2000），
         * 页太大就会被那道闸从中间砍掉，模型会以为正文本身缺了一段。9000 留出页头提示的余量。
         */
        const val PAGE_CHARS = 9_000
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "检索技能或加载技能正文。传 keyword 按关键词检索（支持中文分词与中英别名，匹配名称、描述与正文），" +
            "返回名字与一行描述；传 skill_name 加载该技能的指令正文。正文较长时分页返回，" +
            "返回里会给出下一个 offset，用同一个 skill_name 加 offset 继续读，直到提示已到结尾。"

    override val useWhen =
        "需要某类能力但技能清单里没有对应条目时先按关键词检索（中英文各试一次），拿到准确名字后再加载正文；" +
            "正文返回里说还有剩余时，用同一个 skill_name 加 offset 继续读下一段"
    override val avoidWhen =
        "已在本轮看到技能清单或正文时不要重复检索；触发词命中的技能正文已注入，不必再加载"
    override val successCriteria =
        "检索返回名字与一行描述（或明确说没有匹配）；加载返回技能正文（分页时带页码与下一个 offset）"

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
            description = "检索关键词（只列不加载）：匹配名称、描述与正文，支持中文分词与中英别名；不确定确切名字时先用它",
            required = false
        ),
        "offset" to ToolParameter(
            name = "offset",
            type = ParameterType.INTEGER,
            description = "加载正文时的起始字符偏移（从 0 计，默认 0 = 第一页）。上一页返回里有下一个 offset，" +
                "只有正文超过一页时才需要它",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        // 两个参数都给时优先加载：模型已经认定要哪个技能，再检索一遍只是浪费一轮往返。
        if (!skillName.isNullOrEmpty()) return loadByName(skillName, offset)

        if (offset != 0) {
            return ToolResult.Error(
                "offset 只用于分页读取正文，需与 skill_name 一起用（检索结果不分页）。",
                "INVALID_OFFSET"
            )
        }

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
    private fun loadByName(skillName: String, offset: Int): ToolResult {
        val instructions = skillRepository.loadInstructions(skillName)
        if (instructions == null) {
            FileLogger.w(TAG, "load_skill 未找到: $skillName")
            return ToolResult.Error(
                "未找到技能「$skillName」（按名字全等匹配）。先用 `loadSkill(keyword=\"关键词\")` " +
                    "检索出准确名字，再按名字加载。",
                "SKILL_NOT_FOUND"
            )
        }
        return pageOf(skillName, instructions, offset)
    }

    /**
     * 把正文切成单页 [PAGE_CHARS] 的片段返回，并明确告诉模型「本次读了多少 / 总长多少 / 下一段从哪开始」
     * 或「已到结尾」。
     *
     * 为什么必须分页而不是整篇回吐：一个 SKILL.md 动辄几万字，整篇塞进来会先被工具层统一的
     * 10000 字符闸截掉中段，模型看到的正文缺一截却不知道，照着读下去就是按残缺步骤执行。
     */
    private fun pageOf(skillName: String, instructions: String, offset: Int): ToolResult {
        val total = instructions.length
        if (offset < 0) {
            return ToolResult.Error(
                "offset 不能为负数（收到 $offset）。从开头读时不要传 offset，或用 offset=0。",
                "INVALID_OFFSET"
            )
        }
        if (total == 0) {
            return ToolResult.Success(JsonPrimitive("技能「$skillName」的指令正文为空，没有可读内容。"))
        }
        if (offset >= total) {
            return ToolResult.Error(
                "offset=$offset 超出正文范围：技能「$skillName」的正文共 $total 字符（有效 offset 为 0..${total - 1}）。" +
                    "不传 offset 即从第一页重读。",
                "OFFSET_OUT_OF_RANGE"
            )
        }

        var end = minOf(offset + PAGE_CHARS, total)
        // 不把代理对（emoji 等）从中间切开：否则本页末尾会留下半个字符，下一页又从半个字符开始。
        // 切点往前退一格即可，下一轮的 offset 也就不会落在代理对中间。
        if (end < total && end > offset && instructions[end - 1].isHighSurrogate()) end--
        val page = instructions.substring(offset, end)
        val hasMore = end < total
        val header = buildString {
            append("【分页】技能「").append(skillName).append("」本次返回 ").append(page.length)
            append(" 字符（第 ").append(offset + 1).append("-").append(end).append(" 字符），总长 ")
            append(total).append(" 字符；")
            if (hasMore) {
                append("还有剩余，用同一 skill_name 加 offset=").append(end).append(" 继续读。")
            } else {
                append("已读到结尾，没有剩余内容。")
            }
        }

        FileLogger.d(
            TAG,
            "load_skill 加载成功: $skillName offset=$offset 返回 ${page.length}/$total 字符 剩余=$hasMore"
        )
        return ToolResult.Success(JsonPrimitive("$header\n\n$page"))
    }

    /** 按关键词检索：命中时给名字与一行描述，零命中时明确说没有，不给空数组让模型猜。 */
    private fun searchByKeyword(keyword: String): ToolResult {
        val result = skillRepository.searchSkills(keyword, MAX_SEARCH_HITS)
        if (result.matches.isEmpty()) {
            // 时间预算用完时必须和「确实没有」分开说：否则模型会以为库里真没这个能力，转而去猜名字。
            if (result.truncatedByBudget) {
                FileLogger.w(TAG, "检索技能 $keyword 超时未完成（共 ${result.skillCount} 个技能）")
                return ToolResult.Success(
                    JsonPrimitive(
                        "检索没跑完：时间预算用完，已扫描的部分没命中「$keyword」（共 ${result.skillCount} 个技能参与）。" +
                            "这不等于没有对应技能，换一个更短、更具体的词再试一次。"
                    )
                )
            }
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
            if (result.truncatedByBudget) {
                append("\n（时间预算用完，只扫了部分技能，结果可能不全；换更具体的词或重试再搜一遍）")
            }
            append("\n用 loadSkill(skill_name=\"<名字>\") 加载正文。")
        }
        FileLogger.d(TAG, "检索技能 $keyword 命中 ${result.hitCount} 个，返回 ${result.matches.size} 个")
        return ToolResult.Success(JsonPrimitive(text))
    }
}
