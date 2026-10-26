package com.aicode.feature.agent.domain.tool

/**
 * 把工具的 [AgentTool.useWhen] / [AgentTool.avoidWhen] / [AgentTool.successCriteria] 渲染成
 * 提示词里的工具清单（`{{AICODE_TOOLS}}`）。
 *
 * 为什么要单独一层：同一份清单要同时供主代理基线片段、自定义片段与子代理提示词使用。任何一处手写
 * 都会随工具实现漂移，而工具作者本来就在改工具，让他们在同一处顺手声明才守得住。渲染保持纯函数、
 * 只由工具字段拼成，是为了让同样的输入逐字节稳定——system prompt 抖动会打断 KV 缓存。
 *
 * 只输出至少声明了一项的工具，顺序按传入顺序（注册表顺序）原样保留。
 */
internal fun renderToolGuide(tools: List<AgentTool>): String {
    return tools.mapNotNull { tool ->
        val segments = listOfNotNull(
            tool.useWhen.declared("适用"),
            tool.avoidWhen.declared("不适用"),
            tool.successCriteria.declared("成功判定")
        )
        if (segments.isEmpty()) null else "- ${tool.name}：" + segments.joinToString("；")
    }.joinToString("\n")
}

/**
 * 声明的字段渲染为「前缀 + 内容」；未声明（空白串）返回 null。
 *
 * 用 null 而不是空串，是为了让 [listOfNotNull] 少一段——否则悬空的分隔符会留在清单里，
 * 模型会读到「不适用」后面什么都没有这种半截句子。
 */
private fun String.declared(prefix: String): String? {
    val text = trim()
    return if (text.isEmpty()) null else "$prefix $text"
}
