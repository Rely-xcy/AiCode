package com.aicode.feature.agent.domain.container

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可下载的镜像条目：[path] 是相对路径，{abi} 占位符由 [ContainerImageCatalog.urlFor] 按架构替换。 */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    /**
     * 内置版本号。Alpine/Ubuntu 直接是完整补丁版本（如 `3.21.8`）；
     * Debian 这类「版本随上游滚动、内置值只是主版本」的条目，精确补丁号由 [versionPath] 运行时探测覆盖。
     */
    val version: String,
    /** 版本号后缀（如 Debian 的 `(bookworm)`），与解析后的版本拼成展示名。 */
    val versionSuffix: String = "",
    /**
     * 版本元数据文件的相对路径（{abi} 同 [path]），内容为纯文本版本号（如 Debian 的 `rootfs.debian_version` → `12.15`）。
     * 留空表示该条目版本固定、无需探测。
     */
    val versionPath: String = "",
    /** 上游 EOL 日期（`yyyy-MM-dd`），留空表示未知；用于列表标注即将/已经停止支持的分支。 */
    val eol: String = "",
    val description: String = "",
    val sizeBytes: Long = 0,
    /** 所属发行版（对应全局 sources 表的键，如 alpine/ubuntu）。 */
    val distro: String = "",
    val path: String,
    /** 标准架构键（arm64/x86_64）→ 该发行版实际目录名（如 aarch64/amd64）。 */
    val abiNames: Map<String, String> = emptyMap()
)

/** 发行版分支的支持状态，用于列表标注。 */
enum class ContainerImageEol { NONE, SOON, ENDED }

/**
 * 按条目自带的 [ContainerImageEntry.eol] 判断支持状态：
 * 已过期为 [ContainerImageEol.ENDED]，距今不足 [EOL_SOON_DAYS] 天为 [ContainerImageEol.SOON]。
 * 未配置 eol 或日期非法一律返回 [ContainerImageEol.NONE]（宁可漏标也不误标）。
 * [today] 可注入，便于单测。
 */
fun ContainerImageEntry.eolStatus(today: java.time.LocalDate = java.time.LocalDate.now()): ContainerImageEol {
    val eolDate = runCatching { java.time.LocalDate.parse(eol) }.getOrNull() ?: return ContainerImageEol.NONE
    return when {
        eolDate.isBefore(today) -> ContainerImageEol.ENDED
        eolDate.toEpochDay() - today.toEpochDay() < EOL_SOON_DAYS -> ContainerImageEol.SOON
        else -> ContainerImageEol.NONE
    }
}

/** EOL 预警窗口：距停止支持不足这些天即标注。 */
const val EOL_SOON_DAYS = 30L

/**
 * 展示用名称：发行版名 + 版本 + 可选后缀。
 * [resolvedVersion] 为运行时探测到的精确版本（如 Debian 的 `12.15`），为空时退回条目内置的 [ContainerImageEntry.version]。
 */
fun ContainerImageEntry.displayLabel(resolvedVersion: String? = null): String =
    listOf(name, resolvedVersion?.takeIf { it.isNotBlank() } ?: version, versionSuffix)
        .filter { it.isNotBlank() }
        .joinToString(" ")

/** 一个下载源：中英文显示名 + 各发行版的前缀（缺失/为空表示该源不提供此发行版）。 */
@Serializable
data class ContainerImageSource(
    val name: Map<String, String> = emptyMap(),
    val distros: Map<String, String> = emptyMap()
)

/** 全局镜像目录：sources（源 id → 定义）+ 镜像列表。 */
@Serializable
data class ContainerImageCatalogData(
    val sources: Map<String, ContainerImageSource> = emptyMap(),
    val images: List<ContainerImageEntry> = emptyList()
)

/**
 * 从内置 assets/container-images.json 加载可下载镜像目录。
 * 源与镜像分离存储：URL = sources[源][发行版] 前缀 + 条目 path（{abi} 按设备架构替换）。
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var data = ContainerImageCatalogData()

    fun load(): List<ContainerImageEntry> {
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(raw)
        }.getOrNull()
        data = parsed ?: ContainerImageCatalogData()
        return data.images
    }

    /** 全局源 id 列表（保持 JSON 顺序），供右上角源切换展示。 */
    val sourceIds: List<String>
        get() = data.sources.keys.toList()

    /** 源显示名（按语言键 zh/en 取，缺省回退任意可用名）；源不存在返回 null。 */
    fun sourceName(sourceId: String, lang: String): String? {
        val names = data.sources[sourceId]?.name ?: return null
        return names[lang] ?: names.values.firstOrNull()
    }

    /** 当前设备架构下，[entry] 指定源的完整下载 URL；源/发行版/架构缺失返回 null。 */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? =
        buildUrl(sourceId, entry.distro, entry.path, entry.abiNames, abi)

    /**
     * 当前设备架构下，[entry] 版本元数据文件的完整 URL（纯文本版本号）。
     * 条目未配置 [ContainerImageEntry.versionPath] 或源/发行版/架构缺失时返回 null——调用方据此跳过探测。
     */
    fun versionUrlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? =
        buildUrl(sourceId, entry.distro, entry.versionPath, entry.abiNames, abi)

    /** 由「源前缀 + 发行版相对路径 + 架构名」拼 URL，任一环节缺失返回 null。 */
    private fun buildUrl(
        sourceId: String,
        distro: String,
        path: String,
        abiNames: Map<String, String>,
        abi: String
    ): String? {
        if (path.isBlank()) return null
        val prefix = data.sources[sourceId]?.distros?.get(distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = abiNames[abi] ?: return null
        return "$prefix/$path".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** 与 [com.aicode.feature.agent.domain.container.ContainerInstaller] 一致的架构判定：x86 设备走 x86_64，其余走 arm64。 */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
