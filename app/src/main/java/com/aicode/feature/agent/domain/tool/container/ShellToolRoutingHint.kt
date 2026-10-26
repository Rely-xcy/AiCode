package com.aicode.feature.agent.domain.tool.container

/**
 * 模型用 `Bash` 跑了一条「自带工具明显更合适」的命令时，在结果末尾追加一行提示。
 *
 * 为什么放在结果里而不是系统提示：模型是在**这一处**做决策的，提示贴着决策点最有效；
 * 常驻全文只会稀释注意力，还会把每轮前缀撑长。因此只覆盖判定最确定的几种单条命令，
 * 带管道 / 重定向 / 多语句（语义已变）时一律不猜，宁可不提示。
 */
internal object ShellToolRoutingHint {

    /** 返回要追加的提示行；不需要提示时返回 null。 */
    fun forCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it in "|;&><" }) return null
        val first = trimmed.substringBefore(' ').substringBefore('\t')
        return when (first) {
            "cat" -> "提示：读文件用 readFile 更好——返回带行号、超长自动截断并给回读路径，比 cat 省 token。"
            "grep", "egrep", "fgrep", "rg", "ack" ->
                "提示：找内容用 search 更好——返回 file:line 且能按 glob 过滤，结果更短。"
            "ls" -> "提示：列目录用 list 更好——结构化输出，可递归并限制层级。"
            "sed" -> if (trimmed.contains("-n")) {
                "提示：按行区间读用 readFile 的 start_line / end_line 更好——带行号且不受 sed 语法限制。"
            } else {
                null
            }
            else -> null
        }
    }

    /** 把提示拼到结果末尾；不需要提示时原样返回。 */
    fun appendTo(command: String, output: String): String {
        val hint = forCommand(command) ?: return output
        return if (output.isEmpty()) hint else "$output\n\n$hint"
    }
}
