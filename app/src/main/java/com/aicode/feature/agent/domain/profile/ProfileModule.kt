package com.aicode.feature.agent.domain.profile

import com.aicode.core.engine.EngineContext
import com.aicode.core.engine.EngineModule
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ProfileEntryDao
import com.aicode.feature.agent.data.local.entity.ProfileEntryEntity
import com.aicode.feature.agent.data.local.entity.ProfileSection
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.PromptFileResolver
import com.aicode.feature.agent.domain.provider.AIProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 模型给出的单条画像候选。 */
@Serializable
private data class ProfileCandidate(
    val section: String = "",
    val key: String = "",
    val value: String = "",
    val evidence: String = "",
    val confidence: Float = 0.5f
)

/**
 * 用户画像模块：把跨会话沉淀下来的稳定结论按分节注入提示词，并在轮次结束后继续沉淀。
 *
 * 与记忆（memory）的分工：记忆是「一条条互不相关的笔记」，同名直接跳过；
 * 画像是「同一件事的最新结论」——以 `(section, key)` 为身份，新结论会把旧的标成已被取代并留痕，
 * 所以「以前说喜欢用 X、现在改用 Y」能以 Y 为准，同时保留可回查的变更痕迹。
 *
 * 沉淀不挂在 [onTurnCompleted] 上，而是与 MemoryCurator 一样由调用方传入已解析好的轻量 provider
 * （见 [curate]）——引擎的生命周期回调里拿不到 provider，硬塞依赖会把模块和 provider 解析耦死。
 */
@Singleton
class ProfileModule @Inject constructor(
    private val profileEntryDao: ProfileEntryDao,
    private val promptFileResolver: PromptFileResolver
) : EngineModule {

    private companion object {
        const val TAG = "ProfileModule"

        /** 低于该置信度的条目不注入：猜测性的结论放进去只会干扰模型。 */
        const val MIN_INJECT_CONFIDENCE = 0.5f

        /** 单次注入的条目上限，避免画像长期累积后把提示词撑爆。 */
        const val MAX_INJECT_ENTRIES = 20

        const val MAX_TRANSCRIPT_CHARS = 12_000
        const val MAX_EVIDENCE_CHARS = 300
        const val MAX_KEY_CHARS = 64
        const val MAX_VALUE_CHARS = 500

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        const val PROMPT_FILE = "agent/profile-curator.md"
        const val EXISTING_PLACEHOLDER = "{{EXISTING}}"
    }

    override val id = "profile"

    /** 排在待办模块之后：进度信息比画像更需要模型优先读到。 */
    override val order = 20

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun promptFragment(ctx: EngineContext): String? {
        val entries = runCatching { profileEntryDao.activeEntriesOnce() }
            .getOrElse {
                FileLogger.w(TAG, "读取画像失败: ${it.message}")
                return null
            }
            .filter { it.confidence >= MIN_INJECT_CONFIDENCE }
            .take(MAX_INJECT_ENTRIES)
        if (entries.isEmpty()) return null
        val bySection = entries.groupBy { it.section }
        return buildString {
            append("用户画像 (profile)（跨会话沉淀的稳定结论；与用户当前的说法冲突时，以当前说法为准）：")
            ProfileSection.values().forEach { section ->
                val inSection = bySection[section.key].orEmpty()
                if (inSection.isEmpty()) return@forEach
                append("\n- ${section.title}:")
                inSection.forEach { append("\n  · ${it.value}") }
            }
        }
    }

    /**
     * 从本轮对话抽取画像并落盘，返回实际写入（含更新）的条数。
     *
     * @param provider 调用方解析好的轻量 provider（与记忆沉淀复用同一个）。
     */
    suspend fun curate(provider: AIProvider, sessionId: String, transcript: String): Int = runCatching {
        if (transcript.isBlank()) return@runCatching 0
        val existing = profileEntryDao.activeEntriesOnce()
        val existingText = if (existing.isEmpty()) {
            "（暂无）"
        } else {
            existing.joinToString("\n") { "- [${it.section}/${it.entryKey}] ${it.value}" }
        }
        val systemPrompt = promptFileResolver.resolve(PROMPT_FILE)
            .replace(LEADING_COMMENT, "")
            .replace(EXISTING_PLACEHOLDER, existingText)
            .trim()
        if (systemPrompt.isEmpty()) return@runCatching 0

        val response = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(
                AgentMessage.UserMessage(content = transcript.takeLast(MAX_TRANSCRIPT_CHARS))
            ),
            tools = emptyList()
        )
        val candidates = json.decodeFromString<List<ProfileCandidate>>(extractJsonArray(response.content))
        var saved = 0
        candidates.forEach { candidate ->
            val section = ProfileSection.fromKey(candidate.section) ?: return@forEach
            val key = candidate.key.trim().take(MAX_KEY_CHARS)
            val value = candidate.value.trim().take(MAX_VALUE_CHARS)
            if (key.isEmpty() || value.isEmpty()) return@forEach

            val now = System.currentTimeMillis()
            val existingActive = profileEntryDao.findActive(section.key, key)
            // 结论没变就不动：否则每轮都会把同一条"取代"一次，历史被无意义地刷屏。
            if (existingActive != null && existingActive.value.trim() == value) return@forEach

            val newId = UUID.randomUUID().toString()
            existingActive?.let { old ->
                profileEntryDao.markSuperseded(old.id, newId, now)
                FileLogger.i(TAG, "画像「${section.key}/$key」以新结论取代旧结论")
            }
            profileEntryDao.upsert(
                ProfileEntryEntity(
                    id = newId,
                    section = section.key,
                    entryKey = key,
                    value = value,
                    evidence = candidate.evidence.trim().take(MAX_EVIDENCE_CHARS),
                    confidence = candidate.confidence.coerceIn(0f, 1f),
                    sourceSessionId = sessionId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            saved++
        }
        saved
    }.getOrElse {
        FileLogger.w(TAG, "画像沉淀失败: ${it.message}")
        0
    }

    /** 模型偶尔会包一层 ```json 围栏或加一句解释，这里只取第一个数组字面量。 */
    private fun extractJsonArray(raw: String): String {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else "[]"
    }
}
