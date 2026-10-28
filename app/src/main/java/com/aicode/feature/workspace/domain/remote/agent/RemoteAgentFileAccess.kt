package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "RemoteAgentFileAccess"

/**
 * 远程文件访问的 agent 实现：**只接管读路径，写路径全部委托给 [delegate]**。
 *
 * 为什么做成装饰器而不是重写一遍完整实现：期 2 的目标是「读变快」，而写路径（临时文件 +
 * 原子 rename、编码保持、覆盖语义）已经被 [RemoteSftpFileAccess] 打磨过一遍，重写一遍只会
 * 把那些边界重新踩一遍。装饰器还能做到「读出问题一键退回」——把 delegate 换成原实例即可，
 * 调用方无感。
 *
 * 路径约定：接口入参是容器路径（`~/workspace/a.txt`、`~/.aicode/skills/...`），
 * agent 只认绝对路径，所以这里用 hello 返回的 `home` 展开 `~`，不依赖远端 shell 展开。
 * agent 的 `-root` 传的是 `$HOME` —— `~/workspace` 与 `~/.aicode` 都在它下面，
 * 换成一格更窄的 `~/workspace` 会让技能与记忆访问被安全边界挡掉。
 *
 * 同步/异步的缝：接口是同步的（历史原因，本地与 SFTP 实现都如此），agent 会话是 suspend。
 * 这里用 [runBlocking] 桥接，与 [RemoteSftpFileAccess] 的 `withSftp` 做法一致，
 * 调用方一律在 IO 线程上，不会卡主线程。
 */
