package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContainerSettingsRepo"

private val Context.containerDataStore by preferencesDataStore(
    name = "container_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 下载镜像页里已下载/已安装的镜像记录，跨重启保留。 */
@Serializable
data class DownloadedImageRecord(
    val entryId: String,
    val fileUri: String,
    /**
     * 下载当时的版本号（如 `3.21.8` / `12.15`）。
     * 目录版本会随上游刷新，而磁盘上的文件不会跟着变，靠它才能判断「已下载的是不是旧补丁」。
     * 空串表示升级前留下的旧记录，此时不参与「有新版」判定（宁可漏提示也不误报）。
     */
    val version: String = "",
    val installed: Boolean = false
)

private val json = Json { ignoreUnknownKeys = true }
private val profileSerializer = ListSerializer(ContainerProfile.serializer())
private val downloadedImageSerializer = ListSerializer(DownloadedImageRecord.serializer())

/**
 * 解码用户自定义 profile 列表。
 *
 * 返回 null 表示「磁盘上有值但无法解码」——调用方**必须保留原值**，绝不能用空表覆写：
 * 一次解码失败若当成空表写回，会把整份容器清单清掉（用户报的「导入的容器在列表里消失」）。
 * 只有 `raw` 为 null（从未写过）才按空表处理。
 */
internal fun decodeProfiles(raw: String?): List<ContainerProfile>? {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(profileSerializer, raw) }
        .getOrElse { e ->
            FileLogger.w(TAG, "容器 profile 解码失败，保留原值不覆写：${e.message}")
            null
        }
}

/** 把 profile 列表编码为持久化 JSON。 */
internal fun encodeProfiles(profiles: List<ContainerProfile>): String =
    json.encodeToString(profileSerializer, profiles)

/**
 * 把 [profile] 合并进 [current]：同 id 覆盖并挪到末尾，其余保持原样。
 *
 * createdAt 规则：传入非 0 用传入值；为 0 且原记录存在（编辑场景，表单重建丢了时间）保留原时间，
 * 否则用 [now]——保证编辑保存后不改变排序位置。
 */
internal fun mergeProfiles(
    current: List<ContainerProfile>,
    profile: ContainerProfile,
    now: Long
): List<ContainerProfile> {
    val existing = current.firstOrNull { it.id == profile.id }
    val final = when {
        profile.createdAt != 0L -> profile
        existing != null && existing.createdAt != 0L -> profile.copy(createdAt = existing.createdAt)
        else -> profile.copy(createdAt = now)
    }
    return current.filterNot { it.id == final.id } + final
}

/** 从 [current] 中删除 id 为 [id] 的 profile，其余保持原样。 */
internal fun removeProfile(current: List<ContainerProfile>, id: String): List<ContainerProfile> =
    current.filterNot { it.id == id }

/** 解码已下载镜像记录；返回 null 表示有值但无法解码，调用方须保留原值不覆写。 */
internal fun decodeDownloadedImages(raw: String?): List<DownloadedImageRecord>? {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(downloadedImageSerializer, raw) }
        .getOrElse { e ->
            FileLogger.w(TAG, "已下载镜像记录解码失败，保留原值不覆写：${e.message}")
            null
        }
}

/** 把已下载镜像记录编码为持久化 JSON。 */
internal fun encodeDownloadedImages(records: List<DownloadedImageRecord>): String =
    json.encodeToString(downloadedImageSerializer, records)

/** 按 entryId 覆盖合并：同 entryId 的旧记录替换为 [record] 并挪到末尾，其余保持原样。 */
internal fun mergeDownloadedImage(
    current: List<DownloadedImageRecord>,
    record: DownloadedImageRecord
): List<DownloadedImageRecord> = current.filterNot { it.entryId == record.entryId } + record

/** 从 [current] 中删除 entryId 为 [entryId] 的记录，其余保持原样。 */
internal fun removeDownloadedImageEntry(
    current: List<DownloadedImageRecord>,
    entryId: String
): List<DownloadedImageRecord> = current.filterNot { it.entryId == entryId }

