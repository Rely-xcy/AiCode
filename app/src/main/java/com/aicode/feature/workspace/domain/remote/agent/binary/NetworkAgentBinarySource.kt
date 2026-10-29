package com.aicode.feature.workspace.domain.remote.agent.binary

import android.content.Context
import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.agent.REMOTE_AGENT_PROTOCOL_VERSION
import com.aicode.feature.workspace.domain.remote.agent.RemoteAgentArch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从站点下载 agent 二进制（`{BASE}/agent/manifest.json` → `{BASE}/agent/<file>`）。
 *
 * 排在来源链最后：能离线、能用内置，就不该联网。清单只在真的要下载时才拉一次。
 *
 * 几条必须守住的：
 * - **原子落盘**：先写 `<文件>.part`，校验通过再 rename。直接写目标文件，中途断网会留下半截，
 *   下次启动会把坏文件当好的传上去。
 * - **大小 + sha256 都要校验**：size 先挡掉明显错的，sha256 才是真校验。
 * - **串行**：工作区初始化可能并发触发 warmUp，两个协程同时下同一个文件会把彼此写坏。
 * - 失败一律返回 null，不抛异常——远程模式的可用性优先，拿不到就让上层降级回 SFTP。
 */
@Singleton
class NetworkAgentBinarySource @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AgentBinarySource {

    override val name: String = "network"

    /** 同一时刻只允许一个下载在跑。 */
    private val mutex = Mutex()

    override suspend fun load(arch: RemoteAgentArch): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) { download(arch) }
    }

    private fun download(arch: RemoteAgentArch): ByteArray? {
        val manifest = fetchManifest() ?: return null
        val entry = manifest.pick(REMOTE_AGENT_PROTOCOL_VERSION, arch.suffix) ?: run {
            FileLogger.d(TAG, "清单里没有 proto=$REMOTE_AGENT_PROTOCOL_VERSION arch=${arch.suffix} 的条目")
            return null
        }
        if (entry.size <= 0L || entry.size > MAX_AGENT_BINARY_BYTES) {
            FileLogger.e(TAG, "清单里的 size 不合理: ${entry.size}")
            return null
        }

        val dir = AgentBinaryStorage.dir(context).apply { mkdirs() }
        val target = File(dir, AgentBinaryStorage.name(arch))
        val part = File(dir, target.name + ".part")
        runCatching { part.delete() }

        val url = "$BASE/${entry.file}"
        val ok = runCatching { fetchTo(url, part, entry.size) }.getOrElse { e ->
            FileLogger.e(TAG, "下载失败: $url", e)
            false
        }
        if (!ok) {
            runCatching { part.delete() }
            return null
        }

        if (part.length() != entry.size) {
            FileLogger.e(TAG, "大小不符（期望 ${entry.size}，实际 ${part.length()}），丢弃")
            runCatching { part.delete() }
            return null
        }
        val actual = runCatching { AgentBinaryStorage.sha256Hex(part.readBytes()) }.getOrNull()
        if (actual == null || actual != entry.sha256) {
            FileLogger.e(TAG, "sha256 不符（期望 ${entry.sha256}，实际 $actual），丢弃")
            runCatching { part.delete() }
            return null
        }

        runCatching {
            // 只留当前版本：先删旧的再换上新的
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("rename 失败")
            AgentBinaryStorage.sidecarOf(target).writeText(entry.sha256)
        }.onFailure { e ->
            FileLogger.e(TAG, "落盘失败", e)
            runCatching { part.delete() }
            return null
        }
        FileLogger.d(TAG, "已下载 agent: ${target.name} (${entry.size}B)")
        return runCatching { target.readBytes() }.getOrNull()
    }

    /** 拉清单；10 秒超时，失败返回 null（不重试：连接时不该卡住）。 */
    private fun fetchManifest(): AgentBinaryManifest? {
        val req = Request.Builder().url("$BASE/manifest.json").get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FileLogger.e(TAG, "清单 HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string().orEmpty()
                AgentBinaryManifest.parse(body)
            }
        }.getOrElse { e ->
            FileLogger.e(TAG, "拉清单失败", e)
            null
        }
    }

    /** 流式写入 [part]，边写边卡上限；返回是否成功。 */
    private fun fetchTo(url: String, part: File, expectedSize: Long): Boolean {
        val req = Request.Builder().url(url).get().build()
        val call = client.newCall(req)
        // 清单要快，二进制可以慢些
        call.timeout().timeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                FileLogger.e(TAG, "下载 HTTP ${resp.code}: $url")
                return false
            }
            val stream = resp.body?.byteStream() ?: return false
            stream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_AGENT_BINARY_BYTES) {
                            FileLogger.e(TAG, "超过 $MAX_AGENT_BINARY_BYTES 字节上限，中止")
                            return false
                        }
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
        }
        return true
    }

    private companion object {
        const val TAG = "AgentBinaryNetwork"

        /** 站点根：与更新接口同一个域名，Worker 上有 /agent 前缀反代到 Pages。 */
        const val BASE = "https://rely-xfer.de5.net/agent"

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val TRANSFER_TIMEOUT_SECONDS = 60L

        /** 挂全局代理认证：与更新检测、APK 下载同一套；其余用我们自己的超时。 */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}
