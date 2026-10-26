package com.aicode.feature.agent.domain.prompt

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统提示词拼装里的一段：名字用于日志与跨轮比对，[volatile] 标记「不随配置变化、本来就会变」的段。
 *
 * 段粒度取**输入**而不是拼好正文的切片：静态片段按模板正文哈希（改片段即进 changed），
 * 注入变量按注入值哈希（技能/工具/时间变动即进 changed）。两者合起来覆盖提示词里每一处可变的字节，
 * 又不必对拼好的长文做二次切片。
 */
internal data class PromptSegment(
    val name: String,
    val content: String,
    val volatile: Boolean = false
) {
    val hash: String get() = PromptSegmentTracker.shortHash(content)
}

/** 一次构建相对上一轮的比对结果。 */
internal data class PromptSegmentDiff(
    val changed: List<String>,
    val reused: Int,
    val firstBuild: Boolean
) {
    /** 首次构建没有可比对象，统一写 `all`：否则空列表会被误读成「一段都没变」。 */
    fun changedText(): String = when {
        firstBuild -> FIRST_BUILD
        changed.isEmpty() -> NONE
        else -> changed.joinToString(",")
    }

    private companion object {
        const val FIRST_BUILD = "all"
        const val NONE = "none"
    }
}

/**
 * 段级哈希的上一轮快照，用来观测「这一轮提示词还能复用上一轮多少前缀」。
 *
 * 按「会话 + 工作区」存，与各 Source 缓存同一把 key：要观测的是同一会话相邻两轮的前缀是否稳定；
 * 跨会话比对只会把每个新会话的第一次构建都报成 changed，没有信息量。上限与
 * [SystemPromptProvider] 的 SOURCE_CACHE_LIMIT 对齐，仅防长期累积。
 */
internal class PromptSegmentTracker(private val limit: Int = 32) {

    private val previousByKey = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * 记录本次段哈希并与该 key 的上一轮比对。首次构建 [PromptSegmentDiff.changed] 为空、
     * [PromptSegmentDiff.firstBuild] 为 true；段落消失也算变化，记为 `-段名`，与「改内容」区分开。
     */
    @Synchronized
    fun record(key: String, segments: List<PromptSegment>): PromptSegmentDiff {
        val current = LinkedHashMap<String, String>(segments.size)
        segments.forEach { current[it.name] = it.hash }
        if (previousByKey.size > limit) previousByKey.clear()
        val previous = previousByKey.put(key, current)
        if (previous == null) return PromptSegmentDiff(emptyList(), reused = 0, firstBuild = true)

        val changed = ArrayList<String>()
        var reused = 0
        current.forEach { (name, hash) ->
            if (previous[name] == hash) reused++ else changed += name
        }
        previous.keys.filter { it !in current }.forEach { changed += "-$it" }
        return PromptSegmentDiff(changed, reused, firstBuild = false)
    }

    companion object {
        /**
         * sha256 前 8 字节的 hex（16 字符）：够区分段，一行日志放得下。
         * 用它而不是 [String.hashCode]：后者 32 位且碰撞可控，日志里会出现「明明改了两段却说没变」。
         */
        fun shortHash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(16)
            for (i in 0 until 8) {
                val byte = digest[i].toInt() and 0xFF
                out.append(HEX[byte ushr 4]).append(HEX[byte and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
