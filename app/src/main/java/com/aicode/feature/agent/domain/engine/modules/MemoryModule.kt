package com.aicode.feature.agent.domain.engine.modules

import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆模块：把「AI 记住了什么」接进引擎。
 *
 * 目前只负责注入——把记忆清单（名称 + 描述）拼进系统提示词，详情仍由模型调
 * `memory(action=read)` 自取；自动沉淀等能力后续接在同一模块内。
 *
 * 缓存策略沿用迁移前的实现：按 (sessionId, projectRoot) 会话级缓存，同一会话内只读一次盘，
 * 保持 system prompt 稳定以命中 KV 缓存；空结果用 "" 占位以区分「未缓存」。
 */
@Singleton
class MemoryModule @Inject constructor(
    private val memoryRepository: MemoryRepository
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

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        cachedByKey.keys.removeAll { it.first == ctx.sessionId }
    }

    private fun trimIfNeeded() {
        if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
    }

    private companion object {
        const val MODULE_ID = "memory"
        const val SOURCE_CACHE_LIMIT = 64
    }
}
