package com.aicode.feature.backup.data

import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import com.aicode.feature.backup.domain.BackupTargetDto
import com.aicode.feature.backup.domain.SecretCipher
import com.aicode.feature.backup.domain.importSecretValue
import com.aicode.feature.backup.domain.remote.BackupTargetType

/**
 * 登录凭据段的映射规则（`secrets/` 条目）。
 *
 * 与 [BackupSecretMapping] 同一套语义：导出能解开就写明文、解不开保留原密文并计数；
 * 导入明文才加密、已是密文原样落库。差别只在容器类型——这里有两类特殊载体：
 *
 * - 字节型密文（SSH 私钥文件）：规则相同，但 KeystoreCipher 把「解不开」吞成空数组，
 *   所以解密入口换成「null 表示解不开」的版本；
 * - SharedPreferences 原文（SSH 登录密钥）：整份搬运，只对口令列做上述处理。
 *
 * 加解密实现都绑定 Android Keystore，因此这里只接函数参数，规则本身保持纯函数、可 JVM 单测。
 */

/** 导出字节型密文的结果：[bytes] 为写进备份的内容，[undecryptable] 为 true 时它就是原密文。 */
internal class SecretBytesExport(val bytes: ByteArray, val undecryptable: Boolean)

/**
 * 导出字节型密文：能解开就写明文，解不开原样保留并标记。
 *
 * 空内容视为「没有可导出内容」：既不计入解不开，也不去碰解密器。
 */
internal fun exportSecretBytes(
    stored: ByteArray,
    isEncrypted: (ByteArray) -> Boolean,
    decrypt: (ByteArray) -> ByteArray?
): SecretBytesExport {
    if (stored.isEmpty() || !isEncrypted(stored)) return SecretBytesExport(stored, false)
    val plain = decrypt(stored) ?: return SecretBytesExport(stored, true)
    return SecretBytesExport(plain, false)
}

/** 导入字节型密文的结果：[bytes] 为落盘内容，[undecryptable] 表示本机解不开、需用户重新导入密钥。 */
internal class SecretBytesImport(val bytes: ByteArray, val undecryptable: Boolean)

/**
 * 导入字节型密文：非密文才加密；已是密文就原样落盘（再加密一次会得到本机永远解不开的两层密文），
 * 并通过能否解密判断它在本机可用。
 */
internal fun importSecretBytes(
    value: ByteArray,
    isEncrypted: (ByteArray) -> Boolean,
    decrypt: (ByteArray) -> ByteArray?,
    encrypt: (ByteArray) -> ByteArray
): SecretBytesImport {
    if (value.isEmpty()) return SecretBytesImport(value, false)
    if (!isEncrypted(value)) return SecretBytesImport(encrypt(value), false)
    return SecretBytesImport(value, decrypt(value) == null)
}

/** SSH 登录密钥条目所在 SharedPreferences 的名字（与 SharedPrefsSshLoginKeyStore 保持同一份存储）。 */
internal const val SSH_LOGIN_PREFS = "ssh_login_keys"

/** 条目名字列的后缀：一个 id 一条，用它数「恢复了几个密钥条目」。 */
internal const val SSH_LOGIN_NAME_SUFFIX = "_name"

/** 口令列的后缀：只有这一列是 Keystore 密文。 */
internal const val SSH_LOGIN_PASSPHRASE_SUFFIX = "_passphrase"

/** 导出：口令列走敏感字段导出规则（解得开写明文、解不开原样），其余列原样搬运。 */
internal fun exportSshLoginPrefs(
    raw: Map<String, String>,
    exportSecret: (String) -> String
): Map<String, String> = raw.mapValues { (key, value) ->
    if (key.endsWith(SSH_LOGIN_PASSPHRASE_SUFFIX)) exportSecret(value) else value
}

/** SSH 登录密钥条目的导入结果；[keyCount] 为条目数（按名字列计数，与列表页口径一致）。 */
internal class SshLoginPrefsImport(
    val entries: Map<String, String>,
    val undecryptableSecrets: Int,
    val keyCount: Int
)

/** 导入：口令列明文则加密、密文原样，并统计本机解不开的条目数。 */
internal fun importSshLoginPrefs(exported: Map<String, String>, cipher: SecretCipher): SshLoginPrefsImport {
    var undecryptable = 0
    val stored = exported.mapValues { (key, value) ->
        if (!key.endsWith(SSH_LOGIN_PASSPHRASE_SUFFIX)) {
            value
        } else {
            val imported = importSecretValue(value, cipher.isEncrypted, cipher.decrypt, cipher.encrypt)
            if (imported.undecryptable) undecryptable++
            imported.stored
        }
    }
    return SshLoginPrefsImport(
        entries = stored,
        undecryptableSecrets = undecryptable,
        keyCount = stored.keys.count { it.endsWith(SSH_LOGIN_NAME_SUFFIX) }
    )
}

/** 一个网络备份目标的导入结果；[entity] 为 null 表示类型不认识（旧/新版本差异），该条跳过。 */
internal class MappedBackupTarget(val entity: BackupTargetEntity?, val undecryptableSecrets: Int)

internal fun BackupTargetEntity.toExportDto(secrets: ExportSecretTally): BackupTargetDto = BackupTargetDto(
    id = id,
    name = name,
    type = type.name,
    endpoint = endpoint,
    basePath = basePath,
    username = username,
    password = secrets.export(password),
    accessKey = accessKey,
    secretKey = secrets.export(secretKey),
    sessionToken = secrets.export(sessionToken),
    bucket = bucket,
    region = region,
    pathStyle = pathStyle,
    createdAt = createdAt
)

internal fun BackupTargetDto.toImportedEntity(cipher: SecretCipher): MappedBackupTarget {
    val parsed = runCatching { BackupTargetType.valueOf(type) }.getOrNull()
        ?: return MappedBackupTarget(null, 0)
    var undecryptable = 0

    fun secret(value: String): String {
        val imported = importSecretValue(value, cipher.isEncrypted, cipher.decrypt, cipher.encrypt)
        if (imported.undecryptable) undecryptable++
        return imported.stored
    }

    val entity = BackupTargetEntity(
        id = id,
        name = name,
        type = parsed,
        endpoint = endpoint,
        basePath = basePath,
        username = username,
        password = secret(password),
        accessKey = accessKey,
        secretKey = secret(secretKey),
        sessionToken = secret(sessionToken),
        bucket = bucket,
        region = region,
        pathStyle = pathStyle,
        createdAt = createdAt
    )
    return MappedBackupTarget(entity, undecryptable)
}
