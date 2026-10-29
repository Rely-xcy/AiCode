package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.R
import com.aicode.feature.settings.data.repository.UpdateChannel
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本 fork 的更新源是否支持「更新通道」选择。自建接口按通道分槽发布，故为 true——
 * 关于页据此展示通道设置项。
 */
internal const val FORK_UPDATE_SUPPORTS_CHANNEL = true

/**
 * 本 fork 专属的检查更新数据源：把上游的 GitHub Releases 换成本 fork 站点的接口。
 *
 * 接口（无鉴权，UTF-8 JSON，正常恒返回 HTTP 200）：
 * `GET {ENDPOINT}?channel=<通道>&code=<本机 versionCode>` 一次拿回该通道发布版的完整信息
 * （versionName / title / sizeText / date / noteShort / url / sha256）。
 * 带上 `code` 后服务端会把 versionCode 与本机比好，给出 `hasUpdate` 与 `direction`。
 *
 * **判新只有一条规则**：以服务端结论为准，客户端不再自己算（见 [shouldReportUpdate]）。
 * 结果里的 asset 直接来自服务端给的直链，弹窗据此走应用内下载安装。
 */
@Singleton
class ForkUpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * 按通道拉一次发布版，判断要不要提示更新。
     *
     * [localVersionCode] 是本机 versionCode，传给服务端后由它判新旧；[channel] 决定问哪个发布槽。
     */
    suspend fun checkForUpdate(
        localVersionCode: Int,
        channel: UpdateChannel
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder()
                .url("$ENDPOINT?channel=${channel.wireName}&code=$localVersionCode")
                .get()
                .build()
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

                val report = shouldReportUpdate(
                    released = obj.booleanOrFalse("released"),
                    hasUpdate = obj.booleanOrFalse("hasUpdate"),
                    direction = obj.stringOrEmpty("direction")
                )
                if (!report) {
                    return@use UpdateCheckResult.UpToDate
                }

                // 弹窗标题用服务端给的人类可读版本号（versionName / title），后面跟发布日期；
                // 都缺失时才退回包名（去掉 .apk）。
                val versionName = obj.stringOrEmpty("versionName")
                    .ifBlank { obj.stringOrEmpty("title") }
                    .ifBlank { obj.stringOrEmpty("name").trim().removeSuffix(".apk") }
                val label = listOf(versionName.trim(), obj.stringOrEmpty("date").trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                // 优先用服务端裁好的前 16 行 noteShort；缺字段时退回整份 note，由本地按行截断。
                val note = obj.stringOrEmpty("noteShort").ifBlank { obj.stringOrEmpty("note") }
                val url = downloadUrlOf(obj)
                // 服务端给的是裸 64 位小写 hex；长度不对就不当摘要用，让下载器跳过校验而不是必然失败。
                val sha256 = obj.stringOrEmpty("sha256").takeIf { it.length == 64 }
                val size = obj.longOrZero("size")
                // 拿不到直链就不给 asset：弹窗会退回「浏览器打开」，而不是进一个必然失败的内嵌下载。
                val asset = url?.let {
                    UpdateAsset(
                        name = obj.stringOrEmpty("name").ifBlank { "aicode-update.apk" },
                        url = it,
                        size = size,
                        sha256 = sha256
                    )
                }
                UpdateCheckResult.NewVersion(
                    UpdateInfo(
                        latestTag = label,
                        changelog = composeChangelog(
                            label = label,
                            sizeText = obj.stringOrEmpty("sizeText"),
                            note = note
                        ),
                        updates = listOf(VersionUpdate(tag = label, changelog = note, asset = asset)),
                        downloadUrl = url
                    )
                )
            }
        }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
    }

    /** 更新包直链：优先用服务端给的 `url`（已带 `?dl=1`），缺字段时按 `path` 拼站点地址。 */
    private fun downloadUrlOf(obj: JsonObject): String? {
        val url = obj.stringOrEmpty("url")
        if (url.isNotBlank()) return url
        val path = obj.stringOrEmpty("path")
        if (path.isBlank()) return null
        return SITE_BASE + path + "?dl=1"
    }

    /** 弹窗正文：首行是版本标识与安装包大小，空行后接更新日志预览（截断规则见 [previewChangelog]）。 */
    private fun composeChangelog(label: String, sizeText: String, note: String): String {
        val header = when {
            label.isBlank() -> ""
            sizeText.isBlank() -> label
            else -> context.getString(R.string.fork_update_release_title, label, sizeText)
        }
        val preview = if (note.isBlank()) {
            ""
        } else {
            previewChangelog(
                note = note,
                maxLines = CHANGELOG_PREVIEW_LINES,
                truncatedHint = context.getString(R.string.fork_update_changelog_truncated, CHANGELOG_PREVIEW_LINES)
            )
        }
        return listOf(header, preview).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private fun JsonObject.longOrZero(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

    private companion object {
        /** 本 fork 的发布信息接口，无鉴权，按 `?channel=&code=` 取对应通道的发布版。 */
        const val ENDPOINT = "https://rely-xfer.de5.net/dl/update"

        /** 同一站点的根地址，用于从响应里的 `path` 拼出下载直链。 */
        const val SITE_BASE = "https://rely-xfer.de5.net"

        /** 弹窗正文里更新日志最多显示多少行，超出部分截断（见 [previewChangelog]）。 */
        const val CHANGELOG_PREVIEW_LINES = 16

        /** 与上游 UpdateCheckService 同款：只挂全局代理认证，其余走 OkHttp 默认超时（10s 连接/读取）。 */
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

/** `direction` 取值之一：服务端发布版比本机旧（用户装的是测试包），此时不提示。 */
internal const val DIRECTION_DOWN = "down"

/**
 * 判新规则，全项目只有这一处：
 * 通道没发布版、服务端说没更新、或方向是降级（站上那份比本机旧），都当「已是最新」。
 *
 * 降级这一条必须单独判：`hasUpdate` 描述的是「两边有差异」，不判会把装了新版测试包的用户
 * 提示成「降级到更旧的版本」。
 */
internal fun shouldReportUpdate(released: Boolean, hasUpdate: Boolean, direction: String): Boolean =
    released && hasUpdate && direction != DIRECTION_DOWN

/**
 * 弹窗正文只取更新日志的前 [maxLines] 行：站点 note 是整份更新日志（几十行，含大量 ──── 分隔线），
 * 整份塞进弹窗是一面墙；截断只作用于弹窗文本——update-info.json 里仍写完整 note 供 AI 读取。
 */
internal fun previewChangelog(note: String, maxLines: Int, truncatedHint: String): String {
    val lines = note.trimEnd().lines()
    if (lines.size <= maxLines) return lines.joinToString("\n")
    return lines.take(maxLines).joinToString("\n") + "\n" + truncatedHint
}
