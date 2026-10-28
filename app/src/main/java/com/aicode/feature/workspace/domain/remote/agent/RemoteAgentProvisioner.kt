package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException

private const val TAG = "RemoteAgentProvisioner"

/** 远端 agent 默认安装目录。`~` 由 [FileAccessProvider] 的远程实现展开为登录用户 home。 */
const val REMOTE_AGENT_DEFAULT_DIR: String = "~/.aicode/agent"

/** 远端 CPU 架构。文件名后缀跟 `uname -m` 的常见两种取值对齐，其余架构暂不支持。 */
enum class RemoteAgentArch(val suffix: String, private val unameNames: Set<String>) {
    AMD64("amd64", setOf("x86_64", "amd64")),
    ARM64("arm64", setOf("aarch64", "arm64"));

    companion object {
        /** `uname -m` 输出 → 架构；未知架构返回 null，由调用方给出明确错误。 */
        fun fromUname(uname: String): RemoteAgentArch? {
            val normalized = uname.trim().lowercase()
            return entries.firstOrNull { normalized in it.unameNames }
        }
    }
}

/** 部署结果。[uploaded] 为 false 表示远端已有一致版本、本次跳过上传。 */
data class RemoteAgentInstallation(
    val binaryPath: String,
    val arch: RemoteAgentArch,
    val version: Int,
    val uploaded: Boolean
) {
    /** 拼出可直接交给 exec 会话的启动命令（路径已做 shell 转义）。 */
    fun launchCommand(root: String): String = RemoteAgentLaunch.command(binaryPath, root)
}

/** 部署失败：架构不支持、二进制为空、上传后大小不符等。 */
class RemoteAgentProvisionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 把内置的 Go agent 二进制送上远端并赋予可执行位。
 *
 * 上传一律走 [FileAccessProvider.writeBytes]（远程模式即 RemoteSftpFileAccess），不在本地拼
 * 上传命令：SFTP 写法已有「临时文件 + 原子 rename」保护，拼 shell 会把这份保护绕掉。
 * `chmod` 没有对应的 FileAccessProvider 接口，只能走 [runCommand]；路径必须经
 * [shellPath] 转义。
 *
 * 跳过逻辑依赖「文件名带协议版本」：`aicode-agent-v1-linux-amd64`。版本升级会换文件名，
 * 因此只要远端同名文件大小一致就可判定为同一版本，不必每次下载回来算 hash。
 *
 * [agentDir] 默认含 `~`，由 [FileAccessProvider] 的远程实现展开；若该实现拿不到登录 home
 * （`~` 不会被展开成绝对路径），调用方必须改传绝对的 agentDir，否则文件会落到错误位置。
 */
class RemoteAgentProvisioner(
    private val fileAccess: FileAccessProvider,
    /** 执行远端命令并返回 stdout；非零退出应由实现抛异常。 */
    private val runCommand: suspend (String) -> String,
    /** 按架构提供内置二进制（期 1 由调用方从 assets 读取，本类不绑定 Android Context）。 */
    private val loadBinary: suspend (RemoteAgentArch) -> ByteArray,
    private val agentDir: String = REMOTE_AGENT_DEFAULT_DIR
) {

    init {
        require(agentDir.isNotBlank()) { "agentDir 不能为空" }
    }

    /** 确保远端有可用 agent，返回可执行文件路径与启动命令。 */
    suspend fun ensureInstalled(): RemoteAgentInstallation {
        val arch = detectArch()
        val binary = loadBinary(arch)
        if (binary.isEmpty()) {
            throw RemoteAgentProvisionException("内置 agent 二进制为空: $arch")
        }

        val fileName = "aicode-agent-v$REMOTE_AGENT_PROTOCOL_VERSION-linux-${arch.suffix}"
        val remotePath = joinPath(agentDir, fileName)
        val expectedSize = binary.size.toLong()
        val existingSize = sizeOrNull(remotePath)
        val uploaded = if (existingSize == expectedSize) {
            FileLogger.d(TAG, "远端 agent 版本一致，跳过上传: $remotePath (${expectedSize}B)")
            false
        } else {
            FileLogger.d(TAG, "上传 agent: $remotePath (${expectedSize}B，远端 ${existingSize ?: "无"}B)")
            fileAccess.writeBytes(remotePath, binary, overwrite = true)
            true
        }

        // 无论是否跳过上传都 chmod：从备份恢复或手工拷贝可能丢掉可执行位，而文件名/大小都正常
        runCommand("chmod +x ${shellPath(remotePath)}")

        val installedSize = sizeOrNull(remotePath)
        if (installedSize != expectedSize) {
            throw RemoteAgentProvisionException(
                "agent 落盘大小不符: 期望 ${expectedSize}B，实际 ${installedSize ?: "读取失败"}B ($remotePath)"
            )
        }
        return RemoteAgentInstallation(remotePath, arch, REMOTE_AGENT_PROTOCOL_VERSION, uploaded)
    }

    private suspend fun detectArch(): RemoteAgentArch {
        val uname = try {
            runCommand("uname -m").trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteAgentProvisionException("探测远端架构失败: ${e.message}", e)
        }
        return RemoteAgentArch.fromUname(uname)
            ?: throw RemoteAgentProvisionException("不支持的远端架构: '$uname'（仅支持 x86_64 / aarch64）")
    }

    /**
     * 远端文件大小。RemoteSftpFileAccess 对不存在的文件返回 0，LocalFileAccess 可能抛
     * NoSuchFileException；两种都要归成「未安装」，不能因此中断部署。
     */
    private fun sizeOrNull(path: String): Long? = try {
        fileAccess.fileSize(path).takeIf { it > 0L }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "读取远端 agent 大小失败，按未安装处理: $path", e)
        null
    }

    private fun joinPath(dir: String, name: String): String = dir.trimEnd('/') + "/" + name
}
