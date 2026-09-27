package com.aicode.feature.workspace.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "remote_mounts",
    foreignKeys = [
        ForeignKey(
            entity = RemoteConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("connectionId")]
)
data class RemoteMountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    val autoConnect: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    /** 列表拖拽排序序号；新建时取当前最大值 +1。 */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)
