package com.aicode.feature.agent.domain.skill

/**
 * 技能数据源抽象接口，支持多渠道加载（本地目录、远程 URL 等）。
 */
interface SkillSource {
    /** 扫描并返回该数据源下当前所有合法的 Skill。 */
    fun listSkills(): List<Skill>

    /**
     * 同 [listSkills]，但读取失败（IO 异常、远程未连接）时返回 null，与「一条技能都没有」区分开：
     * 会把结果缓存起来的调用方据此不缓存失败。
     */
    fun listSkillsOrNull(): List<Skill>? = listSkills()

    /** 读取指定 skill 的完整指令正文；不存在或解析失败时返回 null。 */
    fun loadInstructions(name: String): String?
}
