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
 * 关于页据此展示通道设置项。推上游 PR 前：删除本常量，并拆掉 AboutSection 里引用它的 if 包裹。
 */
internal const val FORK_UPDATE_SUPPORTS_CHANNEL = true

/**
 * 本 fork 专属的检查更新数据源：把上游的 GitHub Releases 换成本 fork 站点的接口。
 *
 * 接口（无鉴权，UTF-8 JSON，正常恒返回 HTTP 200）：
 * `GET {ENDPOINT}?channel=<通道>&code=<本机 versionCode>` 一次拿回该通道发布版的完整信息
 * （versionName / title / sizeText / date / noteShort / url）。带了 `code`，服务端就会把
 * `versionCode` 与本机比好，给出 `hasUpdate` 与 `direction`（up / down / same）。
 *
 * 判新规则：
 * 1. 响应里有 `hasUpdate` 就以它为准；`direction == "down"`（站上那份比本机旧，用户装的测试包）
 *    静默当「已是最新」，不再提示。
 * 2. 只有响应里**没有** `hasUpdate` 字段（老服务端）时，才退回旧的构建哈希 / 发布 id 判据
 *    （见 [hasNewVersion]），两套不会同时跑。
 *
 * 旧判据保留为兜底，其原理：
 * 1. 两边都拿得到构建短哈希——站点包名形如 `aicode-beta-b6c45eea.apk`，App 的 versionName 形如
 *    `1.12.0-rc2-dev.289+gb6c45eea`（见 app/build.gradle.kts 的 gitVersionName()）——按哈希比，
 *    **相等才算最新**。装的是旧包就一定会提示，首次检查也不例外。
 * 2. 拿不到可比标识（正好落在 tag 上的正式包没有 `+g<哈希>`、站点包名里没哈希）——退回「发布 id
 *    认账」：id 变了提示，从没记过 id（首次）也提示。此时无法证明站上那份与已装版本一致，
 *    静默会永久漏掉旧包。
 *
 * 三条硬规则：请求失败什么都不做（返回 Error，由调用方静默）；`released == false` 与
 * `direction == "down"` 都当「已是最新」；只有走旧判据兜底时才需要记发布 id。
 *
 * 与上游 [UpdateCheckService] 的差异只有「去哪问、拿回什么」：返回类型、弹窗 UI 与触发时机全部
 * 复用上游实现，由 SettingsViewModel.checkUpdate 调换数据源，弹窗按钮改开接口给的 APK 直链。
 *
 * 本文件是 fork 专属，推上游 PR 前必须删除；改回方式：
 * 1. 删除本文件；
 * 2. SettingsViewModel：去掉 forkUpdateCheckService 构造参数与 import，恢复注入 updateCheckService，
 *    checkUpdate 里改回 updateCheckService.checkForUpdate(currentVersionName(), updateCheckSettingsRepository.channel)，
 *    并删掉 NewVersion(..., downloadUrl = ...) 的 downloadUrl 实参，把末尾的 markCheckedToday 恢复成无条件调用（去掉 result !is Error 判断）；
 * 3. SettingsViewModel.UpdateCheckUiState.NewVersion：删掉 downloadUrl 字段；
 * 4. UpdateCheckService.UpdateInfo：删掉 downloadUrl 字段；
 * 5. UpdateCheckDialog：onOpenDownload 改回 onOpenRelease，按钮回到 githubReleaseUrl(state.latestTag) 与 R.string.about_download；
 * 6. MainActivity：onOpenDownload 改回 onOpenRelease(tag) 并补回 githubReleaseUrl import；
 * 7. AboutSection：拆掉 FORK_UPDATE_SUPPORTS_CHANNEL 的 if 包裹与 import；
 * 8. strings.xml（中英两份）删掉 fork_ 前缀的三条；docs-site/docs/guide/about.md 改回 GitHub Releases 的描述。
 * 一行定位：rg -n "FORK_UPDATE_SUPPORTS_CHANNEL|ForkUpdateCheckService|forkUpdateCheckService|fork_update_|downloadUrl"
 */
