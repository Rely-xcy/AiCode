package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 被打断的代理留下的半成品：备份 + 登记 + 给下一个代理的提示。
 *
 * 解决的问题很具体：子代理被停掉、跑挂或取消时没有人收尾——它改到一半的文件留在工作区里，
 * 下一个来的代理（或主代理）看到的是几个「看起来像成品」的文件，不知道谁是半成品、谁已经写完。
 * 这里在运行结束的边界上留痕：把它写过的具体文件复制一份、写一份 manifest、并给下一个跑起来的
 * 代理一句提示（提示搭本轮的用户消息走带外通道，不进 system 前缀，不打断 KV 缓存）。
 *
 * 刻意不做的事：
 * - 不用 git 判断「改过哪些文件」：用户的工作区未必是仓库（本仓库才是），只按存在性与内容哈希算；
 * - 不扫描目录：文件清单只来自租约里已经认领过的具体路径（[com.aicode.feature.agent.domain.schedule.WriteLeaseRegistry]），
 *   声明式范围（`app/src` 这类含通配符的）不枚举；
 * - 不记录主代理自己的中断：它的半成品就在用户眼前那一轮对话里，再备份一份只是噪声。
 *
 * 用 [FileAccessProvider] 而不是 java.io.File：远程 SSH 模式下工作区不在本机，
 * 只有这一层能同时覆盖本地与远程。
 */
