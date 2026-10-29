package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 更新通道。[wireName] 是与自建更新源 `/dl/update?channel=` 约定的通道名，
 * 与后台「上传时按包名/版本名自动推通道」的取值一致。
 */
enum class UpdateChannel(val wireName: String) {
    STABLE("stable"),
    BETA("beta"),
    RC("rc"),
    DEV("dev"),
    DEBUG("debug");

    companion object {
        /**
         * 解析存储值，无法识别时返回 null。旧版本把 beta 通道存成 `LATEST`，改名后必须映射回
         * [BETA]——否则老用户的存量值会变成非法值，被换回包名推导出的默认通道。
         */
        fun fromStored(value: String?): UpdateChannel? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null
            if (raw.equals("latest", ignoreCase = true)) return BETA
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }

        /**
         * 用户从未设置过时的默认通道，按包名后缀推导，与后台推通道规则一致：
         * `.beta` → [BETA]、`.debug` → [DEBUG]、`.dev` → [DEV]，其余一律 [STABLE]。
         */
        fun defaultFor(applicationId: String): UpdateChannel = when {
            applicationId.endsWith(".beta") -> BETA
            applicationId.endsWith(".debug") -> DEBUG
            applicationId.endsWith(".dev") -> DEV
            else -> STABLE
        }
    }
}

/**
 * 「自动检查更新」偏好存储：开关、更新通道、上次检查时间统一存 SharedPreferences
 * （键值文件，非数据库）。检查时间不写数据库，但也不放 cacheDir——缓存目录可能被
 * 系统清理导致「每天一次」失效，持久化到 SharedPreferences 更可靠。
 */
@Singleton
class UpdateCheckSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)

    /** 自动检查更新开关，默认开启。 */
    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * 更新通道。没存过（或存了无法识别的值）时按包名推导默认值（见 [UpdateChannel.defaultFor]）；
     * 读到旧值 `LATEST` 时顺手写回 `BETA`，完成一次性迁移。
     */
    var channel: UpdateChannel
        get() {
            val stored = prefs.getString(KEY_CHANNEL, null)
            if (stored.isNullOrBlank()) return UpdateChannel.defaultFor(context.packageName)
            val parsed = UpdateChannel.fromStored(stored)
                ?: return UpdateChannel.defaultFor(context.packageName)
            if (parsed.name != stored) prefs.edit().putString(KEY_CHANNEL, parsed.name).apply()
            return parsed
        }
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /**
     * 更新包下载镜像源：留空表示直连 GitHub。默认走 gh-proxy 的 CDN 节点
     * （实测整包 sha256 与 GitHub API 的 digest 一致，首字节延迟也最低）。
     */
    var downloadMirror: String
        get() = prefs.getString(KEY_DOWNLOAD_MIRROR, DEFAULT_DOWNLOAD_MIRROR) ?: DEFAULT_DOWNLOAD_MIRROR
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_MIRROR, value.trim()).apply()
        }

    /**
     * 上次检查完成的时间戳（[System.currentTimeMillis]，0 = 从未检查）。
     *
     * 用时间戳而不是「今天几号」：跨时区、用户改系统时间、跨年都不会把“24 小时”算错。
     */
    val lastCheckedAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)

    /** 记录检查完成。只在「问成了」时调，失败不记——否则用户点一次失败要等一整天。 */
    fun markChecked(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECKED_AT, now).apply()
    }

    /** 自动检查是否到了该跑的时间（手动检查不受此限）。 */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheckedAt >= CHECK_INTERVAL_MS

    /**
     * 把版本与更新信息写入 `~/.aicode/update-info.json`（宿主 filesDir/aicode/），
     * 供容器内 AI 读取（当前版本、更新通道、最近检查时间、最新版本与逐版本更新日志）。
     * 写入失败静默，不影响检测流程。
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "aicode").apply { mkdirs() }
            val obj = JSONObject()
            obj.put("currentVersion", currentVersion)
            obj.put("channel", channel.name.lowercase())
            obj.put("lastCheckedAt", java.time.LocalDateTime.now().toString())
            when (result) {
                is UpdateCheckResult.UpToDate -> {
                    obj.put("status", "upToDate")
                    obj.put("hasUpdate", false)
                }
                is UpdateCheckResult.NewVersion -> {
                    obj.put("status", "newVersion")
                    obj.put("hasUpdate", true)
                    obj.put("latestVersion", result.info.latestTag)
                    val updates = org.json.JSONArray()
                    result.info.updates.forEach { u ->
                        updates.put(
                            JSONObject().apply {
                                put("tag", u.tag)
                                put("changelog", u.changelog)
                            }
                        )
                    }
                    obj.put("updates", updates)
                }
                is UpdateCheckResult.Error -> {
                    // 不能写 hasUpdate:false——读这份文件的 AI 会把「没查成」当成「已是最新」
                    obj.put("status", "error")
                    obj.put("error", result.message)
                }
            }
            File(dir, "update-info.json").writeText(obj.toString())
        }
    }

    private companion object {
        const val KEY_ENABLED = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_DOWNLOAD_MIRROR = "download_mirror"

        /** 自动检查间隔：24 小时。 */
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/** 默认下载镜像源：gh-proxy 的 CDN 节点。 */
const val DEFAULT_DOWNLOAD_MIRROR = "https://cdn.gh-proxy.com/"

/** 备选镜像源（设置页快捷项）：gh-proxy 的 IPv6 优选节点。 */
const val ALT_DOWNLOAD_MIRROR = "https://v6.gh-proxy.org/"

/** 镜像源是否合法：留空（直连）或以 http/https 开头。 */
fun isValidDownloadMirror(value: String): Boolean {
    val v = value.trim()
    return v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")
}

/**
 * 把 GitHub 原始下载地址套上镜像前缀。
 *
 * 留空或非法（非 http/https）时原样返回直连地址；含 `{url}` 占位符时按占位符替换，
 * 否则按 gh-proxy 系约定做前缀拼接：`https://cdn.gh-proxy.com/https://github.com/...`。
 */
fun buildDownloadUrl(original: String, mirror: String): String {
    val m = mirror.trim()
    if (!isValidDownloadMirror(m) || m.isEmpty()) return original
    return if (m.contains("{url}")) m.replace("{url}", original) else m.trimEnd('/') + "/" + original
}
