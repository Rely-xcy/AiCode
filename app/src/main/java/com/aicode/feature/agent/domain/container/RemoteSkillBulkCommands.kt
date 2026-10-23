package com.aicode.feature.agent.domain.container

import java.util.Base64

/**
 * 技能 / 子代理文件的远端批量读取：一条命令取回四个根下的候选指令文件，替代逐目录、逐文件的
 * SFTP 往返（一次扫描上千次往返的主因）。
 *
 * 协议（制表符分隔，行首标记决定语义）：
 * - `B<TAB><tag>`：根块开始；
 * - `F<TAB><绝对路径><TAB><字节数>`：一个文件，随后跟着它的 base64 内容行；
 * - `C<TAB><base64 行>`：内容分段，保持默认 76 列换行（不能用 `-w0`：引擎单行上限 64K 字符）；
 * - `D<TAB><退出码>`：根块结束；
 * - `END`：整条命令结束。
 *
 * 路径用**远端绝对路径**回传、由解析侧按根前缀换算成相对路径：若改成先 `cd` 进根再 `find .`，
 * `cd` 失败（目录无执行权限）时 `find` 会静默跑在别的目录里，把别处的文件当成这个根的技能。
 */
internal const val BULK_BEGIN_PREFIX = "B\t"
internal const val BULK_FILE_PREFIX = "F\t"
internal const val BULK_CONTENT_PREFIX = "C\t"
internal const val BULK_DONE_PREFIX = "D\t"

/** 整条命令的结束标记；缺了它说明命令没跑完（断线 / 被杀 / 能力探测直接退出），整批作废。 */
internal const val BULK_END_MARKER = "END"

/** 缺 `base64` / `find` / `wc` 时命令的统一退出码，与「执行失败」一样走逐文件回退。 */
internal const val BULK_MISSING_TOOL_EXIT = 3

/** 根下哪些文件算候选：技能看任意深度的 SKILL.md / CLAUDE.md，子代理只看顶层 `*.md`。 */
internal enum class RemoteBulkRootKind { SKILLS, AGENTS }

/** 一个待读取的根：[remotePath] 为服务器上的绝对路径，[maxDepth] 与逐文件扫描的深度一致。 */
internal data class RemoteBulkRoot(
    val tag: String,
    val remotePath: String,
    val maxDepth: Int,
    val kind: RemoteBulkRootKind
)

/**
 * 构造覆盖 [roots] 的批量读取命令（整批只有这一次远端往返）。
 *
 * 能力探测并进同一条命令：缺工具就整条 `exit 3`，既不输出 END 也不动 SFTP，恰好是「整批作废 →
 * 逐文件回退」的信号，不额外花一次往返去问服务器装没装 base64。
 *
 * 每个根先 `[ -d ]` 判断再 `find`：目录不存在是常态（比如没建 `~/.aicode/agents`），此时输出
 * `D<TAB>0` 表示「这个根读到了，就是没有文件」，不能让整条命令非零把其它根一起拖下水。
 */
internal fun buildRemoteSkillBulkCommand(roots: List<RemoteBulkRoot>): String {
    val sb = StringBuilder()
    sb.append("if ! command -v base64 >/dev/null 2>&1 ")
        .append("|| ! command -v find >/dev/null 2>&1 ")
        .append("|| ! command -v wc >/dev/null 2>&1; then exit ")
        .append(BULK_MISSING_TOOL_EXIT)
        .append("; fi; ")
    for (root in roots) {
        val quoted = remoteShellQuote(root.remotePath)
        sb.append("printf '").append(shellMarker(BULK_BEGIN_PREFIX)).append("%s\\n' ")
            .append(remoteShellQuote(root.tag)).append("; ")
        sb.append("if [ ! -d ").append(quoted).append(" ]; then printf '")
            .append(shellMarker(BULK_DONE_PREFIX)).append("0\\n'; else ")
        // 先整批取文件名再逐个读：文件名列表进内存（技能目录很小），换来 find 的退出码可判。
        sb.append("files=$(find ").append(quoted)
            .append(" -maxdepth ").append(root.maxDepth)
            .append(" -mindepth 1 \\( -type f -o -type l \\) ")
            .append(candidateNameFilter(root.kind))
            .append(" 2>/dev/null); rc=$?; ")
        sb.append("printf '%s\\n' \"\$files\" | while IFS= read -r f; do ")
        sb.append("[ -n \"\$f\" ] || continue; ")
        sb.append("sz=\$(wc -c < \"\$f\" 2>/dev/null) || continue; ")
        sb.append("printf '").append(shellMarker(BULK_FILE_PREFIX)).append("%s\\t%s\\n' \"\$f\" \"\$sz\"; ")
        sb.append("base64 \"\$f\" 2>/dev/null | while IFS= read -r line; do ")
        sb.append("printf '").append(shellMarker(BULK_CONTENT_PREFIX)).append("%s\\n' \"\$line\"; done; done; ")
        sb.append("printf '").append(shellMarker(BULK_DONE_PREFIX)).append("%s\\n' \"\$rc\"; fi; ")
    }
    sb.append("printf '").append(BULK_END_MARKER).append("\\n'")
    return sb.toString()
}

