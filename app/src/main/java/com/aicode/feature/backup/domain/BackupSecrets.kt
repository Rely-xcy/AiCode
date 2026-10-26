package com.aicode.feature.backup.domain

/**
 * 备份里敏感字段（API Key、代理密码、自定义请求头等）的读写语义。
 *
 * Keystore 密钥与设备绑定，因此同一个字段可能有三种状态：本机密文、别处密文（本机解不开）、旧明文。
 * 这层语义必须显式表达，否则两个方向都会静默丢数据：
 *
 * - 导出：解不开就写空串，等于把密钥从备份里抹掉，用户换机后无从恢复；
 * - 导入：把别处密文再加密一次，落库的是「密文的密文」，本机解出来是空串，同样是死数据。
 *
 * 加解密实现绑定 Android Keystore（无法在 JVM 单测里跑），所以这里只接函数参数，
 * 规则本身保持纯函数、可单测。
 */

/** 一个敏感字段导出后的结果：[value] 是写进备份的值，[undecryptable] 为 true 时它就是原样密文。 */
data class SecretExportValue(val value: String, val undecryptable: Boolean)

/**
 * 导出：能解密就写明文（换设备也能用）；解不开时**保留原密文**并标记，绝不写空串。
 *
 * 空值视为「没有可导出内容」，既不计入解不开，也不去碰解密器。
 */
fun exportSecretValue(stored: String?, decrypt: (String) -> Result<String>): SecretExportValue {
    if (stored.isNullOrEmpty()) return SecretExportValue("", false)
    return decrypt(stored).fold(
        onSuccess = { SecretExportValue(it, false) },
        onFailure = { SecretExportValue(stored, true) }
    )
}

/** 一个敏感字段导入后的结果：[stored] 是落库的值，[undecryptable] 表示本机解不开、需用户重填。 */
data class SecretImportValue(val stored: String, val undecryptable: Boolean)

/**
 * 导入：非密文才加密；已是密文就原样落库（再加密一次会得到本机永远解不开的两层密文），
 * 并通过能否解密判断它在本机可用，据此给出「需要重新填写」的提示。
 */
fun importSecretValue(
    value: String,
    isEncrypted: (String) -> Boolean,
    decrypt: (String) -> Result<String>,
    encrypt: (String) -> String
): SecretImportValue {
    if (!isEncrypted(value)) return SecretImportValue(encrypt(value), false)
    return SecretImportValue(value, decrypt(value).isFailure)
}

/** 敏感字段的加解密入口，由调用方注入（生产用 KeystoreCipher，单测用假实现）。 */
class SecretCipher(
    val isEncrypted: (String) -> Boolean,
    val decrypt: (String) -> Result<String>,
    val encrypt: (String) -> String
)
