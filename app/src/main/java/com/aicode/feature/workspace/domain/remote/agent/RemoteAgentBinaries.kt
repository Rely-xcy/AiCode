package com.aicode.feature.workspace.domain.remote.agent

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.zip.GZIPInputStream
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
 * 文件按「协议版本 + 目标架构」命名，与远端落盘名一致（只是多一个 `.gz`）：
 * `remote-agent/aicode-agent-v1-linux-amd64.gz`。名字里带版本是刻意的——
 * [RemoteAgentProvisioner] 靠「远端同名文件大小一致」判定跳过上传，换版本即换文件名，
 * 不必每次下载回来算哈希。
 *
 * 为什么存的是 gzip 而不是裸二进制：两个架构裸文件加起来 4.66MB，压缩后只有 1.96MB，
 * 而解压只多占一次瞬时内存（读出即吃完就丢），比让每个用户都多装 2.7MB 划算。
 * 上传给远端的仍然是解压后的原始字节，文件名不带 `.gz`。
 */
@Singleton
class RemoteAgentBinaries @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 读取指定架构的二进制（已解压）。文件缺失、为空、gzip 损坏都抛 [RemoteAgentProvisionException]：
     * 这几种情况都表示打包出了问题，静默返回空数组只会在远端换来一条难查的 exec 失败。
     */
    suspend fun load(arch: RemoteAgentArch): ByteArray = withContext(Dispatchers.IO) {
        val name = assetName(arch)
        val bytes = try {
            context.assets.open(name).use { raw ->
                GZIPInputStream(raw, GZIP_BUFFER_BYTES).use { it.readBytes() }
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "读取内置 agent 失败: $name", e)
            throw RemoteAgentProvisionException("内置 agent 二进制读不出: $name", e)
        }
        if (bytes.isEmpty()) {
            throw RemoteAgentProvisionException("内置 agent 二进制为空: $name")
        }
        FileLogger.d(TAG, "读取内置 agent: $name (解压后 ${bytes.size}B)")
        bytes
    }

    companion object {
        private const val TAG = "RemoteAgentBinaries"

        /** assets 下的子目录。 */
        const val ASSET_DIR: String = "remote-agent"

        /** 与 [RemoteAgentProvisioner] 远端落盘名保持同一个规则（多一个 `.gz`），否则「大小一致即跳过」会永远不命中。 */
        fun assetName(arch: RemoteAgentArch): String =
            "$ASSET_DIR/aicode-agent-v$REMOTE_AGENT_PROTOCOL_VERSION-linux-${arch.suffix}.gz"

        /** 64KB 够大，解压一次就完；再大只是白占内存。 */
        private const val GZIP_BUFFER_BYTES = 64 * 1024
    }
}
