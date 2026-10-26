package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

object SkillParser {
    private const val TAG = "SkillParser"
    private const val MAX_DESC_CHARS = 500
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /** 兜底日志里的失败原因最多留这么多字符。 */
    private const val MAX_FAILURE_REASON_CHARS = 160

    /** frontmatter 顶层键：行首无缩进，值可为空（后续缩进行或块列表）。 */
    private val TOP_LEVEL_KEY = Regex("^([A-Za-z0-9_-]+)\\s*:\\s*(.*)$")

    /**
     * 解析一个 skill 目录；无 SKILL.md / CLAUDE.md 或无 name 时视为非法，返回 null。
     * 目录经 [provider] 以容器路径读取，本地/远程统一。
     *
     * [fileName] 由扫描阶段已知时传入，省掉再列一次目录的往返；为 null 时才去列目录按
     * SKILL.md → CLAUDE.md 的顺序挑。
     */
    fun parse(provider: FileAccessProvider, dirPath: String, fileName: String? = null): Skill? =
        parseWithStatus(provider, dirPath, fileName)?.skill

    /**
     * 同 [parse]，另外报告 frontmatter 是否走了按行兜底（扫描阶段据此汇总失败数）。
     */
    internal fun parseWithStatus(
        provider: FileAccessProvider,
        dirPath: String,
        fileName: String? = null
    ): ParseOutcome? {
        val dir = dirPath.trimEnd('/')
        // 优先 SKILL.md，其次 CLAUDE.md（兼容只用 CLAUDE.md 的技能）；名称匹配忽略大小写
        val name = fileName ?: resolveInstructionFileName(provider, dir) ?: return null

        val text = runCatching { provider.readFile("$dir/$name") }.getOrElse {
            FileLogger.w(TAG, "读取 Skill 文件失败: $dir/$name", it)
            return null
        }

        val outcome = parseTextWithStatus(text, dir.substringAfterLast('/').ifBlank { dir }, "$dir/$name")
        return outcome.copy(skill = outcome.skill.copy(dirPath = dir))
    }

    /** 列出目录后按 SKILL.md → CLAUDE.md 的顺序挑指令文件；列目录失败按「没有指令文件」处理。 */
    private fun resolveInstructionFileName(provider: FileAccessProvider, dir: String): String? {
        val names = runCatching {
            provider.listFiles(dir).map { it.name }
        }.getOrElse {
            FileLogger.w(TAG, "列出技能目录失败: $dir", it)
            return null
        }
        return names.firstOrNull { it.equals(SKILL_FILE, ignoreCase = true) }
            ?: names.firstOrNull { it.equals(CLAUDE_FILE, ignoreCase = true) }
    }

    /** 解析产物：技能本体 + frontmatter 是否走了按行兜底。 */
    internal data class ParseOutcome(val skill: Skill, val frontmatterFellBack: Boolean)

    /**
     * 从原始 Markdown 文本解析技能（不依赖磁盘），供文件/压缩包导入使用。
     * [fallbackName] 为 frontmatter 缺 name 时的兜底（通常传源文件名或所在目录名）。
     * [sourcePath] 只用于日志定位（有磁盘路径就传完整路径），不影响解析结果。
     */
    fun parseText(text: String, fallbackName: String, sourcePath: String? = null): Skill =
        parseTextWithStatus(text, fallbackName, sourcePath).skill

    /**
     * 同 [parseText]，另外报告 frontmatter 是否解析失败后走了按行兜底；
     * 扫描阶段靠它汇总「这次扫描一共有几个技能 frontmatter 解析失败」。
     */
    internal fun parseTextWithStatus(
        text: String,
        fallbackName: String,
        sourcePath: String? = null
    ): ParseOutcome {
        val frontmatter = splitAndParseFrontmatter(text, sourcePath ?: fallbackName)

        // name 优先取 frontmatter，缺省回退到兜底名
        val name = frontmatter.values["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = (frontmatter.values["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter.values["required_tools"]
            if (toolsRaw is List<*>) toolsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 required_tools 失败: ${sourcePath ?: fallbackName}", e)
            emptyList()
        }

        val triggers = try {
            val raw = frontmatter.values["triggers"]
            if (raw is List<*>) raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 triggers 失败: ${sourcePath ?: fallbackName}", e)
            emptyList()
        }
        // 多词触发词在「空格分词全等」规则下永远命中不了（见 SkillTriggerMatcher），解析时就丢弃，
        // 不留「配了没用」的假象；<2 字符也在这里拦掉。
        triggers.filter { it.length >= 2 && !Regex("[\\s\u3000]+").containsMatchIn(it) }

        return ParseOutcome(
            skill = Skill(
                name = name,
                description = description,
                requiredTools = requiredTools,
                triggers = triggers,
                dirPath = null,
                instructions = frontmatter.body.trim()
            ),
            frontmatterFellBack = frontmatter.fellBack
        )
    }

