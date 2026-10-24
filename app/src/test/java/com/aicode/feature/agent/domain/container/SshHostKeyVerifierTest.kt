package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * SSH 主机密钥校验逻辑：首次连接需确认（不自动放行）、确认后匹配放行、指纹变化拒绝、删除后重新要求确认、端口隔离。
 * 存储用内存实现（[SshHostKeyStore] 接口），不依赖 Android。
 *
 * 指纹用例里的 GitHub 公钥与期望值取自其官方公布的指纹表（docs.github.com 的
 * githubs-ssh-key-fingerprints 页），那就是 `ssh-keygen -lf` 的输出，因此能钉住「算法与 OpenSSH 一致」。
 */
class SshHostKeyVerifierTest {

    private val githubRsaBlobBase64 =
        "AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk="
    private val githubRsaFingerprint = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"

    private fun githubRsaKey(): java.security.PublicKey =
        net.schmizz.sshj.common.Buffer.PlainBuffer(java.util.Base64.getDecoder().decode(githubRsaBlobBase64))
            .readPublicKey()

    private class InMemorySshHostKeyStore : SshHostKeyStore {
        private val map = mutableMapOf<String, String>()
        override fun get(host: String, port: Int): String? = map["$host:$port"]
        override fun save(host: String, port: Int, fingerprint: String) {
            map["$host:$port"] = fingerprint
        }
        override fun remove(host: String, port: Int) {
            map.remove("$host:$port")
        }
        override fun entries(): Map<String, String> = map.toMap()
    }

    private fun rsaPublicKey(): java.security.PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public

    private fun pendingException(
        verifier: SshHostKeyVerifier,
        host: String,
        port: Int,
        key: java.security.PublicKey
    ): SshHostKeyPendingException = try {
        verifier.verify(host, port, key)
        fail("应抛出 SshHostKeyPendingException")
        error("unreachable")
    } catch (e: SshHostKeyPendingException) {
        e
    }