@Singleton
class ForkUpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * 按通道拉一次发布版，判断要不要提示更新。
     *
     * [localVersionCode] 是本机 versionCode，传给服务端后由它判新旧；[localVersionName] 只在响应
     * 缺 `hasUpdate` 字段时用于旧的构建哈希兜底（见 [buildShaFromVersionName]）；
     * [channel] 决定问哪个发布槽。
     */
    suspend fun checkForUpdate(
        localVersionName: String,
        localVersionCode: Int,
        channel: UpdateChannel
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

                // 通道还没有发布版（或发布版指向的文件已删）：静默当最新。
                if (!obj.booleanOrFalse("released")) {
                    return@use UpdateCheckResult.UpToDate
                }

                val releaseId = obj.stringOrEmpty("id")
                val hasUpdate = obj.get("hasUpdate")?.takeIf { !it.isJsonNull }?.asBoolean
                val shouldReport = shouldReportUpdate(
                    hasUpdate = hasUpdate,
                    direction = obj.stringOrEmpty("direction")
                ) {
                    // 兜底：老服务端没给 hasUpdate，退回构建哈希 / 发布 id 判新。
                    val lastReleaseId = prefs.getString(KEY_LAST_RELEASE_ID, null)?.takeIf { it.isNotBlank() }
                    val report = hasNewVersion(
                        localSha = buildShaFromVersionName(localVersionName),
                        remoteSha = buildShaFromApkName(obj.stringOrEmpty("name")),
                        lastSeenReleaseId = lastReleaseId,
                        releaseId = releaseId
                    )
                    if (releaseId.isNotBlank()) {
                        prefs.edit().putString(KEY_LAST_RELEASE_ID, releaseId).apply()
                    }
                    report
                }
                if (!shouldReport) {
                    return@use UpdateCheckResult.UpToDate
                }

                // 弹窗标题用服务端给的人类可读版本号（title / versionName），后面跟发布日期；
                // 都缺失时才退回包名（去掉 .apk）。
                val versionName = obj.stringOrEmpty("versionName")
                    .ifBlank { obj.stringOrEmpty("title") }
                    .ifBlank { obj.stringOrEmpty("name").trim().removeSuffix(".apk") }
                val label = listOf(versionName.trim(), obj.stringOrEmpty("date").trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                // 优先用服务端裁好的前 16 行 noteShort；缺字段时退回整份 note，由本地按行截断。
                val note = obj.stringOrEmpty("noteShort").ifBlank { obj.stringOrEmpty("note") }
                UpdateCheckResult.NewVersion(
                    UpdateInfo(
                        latestTag = label,
                        changelog = composeChangelog(
                            label = label,
                            sizeText = obj.stringOrEmpty("sizeText"),
                            note = note
                        ),
                        updates = listOf(VersionUpdate(tag = label, changelog = note)),
                        downloadUrl = downloadUrlOf(obj)
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

    private companion object {
        /** 本 fork 的发布信息接口，无鉴权，按 `?channel=&code=` 取对应通道的发布版。 */
        const val ENDPOINT = "https://rely-xfer.de5.net/dl/update"

        /** 同一站点的根地址，用于从响应里的 `path` 拼出下载直链。 */
        const val SITE_BASE = "https://rely-xfer.de5.net"

        /** 沿用仓库既有的更新偏好文件（见 UpdateCheckSettingsRepository），只加一个自有键。 */
        const val PREFS_NAME = "update_check_prefs"

        /** 上一次见到的发布 id，只在拿不到可比标识的回退路径上用（见 [hasNewVersion]）。 */
        const val KEY_LAST_RELEASE_ID = "fork_last_release_id"

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
private const val DIRECTION_DOWN = "down"

/**
 * 要不要提示更新。服务端给了 `hasUpdate` 就以它为准：`direction == "down"`（站上那份比本机旧）
 * 静默当「已是最新」。只有字段缺失（老服务端）时才跑 [fallback] 的旧判据，两套不并行。
 */
internal fun shouldReportUpdate(
    hasUpdate: Boolean?,
    direction: String,
    fallback: () -> Boolean
): Boolean = if (hasUpdate != null) hasUpdate && direction != DIRECTION_DOWN else fallback()

/** git 短哈希最少 7 位（`git describe` 的默认下限），站点文件名给 8 位；不足 7 位不足以唯一标识一个提交。 */
private const val MIN_BUILD_SHA_LENGTH = 7

/** 包名的最后一段必须是这种纯十六进制才算构建短哈希，否则一律当「站上没给可比标识」。 */
private val BUILD_SHA_TOKEN = Regex("""^[0-9a-f]{7,40}$""")

/** versionName 里的构建哈希：`+g<哈希>`（有 tag 可达）或 `+<哈希>`（仓库里一个 tag 都没有）。 */
private val BUILD_SHA_IN_VERSION_NAME = Regex("""\+g?([0-9a-f]{7,40})""")

/**
 * 从 App 自己的 versionName 里取出构建短哈希。
 * app/build.gradle.kts 的 gitVersionName() 走 `git describe --tags --always --dirty`，产出两种形态：
 * `1.12.0-rc2-dev.289+gb6c45eea`（有 tag 可达，带 `g` 前缀）与 `1.7.0-dev+b6c45eea`（没 tag，无前缀）；
 * 本地改过文件还会多一个 `-dirty` 尾巴，取哈希时不看它。
 * 正好落在 tag 上的正式包（如 `1.12.0-rc2`）与读不到 versionName 的情况都没有哈希可比，返回 null 走回退。
 */
internal fun buildShaFromVersionName(versionName: String?): String? =
    BUILD_SHA_IN_VERSION_NAME.find(versionName.orEmpty())?.groupValues?.get(1)

/**
 * 从站点给的发布包名里取出构建短哈希：站上形如 `aicode-beta-b6c45eea.apk`，
 * 取「去掉 .apk 后最后一段」并要求是纯十六进制（小写）；包名换成别的形状时返回 null 走回退，不猜。
 */
internal fun buildShaFromApkName(apkName: String?): String? {
    val base = apkName.orEmpty().trim().removeSuffix(".apk")
    return base.substringAfterLast('-').takeIf { BUILD_SHA_TOKEN.matches(it) }
}

/**
 * 两个短哈希是否指向同一个提交。长度不一定相等（站点给 8 位，git 给 7~40 位），
 * 故按「短的那个是长的那个的前缀」判；不足 [MIN_BUILD_SHA_LENGTH] 位的一律判为不同——
 * 宁可多提示一次，也不冒把「装的是旧包」误判成最新而永久静默的风险。
 */
internal fun isSameBuildSha(a: String, b: String): Boolean {
    val n = minOf(a.length, b.length)
    if (n < MIN_BUILD_SHA_LENGTH) return false
    return a.regionMatches(0, b, 0, n)
}

/**
 * 要不要提示更新。
 *
 * - 两边都拿得到构建短哈希：哈希不同就提示，**首次检查也不例外**——装的是旧包凭什么静默。
 * - 否则退回发布 id 认账：与上次记下的 id 不同就提示；从没记过（首次）也提示，因为此时无法证明
 *   站上那份与已装版本一致，静默会永久漏掉旧包。代价是「装的就是最新版」时首次会多提示一次，
 *   之后 id 记住即不再提示。
 * - 两边都没有可比标识（站点没给 id）：无从判断，不提示——逐日弹一次假更新比偶尔漏提示更烦人。
 */
internal fun hasNewVersion(
    localSha: String?,
    remoteSha: String?,
    lastSeenReleaseId: String?,
    releaseId: String
): Boolean {
    if (localSha != null && remoteSha != null) return !isSameBuildSha(localSha, remoteSha)
    if (releaseId.isBlank()) return false
    return releaseId != lastSeenReleaseId
}

/**
 * 弹窗正文只取更新日志的前 [maxLines] 行：站点 note 是整份更新日志（几十行，含大量 ──── 分隔线），
 * 整份塞进弹窗是一面墙；截断只作用于弹窗文本——update-info.json 里仍写完整 note 供 AI 读取。
 */
internal fun previewChangelog(note: String, maxLines: Int, truncatedHint: String): String {
    val lines = note.trimEnd().lines()
    if (lines.size <= maxLines) return lines.joinToString("\n")
    return lines.take(maxLines).joinToString("\n") + "\n" + truncatedHint
}