/** 协议标记的 shell printf 形式：真实制表符换成 printf 能解释的转义，格式串与解析侧常量同源。 */
private fun shellMarker(marker: String): String = marker.replace("\t", "\\t")

/** 候选文件名过滤表达式；名称匹配一律忽略大小写（与逐文件扫描的 equals(ignoreCase) 对齐）。 */
private fun candidateNameFilter(kind: RemoteBulkRootKind): String = when (kind) {
    RemoteBulkRootKind.SKILLS -> "\\( -iname 'SKILL.md' -o -iname 'CLAUDE.md' \\)"
    RemoteBulkRootKind.AGENTS -> "-iname '*.md'"
}

/**
 * 解析 [buildRemoteSkillBulkCommand] 的输出，返回「tag → (相对路径 → 文件全文)」。
 *
 * [roots] 为 tag → 远端绝对路径，用来把 F 行的绝对路径换算成相对路径。
 *
 * 返回 null 表示整批不可信（缺 END、命令没跑完），调用方应整批回退。单个根出错（`D` 行非 0）只丢掉
 * 这个根，其余照用；单个文件出错（大小对不上、base64 脏行、路径不在根下）只丢这个文件——宁可少一个
 * 文件，也不能把半个文件的内容当正文解析。
 */
internal fun parseRemoteSkillBulkOutput(
    output: String,
    roots: Map<String, String>
): Map<String, Map<String, String>>? {
    val files = LinkedHashMap<String, MutableMap<String, String>>()
    val failedTags = HashSet<String>()
    var tag: String? = null
    var path: String? = null
    var size = -1L
    var encoded = StringBuilder()
    var ended = false

    fun flushFile() {
        val currentTag = tag
        val currentPath = path
        val currentSize = size
        val currentEncoded = encoded.toString()
        path = null
        size = -1L
        encoded = StringBuilder()
        if (currentTag == null || currentPath == null) return
        val root = roots[currentTag] ?: return
        val relative = currentPath.removePrefix(root.trimEnd('/') + "/")
        if (relative == currentPath || relative.isEmpty()) return
        val bytes = runCatching { Base64.getMimeDecoder().decode(currentEncoded) }.getOrNull() ?: return
        // 解码字节数与 F 行声明的大小不符：文件在 find 与 base64 之间被改了，或者内容被截断，两种都不能用。
        if (bytes.size.toLong() != currentSize) return
        files.getOrPut(currentTag) { LinkedHashMap() }[relative] = String(bytes, Charsets.UTF_8)
    }

    for (raw in output.split('\n')) {
        val line = raw.removeSuffix("\r")
        if (line == BULK_END_MARKER) {
            flushFile()
            ended = true
        } else if (line.startsWith(BULK_BEGIN_PREFIX)) {
            flushFile()
            val parsedTag = line.removePrefix(BULK_BEGIN_PREFIX).takeIf { it in roots }
            tag = parsedTag
            if (parsedTag != null) files.getOrPut(parsedTag) { LinkedHashMap() }
        } else if (line.startsWith(BULK_FILE_PREFIX)) {
            flushFile()
            val fields = line.split('\t')
            // 文件名含制表符时字段数对不上，整条丢弃：协议本身就不支持这种路径。
            val parsedSize = if (tag != null && fields.size == 3) fields[2].trim().toLongOrNull() else null
            if (parsedSize != null && parsedSize >= 0) {
                path = fields[1]
                size = parsedSize
            }
        } else if (line.startsWith(BULK_CONTENT_PREFIX)) {
            if (path != null) encoded.append(line.removePrefix(BULK_CONTENT_PREFIX))
        } else if (line.startsWith(BULK_DONE_PREFIX)) {
            flushFile()
            val result = line.removePrefix(BULK_DONE_PREFIX).trim()
            val currentTag = tag
            if (currentTag != null && result != "0") failedTags += currentTag
            tag = null
        }
    }
    flushFile()
    if (!ended) return null
    return files.filterKeys { it !in failedTags }
}
