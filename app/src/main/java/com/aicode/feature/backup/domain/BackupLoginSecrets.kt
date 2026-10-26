package com.aicode.feature.backup.domain

import kotlinx.serialization.Serializable

/**
 * 登录凭据段的 DTO：与 [BackupMetadata] 分开成文件，是因为它们只在勾选「包含登录凭据」时才出现，
 * 且各自落在 tar 的 `secrets/` 条目里（不进 metadata.json）。
 */

/** 一条 git 凭据（每个 host 一条）。[token] 在备份里是明文——凭据段只允许进加密备份。 */
@Serializable
data class GitCredentialDto(
    val host: String,
    val username: String,
    val token: String
)

/**
 * 一个网络备份目标（WebDAV / S3）。
 *
 * [type] 存枚举名原文而不是枚举本身：旧版本 App 读到不认识的类型时该条跳过即可，
 * 不会因为反序列化失败把整包导入废掉（与 [RemoteConnectionDto.protocol] 同一考量）。
 * 密码类字段的读写走 [exportSecretValue] / [importSecretValue] 的语义。
 */
@Serializable
data class BackupTargetDto(
    val id: String,
    val name: String,
    val type: String,
    val endpoint: String,
    val basePath: String = "",
    val username: String = "",
    /** WebDAV 密码。 */
    val password: String = "",
    val accessKey: String = "",
    /** S3 SecretKey。 */
    val secretKey: String = "",
    /** S3 SessionToken（临时凭据才有）。 */
    val sessionToken: String = "",
    val bucket: String = "",
    val region: String = "",
    val pathStyle: Boolean = true,
    val createdAt: Long = 0
)

/**
 * 一个供应商的密钥字段，只在勾选「凭据」且带口令的备份里出现，落在 `secrets/` 段。
 *
 * 单独成段而不是留在 metadata.json：这些值能直接用于调用计费接口，旧格式只要勾了
 * 「AI 供应商配置」就会连密钥一起明文导出，既不在凭据开关下，也没有任何提示。
 * metadata 现在只保留 name/type/baseUrl 等配置字段。
 */
@Serializable
data class ProviderSecretsDto(
    val id: String,
    /** 单 Key 模式的密钥。 */
    val apiKey: String = "",
    /** 多 Key 模式的候选列表（换行分隔）。 */
    val apiKeys: String = "",
    /** 供应商级代理密码。 */
    val proxyPassword: String = "",
    /** 自定义请求头（JSON 编码的 Map）。 */
    val customHeaders: String = "",
    /** 自定义面板脚本参数（JSON 编码的 Map）。 */
    val scriptParams: String = ""
) {
    /** 这条记录是否真的带了一项凭据：全空占位行不算，导入摘要按有内容的记录计数。 */
    fun hasAnyCredential(): Boolean =
        apiKey.isNotEmpty() || apiKeys.isNotEmpty() || proxyPassword.isNotEmpty() ||
            customHeaders.isNotEmpty() || scriptParams.isNotEmpty()
}

/**
 * 一条远程连接的凭据字段。[authData] 只在 PASSWORD 模式有值（登录密码）；
 * KEY 模式的 authData 是私钥路径，属于配置，留在 metadata.json。
 */
@Serializable
data class ConnectionSecretsDto(
    val id: String,
    val authData: String? = null,
    /** 私钥口令（KEY 模式）或登录口令的附带口令。 */
    val passphrase: String? = null
) {
    /** 这条记录是否真的带了一项凭据：全空占位行不算，导入摘要按有内容的记录计数。 */
    fun hasAnyCredential(): Boolean = !authData.isNullOrEmpty() || !passphrase.isNullOrEmpty()
}

/**
 * SSH 登录密钥条目的原文：`ssh_login_keys` 这份 SharedPreferences 的 key → value 原样搬运。
 *
 * 不按 SshLoginKey 拆字段，是因为口令列以 Keystore 密文存储，而公开接口只返回解密后的值：
 * 解不开时那里是空串，按字段搬运就等于把口令从备份里抹掉。原文搬运则能保留「解不开的原密文」。
 */
@Serializable
data class SshLoginKeysDto(
    val entries: Map<String, String> = emptyMap()
)
