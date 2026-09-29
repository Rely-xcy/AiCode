package com.aicode.feature.agent.domain.prompt

/**
 * 用户自定义提示词。
 *
 * 与内置片段（`assets/prompts/` 下按编号排序的 00/10/15…）不同，它由用户在 App 里创建，
 * 不参与编号排序，只决定「插在哪」——见 [UserPromptPosition]。
 *
 * @param id 稳定标识，落盘时作为文件名（不含扩展名）
 * @param name 用户起的名字，用于列表显示
 * @param position 注入位置
 * @param content 提示词正文
 * @param enabled 是否参与注入；与 [position] 相互独立——关掉它只停注入，不动位置
 */
data class UserPrompt(
    val id: String,
    val name: String,
    val position: UserPromptPosition,
    val content: String,
    val enabled: Boolean = true
)

/** 作用域：全局（跟 App 走）与项目（跟工作区走）。 */
enum class UserPromptScope {
    GLOBAL,
    PROJECT
}

/**
 * 注入位置。
 *
 * 内置片段的顺序由编号固定，用户提示词不掺进那套编号，而是走两个独立槽位：
 * - [BEFORE_ALL]：拼在所有提示词之前（基线之前）
 * - [AFTER_SYSTEM]：拼在系统提示词之后（动态段之后）
 * - [OFF]：旧版用来表示「不注入」，现已由 [UserPrompt.enabled] 承担；
 *   读旧文件时会转成 enabled=false，写盘不再产生该值（保留枚举值仅为兼容读取）。
 */
enum class UserPromptPosition {
    BEFORE_ALL,
    AFTER_SYSTEM,
    OFF;

    companion object {
        fun fromStorage(raw: String?): UserPromptPosition = when (raw?.trim()?.lowercase()) {
            "before_all" -> BEFORE_ALL
            "after_system" -> AFTER_SYSTEM
            else -> OFF
        }
    }

    fun toStorage(): String = when (this) {
        BEFORE_ALL -> "before_all"
        AFTER_SYSTEM -> "after_system"
        OFF -> "off"
    }
}
