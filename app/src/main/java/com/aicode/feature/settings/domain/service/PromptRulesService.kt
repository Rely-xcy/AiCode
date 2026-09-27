package com.aicode.feature.settings.domain.service

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.prompt.PromptFragmentResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可查看/可编辑的提示词片段。 */
data class PromptFragmentInfo(
    /** 两位数字身份；内置静态片段与按数字新增的自定义片段都有。 */
    val number: Int,
    /** 内置片段的文件名（自定义新增时为生成的 `<NN>-<名称>.md`）。 */
    val fileName: String,
    val title: String,
    val subtitle: String,
    /** 是否有内置默认内容（即覆盖内置片段）；false 表示纯新增片段。 */
    val isBuiltin: Boolean,
    /** 当前是否存在自定义文件。 */
    val hasCustom: Boolean,
    /** 关键片段，禁止修改。 */
    val isProtected: Boolean
)

/**
 * 自定义提示词（`~/.aicode/prompts.custom/`）的读写服务。
 *
 * 片段身份是文件名里的两位数字：命中内置数字即为**覆盖**，其它数字为**新增**（见 [PromptFragmentResolver]）。
 * 少数关键片段（身份、安全）不允许改——它们决定 Agent 的基本行为与安全边界，改坏会让 App 失去兜底。
 */
@Singleton
class PromptRulesService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "PromptRulesService"
        const val ASSET_DIR = "prompts"

        /** 关键片段：身份与安全边界，禁止用户覆盖。 */
        val PROTECTED_NUMBERS = setOf(0, 50)

        /** 内置静态基线：数字 → (文件名, 中文标题, 一句话说明)。 */
        val BUILTIN_FRAGMENTS = listOf(
            BuiltinFragment(0, "00-identity.md", "身份", "Agent 是谁、能做什么"),
            BuiltinFragment(10, "10-communication.md", "沟通", "回复语气与格式"),
            BuiltinFragment(15, "15-project-rules.md", "项目规则", "如何读取并使用项目规范文件"),
            BuiltinFragment(20, "20-coding-discipline.md", "编码纪律", "改动范围、依赖与风格约束"),
            BuiltinFragment(30, "30-comments.md", "注释", "什么情况才写注释"),
            BuiltinFragment(40, "40-approach.md", "工作方式", "先核实、改动后自验等流程要求"),
            BuiltinFragment(50, "50-safety.md", "安全", "不可越过的安全边界"),
            BuiltinFragment(60, "60-tools-and-paths.md", "工具与路径", "工具用法与路径约定"),
            BuiltinFragment(70, "70-skills-and-mcp.md", "技能与 MCP", "技能加载与 MCP 使用规范")
        )

        val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")
    }

    data class BuiltinFragment(
        val number: Int,
        val fileName: String,
        val title: String,
        val subtitle: String
    )

    val customDir: File get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 全部片段：内置 9 个（标注是否已被覆盖）+ 自定义新增的数字片段，按数字升序。 */
    fun fragments(): List<PromptFragmentInfo> {
        val customByNumber = customFilesByNumber()
        val builtin = BUILTIN_FRAGMENTS.map { b ->
            PromptFragmentInfo(
                number = b.number,
                fileName = b.fileName,
                title = b.title,
                subtitle = b.subtitle,
                isBuiltin = true,
                hasCustom = customByNumber.containsKey(b.number),
                isProtected = b.number in PROTECTED_NUMBERS
            )
        }
        val extras = customByNumber
            .filterKeys { number -> BUILTIN_FRAGMENTS.none { it.number == number } }
            .map { (number, file) ->
                PromptFragmentInfo(
                    number = number,
                    fileName = file.name,
                    title = displayNameOf(file),
                    subtitle = "自定义新增片段",
                    isBuiltin = false,
                    hasCustom = true,
                    isProtected = false
                )
            }
        return (builtin + extras).sortedBy { it.number }
    }

    /** 内置默认内容（从 App 资源读，不受自定义覆盖影响）。 */
    fun builtinText(fileName: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
    }.getOrElse {
        FileLogger.w(TAG, "读取内置提示词失败 $fileName: ${it.message}")
        null
    }

    /** 该数字当前的自定义内容；没有自定义文件返回 null。 */
    fun customText(number: Int): String? = customFilesByNumber()[number]?.let { file ->
        runCatching { file.readText() }.getOrNull()
    }

    /**
     * 保存某数字的自定义片段。
     *
     * 覆盖内置片段时沿用内置文件名（保持 `00-identity.md` 这种可读命名）；新增片段用 `<NN>-<名称>.md`。
     * 同一数字已有别的自定义文件时先删掉，避免 [PromptFragmentResolver.numberedFragments] 因同数字多文件而取错。
     */
    fun save(number: Int, fileName: String, content: String): Result<Unit> {
        if (number in PROTECTED_NUMBERS) {
            return Result.failure(IllegalArgumentException("该片段是关键提示词，不允许修改"))
        }
        if (number !in 0..99) return Result.failure(IllegalArgumentException("数字需在 00~99 之间"))
        if (content.isBlank()) return Result.failure(IllegalArgumentException("内容不能为空"))
        return runCatching {
            customDir.mkdirs()
            customFilesByNumber()[number]?.takeIf { it.name != fileName }?.delete()
            File(customDir, fileName).writeText(content)
            FileLogger.i(TAG, "已保存自定义提示词片段 $fileName")
        }
    }

    /** 新建片段的目标文件名（把用户输入的名称规整成文件名安全的形式）。 */
    fun newFileName(number: Int, rawName: String): String {
        val slug = rawName.trim()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "custom" }
            .take(40)
        return String.format("%02d-%s.md", number, slug)
    }

    /** 删除某数字的自定义文件；返回是否删掉了东西（删掉即恢复内置默认）。 */
    fun deleteCustom(number: Int): Boolean {
        if (number in PROTECTED_NUMBERS) return false
        val file = customFilesByNumber()[number] ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** 是否处于「完全禁用内置提示词」状态（`prompts.custom/.no-builtin`）。 */
    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(customDir)

    private fun customFilesByNumber(): Map<Int, File> =
        PromptFragmentResolver.numberedFragments(customDir).toMap()

    private fun displayNameOf(file: File): String =
        NUMBERED.matchEntire(file.name)?.groupValues?.get(2).orEmpty().ifBlank { file.name }
}
