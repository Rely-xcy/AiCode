package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
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
            lastHitAt = lastHitAt
        )
    }

    /**
     * 组装记忆文件。
     *
     * [kind]/[source]/[createdAt] 为空或 0 时**不写该行**——[MemoryKind.NOTE] 的旧记忆文件
     * 必须保持字节不变，不能因为新增元数据把所有历史文件都重写一遍。
     */
    fun format(
        name: String,
        description: String,
        content: String,
        kind: MemoryKind = MemoryKind.NOTE,
        source: String = "",
        createdAt: Long = 0L,
        hitCount: Int = 0,
        lastHitAt: Long = 0L
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val kindLine = if (kind == MemoryKind.PROFILE) "kind: profile\n" else ""
        val sourceLine = if (source.isNotBlank()) "source: ${yamlScalar(source)}\n" else ""
        val createdLine = if (createdAt > 0) "created_at: $createdAt\n" else ""
        val hitCountLine = if (hitCount > 0) "hit_count: $hitCount\n" else ""
        val lastHitLine = if (lastHitAt > 0) "last_hit_at: $lastHitAt\n" else ""
        return "---\nname: $safeName\ndescription: $safeDesc\n$kindLine$sourceLine$createdLine$hitCountLine$lastHitLine---\n$content"
    }

    /**
     * 把任意字符串转成安全的 YAML 标量。
     *
     * **白名单**策略：只有字母数字、空格、`_ - . /` 与非 ASCII（中日韩等）才裸写，
     * 其余一律双引号并转义。之前是黑名单（只拦 `:` `#` 与引号），漏了 `*` `!` `%` `@`
     * 反引号 `>` `|` `?` `,` 与真实 TAB——一旦命中 YAML 解析直接抛异常，而异常被吞成
     * 「frontmatter 全空」，整条记忆的描述/类型/来源/时间会一起消失。
     */
    private fun yamlScalar(value: String): String {
        val bare = value.isNotBlank() &&
            !value.startsWith(" ") && !value.endsWith(" ") &&
            BARE_SCALAR.matches(value)
        if (bare) return value

        val escaped = buildString {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\x%02x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    /** 允许裸写的字符集：ASCII 字母数字 + 常见安全符号 + 非 ASCII（中文等）。 */
    private val BARE_SCALAR = Regex("^[A-Za-z0-9 _\\-./\\u0080-\\uFFFF]+$")

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