@Singleton
class InterruptedWorkRecorder @Inject constructor(
    private val fileAccess: FileAccessProvider
) {

    /**
     * 备份根目录。放在工作区的 `build/` 下：与构建产物同域，用户清构建目录时一起清掉，
     * 不会在源码树里越积越多。
     */
    companion object {
        const val BACKUP_ROOT = "${WorkspacePathMapper.CONTAINER_ROOT}/build/interrupted"

        private const val TAG = "InterruptedWorkRecorder"

        /** 单个文件超过这么大就只登记不复制：备份是给人看的，不该把大二进制再拷一份。 */
        private const val MAX_COPY_BYTES = 2L * 1024 * 1024

        /** 单次最多复制这么多个文件，其余只登记（manifest 里标明）。 */
        private const val MAX_COPY_FILES = 20

        /** 提示里最多列几个文件名。 */
        private const val MAX_NOTICE_FILES = 5

        /** 每个工作区最多排队的提示条数。 */
        private const val MAX_PENDING_NOTICES = 3

        private val prettyJson = Json { prettyPrint = true }
    }

    /** 进程内队列，进程被杀就没了——它只服务于「紧接着的下一个代理」，不需要落盘。 */
    private val noticesByProject = ConcurrentHashMap<String, MutableList<String>>()

    // 运行结束回调不是 suspend，备份只能另起协程；与 TaskModule 一样自持一个 IO scope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 记录一次被打断的运行。[paths] 是该代理写过的具体文件（容器路径）。
     * 没有任何路径（或路径都已被删掉）时**不落盘、不发提示**——正常流程里看不到它的存在。
     *
     * [titleProvider] 在协程里求值：取会话标题要读库，而调用方（运行结束回调）不是 suspend。
     */
    fun record(
        sessionId: String,
        projectRoot: String,
        reason: String,
        paths: List<String>,
        titleProvider: suspend () -> String
    ) {
        if (paths.none { it.isNotBlank() }) return
        scope.launch {
            runCatching {
                val title = runCatching { titleProvider() }.getOrNull().orEmpty().ifBlank { sessionId.take(8) }
                recordNow(sessionId, projectRoot, reason, title, paths)
            }.onFailure { FileLogger.w(TAG, "半成品备份失败：$sessionId", it) }
        }
    }

    /**
     * 取走该工作区待送的「半成品提示」；取走即消费，同一条不会重复塞给每个开跑的代理。
     * 没有待送提示时返回 null（绝大多数轮次如此）。
     */
    fun takeNotice(projectRoot: String): String? {
        val key = projectRoot.trim()
        if (key.isEmpty()) return null
        val queue = noticesByProject[key] ?: return null
        return synchronized(queue) {
            if (queue.isEmpty()) return@synchronized null
            val text = queue.joinToString("\n\n")
            queue.clear()
            text
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 真正干活的入口：可等待（不是扔进协程就算完），备份根可换——单测指到临时目录，
     * 免得往用户真实工作区里写备份。
     */
    internal suspend fun recordNow(
        sessionId: String,
        projectRoot: String,
        reason: String,
        title: String,
        paths: List<String>,
        backupRoot: String = BACKUP_ROOT
    ) {
        val targets = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return
        // 每个被打断的代理一个目录：同名文件不会在不同代理之间互相覆盖
        val backupDir = "$backupRoot/$sessionId"
        val entries = mutableListOf<JsonObject>()
        var copied = 0
        targets.forEach { path ->
            val entry = backupOne(path, backupDir, copyAllowed = copied < MAX_COPY_FILES) ?: return@forEach
            if (entry["copied"]?.asText() == "true") copied++
            entries += entry
        }
        if (entries.isEmpty()) {
            FileLogger.i(TAG, "半成品备份跳过：$sessionId 写过的文件都不在了")
            return
        }

        val registeredOnly = entries.count { it["copied"]?.asText() != "true" }
        val manifest = buildManifest(sessionId, title, reason, projectRoot, backupDir, entries, copied, registeredOnly)
        runCatching {
            fileAccess.writeFile("$backupDir/manifest.json", prettyJson.encodeToString(JsonObject.serializer(), manifest))
        }.onFailure { FileLogger.w(TAG, "写 manifest 失败：$backupDir", it) }

        FileLogger.w(TAG, "已登记被打断的代理留下的半成品：$sessionId 复制 $copied 份、只登记 $registeredOnly 份")
        publishNotice(projectRoot, buildNotice(title, reason, backupDir, entries))
    }

    /** 备份单个文件；文件已不在时返回 null。体积过大 / 超出复制上限时只登记。 */
    private fun backupOne(path: String, backupDir: String, copyAllowed: Boolean): JsonObject? {
        val exists = runCatching { fileAccess.isFile(path) }.getOrDefault(false)
        if (!exists) return null
        val size = runCatching { fileAccess.fileSize(path) }.getOrDefault(-1L)
        val relative = relativeUnderWorkspace(path)
        val tooBig = size < 0 || size > MAX_COPY_BYTES
        if (!copyAllowed || tooBig) return fileEntry(path, size, relative, null, copied = false)

        val bytes = runCatching { fileAccess.readBytes(path) }.getOrNull()
            ?: return fileEntry(path, size, relative, null, copied = false)
        return runCatching {
            fileAccess.writeBytes("$backupDir/$relative", bytes, overwrite = true)
            fileEntry(path, bytes.size.toLong(), relative, sha256Of(bytes), copied = true)
        }.getOrElse { e ->
            FileLogger.w(TAG, "备份文件失败：$path", e)
            fileEntry(path, size, relative, null, copied = false)
        }
    }

    private fun fileEntry(
        path: String,
        size: Long,
        backupRelativePath: String,
        sha256: String?,
        copied: Boolean
    ): JsonObject = JsonObject(
        mapOf<String, JsonElement>(
            "path" to JsonPrimitive(path),
            "bytes" to JsonPrimitive(size),
            "sha256" to JsonPrimitive(sha256.orEmpty()),
            "copied" to JsonPrimitive(copied),
            "backup" to JsonPrimitive(if (copied) backupRelativePath else "")
        )
    )

    private fun buildManifest(
        sessionId: String,
        title: String,
        reason: String,
        projectRoot: String,
        backupDir: String,
        entries: List<JsonObject>,
        copied: Int,
        registeredOnly: Int
    ): JsonObject {
        val now = System.currentTimeMillis()
        return JsonObject(
            mapOf<String, JsonElement>(
                "sessionId" to JsonPrimitive(sessionId),
                "title" to JsonPrimitive(title),
                "reason" to JsonPrimitive(reason),
                "projectRoot" to JsonPrimitive(projectRoot),
                "interruptedAt" to JsonPrimitive(now),
                "interruptedAtIso" to JsonPrimitive(Instant.ofEpochMilli(now).toString()),
                "backupDir" to JsonPrimitive(backupDir),
                "files" to JsonArray(entries),
                "copiedCount" to JsonPrimitive(copied),
                "registeredOnlyCount" to JsonPrimitive(registeredOnly),
                "note" to JsonPrimitive(
                    "copied=false 的项只登记未复制（文件过大、读不到，或超出单次复制上限 $MAX_COPY_FILES 个）；" +
                        "sha256 为空表示未复制，可对着工作区里的文件自己算。"
                )
            )
        )
    }

    private fun buildNotice(
        title: String,
        reason: String,
        backupDir: String,
        entries: List<JsonObject>
    ): String {
        val listed = entries.take(MAX_NOTICE_FILES).joinToString("\n") { entry ->
            val path = entry["path"]?.asText().orEmpty()
            val suffix = if (entry["copied"]?.asText() == "true") {
                "（备份：$backupDir/${entry["backup"]?.asText().orEmpty()}）"
            } else {
                "（只登记，未复制）"
            }
            "· $path$suffix"
        }
        val rest = entries.size - MAX_NOTICE_FILES
        return buildString {
            append("[半成品提示] 上一个代理「$title」$reason，它改过下面这些文件，可能只做了一半：\n")
            append(listed)
            if (rest > 0) append("\n· …另有 $rest 个文件")
            append("\n动手前先读一眼当前内容，别假设它已经写完；备份与清单在 $backupDir/。")
        }
    }

    private fun publishNotice(projectRoot: String, notice: String) {
        val key = projectRoot.trim()
        if (key.isEmpty()) return
        val queue = noticesByProject.computeIfAbsent(key) { mutableListOf() }
        synchronized(queue) {
            queue += notice
            while (queue.size > MAX_PENDING_NOTICES) queue.removeAt(0)
        }
    }

    /**
     * 备份里的相对路径：工作区内的文件按原相对路径放，工作区外的归到 `_abs/` 下（保留原层级）。
     * 逐段过滤 `.` 与 `..`，避免路径里出现跳出备份目录的段。
     */
    private fun relativeUnderWorkspace(path: String): String {
        val normalized = path.trim().removePrefix("./")
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val stripped = when {
            normalized == root -> "workspace"
            normalized.startsWith("$root/") -> normalized.removePrefix("$root/")
            else -> "_abs/${normalized.removePrefix("/")}"
        }
        return stripped.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
            .ifBlank { "unnamed" }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content.orEmpty()
}
