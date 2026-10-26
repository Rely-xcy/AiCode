package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话工作状态：本会话「在做什么、做到哪、定过什么」的持续更新摘要。
 *
 * 与消息历史分开存：消息会被上下文压缩折掉，而这份状态每轮注入系统提示词，
 * 压缩不会让它丢失。与长期记忆（用户画像）也分开：那份记「用户是谁」，
 * 这份记「当前任务」，两者的更新时机与判据都不同。
 */
@Entity(tableName = "session_briefs")
data class SessionBriefEntity(
    @PrimaryKey val sessionId: String,
    /** Markdown 正文（目标 / 已定决策 / 当前进展 / 约束 / 下一步），限长见提取侧。 */
    val content: String,
    /** 最后一次自动或手动更新的时间。 */
    val updatedAt: Long
)
