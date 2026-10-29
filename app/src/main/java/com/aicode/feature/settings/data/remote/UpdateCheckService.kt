package com.aicode.feature.settings.data.remote


/** Release 里的一个更新包资产。 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    /**
     * 安装包摘要（裸十六进制，无 `sha256:` 前缀）。GitHub Release 的 `digest` 与自建接口的
     * `sha256` 都会带来；缺失时不阻断下载，只跳过摘要校验（签名校验才是安全锚，见 [ApkVerifier]）。
     */
    val sha256: String?
)

/** 单个版本的更新日志与更新包。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String,
    val asset: UpdateAsset? = null
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>,
    /** fork 专属：更新源给的 APK 绝对直链（弹窗据此走应用内下载）。 */
    val downloadUrl: String? = null
) {
    /** 最新版本的更新包（应用内下载用）；该 Release 没挂 APK 资产时为 null。 */
    val latestAsset: UpdateAsset? get() = updates.firstOrNull()?.asset
}

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * 设备主 ABI 对应的资产名架构段：按 [supportedAbis] 的优先顺序取第一个支持的架构
 * （`arm64-v8a` → `arm64`，`x86_64` → `x86_64`）。都不支持时返回 null，调用方退回 universal 包。
 */
internal fun preferredAbiToken(supportedAbis: List<String>): String? =
    supportedAbis.firstNotNullOfOrNull { abi ->
        when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

/**
 * 从 Release 资产里挑出更新包。
 *
 * 资产命名由 CI 决定（`aicode-<架构>-<版本>.apk`，见 `.github/workflows/android-release.yml`）。
 * 同一 Release 里还混着 `mapping.txt.gz`，所以只认 `.apk` 结尾；精确名没命中时依次退让为
 * 「含架构段」→ `universal`，都不命中返回 null（该版本没有可用安装包，只能去浏览器下载）。
 * 一律按 API 返回的资产名匹配，不自己拼下载 URL——CI 曾因段序拼错导致资产匹配不上。
 */
internal fun selectUpdateAsset(assets: List<UpdateAsset>, rawTag: String, abiToken: String?): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (abiToken != null) {
        apks.firstOrNull { it.name.equals("aicode-$abiToken-$rawTag.apk", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.name.startsWith("aicode-$abiToken-", ignoreCase = true) }?.let { return it }
    }
    return apks.firstOrNull { it.name.startsWith("aicode-universal-", ignoreCase = true) }
}
