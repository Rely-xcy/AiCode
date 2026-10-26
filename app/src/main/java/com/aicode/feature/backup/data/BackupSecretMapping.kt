package com.aicode.feature.backup.data

import com.aicode.feature.backup.domain.ConnectionSecretsDto
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.ProviderSecretsDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.SecretCipher
import com.aicode.feature.backup.domain.exportSecretValue
import com.aicode.feature.backup.domain.importSecretValue
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.domain.model.RemoteProtocol

/**
 * 备份里敏感字段的映射规则（导出计数 + 导入落库）。
 *
 * 单独放文件而不是留在 BackupManagerImpl 里，是为了让两条关键规则能被 JVM 单测直接验证：
 * 「旧备份缺的字段不能覆盖本机既有值」与「已是密文不要再加密一次」。加解密实现（KeystoreCipher）
 * 绑定 Android Keystore、JVM 单测跑不起来，所以这里只接函数参数，规则保持可测。
 */

/**
 * 导出时「本机 Keystore 解不开、只能按密文原样写进备份」的敏感字段累计。
 *
 * 不能用 decryptString：「解不开」与「本来为空」在它那里都是空串，写进备份就等于把密钥抹掉。
 */
internal class ExportSecretTally(private val decrypt: (String) -> Result<String>) {
    var undecryptable = 0
        private set

    fun export(stored: String): String {
        val value = exportSecretValue(stored, decrypt)
        if (value.undecryptable) undecryptable++
        return value.value
    }

    /**
     * 记一次「解不开」。字节型凭据（SSH 私钥文件）由 [exportSecretBytes] 自行判断并按原密文写出，
     * 计数仍汇到这里，让导出摘要的「解不开」口径只此一处。
     */
    fun noteUndecryptable() {
        undecryptable++
    }
}

/**
 * 一个 provider 的导入结果：[entity] 落库，[undecryptableSecrets] 为其中本机解不开、需重填的字段数；
 * [credentialsRestored] 表示密钥确实从凭据段写回（旧包从 metadata 读时恒为 false，不计入凭据项）。
 */
internal data class MappedProvider(
    val entity: AIProviderEntity,
    val undecryptableSecrets: Int,
    val credentialsRestored: Boolean = false
)

/**
 * 一个远程连接的导入结果；[entity] 为 null 表示协议已移除（旧备份的本地通道），这条记录跳过。
 * [credentialsRestored] 含义同 [MappedProvider.credentialsRestored]。
 */
internal data class MappedConnection(
    val entity: RemoteConnectionEntity?,
    val undecryptableSecrets: Int,
    val credentialsRestored: Boolean = false
)

/**
 * @param existing 本机同 id 的既有记录：备份里为 null 的字段（旧备份没有这些列）沿用它的值，
 *   拿不到时才退回实体默认值。
 * @param secrets 新格式下与 metadata 分开存放的密钥字段；null 表示凭据段里没有这条记录。
 * @param secretsSegmented 新格式标记：metadata 里的密钥字段是空占位，不能再当真实值回写；
 *   旧格式（false）继续从 metadata 读，与旧包逐字一致。
 */