    @Test
    fun first_connection_requires_confirmation_and_does_not_save() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val e = pendingException(verifier, "example.com", 22, rsaPublicKey())
        assertTrue(!e.changed)
        assertEquals("example.com", e.host)
        assertEquals(22, e.port)
        // 未确认前不保存指纹
        assertTrue(store.get("example.com", 22) == null)
    }

    @Test
    fun consumePending_exposes_pending_details() {
        val verifier = SshHostKeyVerifier(InMemorySshHostKeyStore())
        val key = rsaPublicKey()
        try {
            verifier.verify("example.com", 22, key)
            fail("应抛出 SshHostKeyPendingException")
        } catch (e: SshHostKeyPendingException) {
            // expected
        }
        val pending = verifier.consumePending("example.com", 22)
        assertEquals("example.com", pending?.host)
        assertEquals(22, pending?.port)
        assertTrue(pending?.fingerprint?.startsWith("SHA256:") == true)
        // 已消费，第二次取不到
        assertTrue(verifier.consumePending("example.com", 22) == null)
    }

    @Test
    fun matching_fingerprint_after_confirmation_is_allowed() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        assertTrue(verifier.verify("example.com", 22, key))
    }

    @Test
    fun changed_fingerprint_is_rejected_and_flagged() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        store.save("example.com", 22, sshHostKeyFingerprint(rsaPublicKey()))
        val e = pendingException(verifier, "example.com", 22, rsaPublicKey())
        assertTrue(e.changed)
    }

    @Test
    fun removing_fingerprint_requires_confirmation_again() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        assertTrue(verifier.verify("example.com", 22, key))
        store.remove("example.com", 22)
        val e = pendingException(verifier, "example.com", 22, key)
        assertTrue(!e.changed)
    }

    @Test
    fun different_port_is_treated_as_different_server() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        val e = pendingException(verifier, "example.com", 2222, key)
        assertTrue(!e.changed)
    }

    @Test
    fun pending_details_are_isolated_across_hosts_and_ports() {
        val verifier = SshHostKeyVerifier(InMemorySshHostKeyStore())
        val first = pendingException(verifier, "first.example", 22, rsaPublicKey())
        val second = pendingException(verifier, "second.example", 22, rsaPublicKey())
        val otherPort = pendingException(verifier, "first.example", 2222, rsaPublicKey())

        assertEquals(first, verifier.pendingFor("first.example", 22))
        assertEquals(second, verifier.consumePending("second.example", 22))
        assertTrue(verifier.pendingFor("second.example", 22) == null)
        assertEquals(first, verifier.pendingFor("first.example", 22))
        assertEquals(otherPort, verifier.consumePending("first.example", 2222))
        assertEquals(first, verifier.consumePending("first.example", 22))
    }

    @Test
    fun successful_verification_only_clears_its_own_pending_details() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val firstKey = rsaPublicKey()
        pendingException(verifier, "first.example", 22, firstKey)
        val second = pendingException(verifier, "second.example", 22, rsaPublicKey())

        store.save("first.example", 22, sshHostKeyFingerprint(firstKey))
        assertTrue(verifier.verify("first.example", 22, firstKey))
        assertTrue(verifier.pendingFor("first.example", 22) == null)
        assertEquals(second, verifier.pendingFor("second.example", 22))
        assertTrue(verifier.consumePending("unrelated.example", 22) == null)
        assertEquals(second, verifier.consumePending("second.example", 22))
    }

    @Test
    fun fingerprint_is_stable_and_key_specific() {
        val key = rsaPublicKey()
        val other = rsaPublicKey()
        assertEquals(sshHostKeyFingerprint(key), sshHostKeyFingerprint(key))
        assertNotEquals(sshHostKeyFingerprint(key), sshHostKeyFingerprint(other))
        assertTrue(sshHostKeyFingerprint(key).startsWith("SHA256:"))
    }

    /**
     * 新算法（SSH wire blob）与旧算法（JCA 的 DER）对同一把密钥必然给出不同摘要。
     * 回归点：`sshHostKeyFingerprint` 曾直接摘 DER，与 ssh-keygen -lf 对不上。
     */
    @Test
    fun wire_blob_fingerprint_differs_from_der_fingerprint() {
        val key = rsaPublicKey()
        assertNotEquals(sshHostKeyFingerprintDer(key), sshHostKeyFingerprint(key))
        assertTrue(sshHostKeyFingerprintDer(key).startsWith("SHA256:"))
    }

    /** 存量旧算法指纹命中时放行，并把存储值静默升级成新格式。 */
    @Test
    fun legacy_der_fingerprint_matches_and_is_upgraded_in_place() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprintDer(key))

        assertTrue("旧算法指纹下的同一把密钥不能报「主机密钥已变化」", verifier.verify("example.com", 22, key))
        assertEquals(sshHostKeyFingerprint(key), store.get("example.com", 22))
        // 升级后仍可正常校验（走新算法分支）
        assertTrue(verifier.verify("example.com", 22, key))
    }

    /**
     * 新算法必须与 OpenSSH 一致：拿 GitHub 公布的公钥（指纹表就是 ssh-keygen -lf 的输出）钉住。
     * 回归点：曾经直接摘 JCA 公钥的 DER 编码，算出的值与 ssh-keygen 对不上。
     */
    @Test
    fun wire_blob_fingerprint_matches_published_openssh_vector() {
        assertEquals(githubRsaFingerprint, sshHostKeyFingerprint(githubRsaKey()))
        assertNotEquals(githubRsaFingerprint, sshHostKeyFingerprintDer(githubRsaKey()))
    }

    /**
     * 更早版本直接存了公钥 wire blob 本身：它可逆，应能还原成字节认出同一把密钥并升级。
     * 不认这种存量就会让这些用户在自己的服务器上无端撞上「主机密钥已变化」。
     */
    @Test
    fun legacy_raw_blob_stored_value_matches_and_is_upgraded() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        store.save("example.com", 22, githubRsaBlobBase64)

        assertTrue(verifier.verify("example.com", 22, githubRsaKey()))
        assertEquals(githubRsaFingerprint, store.get("example.com", 22))
    }

    /** 两种算法都不命中：仍判定为密钥变化，且不能改动已存指纹。 */
    @Test
    fun neither_algorithm_matches_is_flagged_changed_and_store_is_untouched() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val other = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprintDer(other))

        val e = pendingException(verifier, "example.com", 22, rsaPublicKey())
        assertTrue(e.changed)
        assertEquals(sshHostKeyFingerprintDer(other), store.get("example.com", 22))
    }
}
