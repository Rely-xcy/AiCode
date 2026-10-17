package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.SessionBriefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionBriefDao {

    /** 单会话工作状态流；行不存在时发射 null。 */
    @Query("SELECT * FROM session_briefs WHERE sessionId = :sessionId LIMIT 1")
    fun getBySession(sessionId: String): Flow<SessionBriefEntity?>

    @Query("SELECT * FROM session_briefs WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionOnce(sessionId: String): SessionBriefEntity?

    /** 冷启动预热：把全部会话的状态铺进注入模块的内存快照。 */
    @Query("SELECT * FROM session_briefs")
    suspend fun getAllOnce(): List<SessionBriefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionBriefEntity)

    @Query("DELETE FROM session_briefs WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
