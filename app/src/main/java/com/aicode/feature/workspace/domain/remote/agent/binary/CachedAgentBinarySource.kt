package com.aicode.feature.workspace.domain.remote.agent.binary

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.agent.RemoteAgentArch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已下载到本地的 agent 二进制（`filesDir/remote-agent/`）。
 *
 * 排在来源链最前面：已经下过就没必要每次连接都联网。不做「过期」策略——内容变化会在清单里
 * 体现（sha256 变了），下次真的需要下载时自然会换。
 *
 * 校验靠 sha256 旁文件：只看文件名或大小的话，一个被截断的旧文件会被当成好的传上去。
 * 旁文件缺失或对不上都返回 null，让后面的来源重新取，而不是冒险用。
 */
@Singleton
class CachedAgentBinarySource @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AgentBinarySource {

    override val name: String = "cache"

    override suspend fun load(arch: RemoteAgentArch): ByteArray? = withContext(Dispatchers.IO) {
        val file = AgentBinaryStorage.fileOf(context, arch)
        if (!file.isFile || file.length() == 0L) return@withContext null

        val expected = runCatching {
            AgentBinaryStorage.sidecarOf(file).readText().trim().lowercase()
        }.getOrNull()?.takeIf { it.length == 64 }
        if (expected == null) {
            FileLogger.d(TAG, "缓存缺少 sha256 旁文件，跳过: ${file.name}")
            return@withContext null
        }

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        if (AgentBinaryStorage.sha256Hex(bytes) != expected) {
            FileLogger.e(TAG, "缓存内容与摘要不符，删除后重新获取: ${file.name}")
            runCatching { file.delete() }
            runCatching { AgentBinaryStorage.sidecarOf(file).delete() }
            return@withContext null
        }
        bytes
    }

    private companion object {
        const val TAG = "AgentBinaryCache"
    }
}
