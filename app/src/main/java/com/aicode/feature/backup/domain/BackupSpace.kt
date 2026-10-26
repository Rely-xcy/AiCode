package com.aicode.feature.backup.domain

import android.os.StatFs
import java.io.File
import kotlin.math.ceil

/** 目标目录所在分区的可用空间（字节）。抽成接口只为单测能注入「空间不足」的取值。 */
fun interface AvailableSpaceProvider {
    fun availableBytes(dir: File): Long
}

/**
 * 真实实现：读文件系统剩余空间。
 *
 * 读失败（目录刚被删、StatFs 抛异常）时返回 [Long.MAX_VALUE]，即不阻断操作：
 * 空间检查是保护措施，不该因为「量不出来」变成新的失败点。
 */
object StatFsAvailableSpaceProvider : AvailableSpaceProvider {
    override fun availableBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
}

/** 空间不足，恢复/导出在写入任何数据之前就中止。继承 [IllegalStateException] 以便 import 的错误映射原样透出。 */
class InsufficientSpaceException(
    val requiredBytes: Long,
    val availableBytes: Long
) : IllegalStateException("存储空间不足：需要约 $requiredBytes 字节，可用 $availableBytes 字节")

/**
 * 空间预检：写入前用「待写入体积」估算目标分区是否够用。
 *
 * 判定式：可用空间 >= 待写入体积 * [SAFETY_FACTOR]。
 * 余量依据：恢复要把 jsonl/tar 里的文本落成 SQLite 数据页，还要同时容纳 WAL、页分裂产生的空闲页
 * 以及加密备份已在缓存里的解密临时文件；导出要写 jsonl 临时文件与（加密时）整包临时文件。
 * 同一份数据落库后的体积与解压后体积同量级，取 1.5 留 50% 余量覆盖这些额外占用，
 * 又不至于在小容量设备上把正常操作误判为空间不足。
 */
object BackupSpace {

    const val SAFETY_FACTOR = 1.5

    /** ceil(体积 * [SAFETY_FACTOR])；1.5 在浮点里可精确表示，常见数据量下无精度损失。 */
    fun requiredBytes(payloadBytes: Long): Long =
        if (payloadBytes <= 0) 0 else ceil(payloadBytes * SAFETY_FACTOR).toLong()

    fun isEnough(availableBytes: Long, payloadBytes: Long): Boolean =
        availableBytes >= requiredBytes(payloadBytes)

    fun requireSpace(availableBytes: Long, payloadBytes: Long) {
        val required = requiredBytes(payloadBytes)
        if (availableBytes < required) {
            throw InsufficientSpaceException(required, availableBytes)
        }
    }
}