/**
 * 持久化当前选中的容器 profile 与用户自定义 profile 列表。
 *
 * DataStore 用法与 [ThemeSettingsRepository] 一致（构造注入即可，无需 DI module）。
 * 无 [ACTIVE_PROFILE_ID_KEY] 时返回 [ContainerProfile.BUILTIN_ID]，等同改动前——默认内置 Alpine。
 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CUSTOM_PROFILES_KEY = stringPreferencesKey("custom_profiles_json")
        /** 远程工作区模式下本地 MCP stdio 等服务的运行容器；无值时默认内置 Alpine。 */
        val DEFAULT_CONTAINER_ID_KEY = stringPreferencesKey("default_container_profile_id")
        /** 首次启动是否已写入内置 Alpine 默认项；置位后用户删光列表不再自动补回。 */
        val INITIALIZED_KEY = booleanPreferencesKey("initialized")
        /** 「容器与镜像」页使用说明公告已展示内容的哈希；无值或与当前内容哈希不一致时重新弹出。 */
        val ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("container_announcement_shown_hash")
        /** 下载镜像页已下载/已安装的记录（JSON 列表）。 */
        val DOWNLOADED_IMAGES_KEY = stringPreferencesKey("downloaded_images_json")
    }

    /** 当前选中的 profile id；无值时默认内置 Alpine。 */
    val activeProfileIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** 远程模式下的默认容器 id；无值时默认内置 Alpine。 */
    val defaultContainerIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[DEFAULT_CONTAINER_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /**
     * 用户自定义 profile 列表（不含内置）。解析失败回退空列表——只读流没有「上一份」可沿用，
     * 但解码失败必须留痕（[decodeProfiles] 会记 WARN），便于下次复现时定位。
     */
    val customProfilesFlow: Flow<List<ContainerProfile>> = context.containerDataStore.data.map { prefs ->
        decodeProfiles(prefs[CUSTOM_PROFILES_KEY]) ?: emptyList()
    }

    /**
     * 当前选中的 profile。id 在列表中已不存在（被删除）时回退列表首项，列表为空回退内置 Alpine，
     * 保证调用方总能拿到一个可用 profile。
     */
    val activeProfileFlow: Flow<ContainerProfile> = combine(activeProfileIdFlow, customProfilesFlow) { id, profiles ->
        profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    suspend fun setActiveProfile(id: String) {
        context.containerDataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    suspend fun setDefaultContainerId(id: String) {
        context.containerDataStore.edit { it[DEFAULT_CONTAINER_ID_KEY] = id }
    }

    /**
     * 首次启动（或旧版本升级）兜底：把内置 Alpine 补入列表（若缺），保证列表始终有内置项。
     * 置位初始化标记后不再自动补回——用户后续删光列表由空态手动恢复。
     * 存量设备已配自定义镜像时同样补入（旧版列表始终含内置项，升级不能让它消失）。
     *
     * 存量数据无法解码时直接返回：不写列表、也不置位标记，留给下次启动重试（否则一次解码失败
     * 就把整份清单清空，正是「导入的容器消失」的成因）。
     */
    suspend fun ensureBuiltinDefault() {
        context.containerDataStore.edit { prefs ->
            if (prefs[INITIALIZED_KEY] == true) return@edit
            val current = decodeProfiles(prefs[CUSTOM_PROFILES_KEY]) ?: return@edit
            prefs[INITIALIZED_KEY] = true
            if (current.none { it.id == ContainerProfile.BUILTIN_ID }) {
                val merged = listOf(ContainerProfile.BUILTIN_ALPINE) + current
                prefs[CUSTOM_PROFILES_KEY] = encodeProfiles(merged)
                FileLogger.i(TAG, "ensureBuiltinDefault -> ${merged.map { it.id }}")
            }
        }
    }

    /** 已展示公告内容的哈希；无值表示从未展示过。 */
    val announcementShownHashFlow: Flow<String?> = context.containerDataStore.data.map { prefs ->
        prefs[ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markAnnouncementShown(hash: String) {
        context.containerDataStore.edit { it[ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** 已下载镜像记录列表（按 entryId 去重，最新覆盖旧的）。解析失败回退空列表（会记 WARN）。 */
    val downloadedImagesFlow: Flow<List<DownloadedImageRecord>> = context.containerDataStore.data.map { prefs ->
        decodeDownloadedImages(prefs[DOWNLOADED_IMAGES_KEY]) ?: emptyList()
    }

    suspend fun upsertDownloadedImage(record: DownloadedImageRecord) {
        context.containerDataStore.edit { prefs ->
            val current = decodeDownloadedImages(prefs[DOWNLOADED_IMAGES_KEY]) ?: return@edit
            val merged = mergeDownloadedImage(current, record)
            prefs[DOWNLOADED_IMAGES_KEY] = encodeDownloadedImages(merged)
            FileLogger.i(TAG, "upsertImage entryId=${record.entryId} -> ${merged.map { it.entryId }}")
        }
    }

    suspend fun removeDownloadedImage(entryId: String) {
        context.containerDataStore.edit { prefs ->
            val current = decodeDownloadedImages(prefs[DOWNLOADED_IMAGES_KEY]) ?: return@edit
            val remaining = removeDownloadedImageEntry(current, entryId)
            prefs[DOWNLOADED_IMAGES_KEY] = encodeDownloadedImages(remaining)
            FileLogger.i(TAG, "removeImage entryId=$entryId -> ${remaining.map { it.entryId }}")
        }
    }

    /**
     * 新增或覆盖同名 id 的自定义 profile。
     * createdAt 规则见 [mergeProfiles]（编辑保存不改变排序位置）。
     * 存量数据无法解码时直接返回，保留原值不覆写。
     */
    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        context.containerDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[CUSTOM_PROFILES_KEY]) ?: return@edit
            val merged = mergeProfiles(current, profile, System.currentTimeMillis())
            prefs[CUSTOM_PROFILES_KEY] = encodeProfiles(merged)
            FileLogger.i(TAG, "upsert id=${profile.id} -> ${merged.map { it.id }}")
        }
    }

    /** 删除 id 为 [id] 的 profile；存量数据无法解码时直接返回，保留原值不覆写。 */
    suspend fun deleteCustomProfile(id: String) {
        context.containerDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[CUSTOM_PROFILES_KEY]) ?: return@edit
            val remaining = removeProfile(current, id)
            prefs[CUSTOM_PROFILES_KEY] = encodeProfiles(remaining)
            FileLogger.i(TAG, "delete id=$id -> ${remaining.map { it.id }}")
        }
    }
}
