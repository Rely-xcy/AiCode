package com.aicode.feature.agent.domain.memory

import java.io.File

/**
 * 解析后的单个 Memory 模型。
 *
 * @param name 记忆名称（供大模型调用的唯一标识，通常对应文件名不含扩展名）
 * @param description 记忆描述（一句话摘要，注入到系统提示词中）
 * @param scope 记忆的作用域（GLOBAL 或 PROJECT）
 * @param file 记忆对应的本地文件
 * @param content 记忆正文（剥离 Frontmatter 后的详细内容）
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val kind: MemoryKind = MemoryKind.NOTE
)

enum class MemoryScope {
    GLOBAL, PROJECT
}

/**
 * 记忆类型：[NOTE] 是模型显式记录的内容，[PROFILE] 是从历史对话自动沉淀的长期结论。
 *
 * 仅作 frontmatter 的 kind 字段落盘，[NOTE] 不写该字段——保持既有记忆文件字节不变。
 */
enum class MemoryKind {
    NOTE, PROFILE
}
