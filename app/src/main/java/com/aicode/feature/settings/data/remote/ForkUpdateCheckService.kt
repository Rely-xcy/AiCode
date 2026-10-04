package com.aicode.feature.settings.data.remote

import android.content.Context
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
 * 本 fork 专属的检查更新数据源：把上游的 GitHub Releases 换成本 fork 站点的接口。
 *
 * 接口（无鉴权，UTF-8 JSON，正常恒返回 HTTP 200）：`GET {ENDPOINT}` 一次拿回站上最新发布版的
 * 完整信息（id / name / sizeText / date / note / url）。响应里的 `hasUpdate` 是站点拿请求参数
 * `id` 与最新 id 比出来的结论，**不带 id 时恒为 true**，所以这里不传 id：先取回最新发布版，
 * 再在本地判断要不要提示。
 *
 * 判新分两条路（见 [hasNewVersion]）：
 * 1. 两边都拿得到构建短哈希——站点包名形如 `aicode-beta-b6c45eea.apk`，App 的 versionName 形如
 *    `1.12.0-rc2-dev.289+gb6c45eea`（见 app/build.gradle.kts 的 gitVersionName()）——按哈希比，
 *    **相等才算最新**。装的是旧包就一定会提示，首次检查也不例外。
 * 2. 拿不到可比标识（正好落在 tag 上的正式包没有 `+g<哈希>`、站点包名里没哈希）——退回「发布 id
 *    认账」：id 变了提示，从没记过 id（首次）也提示。此时无法证明站上那份与已装版本一致，
 *    静默会永久漏掉旧包。
 *
 * 两条硬规则：请求失败什么都不做（返回 Error，由调用方静默）；只要响应里带 id 就存下（无论有没有更新）。
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
     * 拉取一次站上最新发布版，判断要不要提示更新。
     * [localVersion] 传本机 versionName（带 `+g<短哈希>` 才有得比，见 [buildShaFromVersionName]）。
     */
    suspend fun checkForUpdate(localVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastReleaseId = prefs.getString(KEY_LAST_RELEASE_ID, null)?.takeIf { it.isNotBlank() }
            // 不传 id：不带 id 时接口给的是站上最新发布版的完整信息（name/note/url 都在），
            // 而 hasUpdate 只是站点按 id 比出来的结论，本地有构建哈希时根本用不上它。
            val req = okhttp3.Request.Builder().url(ENDPOINT).get().build()
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

                val releaseId = obj.stringOrEmpty("id")
                // 无论有没有更新都存 id（回退路径靠它认账）；released=false 时接口给的 id 为空，保持原值不清空。
                if (releaseId.isNotBlank()) {
                    prefs.edit().putString(KEY_LAST_RELEASE_ID, releaseId).apply()
                }
                if (!obj.booleanOrFalse("released")) {
                    return@use UpdateCheckResult.UpToDate
                }

                val name = obj.stringOrEmpty("name")
                val localSha = buildShaFromVersionName(localVersion)
                val remoteSha = buildShaFromApkName(name)
                if (!hasNewVersion(localSha, remoteSha, lastReleaseId, releaseId)) {
                    return@use UpdateCheckResult.UpToDate
                }

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
        /** 本 fork 的发布信息接口，无鉴权、恒不带参数（带了 id 反而只剩站点的 id 比对结论，见类注释）。 */
        const val ENDPOINT = "https://rely-xfer.de5.net/dl/update"

        /** 沿用仓库既有的更新偏好文件（见 UpdateCheckSettingsRepository），只加一个自有键。 */
        const val PREFS_NAME = "update_check_prefs"

        /** 上一次见到的发布 id，只在拿不到可比标识的回退路径上用（见 [hasNewVersion]）。 */
        const val KEY_LAST_RELEASE_ID = "fork_last_release_id"

        /** 与上游 UpdateCheckService 同款：只挂全局代理认证，其余走 OkHttp 默认超时（10s 连接/读取）。 */
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

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
