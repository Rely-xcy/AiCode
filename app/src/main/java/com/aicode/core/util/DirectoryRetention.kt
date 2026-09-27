package com.aicode.core.util

import java.io.File

/**
 * 目录保留策略：按「保留天数 / 总字节上限 / 文件数上限」从最旧的开始裁剪。
 *
 * 用于工具输出、压缩历史这类「写得多、几乎不再读」的中间产物目录——
 * 只靠设置页里的手动清理，用户不点就会一直涨；这里在每次写入后顺带裁一次，
 * 三个上限任一超限都会继续删，直到全部满足。
 */
object DirectoryRetention {

    private const val TAG = "DirectoryRetention"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** @return 本次实际删除的字节数。 */
    fun prune(
        dir: File,
        maxAgeDays: Int,
        maxBytes: Long,
        maxFiles: Int,
        namePrefix: String? = null
    ): Long {
        val files = runCatching {
            dir.listFiles { file: File ->
                file.isFile && (namePrefix == null || file.name.startsWith(namePrefix))
            }?.sortedBy { it.lastModified() }
        }.getOrNull() ?: return 0L
        if (files.isEmpty()) return 0L

        val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MILLIS
        var totalBytes = files.sumOf { it.length() }
        var remaining = files.size
        var freed = 0L

        for (file in files) {
            val overAge = file.lastModified() < cutoff
            val overSize = totalBytes > maxBytes
            val overCount = remaining > maxFiles
            if (!overAge && !overSize && !overCount) break

            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                freed += size
                totalBytes -= size
                remaining--
            }
        }

        if (freed > 0) {
            FileLogger.i(TAG, "裁剪 ${dir.name}: 删除 ${files.size - remaining} 个文件，释放 $freed 字节")
        }
        return freed
    }
}
