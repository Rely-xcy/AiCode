package com.aicode.feature.agent.domain.tool.file

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.sha256Fingerprint
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/**
 * 写入前的文件快照：一次整篇读取同时给出原始字节、文本与内容指纹。
 *
 * 为什么需要它：`writeFile` / `editFile` 的一次调用里，写前版本校验、差异渲染、写后版本登记
 * 都要看同一份内容。各自去读一遍，远程 SFTP 下就是同一个文件被完整传输 3-4 次（每次都是一次
 * stat 加一次通道读取）。快照让这些用途共用同一次读取，同时**保持校验口径不变**：指纹仍按
 * 落盘的原始字节计算，而不是按解码后的文本重算——非 UTF-8 文件重编码后哈希会变，那会把
 * 一次合法写入误判成过期写。
 *
 * [com.aicode.feature.agent.domain.checkpoint.CheckpointManager] 也可用它把
 * 「exists + fingerprint + readFile」三次访问收敛成一次（该文件不在本次改动范围内）。
 */
internal class FileSnapshot(val bytes: ByteArray, val text: String, val fingerprint: String)

/**
 * 读取 [path] 的快照；文件不存在（或不是可读的普通文件）时返回 null，其它失败照常抛出。
 *
 * 用抛出的异常而不是先 `exists()` 再读，是为了把「存在性判断」与「读正文」合并成一次访问：
 * 需要快照的调用方本来就必须读全文，多一次 stat 只是为了区分「不存在」与「读失败」。
 */
internal fun readFileSnapshot(fileAccess: FileAccessProvider, path: String): FileSnapshot? {
    val bytes = try {
        fileAccess.readBytes(path)
    } catch (_: NoSuchFileException) {
        return null
    } catch (_: FileNotFoundException) {
        return null
    }
    return FileSnapshot(bytes, String(bytes, Charsets.UTF_8), sha256Fingerprint(bytes))
}
