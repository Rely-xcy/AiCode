package com.aicode.feature.agent.domain.mcp.auth

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * OAuth 客户端身份与令牌的持久化，按授权服务器 issuer 为键（规范要求凭据绑定 issuer）。
 *
 * 文件内容做 base64 + 整体反转处理，与 `git-credentials` 保持一致：**属混淆、非加密**，
 * 只为避免明文直读，不构成安全承诺。内存缓存（不可变快照，整份替换）+ Mutex 串行化读写，
 * 读到的永远是某一时刻的完整快照，不会看到写到一半的内容。
 *
 * 缓存只在 [update] 里随写随换，因此**绕过本类的整份文件覆盖**（备份导入、手工替换文件）
 * 必须经 [externalChanges] 的监听或显式 [invalidate] 才能被感知，否则调用方会一直读到旧令牌。
 */
@Singleton
class McpTokenStore @Inject constructor(
    // 限定符与 McpAuthModule 的 @Provides 保持一致。
    @param:Named("mcpOAuthFile") private val file: File,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "McpTokenStore"
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }

    private val mutex = Mutex()

    @Volatile
    private var cache: StoreRoot? = null

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [invalidate] 的通知走独立流：触发方是调用方自己，与文件变更监听无关。 */
    private val invalidations = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 令牌文件被本类之外的写入口整份覆盖后广播一次，MCP 连接层据此重建连接。
     *
     * 整份覆盖走不到 [update]，内存快照不会自己更新；不广播的话连接层会继续用旧令牌，
     * 要等下次冷启动才认新的。订阅驱动：没有订阅者时不持有文件监听。
     */
    val externalChanges: SharedFlow<Unit> = merge(
        watchHostFile(),
        invalidations
    ).shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    suspend fun getClient(issuer: String): OAuthClientRegistration? {
        val stored = load().clients[issuer] ?: return null
        return OAuthClientRegistration(issuer, stored.clientId, stored.clientSecret, stored.redirectUri)
    }

    suspend fun putClient(registration: OAuthClientRegistration) = update { root ->
        root.copy(
            clients = root.clients + (registration.issuer to
                StoredClient(registration.clientId, registration.clientSecret, registration.redirectUri))
        )
    }

    suspend fun getTokens(issuer: String): OAuthTokens? {
        val stored = load().tokens[issuer] ?: return null
        return OAuthTokens(stored.accessToken, stored.refreshToken, stored.expiresAt, stored.scope)
    }

    suspend fun putTokens(issuer: String, tokens: OAuthTokens) = update { root ->
        root.copy(
            tokens = root.tokens + (issuer to
                StoredTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtEpochSeconds, tokens.scope))
        )
    }

    /** 清除某个 issuer 的客户端注册（回调端口变化需重新注册时用）。 */
    suspend fun clearClient(issuer: String) = update { root ->
        root.copy(clients = root.clients - issuer)
    }

    suspend fun clear(issuer: String) = update { root ->
        root.copy(
            clients = root.clients - issuer,
            tokens = root.tokens - issuer,
            serverIssuers = root.serverIssuers.filterValues { it != issuer }
        )
    }

    /** server 名到 issuer 的映射，用于重启后按 server 恢复会话与刷新。 */
    suspend fun getIssuerForServer(serverName: String): String? = load().serverIssuers[serverName]

    suspend fun putServerIssuer(serverName: String, issuer: String) = update { root ->
        root.copy(serverIssuers = root.serverIssuers + (serverName to issuer))
    }

    suspend fun clearServerIssuer(serverName: String) = update { root ->
        root.copy(serverIssuers = root.serverIssuers - serverName)
    }

    /**
     * 丢掉内存快照并广播一次。文件被本类之外的入口整份覆盖后调用：不必等文件监听那一轮，
     * 下一次读直接来自磁盘，订阅方（MCP 连接层）立即重建连接。
     */
    suspend fun invalidate() {
        mutex.withLock { cache = null }
        invalidations.tryEmit(Unit)
    }

    private suspend fun load(): StoreRoot {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: readFromDisk().also { cache = it }
        }
    }

    private suspend fun update(transform: (StoreRoot) -> StoreRoot) {
        mutex.withLock {
            val next = transform(cache ?: readFromDisk())
            // 落盘成功才换内存快照：否则内存与磁盘分叉，重启后「刚授权却要重新登录」。
            if (writeToDisk(next)) {
                cache = next
            } else {
                FileLogger.w(TAG, "写入 OAuth 存储失败，内存保持旧快照")
            }
        }
    }

    /** 盯住令牌文件所在目录，本文件被改动时把磁盘内容与快照比对，确有差异才广播。 */
    private fun watchHostFile(): Flow<Unit> {
        val dir = file.absoluteFile.parentFile ?: return emptyFlow()
        return fileChangeHub.watchHostDir(hostDir = dir).mapNotNull { batch ->
            val touched = batch.changes.any { File(it.hostPath).absolutePath == file.absolutePath }
            if (touched) refreshFromDiskIfChanged() else null
        }
    }

    /**
     * 磁盘内容与本进程快照不一致时以磁盘为准，返回 Unit 表示需要广播。
     * 快照尚未加载时不算变化（下次读本来就读磁盘）；文件不存在或这次读不出来也算「没变化」，
     * 不能把「读不到」当成空存储，否则写入方中途的短暂状态会清掉内存里的令牌。
     */
    private suspend fun refreshFromDiskIfChanged(): Unit? = mutex.withLock {
        val current = cache ?: return@withLock null
        if (!file.isFile) return@withLock null
        val disk = readFromDiskOrNull() ?: return@withLock null
        if (disk == current) return@withLock null
        cache = disk
        FileLogger.i(TAG, "磁盘上的 OAuth 令牌已变化，刷新内存快照")
        Unit
    }

    private suspend fun readFromDisk(): StoreRoot = readFromDiskOrNull() ?: StoreRoot()

    /** 读不出来时返回 null（已记日志）：调用方据此区分「存储本来就是空的」与「这次没读到」。 */
    private suspend fun readFromDiskOrNull(): StoreRoot? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext StoreRoot()
        runCatching {
            val raw = file.readText()
            val decoded = tryDecode(raw) ?: raw
            JSON.decodeFromString(StoreRoot.serializer(), decoded)
        }.getOrElse {
            FileLogger.w(TAG, "读取 OAuth 存储失败：${it.message}")
            null
        }
    }

    private suspend fun writeToDisk(root: StoreRoot): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = encode(JSON.encodeToString(StoreRoot.serializer(), root))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encoded)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(encoded)
                tmp.delete()
            }
        }.onFailure { FileLogger.w(TAG, "写入 OAuth 存储失败：${it.message}") }
            .isSuccess
    }

    /** 编码：base64 后整体反转，使文件不是可直接回显的明文。 */
    private fun encode(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8)).reversed()

    /**
     * 解码编码串；不是本格式时返回 null。用往返校验判定格式，避免把明文误当编码串解出乱码。
     */
    private fun tryDecode(text: String): String? = runCatching {
        val raw = text.trim()
        if (raw.isEmpty()) return@runCatching null
        val decoded = String(Base64.getDecoder().decode(raw.reversed()), Charsets.UTF_8)
        if (encode(decoded) != raw) return@runCatching null
        decoded
    }.getOrNull()
}

@Serializable
internal data class StoreRoot(
    val clients: Map<String, StoredClient> = emptyMap(),
    val tokens: Map<String, StoredTokens> = emptyMap(),
    val serverIssuers: Map<String, String> = emptyMap()
)

@Serializable
internal data class StoredClient(
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String
)

@Serializable
internal data class StoredTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val scope: String? = null
)
