package com.aicode.feature.workspace.domain.remote.agent.binary

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.agent.RemoteAgentArch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 内置的 agent 二进制（`assets/remote-agent/`）。
 *
 * 排在缓存之后、网络之前：联网时优先用新下载的，断网或下载失败时它兜底，
 * 保证「最坏情况跟没做这个功能时一样」。第三步（确认下载稳定后）会删掉 assets 与本类。
 */
@Singleton
class AssetsAgentBinarySource @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AgentBinarySource {

    override val name: String = "assets"

    override suspend fun load(arch: RemoteAgentArch): ByteArray? = withContext(Dispatchers.IO) {
        val name = AgentBinaryStorage.assetsPath(arch)
        val bytes = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: IOException) {
            // 这一版可能已经把 assets 删了，属正常情况，不记 error
            FileLogger.d(TAG, "内置 agent 不存在: $name")
            return@withContext null
        }
        if (bytes.isEmpty()) {
            FileLogger.e(TAG, "内置 agent 为空: $name")
            return@withContext null
        }
        bytes
    }

    private companion object {
        const val TAG = "AgentBinaryAssets"
    }
}
