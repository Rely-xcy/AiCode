package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentMessage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩前把被折叠的历史原文存档成文件，并在摘要里附上路径。
 *
 * 压缩会把 head 标成已压缩且不再回放（见 [ContextCompactor]），摘要没覆盖到的内容等于永久丢失。
 * 存档不是要喂给模型，而是给模型留一条「需要细节时自己去读」的退路——路径与
 * [com.aicode.feature.agent.domain.tool.ToolOutputStore] 同属容器内 /root/.aicode，
 * 模型用现成的文件工具就能读取。
 */
@Singleton
class CompactedHistoryArchive @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "CompactedHistoryArchive"
        const val AICODE_ROOT = "/root/.aicode"
        const val DIR = "compacted-history"

        /** 单条消息写入档案的上限，防止一条巨型工具输出撑爆文件。 */
        const val MAX_FIELD_CHARS = 200_000

        /** 归档保留策略：任一上限超出就从最旧的开始删。 */
        const val RETENTION_DAYS = 7
        const val RETENTION_MAX_BYTES = 32L * 1024 * 1024
        const val RETENTION_MAX_FILES = 30

        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    /** 存档目录（宿主路径）。 */
    val archiveDir: File get() = File(containerInstaller.aicodeDir, DIR)

    /** @return 容器内路径；落盘失败返回 null（调用方降级为不提示路径）。 */
    fun archive(sessionId: String?, messages: List<AgentMessage>): String? {
        if (messages.isEmpty()) return null
        return try {
            val dir = archiveDir.apply { mkdirs() }
            val file = uniqueFile(dir, sessionId)
            file.writeText(render(messages), Charsets.UTF_8)
            prune(dir)
            val path = "$AICODE_ROOT/$DIR/${file.name}"
            FileLogger.i(TAG, "已归档 ${messages.size} 条被压缩历史: $path")
            path
        } catch (e: Exception) {
            FileLogger.w(TAG, "归档被压缩历史失败: ${e.message}", e)
            null
        }
    }

    private fun render(messages: List<AgentMessage>): String = buildString {
        appendLine("# 被压缩的历史原文")
        appendLine()
        appendLine("以下内容已从对话上下文中折叠出去，仅在需要核对细节时查阅。")
        appendLine()
        messages.forEachIndexed { index, message ->
            appendLine("## ${index + 1}. ${roleOf(message)}")
            appendLine()
            appendLine(clip(bodyOf(message)))
            appendLine()
        }
    }

    private fun roleOf(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> "用户"
        is AgentMessage.AssistantMessage -> "助手"
        is AgentMessage.ToolResultMessage -> "工具结果"
    }

    private fun bodyOf(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content

        is AgentMessage.AssistantMessage -> buildString {
            if (message.content.isNotBlank()) appendLine(message.content)
            if (message.toolCalls.isNotEmpty()) {
                appendLine()
                message.toolCalls.forEach { call ->
                    appendLine("[工具调用] ${call.name} ${call.arguments}")
                }
            }
        }

        is AgentMessage.ToolResultMessage -> "[${message.toolName}]\n${message.result}"
    }

    // 归档存的是「原文」：modelResult 是软精简后的喂模型投影（中间会被挖掉），
    // 拿它存档等于把「需要时能读回原文」这个唯一退路也一起压掉了。

    private fun clip(text: String): String {
        if (text.length <= MAX_FIELD_CHARS) return text
        return text.take(MAX_FIELD_CHARS) + "\n[档案内单条消息过长，已截断 ${text.length - MAX_FIELD_CHARS} 字符]"
    }

    /** 保留策略：先按天数删过期，再按文件数与总大小从最旧开始删。 */
    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".md") }?.toList().orEmpty()
        if (files.isEmpty()) return
        val sorted = files.sortedBy { it.lastModified() }
        val expireBefore = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000

        var totalBytes = sorted.sumOf { it.length() }
        var remaining = sorted.size
        for (file in sorted) {
            val expired = file.lastModified() < expireBefore
            val tooMany = remaining > RETENTION_MAX_FILES
            val tooBig = totalBytes > RETENTION_MAX_BYTES
            if (!expired && !tooMany && !tooBig) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                remaining--
            }
        }
    }

    private fun uniqueFile(dir: File, sessionId: String?): File {
        val safeSession = sessionId.orEmpty()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "session" }
            .take(32)
        val base = "$safeSession-${LocalDateTime.now().format(TIMESTAMP_FORMAT)}"
        var candidate = File(dir, "$base.md")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$index.md")
            index++
        }
        return candidate
    }
}