    /**
     * 利用 SnakeYAML 切分并解析 YAML frontmatter。
     *
     * YAML 抛异常时退化为 [salvageFrontmatter] 的按行抽取：只靠 `emptyMap()` 会把技能的名字
     * 退成中文目录名、描述清空、触发词丢掉，列表里看着还在，`/原名` 与 loadSkill 却都找不到它。
     */
    private fun splitAndParseFrontmatter(text: String, sourceLabel: String): FrontmatterBlock {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return FrontmatterBlock(emptyMap(), normalized)

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return FrontmatterBlock(emptyMap(), normalized)

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        return try {
            val loaded = Yaml().load<Map<String, Any>>(block)
            FrontmatterBlock(loaded ?: emptyMap(), rest)
        } catch (e: Exception) {
            val salvaged = salvageFrontmatter(block)
            FileLogger.w(
                TAG,
                "技能 frontmatter 解析失败，已按行兜底: $sourceLabel —— ${summarize(e)}; " +
                    "救回字段=${salvaged.keys.joinToString("/").ifEmpty { "无" }}"
            )
            FrontmatterBlock(salvaged, rest, fellBack = true)
        }
    }

    private data class FrontmatterBlock(
        val values: Map<String, Any>,
        val body: String,
        val fellBack: Boolean = false
    )

    /** 只取异常消息的第一行并截断：SnakeYAML 的消息带整段上下文，原样灌进日志没法看。 */
    private fun summarize(throwable: Exception): String {
        val message = throwable.message?.replace('\n', ' ')?.trim().orEmpty()
        return message.take(MAX_FAILURE_REASON_CHARS).ifEmpty { throwable::class.java.simpleName }
    }

    /**
     * YAML 解析失败后的按行兜底：只认平铺在顶层的 name / description / triggers。
     *
     * 这里刻意不做通用 YAML 解析——目标只是把技能从「凭空消失」救回「能按名字调用」。
     * description 正文里那句「触发词: xxx」只是描述文本，**不抽进 triggers**：触发词只认文件里
     * 本来就有的 `triggers:` 字段，兜底不许凭空造出关键词。
     */
    private fun salvageFrontmatter(block: String): Map<String, Any> {
        val salvaged = LinkedHashMap<String, Any>()
        val lines = block.replace("\r\n", "\n").split('\n')
        var index = 0
        while (index < lines.size) {
            val match = TOP_LEVEL_KEY.matchEntire(lines[index].trimEnd())
            if (match == null) {
                index++
                continue
            }
            val key = match.groupValues[1]
            val inline = match.groupValues[2].trim()
            // 该键之后连续的缩进行属于它的值（续行 / 块列表 / 块标量正文）
            var next = index + 1
            while (next < lines.size &&
                (lines[next].isBlank() || lines[next].startsWith(" ") || lines[next].startsWith("\t"))
            ) {
                next++
            }
            val continuation = lines.subList(index + 1, next)
            when (key) {
                "name" -> {
                    val value = unquoteScalar(inline)
                        ?: continuation.firstOrNull { it.isNotBlank() }?.let { unquoteScalar(it) }
                    if (!value.isNullOrBlank()) salvaged["name"] = value
                }
                "description" -> {
                    // 引号包裹的值先摘引号再拼：兜底出来的描述不该带着一对引号显示在列表里
                    val head = unquoteScalar(inline).orEmpty()
                    val text = if (head.isEmpty() || head.startsWith("|") || head.startsWith(">")) {
                        continuation.map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                    } else {
                        (listOf(head) + continuation.map { it.trim() }).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    if (text.isNotBlank()) salvaged["description"] = text
                }
                "triggers" -> {
                    val items = when {
                        inline.startsWith("[") -> inline.removePrefix("[").removeSuffix("]").split(',')
                        inline.isEmpty() -> continuation.map { it.trim() }
                            .filter { it.startsWith("- ") }
                            .map { it.removePrefix("- ") }
                        else -> emptyList()
                    }
                    val cleaned = items.mapNotNull { unquoteScalar(it) }.filter { it.isNotBlank() }
                    if (cleaned.isNotEmpty()) salvaged["triggers"] = cleaned
                }
            }
            index = next
        }
        return salvaged
    }

    /** 去掉成对的引号；不成对就原样返回（兜底宁可选错也不丢文本）。 */
    private fun unquoteScalar(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val quoted = value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        return if (quoted) value.substring(1, value.length - 1) else value
    }

    /**
     * 把设置页表单写回 `SKILL.md` 文本（frontmatter + 正文），与 [parse] 成对。
     *
     * `name` 总是写出来：技能名优先取 frontmatter，改名只需改这里而不必动目录名（技能目录
     * 里的脚本常被正文按原路径引用，跟着改名会把引用打断）。
     * 工具名是标识符，直接进方括号列表；其余文本字段一律加引号，免得描述里的冒号或 # 把 YAML 弄坏。
     */
    fun serialize(
        name: String,
        description: String,
        requiredTools: List<String>,
        triggers: List<String>,
        instructions: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        if (requiredTools.isNotEmpty()) appendLine("required_tools: [${requiredTools.joinToString(", ")}]")
        if (triggers.isNotEmpty()) appendLine("triggers: [${triggers.joinToString(", ")}]")
        appendLine("---")
        appendLine(instructions.trim())
    }

    /** frontmatter 字符串值：双引号包裹，转义反斜杠与引号，换行压成空格保证单行。 */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
