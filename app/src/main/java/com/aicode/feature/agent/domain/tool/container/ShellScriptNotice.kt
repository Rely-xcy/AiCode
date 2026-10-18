package com.aicode.feature.agent.domain.tool.container

/**
 * 脚本执行时给授权弹窗补的说明。
 *
 * 守卫判定的是**这条命令本身**：`sh x.sh` 只看到 `sh x.sh`，脚本内部的管道、多语句、危险操作
 * 全在它视野之外。所以：
 * - `sh -c "…"` / `bash -c '…'` 这类**内联脚本就在命令里**，直接把脚本正文展示给用户；
 * - `sh x.sh` / `python a.py` 这类**外部脚本文件**，明确告知「守卫看不到脚本内容，执行前请自行
 *   确认」，避免用户以为「守卫已经检查过了」。
 *
 * 纯字符串解析，不读文件、不阻塞。
 */
internal object ShellScriptNotice {

    private val INTERPRETERS = setOf(
        "sh", "bash", "zsh", "dash", "ash", "busybox", "python", "python3", "node", "perl", "ruby"
    )

    /** 返回要追加到弹窗详情里的说明；不是脚本执行返回 null。 */
    fun forCommand(command: String): String? {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val program = tokens.first().substringAfterLast('/')
        if (program !in INTERPRETERS) return null

        val args = tokens.drop(1)
        val inlineIndex = args.indexOfFirst { it == "-c" }
        if (inlineIndex >= 0) {
            val code = args.drop(inlineIndex + 1).joinToString(" ").trim().trim('\'', '"', '`')
            if (code.isEmpty()) return null
            val shown = if (code.length <= MAX_INLINE_CHARS) code else code.take(MAX_INLINE_CHARS) + "…"
            return "⚠ 内联脚本（守卫无法静态判定内部构造，请逐行确认）：\n$shown"
        }

        val script = args.firstOrNull { !it.startsWith("-") } ?: return null
        return "⚠ 脚本执行：守卫只看到 `$program $script`，无法检查脚本内部。" +
            "请在授权前自行确认「$script」里没有管道、多语句或危险操作。"
    }

    private const val MAX_INLINE_CHARS = 800
}
