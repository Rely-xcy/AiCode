package com.aicode.feature.workspace.domain.remote.agent

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 APK 的 assets 读内置的远端 agent 二进制，供 [RemoteAgentProvisioner] 上传。
 *
 * 为什么不直接让 [RemoteAgentProvisioner] 持有 Context：那个类要能在纯 JVM 单测里跑
 * （上传、跳过、chmod、大小校验全靠注入的 FileAccess 与 runCommand 替身），
 * 把 Android 依赖关在这一层，测试就不用把它一起 mock 掉。
 *
 * 文件按「协议版本 + 目标架构」命名，与远端落盘名一致：
 * `remote-agent/aicode-agent-v1-linux-amd64`。名字里带版本是刻意的——
 * [RemoteAgentProvisioner] 靠「远端同名文件大小一致」判定跳过上传，换版本即换文件名，
 * 不必每次下载回来算哈希。
 *
 * 这两个文件没有扩展名，AAPT 不会压缩它们，所以 `assets.open` 拿到的是原始字节，
 * 不需要额外的 `noCompress` 配置。
 */
@Singleton
class RemoteAgentBinaries @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 读取指定架构的二进制。文件缺失或为空都抛 [RemoteAgentProvisionException]：
     * 这两种情况都表示打包出了问题，静默返回空数组只会在远端换来一条难查的 exec 失败。
     */
    suspend fun load(arch: RemoteAgentArch): ByteArray = withContext(Dispatchers.IO) {
        val name = assetName(arch)
        val bytes = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: IOException) {
            FileLogger.e(TAG, "读取内置 agent 失败: $name", e)
            throw RemoteAgentProvisionException("内置 agent 二进制缺失: $name", e)
        }
        if (bytes.isEmpty()) {
            throw RemoteAgentProvisionException("内置 agent 二进制为空: $name")
        }
        FileLogger.d(TAG, "读取内置 agent: $name (${bytes.size}B)")
        bytes
    }

    companion object {
        private const val TAG = "RemoteAgentBinaries"

        /** assets 下的子目录。 */
        const val ASSET_DIR: String = "remote-agent"

        /** 与 [RemoteAgentProvisioner] 远端落盘名保持同一个规则，否则「大小一致即跳过」会永远不命中。 */
        fun assetName(arch: RemoteAgentArch): String =
            "$ASSET_DIR/aicode-agent-v$REMOTE_AGENT_PROTOCOL_VERSION-linux-${arch.suffix}"
    }
}
