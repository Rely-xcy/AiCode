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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

    private val cachedByKey = ConcurrentHashMap<Triple<String?, String, Boolean>, String>()

    /** 每个会话已积累的轮数，够 [DISTILL_EVERY_TURNS] 才归约一次。 */
    private val turnsSinceDistill = ConcurrentHashMap<String, Int>()

    /** 每会话一把锁：归约是并发分发的，上一次没跑完就不开新的，避免重复写入。 */
    private val distillLocks = ConcurrentHashMap<String, Mutex>()

    // 声明在 init 之前：Kotlin 按声明顺序初始化
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 记忆被外部改动（如模型主动调 memory 工具写入）时丢掉注入缓存，
        // 否则新记忆要等到换会话才生效——主动记忆就白写了。
        scope.launch {
            memoryRepository.changes.collect { cachedByKey.clear() }
        }
    }

    override fun promptFragment(ctx: EngineContext): String? {
        val autoSave = memorySettings.autoDistillEnabledSync()
        // 开关也进缓存 key：切换开关后注入内容要跟着变
        val key = Triple(ctx.sessionId, ctx.projectRoot, autoSave)
        val cached = cachedByKey[key]
        if (cached != null) return cached.ifEmpty { null }

        // 清单为空时也要可能返回规则本身（新用户没有任何记忆时，主动记忆规则必须照样注入）
        val content = listOfNotNull(
            // 子代理不拿主动记忆规则：写用户画像是主代理的事，子代理只管干活
            AUTO_SAVE_RULE.takeIf { autoSave && !ctx.isSubAgent },
            buildMemoryList(ctx)
        ).joinToString("\n\n")

        cachedByKey[key] = content
        trimIfNeeded()
        return content.ifEmpty { null }
    }

    private fun buildMemoryList(ctx: EngineContext): String? {
        val memories = try {
            memoryRepository.listMemories(ctx.projectRoot)
        } catch (e: Exception) {
            return null
        }
        if (memories.isEmpty()) return null

        // 注入有上限：记忆多了不能把系统提示词撑爆（未列出的靠 memory(action=list) 取）
        val listed = memories.take(MAX_INJECTED_MEMORIES)
        val globalMemories = listed.filter { it.scope == MemoryScope.GLOBAL }
        val projectMemories = listed.filter { it.scope == MemoryScope.PROJECT }

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
            if (memories.size > listed.size) {
                append("\n（另有 ${memories.size - listed.size} 条未列出，需要时用 memory(action=list) 查看）")
            }
        }.trimEnd()

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
        val sessionKey = ctx.sessionId ?: return

        // 攒够若干轮才归约一次：每轮都调模型太贵，而主路的主动记忆已经覆盖了当轮偏好。
        val turns = (turnsSinceDistill[sessionKey] ?: 0) + 1
        if (turns < DISTILL_EVERY_TURNS) {
            turnsSinceDistill[sessionKey] = turns
            return
        }
        turnsSinceDistill[sessionKey] = 0

        // 归约由引擎并发分发，上一轮可能还没跑完；同一会话不重叠，避免重复写入
        val lock = distillLocks.getOrPut(sessionKey) { Mutex() }
        if (!lock.tryLock()) return
        try {
            distill(ctx, complete)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun distill(ctx: EngineContext, complete: suspend (String, String) -> String?) {

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
        // （缓存 key 是 Triple(会话, 工作区, 开关)，这里按前两项清，与开关无关）
        cachedByKey.keys
            .filter { it.first == ctx.sessionId && it.second == ctx.projectRoot }
            .forEach { cachedByKey.remove(it) }
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        cachedByKey.keys.removeAll { it.first == ctx.sessionId }
        ctx.sessionId?.let { turnsSinceDistill.remove(it) }
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

        /** B 路归约间隔：每这么多轮才跑一次独立归纳。 */
        const val DISTILL_EVERY_TURNS = 5

        /**
         * 主动记忆规则（A 路）：开关打开时注入，让主模型在对话中自己把稳定结论存下来。
         * 判据是语义的（「未来会话里知道这条会不会让我做法不同」），不是关键词清单：
         * 用户几乎不会明说「记住这个」，偏好大多是隐含的。
         */
        val AUTO_SAVE_RULE = """
            长期记忆（开关已打开，你需要主动维护）：
            每轮回复收尾前，先判断这一轮是否出现了关于用户的稳定结论——
            偏好与表达习惯、工作方式、对你做法的纠正、环境或工具限制（设备/网络/权限/跑不起来的东西）、
            项目约定与架构决策、反复出现的术语与路径、关于用户自身的稳定事实。
            判据：如果未来某次会话一开始就知道这条，你会不会做得不一样？会 → 调用
            memory(action=save, scope=global, name=<短英文 slug>, description=<一句话>, content=<1-3 行>) 存下来。
            不要等用户说「记住」，也不要等他要求；发现即记。保存成功后在回复末尾用一行说明记了什么。
            不要记：一次性任务细节、工具输出、代码片段、明天就过期的状态。
        """.trimIndent()

        /** 沉淀提示词：与其它一次性调用一样放 assets/prompts 下。 */
        const val PROMPT_FILE = "agent/memory-distiller.md"

        const val TRANSCRIPT_MESSAGES = 20
        const val MAX_MESSAGE_CHARS = 1200
        const val MAX_TOOL_CHARS = 400
        const val MAX_ENTRIES_PER_TURN = 5

        /** 注入系统提示词的记忆条数上限。
         *
         * 实测结论（MemOS 落地笔记，原文“宁少勿多”）：记忆条数多了反而干扰决策，
         * 3 条是甜点值，10 条已开始干扰。这里取 6 条（略宽于甜点值），
         * 超出部分不注入，靠 memory(action=list) 按需取。
         */
        const val MAX_INJECTED_MEMORIES = 6
    }
}
