package com.aicode.feature.workspace.domain.remote.agent.binary

import com.google.gson.JsonParser

/**
 * 远端清单 `/agent/manifest.json` 的模型。
 *
 * 清单是外部输入，解析必须容错：schema 不对、字段缺失、类型不对统统当成「没有可用条目」，
 * 返回 null 而不是抛异常——拿不到清单只表示这次下不了，不该把调用方打断。
 */
internal data class AgentBinaryManifest(val binaries: List<Entry>) {

    /** 清单里的一条二进制。 */
    data class Entry(
        /** agent 协议版本，必须等于客户端的 REMOTE_AGENT_PROTOCOL_VERSION。 */
        val proto: Int,
        val os: String,
        val arch: String,
        /** 服务器上的文件名（不含路径），如 `aicode-agent-v1-linux-amd64`。 */
        val file: String,
        val size: Long,
        /** 裸 64 位小写十六进制。 */
        val sha256: String
    )

    /** 取 proto 与架构都匹配的条目；没有就 null。 */
    fun pick(proto: Int, arch: String): Entry? =
        binaries.firstOrNull { it.proto == proto && it.os == OS_LINUX && it.arch == arch }

    companion object {
        /** 只认这个 schema 版本。 */
        const val SCHEMA: Int = 1

        const val OS_LINUX: String = "linux"

        fun parse(text: String): AgentBinaryManifest? = runCatching {
            val root = JsonParser.parseString(text).asJsonObject
            if (root.get("schema")?.asInt != SCHEMA) return null
            val array = root.getAsJsonArray("binaries") ?: return null
            val entries = array.mapNotNull { element ->
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val proto = runCatching { obj.get("proto")?.asInt }.getOrNull() ?: return@mapNotNull null
                val os = runCatching { obj.get("os")?.asString }.getOrNull() ?: return@mapNotNull null
                val arch = runCatching { obj.get("arch")?.asString }.getOrNull() ?: return@mapNotNull null
                val file = runCatching { obj.get("file")?.asString }.getOrNull() ?: return@mapNotNull null
                val size = runCatching { obj.get("size")?.asLong }.getOrNull() ?: return@mapNotNull null
                val sha = runCatching { obj.get("sha256")?.asString }.getOrNull()
                    ?.lowercase()
                    ?.takeIf { it.length == 64 }
                    ?: return@mapNotNull null
                Entry(proto = proto, os = os, arch = arch, file = file, size = size, sha256 = sha)
            }
            AgentBinaryManifest(entries)
        }.getOrNull()
    }
}
