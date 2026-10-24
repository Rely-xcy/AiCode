package com.aicode.feature.agent.domain.mcp.auth

import com.aicode.core.watch.FileChangeHub
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** 令牌存储：按 issuer 读写、跨实例持久化、文件不做明文直读、整份覆盖后立即对新值可见。 */
class McpTokenStoreTest {

    /** 文件变更监听在这些用例里不启动：只验证缓存与磁盘的一致性。 */
    private fun hub(): FileChangeHub = mockk(relaxed = true)

    private fun tempStore(): Pair<McpTokenStore, File> {
        val file = File.createTempFile("mcp-oauth", ".json")
        file.delete()
        return McpTokenStore(file, hub()) to file
    }

    @Test
    fun client_and_tokens_roundtrip() = runBlocking {
        val (store, _) = tempStore()
        store.putClient(
            OAuthClientRegistration(
                issuer = "https://as.example.com",
                clientId = "cid",
                clientSecret = null,
                redirectUri = "http://127.0.0.1:8765/callback"
            )
        )
        store.putTokens("https://as.example.com", OAuthTokens("access-1", "refresh-1", 1234567890, "repo"))

        assertEquals("cid", store.getClient("https://as.example.com")?.clientId)
        assertEquals("access-1", store.getTokens("https://as.example.com")?.accessToken)
        assertEquals("refresh-1", store.getTokens("https://as.example.com")?.refreshToken)
    }

    @Test
    fun persists_across_instances_and_obfuscates_plaintext() = runBlocking {
        val (store, file) = tempStore()
        store.putTokens("issuer", OAuthTokens("secret-token"))
        assertFalse("文件不应明文包含令牌", file.readText().contains("secret-token"))

        val reopened = McpTokenStore(file, hub())
        assertEquals("secret-token", reopened.getTokens("issuer")?.accessToken)
    }

    @Test
    fun server_issuer_mapping() = runBlocking {
        val (store, _) = tempStore()
        store.putServerIssuer("github", "https://github.com/login/oauth")
        assertEquals("https://github.com/login/oauth", store.getIssuerForServer("github"))
        store.clearServerIssuer("github")
        assertNull(store.getIssuerForServer("github"))
    }

    /** 备份导入这类「整份覆盖文件」不经过本实例的写入口，失效后必须立刻读到新值。 */
    @Test
    fun whole_file_overwrite_is_visible_after_invalidate() = runBlocking {
        val (store, file) = tempStore()
        store.putTokens("issuer", OAuthTokens("before-token"))
        assertEquals("before-token", store.getTokens("issuer")?.accessToken)

        McpTokenStore(file, hub()).putTokens("issuer", OAuthTokens("imported-token"))
        store.invalidate()

        assertEquals("imported-token", store.getTokens("issuer")?.accessToken)
    }

    /** 失效要能通知到订阅方（MCP 连接层据此重建连接），而不是只有调用方自己知道。 */
    @Test
    fun invalidate_broadcasts_to_subscribers() = runBlocking {
        val (store, _) = tempStore()
        store.putTokens("issuer", OAuthTokens("before-token"))
        val received = CompletableDeferred<Unit>()
        coroutineScope {
            val subscription = launch(Dispatchers.Default) {
                store.externalChanges.first()
                received.complete(Unit)
            }
            // 订阅建立是异步的（WhileSubscribed），重复发到收到为止，避免固定 sleep 的偶发失败。
            withTimeout(5_000) {
                while (!received.isCompleted) {
                    store.invalidate()
                    delay(20)
                }
            }
            subscription.cancel()
        }
        assertTrue(received.isCompleted)
    }

    @Test
    fun concurrent_reads_and_writes_never_return_torn_snapshot() = runBlocking {
        val (store, _) = tempStore()
        store.putTokens("issuer", OAuthTokens("token-0", "refresh-0"))

        val writers = 40
        val seen = Collections.synchronizedList(mutableListOf<OAuthTokens?>())
        coroutineScope {
            repeat(writers) { i ->
                launch(Dispatchers.Default) {
                    store.putTokens("issuer", OAuthTokens("token-$i", "refresh-$i"))
                }
            }
            repeat(writers * 4) {
                launch(Dispatchers.Default) {
                    seen += store.getTokens("issuer")
                }
            }
        }

        val valid: Set<Pair<String?, String?>> =
            (0 until writers).map { "token-$it" to "refresh-$it" }.toSet()
        assertTrue(seen.isNotEmpty())
        seen.forEach { tokens ->
            val access = tokens?.accessToken
            val refresh = tokens?.refreshToken
            assertNotNull("读取不应返回 null", tokens)
            assertTrue("读到半截令牌：$access/$refresh", (access to refresh) in valid)
        }
    }
}
