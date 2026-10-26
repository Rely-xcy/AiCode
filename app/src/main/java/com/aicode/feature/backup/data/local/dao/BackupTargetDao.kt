package com.aicode.feature.backup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupTargetDao {
    @Query("SELECT * FROM backup_targets ORDER BY createdAt ASC, rowid ASC")
    fun getAll(): Flow<List<BackupTargetEntity>>

    /** 一次性快照：备份导出要按流式打包，不能挂在 Flow 上等首个值。 */
    @Query("SELECT * FROM backup_targets ORDER BY createdAt ASC, rowid ASC")
    suspend fun getAllOnce(): List<BackupTargetEntity>

    @Query("SELECT * FROM backup_targets WHERE id = :id")
    suspend fun getById(id: String): BackupTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BackupTargetEntity)

    @Query("DELETE FROM backup_targets WHERE id = :id")
    suspend fun deleteById(id: String)
}
