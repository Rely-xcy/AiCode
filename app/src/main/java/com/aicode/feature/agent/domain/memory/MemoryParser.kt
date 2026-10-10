package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.core.util.YamlScalar
import org.yaml.snakeyaml.Yaml
import java.io.File

object MemoryParser {
    private const val TAG = "MemoryParser"
    private const val MAX_DESC_CHARS = 500

    fun parse(file: File, scope: MemoryScope): Memory? {
        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取 Memory 文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val kind = when (frontmatter["kind"]?.toString()?.trim()?.lowercase()) {
            "profile" -> MemoryKind.PROFILE
            else -> MemoryKind.NOTE
        }
        // 旧文件没有这两个字段：source 空串、createdAt 0（回退用文件修改时间）
        val source = frontmatter["source"]?.toString()?.trim().orEmpty()
        val createdAt = frontmatter["created_at"]?.toString()?.trim()?.toLongOrNull() ?: 0L
        val hitCount = frontmatter["hit_count"]?.toString()?.trim()?.toIntOrNull() ?: 0
        val lastHitAt = frontmatter["last_hit_at"]?.toString()?.trim()?.toLongOrNull() ?: 0L
        // 缺失或非法值（非 "true"）一律当 false：旧文件没有这个字段
        val pinned = frontmatter["pinned"]?.toString()?.trim()?.equals("true", ignoreCase = true) ?: false

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = body.trim(),
            kind = kind,
            source = source,
            createdAt = createdAt,
            hitCount = hitCount,
            lastHitAt = lastHitAt,
            pinned = pinned
        )
    }

    /**
     * 组装记忆文件。
     *
     * [kind]/[source]/[createdAt] 为空或 0 时**不写该行**——[MemoryKind.NOTE] 的旧记忆文件
     * 必须保持字节不变，不能因为新增元数据把所有历史文件都重写一遍。
     * [pinned] 同理：false 时不写，旧文件形态不变；只有置顶才写 `pinned: true`。
     */
    fun format(
        name: String,
        description: String,
        content: String,
        kind: MemoryKind = MemoryKind.NOTE,
        source: String = "",
        createdAt: Long = 0L,
        hitCount: Int = 0,
        lastHitAt: Long = 0L,
        pinned: Boolean = false
    ): String {
        val safeName = YamlScalar.quote(name)
        val safeDesc = YamlScalar.quote(description)
        val pinnedLine = if (pinned) "pinned: true\n" else ""
        val kindLine = if (kind == MemoryKind.PROFILE) "kind: profile\n" else ""
        val sourceLine = if (source.isNotBlank()) "source: ${YamlScalar.quote(source)}\n" else ""
        val createdLine = if (createdAt > 0) "created_at: $createdAt\n" else ""
        val hitCountLine = if (hitCount > 0) "hit_count: $hitCount\n" else ""
        val lastHitLine = if (lastHitAt > 0) "last_hit_at: $lastHitAt\n" else ""
        return "---\nname: $safeName\ndescription: $safeDesc\n$pinnedLine$kindLine$sourceLine$createdLine$hitCountLine$lastHitLine---\n$content"
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}