internal fun ProviderDto.toImportedEntity(
    existing: AIProviderEntity?,
    cipher: SecretCipher,
    secrets: ProviderSecretsDto? = null,
    secretsSegmented: Boolean = false
): MappedProvider {
    var undecryptable = 0

    /** 敏感字段：明文加密、密文原样，解不开的计数。 */
    fun secret(value: String): String {
        val imported = importSecretValue(value, cipher.isEncrypted, cipher.decrypt, cipher.encrypt)
        if (imported.undecryptable) undecryptable++
        return imported.stored
    }

    /** 新格式：有对应凭据行就以凭据段为准；没有就沿用本机值，空占位不能当「用户清空」回写。 */
    fun segmented(fromSegment: String?, existingValue: String?): String =
        if (secrets != null) secret(fromSegment ?: "") else existingValue ?: ""

    val entity = AIProviderEntity(
        id = id,
        name = name,
        type = type,
        apiKey = if (secretsSegmented) segmented(secrets?.apiKey, existing?.apiKey) else secret(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints ?: true,
        openaiChatCacheKey = openaiChatCacheKey ?: false,
        dashboardScriptPath = dashboardScriptPath ?: "",
        dashboardRefreshInterval = dashboardRefreshInterval ?: 5,
        // 这两列是库内密文：沿用既有值不能再过 secret()，否则会在既有密文上再套一层。
        customHeaders = if (secretsSegmented) segmented(secrets?.customHeaders, existing?.customHeaders)
            else customHeaders?.let { secret(it) } ?: existing?.customHeaders ?: "",
        proxyEnabled = proxyEnabled ?: existing?.proxyEnabled ?: false,
        proxyType = proxyType ?: existing?.proxyType ?: "HTTP",
        proxyHost = proxyHost ?: existing?.proxyHost ?: "",
        proxyPort = proxyPort ?: existing?.proxyPort ?: 0,
        proxyUsername = proxyUsername ?: existing?.proxyUsername ?: "",
        proxyPassword = if (secretsSegmented) segmented(secrets?.proxyPassword, existing?.proxyPassword)
            else proxyPassword?.let { secret(it) } ?: existing?.proxyPassword ?: "",
        sortOrder = sortOrder ?: 0,
        multiKeyEnabled = multiKeyEnabled ?: false,
        apiKeys = if (secretsSegmented) segmented(secrets?.apiKeys, existing?.apiKeys) else secret(apiKeys ?: ""),
        keyRotationStrategy = keyRotationStrategy ?: "SEQUENTIAL",
        keyFailoverThreshold = keyFailoverThreshold ?: 2,
        keyCooldownMinutes = keyCooldownMinutes ?: 5,
        scriptParams = if (secretsSegmented) segmented(secrets?.scriptParams, existing?.scriptParams)
            else secret(scriptParams ?: ""),
        keySwitchStatusCodes = keySwitchStatusCodes ?: "",
        userAgent = userAgent ?: existing?.userAgent ?: ""
    )
    return MappedProvider(entity, undecryptable, secrets?.hasAnyCredential() == true)
}

/**
 * @param existing 本机同 id 的既有记录：新格式没带凭据段（或没带这条记录）时要沿用它的密码 / 口令，
 *   不能把空占位写成空串。
 * @param secrets 新格式下分开存放的凭据字段；null 表示凭据段里没有这条记录。
 * @param secretsSegmented 新格式标记，含义同 [ProviderDto.toImportedEntity]。
 */
internal fun RemoteConnectionDto.toImportedEntity(
    cipher: SecretCipher,
    existing: RemoteConnectionEntity? = null,
    secrets: ConnectionSecretsDto? = null,
    secretsSegmented: Boolean = false
): MappedConnection {
    var undecryptable = 0

    fun secret(value: String): String {
        val imported = importSecretValue(value, cipher.isEncrypted, cipher.decrypt, cipher.encrypt)
        if (imported.undecryptable) undecryptable++
        return imported.stored
    }

    val parsed = runCatching { RemoteProtocol.valueOf(protocol) }.getOrNull()
        ?: return MappedConnection(null, 0)
    val passwordAuth = authType.equals("PASSWORD", ignoreCase = true)
    val entity = RemoteConnectionEntity(
        id = id,
        name = name,
        protocol = parsed,
        host = host,
        port = port,
        username = username,
        authType = authType,
        // KEY 模式的 authData 是私钥路径，属配置，永远明文存；PASSWORD 模式才是密码。
        authData = when {
            !passwordAuth -> authData
            !secretsSegmented -> secret(authData)
            secrets != null -> secret(secrets.authData ?: "")
            else -> existing?.authData ?: ""
        },
        passphrase = when {
            !secretsSegmented -> passphrase?.let { secret(it) }
            secrets != null -> secrets.passphrase?.let { secret(it) }
            else -> existing?.passphrase
        },
        sortOrder = sortOrder ?: 0
    )
    return MappedConnection(entity, undecryptable, secrets?.hasAnyCredential() == true)
}
