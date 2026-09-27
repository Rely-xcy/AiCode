package com.aicode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 画像分节：技术背景 / 工作偏好 / 沟通风格 / 环境与踩坑。 */
enum class ProfileSection(val key: String, val title: String) {
    TECH("tech", "技术背景"),
    WORKFLOW("workflow", "工作偏好"),
    COMMUNICATION("communication", "沟通风格"),
    ENVIRONMENT("environment", "环境与踩坑");

    companion object {
        fun fromKey(raw: String?): ProfileSection? = values().firstOrNull { it.key == raw }
    }
}

/** 条目状态：生效 / 已被更新的结论取代（保留痕迹，可回查）。 */
object ProfileStatus {
    const val ACTIVE = "ACTIVE"
    const val SUPERSEDED = "SUPERSEDED"
}

/**
 * 用户画像条目。
 *
 * 与记忆（memory）分开存：记忆是「一条条互不相关的笔记」，画像是「同一件事的最新结论」——
 * 所以这里以 `(section, entry_key)` 为身份，新证据写入时把旧条目置为
 * [ProfileStatus.SUPERSEDED] 并指向新条目，而不是像记忆那样同名直接跳过。
 */
@Entity(
    tableName = "profile_entries",
    indices = [Index(value = ["section", "status"])]
)
data class ProfileEntryEntity(
    @PrimaryKey val id: String,
    /** 分节 key，见 [ProfileSection.key]。 */
    @ColumnInfo(name = "section") val section: String,
    /** 稳定标识（如 `build_command`）；同一 (section, entryKey) 只会有一条 ACTIVE。 */
    @ColumnInfo(name = "entry_key") val entryKey: String,
    @ColumnInfo(name = "value") val value: String,
    /** 支撑该结论的原话/上下文，用于用户核对与回查。 */
    @ColumnInfo(name = "evidence") val evidence: String = "",
    /** 模型给出的置信度 0~1；注入时按它过滤低可信条目。 */
    @ColumnInfo(name = "confidence") val confidence: Float = 0.5f,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String? = null,
    @ColumnInfo(name = "status") val status: String = ProfileStatus.ACTIVE,
    /** 被哪条新条目取代（仅 SUPERSEDED 时有值）。 */
    @ColumnInfo(name = "superseded_by") val supersededBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
