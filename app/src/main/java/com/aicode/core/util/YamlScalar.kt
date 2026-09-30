package com.aicode.core.util

/**
 * YAML frontmatter 里字符串值的转义，供记忆文件与用户提示词文件共用。
 *
 * **白名单**策略：只有字母数字、空格、`_ - . /` 与非 ASCII（中日韩等）才裸写，
 * 其余一律双引号并转义。黑名单（只拦 `:` `#` 与引号）会漏 `*` `!` `&` `%` `@`
 * 反引号 `>` `|` `?` `,` 与真实 TAB——这些字符出现在标量开头时是 YAML 的
 * 锚点/别名/标签/指令等指示符，解析直接抛异常；而调用方普遍把解析异常
 * 当成「frontmatter 为空」，于是整条元数据静默消失（记忆的描述/来源、
 * 用户提示词的名字与启用状态都会这样丢）。
 */
object YamlScalar {
    /** 允许裸写的字符集：ASCII 字母数字 + 常见安全符号 + 非 ASCII（中文等）。 */
    private val BARE = Regex("^[A-Za-z0-9 _\\-./\\u0080-\\uFFFF]+$")

    fun quote(value: String): String {
        val bare = value.isNotBlank() &&
            !value.startsWith(" ") && !value.endsWith(" ") &&
            BARE.matches(value)
        if (bare) return value

        val escaped = buildString {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\x%02x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }
}
