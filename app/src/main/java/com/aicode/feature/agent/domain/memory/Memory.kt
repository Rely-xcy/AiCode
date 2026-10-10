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
 * @param source 写入来源（auto-distill / pre-fold / model-tool）；空串表示未标注（旧文件）
 * @param createdAt 创建时间（epoch millis）；0 表示未标注（旧文件），此时回退用文件修改时间
 * @param hitCount 被注入进上下文的会话数；0 表示从未被用过
 * @param lastHitAt 最近一次被注入/读取的时间（epoch millis）；0 表示从未
 * @param pinned 置顶：为 true 时每轮无条件注入，不参与话题相关度竞争（用户画像这类
 *   跨项目偏好常被项目记忆的字面分挤掉，置顶给它们一个稳定位置）；缺省 false，旧文件无此字段
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val kind: MemoryKind = MemoryKind.NOTE,
    val source: String = "",
    val createdAt: Long = 0L,
    val hitCount: Int = 0,
    val lastHitAt: Long = 0L,
    val pinned: Boolean = false
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
