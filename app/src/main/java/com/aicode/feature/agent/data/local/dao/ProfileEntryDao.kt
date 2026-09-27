package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.ProfileEntryEntity
import com.aicode.feature.agent.data.local.entity.ProfileStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileEntryDao {

    @Query("SELECT * FROM profile_entries WHERE status = 'ACTIVE' ORDER BY section ASC, updated_at DESC")
    fun activeEntries(): Flow<List<ProfileEntryEntity>>

    @Query("SELECT * FROM profile_entries WHERE status = 'ACTIVE' ORDER BY section ASC, updated_at DESC")
    suspend fun activeEntriesOnce(): List<ProfileEntryEntity>

    /** 同一 (section, entryKey) 的当前生效条目；写入新证据前用它判断是新增还是修正。 */
    @Query(
        "SELECT * FROM profile_entries WHERE section = :section AND entry_key = :entryKey " +
            "AND status = 'ACTIVE' LIMIT 1"
    )
    suspend fun findActive(section: String, entryKey: String): ProfileEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ProfileEntryEntity)

    /** 把旧结论标记为被取代：保留痕迹，用户可回查「以前是怎么说的」。 */
    @Query(
        "UPDATE profile_entries SET status = '" + ProfileStatus.SUPERSEDED + "', " +
            "superseded_by = :newId, updated_at = :now WHERE id = :oldId"
    )
    suspend fun markSuperseded(oldId: String, newId: String, now: Long)

    /** 某个标识的完整变更历史（新的在前）。 */
    @Query("SELECT * FROM profile_entries WHERE entry_key = :entryKey ORDER BY updated_at DESC")
    suspend fun historyOf(entryKey: String): List<ProfileEntryEntity>

    @Query("DELETE FROM profile_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM profile_entries")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM profile_entries WHERE status = 'ACTIVE'")
    suspend fun activeCount(): Int
}
