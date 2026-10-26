package com.aicode.feature.agent.domain.vscreen

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * root 特权通道：以 uid 0 执行宿主 Android 命令。
 *
 * 与 [com.aicode.feature.agent.domain.shizuku.ShizukuManager]（uid 2000）的区别：
 * - 能创建交互式 VirtualDisplay（普通 app 进程内建的屏是 PRESENTATION 级，承载不了完整 Activity）；
 * - 无 Shizuku 的绑定/断连状态机，调用更直接。
 *
 * 候选 su 依次探测并缓存：容器 PRoot 里的 busybox `su` 是假 root（uid 0 但非设备 root，
 * 宿主命令不可用），所以必须**验证 `id` 真的返回 uid=0** 才算可用，不能只看二进制存在。
 *
 * [temp] root 分支实验件：命令模板未在真机验证过，随阶段 0 的可行性结论调整。
 */
@Singleton
class RootShell @Inject constructor() {

    data class Result(val output: String, val exitCode: Int) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 已验证可用的 su 路径；null 表示尚未验证过，[NOT_AVAILABLE] 表示设备没有可用的 root。 */
    @Volatile
    private var cachedSu: String? = null

    private val probeFailures = ConcurrentHashMap<String, String>()

    /** 设备是否具备可用的 root（`su -c id` 返回 uid=0）。 */
    suspend fun isAvailable(): Boolean = resolveSu() != null

    /** 根因描述：为什么没有可用的 root。用于工具把失败原因如实回给模型。 */
    suspend fun unavailableReason(): String =
        "未找到可用的 root（su -c id 未返回 uid=0）。依次尝试过：${SU_CANDIDATES.joinToString(", ")}；" +
            probeFailures.entries.joinToString("；") { "${it.key}: ${it.value}" }

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val su = resolveSu() ?: return Result(unavailableReason(), -1)
        return withContext(Dispatchers.IO) { exec(listOf(su, "-c", command), timeoutMs) }
    }

    /** 探测并缓存可用的 su。并发调用无妨：重复探测只是多跑几次 `id`。 */
    private suspend fun resolveSu(): String? {
        cachedSu?.let { return it }
        for (candidate in SU_CANDIDATES) {
            val r = withContext(Dispatchers.IO) { exec(listOf(candidate, "-c", "id"), PROBE_TIMEOUT_MS) }
            when {
                r.exitCode == 0 && r.output.contains("uid=0") -> {
                    cachedSu = candidate
                    FileLogger.i(TAG, "root 通道可用：$candidate")
                    return candidate
                }
                else -> probeFailures[candidate] = r.output.trim().take(120).ifEmpty { "exit=${r.exitCode}" }
            }
        }
        FileLogger.w(TAG, "root 通道不可用：${probeFailures.entries.joinToString("；") { "${it.key}=${it.value}" }}")
        return null
    }

    /**
     * 执行一条 argv。
     *
     * 输出重定向到临时文件再读：直接读 [Process] 的 stdout 会在「命令挂住不吐字」时阻塞住超时判定，
     * 而那些命令（`dumpsys display` 偶发挂住）正是最需要超时兜底的。
     */
    private fun exec(argv: List<String>, timeoutMs: Long): Result {
        val outFile = File.createTempFile("rootshell-", ".log")
        return try {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(outFile)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                Result("命令超时（${timeoutMs}ms）：${argv.joinToString(" ")}", -1)
            } else {
                Result(outFile.readText(), process.exitValue())
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "RootShell 执行失败: ${argv.joinToString(" ")}", e)
            Result(e.message ?: e.javaClass.simpleName, -1)
        } finally {
            outFile.delete()
        }
    }

    private companion object {
        const val TAG = "RootShell"
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val PROBE_TIMEOUT_MS = 5_000L

        /** 候选 su 路径，按顺序探测。 */
        val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")
    }
}
