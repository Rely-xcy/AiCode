package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏截图目录的容量治理（设计 §4.3）。
 *
 * 截图是测试证据、不是长期数据：目录里本应用写的截图总额超预算时按「最久未改」先删（LRU），
 * 保证长时间跑测试不会把磁盘吃满。
 *
 * 清理**只动自己的文件**：截图目录是调用方指过来的（工具参数可以是任意目录，包括工作区根目录），
 * 按「目录里的所有文件」删会把用户自己的文件一并删掉。所以写入方与清理方共用同一份命名规则
 * （[newScreenshotFile] / [isOwnScreenshot]），规则之外的文件一个都不碰。
 *
 * 单文件超限只记日志不删：截图刚拍出来就被删掉会让 AI 拿到一个指向不存在文件的路径，
 * 那是更糟的失败模式；真出现超大截图说明目标 app 分辨率异常，值得留证。
 */
@Singleton
class ScreenshotStore @Inject constructor() {

    data class PruneResult(val deleted: Int, val freedBytes: Long)

    /** 按自己的命名规则造一个截图路径；文件名里带 displayId 便于多轮任务里对照是哪块屏。 */
    fun newScreenshotFile(dir: File, displayId: Int, nowMs: Long): File =
        File(dir, "$FILE_PREFIX$displayId-$nowMs$FILE_SUFFIX")

    /** 目录里的这个名字是不是本应用写的截图。 */
    fun isOwnScreenshot(name: String?): Boolean = name != null && FILE_NAME.matches(name)

    /**
     * 清理 [dir] 下超过 [maxTotalBytes] 的**自己的**截图，返回删除结果。
     *
     * 目录不存在、没有自己的截图、或未超预算时不做事（返回 0），调用方不需先判断。
     */
    fun prune(dir: File, maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES): PruneResult {
        val files = dir.listFiles()
            ?.filter { it.isFile && isOwnScreenshot(it.name) }
            ?.sortedBy { it.lastModified() }
            ?: return PruneResult(0, 0)
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return PruneResult(0, 0)

        var deleted = 0
        var freed = 0L
        for (file in files) {
            if (total <= maxTotalBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                freed += size
                deleted++
            }
        }
        if (deleted > 0) {
            FileLogger.i(TAG, "截图目录超预算，清理 $deleted 个文件、释放 $freed 字节：${dir.absolutePath}")
        }
        return PruneResult(deleted, freed)
    }

    /** 单张截图是否超过单文件上限（超限只记日志，见类注释）。 */
    fun warnIfOversized(file: File): Boolean {
        val oversized = file.length() > DEFAULT_MAX_FILE_BYTES
        if (oversized) {
            FileLogger.w(TAG, "单张截图超过上限（${file.length()} 字节）：${file.absolutePath}")
        }
        return oversized
    }

    companion object {
        const val FILE_PREFIX = "vscreen-"
        const val FILE_SUFFIX = ".png"

        /**
         * 自己的截图长这样：`vscreen-<displayId>-<毫秒时间戳>.png`。
         * 用 `\d+` 而不是只比前缀，是为了让「同名但不是自己的」文件（例如用户手写的
         * `vscreen-notes.png`）也不会被删。
         */
        private val FILE_NAME =
            Regex("^" + Regex.escape(FILE_PREFIX) + "\\d+-\\d+" + Regex.escape(FILE_SUFFIX) + "$")

        const val DEFAULT_MAX_TOTAL_BYTES = 200L * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 5L * 1024 * 1024
        private const val TAG = "ScreenshotStore"
    }
}
