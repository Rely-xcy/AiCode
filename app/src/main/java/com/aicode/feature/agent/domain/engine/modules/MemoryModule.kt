package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆模块：把「AI 记住了什么」接进引擎。
 *
 * 两件事：
 * 1. 注入——把记忆清单（名称 + 描述）拼进系统提示词，详情仍由模型调 `memory(action=read)` 自取；
 * 2. 沉淀——每轮对话结束后（开关打开时）归纳出关于用户的稳定结论，写进长期记忆。
 *
 * 缓存策略沿用迁移前的实现：按 (sessionId, projectRoot) 会话级缓存，同一会话内只读一次盘，
 * 保持 system prompt 稳定以命中 KV 缓存；空结果用 "" 占位以区分「未缓存」。
 */
@Singleton
class MemoryModule @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memorySettings: MemorySettingsRepository
) : EngineModule {

    override val id = MODULE_ID

    // 注入顺序：记忆清单跟着「项目规则 / 技能」这类上下文走，用默认序即可。
    override val order = 50

    private val cachedByKey = ConcurrentHashMap<Pair<String?, String>, String>()

    override fun promptFragment(ctx: EngineContext): String? {
        val key = ctx.sessionId to ctx.projectRoot
        val cached = cachedByKey[key]
        if (cached != null) return cached.ifEmpty { null }

        val memories = try {
            memoryRepository.listMemories(ctx.projectRoot)
        } catch (e: Exception) {
            return null
        }
        if (memories.isEmpty()) {
            cachedByKey[key] = ""
            return null
        }

        val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
        val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }

        val content = buildString {
            if (globalMemories.isNotEmpty()) {
                append("全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))：\n")
                globalMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
            }
            if (projectMemories.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))：\n")
                projectMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
            }
        }.trimEnd()

        cachedByKey[key] = content
        trimIfNeeded()
        return content
    }

    /**
     * 每轮对话结束后沉淀长期记忆。
     *
     * 只有开关打开、非子代理会话、且调用方递进了一次性模型调用能力时才干活；
     * 归纳结果解析失败或模型返回空数组都算「这轮没什么可记的」，静默跳过。
     */
    override suspend fun onTurnCompleted(ctx: EngineContext) {
        if (ctx.isSubAgent) return
        if (ctx.history.isEmpty()) return
        val complete = ctx.oneShot ?: return
        if (!memorySettings.autoDistillEnabled()) return

        val existing = runCatching { memoryRepository.listMemories(ctx.projectRoot) }
            .getOrDefault(emptyList())
            .filter { it.kind == MemoryKind.PROFILE }

        val raw = complete(PROMPT_FILE, buildUserPrompt(ctx, existing)) ?: return
        val entries = parseEntries(raw)
        if (entries.isEmpty()) return

        entries.forEach { entry ->
            // 沉淀出来的结论都是「关于用户」的，跨项目通用，因此统一写全局。
            memoryRepository.saveMemory(
                name = entry.name,
                description = entry.description,
                content = entry.content,
                scope = MemoryScope.GLOBAL,
                projectRoot = ctx.projectRoot,
                kind = MemoryKind.PROFILE
            )
        }
        FileLogger.i(TAG, "沉淀长期记忆 ${entries.size} 条: ${entries.joinToString { it.name }}")
        // 本轮新增/更新了记忆 → 丢掉本会话的注入缓存，下一轮注入就带上新内容
        cachedByKey.remove(ctx.sessionId to ctx.projectRoot)
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        cachedByKey.keys.removeAll { it.first == ctx.sessionId }
    }

    /** 交给模型的输入：已有长期记忆（防重复）+ 本轮对话（截断，控制 token）。 */
    private fun buildUserPrompt(ctx: EngineContext, existing: List<Memory>): String =
        buildString {
            appendLine("Existing long-term memories (same name = replaces the old entry):")
            if (existing.isEmpty()) {
                appendLine("(none)")
            } else {
                existing.forEach { appendLine("- ${it.name}: ${it.description}") }
            }
            appendLine()
            appendLine("Transcript of the latest turn:")
            ctx.history.takeLast(TRANSCRIPT_MESSAGES).forEach { appendLine(renderMessage(it)) }
        }

    private fun renderMessage(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> "user: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.AssistantMessage -> "assistant: ${message.content.take(MAX_MESSAGE_CHARS)}"
        is AgentMessage.ToolResultMessage ->
            "tool(${message.toolName}): ${(message.modelResult ?: message.result).take(MAX_TOOL_CHARS)}"
    }

    /** 解析模型输出：容错地取出 JSON 数组段，容忍 ``` 包裹与前后废话。 */
    private fun parseEntries(raw: String): List<DistilledEntry> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(ListSerializer(DistilledEntry.serializer()), raw.substring(start, end + 1))
                .filter { it.name.isNotBlank() && it.content.isNotBlank() }
                .take(MAX_ENTRIES_PER_TURN)
        }.onFailure {
            FileLogger.w(TAG, "沉淀结果解析失败，已跳过本轮", it)
        }.getOrDefault(emptyList())
    }

    private fun trimIfNeeded() {
        if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
    }

    @Serializable
    private data class DistilledEntry(
        val name: String,
        val description: String = "",
        val content: String = ""
    )

    private companion object {
        const val MODULE_ID = "memory"
        const val TAG = "MemoryModule"
        const val SOURCE_CACHE_LIMIT = 64

        /** 沉淀提示词：与其它一次性调用一样放 assets/prompts 下。 */
        const val PROMPT_FILE = "agent/memory-distiller.md"

        const val TRANSCRIPT_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 1200
        const val MAX_TOOL_CHARS = 400
        const val MAX_ENTRIES_PER_TURN = 5
    }
}
