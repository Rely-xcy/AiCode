package com.aicode.feature.agent.domain.mcp.auth

import android.content.Context
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.mcp.McpAuthConfig
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 令牌文件被整份覆盖（备份导入）后，已就绪的内存会话要同步到磁盘上的令牌，
 * 不必等下次冷启动。prepare 走磁盘恢复路径，不联网（令牌未过期）。
 */
class McpOAuthManagerSyncTest {

    private val issuer = "https://as.example.com"

    private fun hub(): FileChangeHub = mockk(relaxed = true)

    private fun tempFile(): File {
        val file = File.createTempFile("mcp-oauth-sync", ".json")
        file.delete()
        return file
    }

    private fun future(): Long = System.currentTimeMillis() / 1000 + 3600

    private fun manager(store: McpTokenStore): McpOAuthManager = McpOAuthManager(
        mockk<Context>(relaxed = true),
        OkHttpClient(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        store
    )

    private fun serverConfig(): McpServerConfig =
        McpServerConfig(name = "demo", url = "https://mcp.example.com", auth = McpAuthConfig())

    private suspend fun primedStore(file: File): McpTokenStore {
        val store = McpTokenStore(file, hub())
        store.putClient(OAuthClientRegistration(issuer, "cid", null, "http://127.0.0.1:8765/callback"))
        store.putTokens(issuer, OAuthTokens("old-token", null, future(), null))
        store.putServerIssuer("demo", issuer)
        return store
    }

    @Test
    fun imported_token_replaces_stale_session() = runBlocking {
        val file = tempFile()
        val store = primedStore(file)
        val oauth = manager(store)
        oauth.prepare(serverConfig())
        assertEquals("old-token", oauth.cachedToken("demo"))

        McpTokenStore(file, hub()).putTokens(issuer, OAuthTokens("imported-token", null, future(), null))
        store.invalidate()
        oauth.syncFromStore()

        assertEquals("imported-token", oauth.cachedToken("demo"))
    }

    @Test
    fun missing_token_drops_session() = runBlocking {
        val file = tempFile()
        val store = primedStore(file)
        val oauth = manager(store)
        oauth.prepare(serverConfig())
        assertEquals("old-token", oauth.cachedToken("demo"))

        // 导入的备份没有这个 issuer 的令牌：继续用旧令牌只会拿到 401
        McpTokenStore(file, hub()).clear(issuer)
        store.invalidate()
        oauth.syncFromStore()

        assertNull(oauth.cachedToken("demo"))
    }
}
