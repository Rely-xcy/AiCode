package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.SessionGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionGoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: SessionGoalEntity)

    @Query("SELECT * FROM session_goals WHERE sessionId = :sessionId")
    suspend fun getBySessionOnce(sessionId: String): SessionGoalEntity?

    /** 备份导出用：一次取全部目标（目标与会话一一对应，量级有限）。 */
    @Query("SELECT * FROM session_goals")
    suspend fun getAllOnce(): List<SessionGoalEntity>

    @Query("SELECT * FROM session_goals WHERE sessionId = :sessionId")
    fun getBySession(sessionId: String): Flow<SessionGoalEntity?>

    @Query("DELETE FROM session_goals WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
