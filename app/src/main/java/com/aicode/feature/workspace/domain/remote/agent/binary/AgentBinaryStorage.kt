package com.aicode.feature.workspace.domain.remote.agent.binary

import android.content.Context
import com.aicode.feature.workspace.domain.remote.agent.REMOTE_AGENT_PROTOCOL_VERSION
import com.aicode.feature.workspace.domain.remote.agent.RemoteAgentArch
import java.io.File
import java.security.MessageDigest

/**
 * 缓存目录与文件命名的唯一出处。
 *
 * 文件名与远端落盘名、APK 内 assets 名保持同一个规则（`aicode-agent-v<proto>-linux-<arch>`），
 * 因为 [com.aicode.feature.workspace.domain.remote.agent.RemoteAgentProvisioner] 靠
 * 「远端同名文件大小一致」判定跳过上传——名字对不上就会每次都重传。
 */
internal object AgentBinaryStorage {

    /** `filesDir/remote-agent/`，缓存目录只留当前版本。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 文件名（不含目录）。 */
    fun name(arch: RemoteAgentArch): String =
        "aicode-agent-v$REMOTE_AGENT_PROTOCOL_VERSION-linux-${arch.suffix}"

    /** APK 内 assets 的完整路径（供 [AssetsAgentBinarySource] 用，避免与 RemoteAgentBinaries 互相引用）。 */
    fun assetsPath(arch: RemoteAgentArch): String = "$ASSET_DIR/${name(arch)}"

    /** assets 下的子目录；缓存目录同名（`filesDir/remote-agent/`）。 */
    const val ASSET_DIR: String = "remote-agent"

    fun fileOf(context: Context, arch: RemoteAgentArch): File = File(dir(context), name(arch))

    /**
     * sha256 旁文件。离线且没有清单时，全靠它证明缓存文件没被截断——
     * 只信文件名或大小是不够的。
     */
    fun sidecarOf(file: File): File = File(file.parentFile, file.name + SIDECAR_SUFFIX)

    /** 字节的 sha256，裸 64 位小写十六进制。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val DIR = "remote-agent"
    private const val SIDECAR_SUFFIX = ".sha256"
    private val HEX = "0123456789abcdef".toCharArray()
}

/** 单个二进制的下载大小上限，防止清单被改成指向一个巨大文件。 */
internal const val MAX_AGENT_BINARY_BYTES: Long = 32L * 1024 * 1024
