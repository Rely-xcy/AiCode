package com.aicode.feature.settings.presentation

/** 技能的保存 / 导入目标来源。 */
enum class SkillSource {
    /** 全局技能（App 私有目录，跨项目共享）。 */
    GLOBAL,

    /** 当前项目工作区技能。 */
    PROJECT
}
