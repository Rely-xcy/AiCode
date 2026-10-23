package com.aicode.feature.workspace.domain.remote

import java.security.MessageDigest
import java.util.Base64

private const val OPENSSH_SHA256_PREFIX = "SHA256:"

/**
 * OpenSSH 形式的主机密钥指纹：`SHA256:` 加无 padding 的 base64，输入是公钥的 SSH wire blob。
 *
 * 只有按 wire blob（known_hosts 条目里那段 base64 解码后的字节）计算，用户才能拿
 * `ssh-keygen -lf` 或首次连接提示里的指纹直接核对；换成别的编码（例如 JCA 的 DER）算出来的串
 * 同样带 `SHA256:` 前缀，却永远对不上。
 */
fun formatHostKeyFingerprint(keyBlob: ByteArray): String =
    OPENSSH_SHA256_PREFIX + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob))

/**
 * 展示用指纹：存量值是公钥 wire blob 时按公钥字节现算，不采信存量串本身。
 *
 * 存量值可能是旧版本写入的裸 base64 blob，直接展示会与常见工具对不上。已是 `SHA256:` 形式的值
 * 原样返回——哈希不可逆，无法反推它当初对哪段字节算出，也不做大小写与 padding 归一化，
 * 免得把「看起来一样」的串当成同一个密钥。识别不出的值同样原样返回，由调用方决定如何展示。
 *
 * 本函数只服务于展示，任何信任判断都必须回到公钥字节比较。
 */
fun hostKeyFingerprintForDisplay(stored: String): String {
    if (stored.startsWith(OPENSSH_SHA256_PREFIX)) return stored
    val blob = decodeBase64Loose(stored) ?: return stored
    return if (isSshPublicKeyBlob(blob)) formatHostKeyFingerprint(blob) else stored
}

/**
 * 先补齐 padding 再解码：存量值与对端写入的 blob 可能来自带 padding 或无 padding 的编码器，
 * 用标准解码器直接吃无 padding 串会抛异常，于是把可认的主体误当成无法识别。
 */
private fun decodeBase64Loose(value: String): ByteArray? {
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
}

/**
 * 按 RFC 4253 第 6.6 节的公钥格式判断字节是否像 wire blob：4 字节类型名长度加类型名字符串。
 *
 * 不加这层判断的话，任何能通过 base64 解码的短垃圾串都会被算成一个像模像样的 `SHA256:` 指纹，
 * 反而让用户以为看到了真实指纹。
 */
private fun isSshPublicKeyBlob(bytes: ByteArray): Boolean {
    if (bytes.size < 5) return false
    val typeLength = ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
    if (typeLength <= 0 || typeLength > bytes.size - 4) return false
    return (4 until 4 + typeLength).all { (bytes[it].toInt() and 0xFF) in 0x20..0x7E }
}
