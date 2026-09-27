package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 自定义系统提示词的注入位置。 */
enum class SystemPromptMode {
    /** 关闭：内置提示词原样使用。 */
    OFF,

    /** 注入到最前面，模型最先读到（相当于重写身份层）。 */
    PREPEND,

    /** 注入到内置静态片段之后、动态片段（技能/记忆/子代理/项目规则/工作区/时间/任务）之前，保住静态前缀的 KV 缓存。 */
    SUFFIX
}

data class CustomSystemPrompt(
    val mode: SystemPromptMode,
    val body: String
)

/**
 * 用户自定义系统提示词（`~/.aicode/prompts.custom/system.md`）。
 *
 * 这是**注入层**而不是替换层：内置的 9 个静态片段一个都不暴露给用户改，用户写的内容作为一个
 * 带优先级声明的独立块注入，内置层完整保留——这样用户拿到控制权的同时，工具可发现性不会被牺牲
 * （若允许覆盖 `60-tools-and-paths.md`，App 升级后 AI 看到的工具定义就可能与实际不一致）。
 *
 * 文件放在 `prompts.custom/` 是刻意的：[PromptFragmentResolver.numberedFragments] 只认
 * `<两位数字>-<名称>.md`，非数字文件名天然被忽略，因此不会干扰既有的片段覆盖机制。
 *
 * 刻意不缓存、也不做内容过滤：不缓存是为了保存后下一轮对话立即生效（不用重启 App）；
 * 不过滤是因为这是用户自己设备上的提示词，只有 [BODY_CHAR_LIMIT] 这个防呆上限，
 * 超限一律拒绝保存与注入，绝不静默截断（截断会悄悄改坏用户看不见的提示词）。
 */
@Singleton
class CustomSystemPromptStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    companion object {
        private const val TAG = "CustomSystemPromptStore"
        private const val FILE_NAME = "system.md"

        /**
         * 防呆上限，不是内容过滤器：远高于任何手写提示词，只用来防止误导入超大文件
         * （或 agent 循环写文件）把上下文窗口撑爆。
         */
        const val BODY_CHAR_LIMIT = 100_000
    }

    val file: File get() = File(File(containerInstaller.aicodeDir, "prompts.custom"), FILE_NAME)

    val isEmpty: Boolean get() = !file.exists()

    /** 读文件；不存在或不可读时返回 OFF 空内容。 */
    fun load(): CustomSystemPrompt {
        if (!file.exists()) return CustomSystemPrompt(SystemPromptMode.OFF, "")
        return runCatching { parse(file.readText()) }.getOrElse {
            FileLogger.w(TAG, "读取 $FILE_NAME 失败: ${it.message}")
            CustomSystemPrompt(SystemPromptMode.OFF, "")
        }
    }

    /** 原子写：先写 `.tmp` 再 rename，避免写到一半被读到半截文件。 */
    fun save(prompt: CustomSystemPrompt): Result<Unit> {
        if (isOverLimit(prompt.body)) {
            return Result.failure(IllegalArgumentException("内容超过 $BODY_CHAR_LIMIT 字符上限"))
        }
        return runCatching {
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(serialize(prompt))
            if (!tmp.renameTo(target)) {
                target.writeText(serialize(prompt))
                tmp.delete()
            }
        }
    }

    /** 删除自定义系统提示词（恢复默认）。 */
    fun clear(): Result<Unit> = runCatching {
        if (file.exists()) file.delete()
    }

    fun isOverLimit(body: String): Boolean = body.trim().length > BODY_CHAR_LIMIT

    /**
     * 渲染注入块；OFF / 空内容 / 超限都返回 null（此时内置提示词保持原样）。
     *
     * 块首声明优先级关系，明确指向默认规则所在位置（PREPEND 时在下方、SUFFIX 时在上方），
     * 并说明工具与技能能力依旧可用——避免模型把这层当成"只按这段执行"而丢掉工具用法。
     */
    fun injectionBlock(prompt: CustomSystemPrompt): String? {
        if (prompt.mode == SystemPromptMode.OFF) return null
        val body = prompt.body.trim()
        if (body.isEmpty() || isOverLimit(body)) return null
        val defaultRulesPosition = if (prompt.mode == SystemPromptMode.PREPEND) "下方" else "上方"
        return "[System] 用户自定义系统提示词（用户手写，优先级最高。与${defaultRulesPosition}默认规则冲突时以本节为准；" +
            "默认规则里描述的工具、技能、记忆等能力依然可用，不要因此忽略）：\n" + body
    }

    /** 极简 frontmatter 解析：只认 `mode` 一个键；无 frontmatter 的手写文件按 OFF + 全文正文处理。 */
    fun parse(source: String): CustomSystemPrompt {
        val lines = source.split("\n")
        if (lines.firstOrNull()?.trim() != "---") {
            return CustomSystemPrompt(SystemPromptMode.OFF, source)
        }
        val closeIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closeIndex < 0) return CustomSystemPrompt(SystemPromptMode.OFF, source)
        val absClose = closeIndex + 1
        var mode = SystemPromptMode.OFF
        lines.subList(1, absClose).forEach { raw ->
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            if (line.substring(0, colon).trim().equals("mode", ignoreCase = true)) {
                mode = parseMode(line.substring(colon + 1).trim().trim('"'))
            }
        }
        val body = lines.drop(absClose + 1).joinToString("\n").dropWhile { it == '\n' || it == '\r' }
        return CustomSystemPrompt(mode, body)
    }

    fun serialize(prompt: CustomSystemPrompt): String = buildString {
        append("---\n")
        append("mode: \"").append(fileValueOf(prompt.mode)).append("\"\n")
        append("---\n\n")
        append(prompt.body)
        if (!endsWith("\n")) append("\n")
    }

    /** 兼容手写：`top`/`bottom` 与大小写都接受，未知值一律按 OFF。 */
    fun parseMode(raw: String?): SystemPromptMode = when (raw?.trim()?.lowercase()) {
        "prepend", "top" -> SystemPromptMode.PREPEND
        "suffix", "bottom" -> SystemPromptMode.SUFFIX
        else -> SystemPromptMode.OFF
    }

    private fun fileValueOf(mode: SystemPromptMode): String = when (mode) {
        SystemPromptMode.OFF -> "off"
        SystemPromptMode.PREPEND -> "prepend"
        SystemPromptMode.SUFFIX -> "suffix"
    }
}
