package com.aicode.feature.workspace.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 指纹算法与展示转换。
 *
 * 期望值取自 GitHub 公布的公钥指纹（docs.github.com 的 githubs-ssh-key-fingerprints 页），
 * 那张表就是 `ssh-keygen -lf` 的输出，因此这些用例同时钉住「算法与 OpenSSH 一致」。
 */
class HostKeyFingerprintTest {

    private val githubEd25519Blob =
        "AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
    private val githubEd25519Fingerprint =
        "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU"

    private val githubRsaBlob =
        "AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk="
    private val githubRsaFingerprint = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"

    private fun blob(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    @Test
    fun fingerprintMatchesPublishedOpensshVectors() {
        assertEquals(githubEd25519Fingerprint, formatHostKeyFingerprint(blob(githubEd25519Blob)))
        assertEquals(githubRsaFingerprint, formatHostKeyFingerprint(blob(githubRsaBlob)))
    }

    @Test
    fun fingerprintIsUnpaddedBase64OfSha256() {
        val encoded = formatHostKeyFingerprint(blob(githubEd25519Blob)).removePrefix("SHA256:")
        // 32 字节摘要的无 padding base64 固定 43 字符；带 padding 就会和 ssh-keygen 输出对不上
        assertEquals(43, encoded.length)
        assertFalse(encoded.contains('='))
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '+' || it == '/' })
    }

    @Test
    fun legacyRawBlobIsDisplayedAsOpensshFingerprint() {
        assertEquals(githubEd25519Fingerprint, hostKeyFingerprintForDisplay(githubEd25519Blob))
        assertEquals(githubRsaFingerprint, hostKeyFingerprintForDisplay(githubRsaBlob))
    }

    @Test
    fun conversionIsIdempotent() {
        val once = hostKeyFingerprintForDisplay(blob(githubEd25519Blob))
        assertEquals(once, hostKeyFingerprintForDisplay(once))
    }

    @Test
    fun legacyRawBlobWithoutPaddingIsAlsoRecognised() {
        // 写入方可能用无 padding 的编码器，缺了尾部的等号仍应被识别成公钥字节
        assertEquals(githubRsaFingerprint, hostKeyFingerprintForDisplay(githubRsaBlob.trimEnd('=')))
    }

    @Test
    fun formattedValueIsReturnedVerbatimWithoutNormalisation() {
        // 大小写与 padding 都不归一化：展示层若替用户「修正」字符串，比较语义会被带偏
        assertEquals("SHA256:AbCdEf", hostKeyFingerprintForDisplay("SHA256:AbCdEf"))
        assertNotEquals("SHA256:AbCdEf", hostKeyFingerprintForDisplay("SHA256:abcdef"))
        assertNotEquals("SHA256:abc", hostKeyFingerprintForDisplay("SHA256:abc="))
    }

    @Test
    fun singleByteChangeInKeyYieldsDifferentFingerprint() {
        val original = blob(githubEd25519Blob)
        val mutated = original.copyOf()
        mutated[mutated.size - 1] = (mutated[mutated.size - 1].toInt() xor 0x01).toByte()

        val before = formatHostKeyFingerprint(original)
        val after = formatHostKeyFingerprint(mutated)
        assertEquals(githubEd25519Fingerprint, before)
        assertNotEquals(before, after)
    }

    @Test
    fun unrecognisedStoredValuesAreShownUnchanged() {
        // 能 base64 解码但不像公钥 blob 的值不能被算成「像模像样」的指纹
        assertEquals("aGVsbG8gd29ybGQ=", hostKeyFingerprintForDisplay("aGVsbG8gd29ybGQ="))
        assertEquals("", hostKeyFingerprintForDisplay(""))
        assertEquals("not a key", hostKeyFingerprintForDisplay("not a key"))
        assertEquals("SHA256:", hostKeyFingerprintForDisplay("SHA256:"))
    }
}
