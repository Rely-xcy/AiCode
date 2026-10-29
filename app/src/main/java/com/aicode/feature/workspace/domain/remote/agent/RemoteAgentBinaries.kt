package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.agent.binary.AgentBinarySource
import com.aicode.feature.workspace.domain.remote.agent.binary.AgentBinaryStorage
import com.aicode.feature.workspace.domain.remote.agent.binary.AssetsAgentBinarySource
import com.aicode.feature.workspace.domain.remote.agent.binary.CachedAgentBinarySource
import com.aicode.feature.workspace.domain.remote.agent.binary.NetworkAgentBinarySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按优先级取 agent 二进制，供 [RemoteAgentProvisioner] 上传。
 *
 * 来源顺序：**缓存（已下载且校验通过）→ APK 内置 assets → 站点下载**。
 *
 * - 缓存排第一：下过就没必要每次连网；离线也能用。
 * - assets 排第二：联网时优先用新的，断网或下载失败时它兜底，保证最坏情况与没做下载功能时一样。
 * - 下载排最后：只在前面都没有时才拉清单。
 *
 * 为什么要多这一层：agent 二进制不再随 APK 分发后，换二进制不用发版；
 * 但远程模式的可用性不能因此变差——四个来源全拿不到时抛 [RemoteAgentProvisionException]，
 * 上层照旧降级回 SFTP。
 *
 * 注意本类不持有 Context：Android 依赖都关在各个 Source 里，
 * [RemoteAgentProvisioner] 与它的纯 JVM 单测因此不用 mock Android 环境。
 */
@Singleton
class RemoteAgentBinaries @Inject constructor(
    cached: CachedAgentBinarySource,
    bundled: AssetsAgentBinarySource,
    network: NetworkAgentBinarySource
) {
    /** 顺序即优先级，别调换。 */
    private val sources: List<AgentBinarySource> = listOf(cached, bundled, network)

    /**
     * 取指定架构的二进制。全都拿不到时抛 [RemoteAgentProvisionException]——
     * 静默返回空数组只会在远端换来一条难查的 exec 失败。
     */
    suspend fun load(arch: RemoteAgentArch): ByteArray {
        for (source in sources) {
            val bytes = source.load(arch)
            if (bytes != null && bytes.isNotEmpty()) {
                FileLogger.d(TAG, "agent 二进制来自 ${source.name}: ${assetName(arch)} (${bytes.size}B)")
                return bytes
            }
        }
        throw RemoteAgentProvisionException("拿不到 agent 二进制（缓存/内置/下载都失败）")
    }

    companion object {
        private const val TAG = "RemoteAgentBinaries"

        /** assets 下的子目录；缓存目录同名（`filesDir/remote-agent/`）。 */
        const val ASSET_DIR: String = "remote-agent"

        /**
         * 与 [RemoteAgentProvisioner] 远端落盘名保持同一个规则，否则「大小一致即跳过」会永远不命中。
         *
         * 为什么文件名不带 `.gz`：AAPT 打包时会把 `.gz` 后缀的文件解压并去掉后缀，
         * 按 `.gz` 名字去取会直接找不到；而 APK 本身就会 deflate 压缩 assets，多一层只是白白多一道解压。
         */
        fun assetName(arch: RemoteAgentArch): String = AgentBinaryStorage.assetsPath(arch)
    }
}