class RemoteAgentFileAccess(
    private val delegate: FileAccessProvider,
    private val session: RemoteAgentSession,
    /** 远端登录用户的 home（来自 hello 的 `home` 字段），用于展开 `~`。 */
    home: String
) : FileAccessProvider {

    private val homePath: String = (session.capabilities?.home ?: home).trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }

    // ---- 读路径：走 agent ----

    override fun readFile(path: String): String {
        // 超过单次 read 上限的文件退回 SFTP 实现：先多一次 stat 也比传 1MiB 后发现截断强。
        val size = fileSizeOrNull(path)
        if (size != null && size > MAX_READ_BYTES) {
            FileLogger.d(TAG, "文件过大(${size}B)，readFile 退回 SFTP: $path")
            return delegate.readFile(path)
        }
        val response = call("read", AgentRequestParams(path = abs(path), offset = 0, len = MAX_READ_BYTES))
        return String(response.body, Charsets.UTF_8)
    }

    /**
     * 逐行读取。实现是「一次性把文件读进内存再按行切」——与 SFTP 版的惰性流不同，
     * 但读路径的调用方（ReadFileTool）本来就要整段文本做行窗口，分页拉取的复杂度换不来收益。
     * 超过 [MAX_READ_BYTES] 的文件仍然走 delegate 的惰性实现，避免把大文件整个吃进内存。
     */
    override fun readLines(path: String): Sequence<String> {
        val size = fileSizeOrNull(path) ?: throw NoSuchFileException(abs(path))
        if (size > MAX_READ_BYTES) {
            FileLogger.d(TAG, "文件过大(${size}B)，readLines 退回 SFTP 惰性实现: $path")
            return delegate.readLines(path)
        }
        return readFile(path).lineSequence()
    }

    override fun readBytes(path: String): ByteArray {
        val size = fileSizeOrNull(path)
        if (size != null && size > MAX_READ_BYTES) {
            FileLogger.d(TAG, "文件过大(${size}B)，readBytes 退回 SFTP: $path")
            return delegate.readBytes(path)
        }
        return call("read", AgentRequestParams(path = abs(path), offset = 0, len = MAX_READ_BYTES)).body
    }

    override fun exists(path: String): Boolean = statOrNull(path) != null

    override fun isDirectory(path: String): Boolean = statOrNull(path)?.isDir == true

    override fun isFile(path: String): Boolean {
        val stat = statOrNull(path) ?: return false
        return !stat.isDir
    }

    override fun fileSize(path: String): Long = statOrNull(path)?.size ?: 0L

    override fun lastModified(path: String): Long = (statOrNull(path)?.mtime ?: 0L) * 1000L

    override fun permissions(path: String): String = statOrNull(path)?.perm ?: "---"

    override fun fingerprint(path: String): String? {
        val response = runCatching { call("hash", AgentRequestParams(path = abs(path))) }.getOrNull()
            ?: return null
        return response.hash
    }

    override fun listFiles(path: String): List<FileEntry> {
        val response = call("list", AgentRequestParams(path = abs(path), maxDepth = 1))
        return parseEntries(response.body).map { it.toFileEntry() }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        // 深度 0 直接返回空，和 SFTP 实现一致（它也只是沿 maxDepth 走）。
        if (maxDepth <= 0) return emptyList()
        val response = call(
            "list",
            AgentRequestParams(path = abs(path), maxDepth = maxDepth, recursive = true)
        )
        return parseEntries(response.body)
            .filter { !it.isDir }
            .map { path.trimEnd('/') + "/" + it.name }
    }

    override fun toDisplayPath(path: String): String = delegate.toDisplayPath(path)

    override fun parentPath(path: String): String? = delegate.parentPath(path)

    // ---- 写路径与其他：期 2 先全量委托 ----

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        delegate.writeFile(path, content, overwrite, encoding)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate.writeBytes(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        delegate.writeStream(path, input, overwrite)

    override fun copyToLocal(path: String): File = delegate.copyToLocal(path)

    override fun delete(path: String) = delegate.delete(path)

    override fun deleteRecursively(path: String) = delegate.deleteRecursively(path)

    override fun rename(path: String, newPath: String) = delegate.rename(path, newPath)

    override fun copy(path: String, newPath: String, overwrite: Boolean) = delegate.copy(path, newPath, overwrite)

    override fun move(path: String, newPath: String, overwrite: Boolean) = delegate.move(path, newPath, overwrite)

    override fun mkdirs(path: String) = delegate.mkdirs(path)

    // ---- 内部 ----

    /**
     * 把容器路径展开成绝对路径。`~` 用 hello 给的 home 展开，不交给远端 shell：
     * 少一层不可控的展开规则，路径里出现 `$`、引号时也不会被解释。
     */
    private fun abs(path: String): String = when {
        path == "~" -> homePath
        path.startsWith("~/") -> homePath + path.substring(1)
        else -> path
    }

    private fun call(op: String, params: AgentRequestParams, body: ByteArray = ByteArray(0)): AgentResponse {
        val response = runBlocking { session.request(op, params, body) }
        if (!response.ok) throw response.toException(abs(params.path ?: ""))
        return response
    }

    private fun statOrNull(path: String): AgentResponse? {
        val response = runCatching { runBlocking { session.request("stat", AgentRequestParams(path = abs(path))) } }
            .getOrNull() ?: return null
        return if (response.ok) response else null
    }

    private fun fileSizeOrNull(path: String): Long? = statOrNull(path)?.size

    private fun parseEntries(body: ByteArray): List<WireEntry> {
        if (body.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<WireEntry>>(String(body, Charsets.UTF_8)) }
            .getOrElse {
                FileLogger.w(TAG, "list 响应解析失败: ${it.message}")
                emptyList()
            }
    }

    private companion object {
        /**
         * 单次 read 的上限（1 MiB，与协议里 `len` 的上限一致）。
         * 超过这个大小的文件在 readFile/readBytes 里读不全——调用方（工具层）本来也有自己的
         * 大小闸，这里只保证不会因为一个超大文件把内存吃穿。
         */
        const val MAX_READ_BYTES = 1 shl 20
    }
}

/** 远端 list 的线格式条目：`n` 名字、`t` 类型、`s` 大小、`m` mtime 秒。 */
@Serializable
private data class WireEntry(
    val n: String,
    val t: Int = 0,
    val s: Long = 0L,
    val m: Long = 0L
) {
    val isDir: Boolean get() = t == TYPE_DIR

    fun toFileEntry(): FileEntry = FileEntry(
        name = n,
        isDirectory = isDir,
        size = s,
        lastModified = m * 1000L,
        localFile = null,
        permissions = "---"
    )

    private companion object {
        const val TYPE_DIR = 1
    }
}

/** 把 agent 的错误码翻成接口约定的异常类型。 */
private fun AgentResponse.toException(path: String): IOException = when (err) {
    "ENOENT" -> NoSuchFileException(path)
    "EEXIST" -> FileAlreadyExistsException(path)
    "EACCES" -> IOException("无权限访问: $path（${msg ?: "EACCES"}）")
    "EISDIR" -> IOException("这是一个目录，不能当文件读写: $path")
    "ENOTDIR" -> IOException("这不是目录: $path")
    else -> IOException("agent 请求失败 ${err ?: "UNKNOWN"}: ${msg ?: path}")
}
