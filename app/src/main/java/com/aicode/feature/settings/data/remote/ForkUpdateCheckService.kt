package com.aicode.feature.settings.data.remote

import android.content.Context
import android.net.Uri
import com.aicode.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本 fork 的更新源是否支持「更新通道」选择。自建接口只有一个发布序列，故恒为 false，
 * 关于页据此隐藏该设置项。推上游 PR 前：删除本常量，并拆掉 AboutSection 里引用它的 if 包裹。
 */
internal const val FORK_UPDATE_SUPPORTS_CHANNEL = false

/**
 * 本 fork 专属的检查更新数据源：把上游的 GitHub Releases 换成本 fork 站点的 id 版接口。
 *
 * 接口（无鉴权，UTF-8 JSON，正常恒返回 HTTP 200）：
 * - 首次 `GET {ENDPOINT}`：拿回发布 id 存下来，本次不弹窗（响应里 hasUpdate 恒为 true，只能用来取 id）；
 * - 之后 `GET {ENDPOINT}?id=<上次拿到的 id>`：只有 hasUpdate 为 true 才算真有新版本。
 *
 * 三条硬规则：首次（本地没 id）绝不弹窗；请求失败什么都不做（返回 Error，由调用方静默）；
 * 只要响应里带 id 就存下（无论有没有更新）。
 *
 * 与上游 [UpdateCheckService] 的差异只有「去哪问、拿回什么」：返回类型、弹窗 UI 与触发时机全部
 * 复用上游实现，由 SettingsViewModel.checkUpdate 调换数据源，弹窗按钮改开接口给的 APK 直链。
 *
 * 本文件是 fork 专属，推上游 PR 前必须删除；改回方式：
 * 1. 删除本文件；
 * 2. SettingsViewModel：去掉 forkUpdateCheckService 构造参数与 import，恢复注入 updateCheckService，
 *    checkUpdate 里改回 updateCheckService.checkForUpdate(currentVersionName(), updateCheckSettingsRepository.channel)，
 *    并删掉 NewVersion(..., downloadUrl = ...) 的 downloadUrl 实参；
 * 3. SettingsViewModel.UpdateCheckUiState.NewVersion：删掉 downloadUrl 字段；
 * 4. UpdateCheckService.UpdateInfo：删掉 downloadUrl 字段；
 * 5. UpdateCheckDialog：onOpenDownload 改回 onOpenRelease，按钮回到 githubReleaseUrl(state.latestTag) 与 R.string.about_download；
 * 6. MainActivity：onOpenDownload 改回 onOpenRelease(tag) 并补回 githubReleaseUrl import；
 * 7. AboutSection：拆掉 FORK_UPDATE_SUPPORTS_CHANNEL 的 if 包裹与 import；
 * 8. strings.xml（中英两份）删掉 fork_ 前缀的两条；docs-site/docs/guide/about.md 改回 GitHub Releases 的描述。
 * 一行定位：rg -n "FORK_UPDATE_SUPPORTS_CHANNEL|ForkUpdateCheckService|forkUpdateCheckService|fork_update_|downloadUrl"
 */
@Singleton
class ForkUpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** 拉取一次发布信息；失败或「没有新版本」都返回 [UpdateCheckResult.UpToDate] 之外的显式结果给调用方处置。 */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastId = prefs.getString(KEY_LAST_RELEASE_ID, null)?.takeIf { it.isNotBlank() }
            val url = if (lastId == null) ENDPOINT else "$ENDPOINT?id=${Uri.encode(lastId)}"

            val req = okhttp3.Request.Builder().url(url).get().build()
            SHARED_CLIENT.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use UpdateCheckResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@use UpdateCheckResult.Error(context.getString(R.string.about_parse_version_failed))
                if (obj.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == false) {
                    return@use UpdateCheckResult.Error(context.getString(R.string.about_network_error))
                }

                val id = obj.stringOrEmpty("id")
                // 无论有没有更新都存 id；released=false 时接口给的 id 为空，保持原值不清空。
                if (id.isNotBlank()) {
                    prefs.edit().putString(KEY_LAST_RELEASE_ID, id).apply()
                }

                val released = obj.booleanOrFalse("released")
                val hasUpdate = obj.booleanOrFalse("hasUpdate")
                // 首次没有 id，请求也没带 id，此时的 hasUpdate 恒为 true 不代表真有新版本 —— 只用来存 id。
                if (!released || !hasUpdate || lastId == null) {
                    return@use UpdateCheckResult.UpToDate
                }

                val name = obj.stringOrEmpty("name")
                val sizeText = obj.stringOrEmpty("sizeText")
                val note = obj.stringOrEmpty("note")
                UpdateCheckResult.NewVersion(
                    UpdateInfo(
                        latestTag = name,
                        changelog = composeChangelog(name = name, sizeText = sizeText, note = note),
                        updates = listOf(VersionUpdate(tag = name, changelog = note)),
                        downloadUrl = obj.stringOrEmpty("url").takeIf { it.isNotBlank() }
                    )
                )
            }
        }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
    }

    /** 弹窗正文：首行是版本名与安装包大小，空行后接接口给的完整更新记录（可能为空）。 */
    private fun composeChangelog(name: String, sizeText: String, note: String): String {
        val header = if (sizeText.isBlank()) {
            name
        } else {
            context.getString(R.string.fork_update_release_title, name, sizeText)
        }
        return if (note.isBlank()) header else "$header\n\n$note"
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private companion object {
        /** 本 fork 的发布信息接口，无鉴权、无参数（首次）。 */
        const val ENDPOINT = "https://rely-xfer.de5.net/dl/update"

        /** 沿用仓库既有的更新偏好文件（见 UpdateCheckSettingsRepository），只加一个自有键。 */
        const val PREFS_NAME = "update_check_prefs"
        const val KEY_LAST_RELEASE_ID = "fork_last_release_id"

        /** 与上游 UpdateCheckService 同款：只挂全局代理认证，其余走 OkHttp 默认超时（10s 连接/读取）。 */
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
