package com.aicode.feature.agent.presentation.component

/**
 * 流式 Markdown 的块切分结果。
 *
 * [frozen] 是已经定型、后续不会再变的块，[tail] 是仍在增长的最后一块。
 * 不变量：`frozen.joinToString("") + tail == 原文`，一字不差（切分只按偏移量截断，
 * 不做 trim、不补分隔符），保证渲染侧不会丢内容也不会重复。
 */
internal data class StreamBlocks(
    val frozen: List<String>,
    val tail: String
)

/** 冻结块数量上限：防止长回复把块表堆成大量碎片（超过后剩余全文都算尾块）。 */
internal const val STREAM_MAX_FROZEN_BLOCKS = 64

/** 尾巴最短长度：再切下去尾块只剩零头，切出来的碎片既省不了多少解析量又容易出排版接缝。 */
internal const val STREAM_MIN_TAIL_CHARS = 32

/**
 * 把流式文本切成「已冻结块 + 尾块」。
 *
 * 只做保守切分：所有不确定的位置一律留在尾块，宁可多解析一点，也不让冻结的块在后续帧里
 * 被误判（冻结意味着这段文本此后按独立 Markdown 文档解析，一旦切错就无法再纠正）。
 * 规则见各分支注释。
 */
internal fun splitStreamingBlocks(
    text: String,
    maxFrozenBlocks: Int = STREAM_MAX_FROZEN_BLOCKS,
    minTailChars: Int = STREAM_MIN_TAIL_CHARS
): StreamBlocks {
    if (text.isBlank() || maxFrozenBlocks <= 0) return StreamBlocks(emptyList(), text)

    val frozen = ArrayList<String>()
    var cut = 0
    var inFence = false
    var fenceMarker = ""
    var inMath = false
    var inHtml = false
    var inIndented = false
    var blockUnsafe = false
    var prevBlank = true
    var lineStart = 0

    while (lineStart < text.length && frozen.size < maxFrozenBlocks) {
        var lineEnd = text.indexOf('\n', lineStart)
        if (lineEnd < 0) lineEnd = text.length
        val nextStart = if (lineEnd < text.length) lineEnd + 1 else text.length
        val raw = text.substring(lineStart, lineEnd)
        val trimmed = raw.trim()
        val blank = trimmed.isEmpty()
        var breakAt = -1

        if (inFence) {
            // 闭合围栏必须是整行只有围栏字符（``` / ```` ），带语言名的行不能误判成闭合。
            // 闭合行本身是安全的切点：此后代码块内容不会再变。
            if (trimmed.startsWith(fenceMarker) && trimmed.all { it == fenceMarker[0] }) {
                inFence = false
                breakAt = nextStart
            }
        } else if (inMath) {
            // 数学块只在显式闭合后才是完整块（否则 MarkdownPreprocessor 会把它转成图片语法，
            // 半个 $$ 块切出去只会渲染出错）。
            if (trimmed.length >= 2 && trimmed.endsWith("$$")) {
                inMath = false
                blockUnsafe = false
                breakAt = nextStart
            }
        } else if (blank) {
            // 空行是默认切点；缩进代码块内部的空行、HTML 块内的空行都不算边界。
            if (!inIndented && !inHtml) breakAt = nextStart
            inHtml = false
        } else {
            if (inIndented && !raw.startsWith("    ") && !raw.startsWith("\t")) inIndented = false
            if (!inIndented) {
                when {
                    // 围栏行内成对（```code```）时不进入围栏态，按普通行处理。
                    trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                        val marker = if (trimmed.startsWith("```")) "```" else "~~~"
                        if (!trimmed.removePrefix(marker).contains(marker)) {
                            inFence = true
                            fenceMarker = marker
                        }
                    }
                    // 单行自闭合 $$x$$ 按普通不安全标记处理，跨行 $$ 进入数学块态。
                    trimmed.startsWith("$$") -> {
                        blockUnsafe = true
                        if (trimmed.length <= 4 || !trimmed.endsWith("$$")) inMath = true
                    }
                    isHtmlLine(raw) -> {
                        inHtml = true
                        blockUnsafe = true
                    }
                    // 顶格空行之后的 4 空格 / Tab 起头：缩进代码块，块内的空行不能当边界。
                    prevBlank && (raw.startsWith("    ") || raw.startsWith("\t")) -> {
                        inIndented = true
                        blockUnsafe = true
                    }
                }
            }
            prevBlank = false
        }

        if (breakAt > cut) {
            // 越往后切尾块越短，一旦低于下限就不可能再有合法切点。
            if (text.length - breakAt < minTailChars) break
            val chunk = text.substring(cut, breakAt)
            if (!chunk.isBlank()) {
                val safe = !blockUnsafe &&
                    !startsListOrQuote(nextNonBlankLine(text, breakAt)) &&
                    isChunkSelfContained(chunk)
                if (safe) {
                    frozen.add(chunk)
                    cut = breakAt
                }
                // 无论是否切成功都清掉不安全标记：被跳过的那段已经完整落在前缀里，
                // 卡住标记会让后续所有块永远无法冻结。
                blockUnsafe = false
            }
        }
        lineStart = nextStart
    }

    return StreamBlocks(frozen, text.substring(cut))
}

/** 列表 / 引用在新行开头续写时，空行不一定是块边界（松散列表、引用内空行），一律不冻结。 */
private fun startsListOrQuote(line: String): Boolean {
    if (line.isEmpty()) return false
    val t = line.trimStart()
    if (t.startsWith(">")) return true
    if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) return true
    var i = 0
    while (i < t.length && i < 10 && t[i].isDigit()) i++
    return i in 1..9 && i + 1 < t.length &&
        (t[i] == '.' || t[i] == ')') && t[i + 1] == ' '
}

private fun nextNonBlankLine(text: String, from: Int): String {
    var i = from
    while (i < text.length) {
        var end = text.indexOf('\n', i)
        if (end < 0) end = text.length
        val line = text.substring(i, end)
        if (line.isNotBlank()) return line
        i = end + 1
    }
    return ""
}

private fun isHtmlLine(raw: String): Boolean {
    val t = raw.trimStart()
    if (t.length < 2 || t[0] != '<') return false
    val c = t[1]
    return c.isLetter() || c == '/' || c == '!'
}

/**
 * 块自身要能作为独立 Markdown 文档成立，切出去才不会变样：
 *
 * - 行内代码的反引号成对：切在未闭合的 ` 之后，前后两半都会显示成字面反引号且不再纠正。
 * - 数学定界符成对：MarkdownPreprocessor 的 $$ / \[ / \( 正则跨空行匹配，切进公式中间会把公式拆坏。
 *   围栏里的反引号/美元符也一并计入，宁可少冻结一块。
 * - 没有链接引用定义（[label]: url）：定义与引用分散在不同块时，各块独立解析会丢掉引用。
 */
private fun isChunkSelfContained(chunk: String): Boolean {
    if (!chunk.contains("```") && !chunk.contains("~~~")) {
        if (chunk.count { it == '`' } % 2 != 0) return false
    }
    if (countOccurrences(chunk, "$$") % 2 != 0) return false
    if (countOccurrences(chunk, "\\[") != countOccurrences(chunk, "\\]")) return false
    if (countOccurrences(chunk, "\\(") != countOccurrences(chunk, "\\)")) return false
    var i = 0
    while (i < chunk.length) {
        var end = chunk.indexOf('\n', i)
        if (end < 0) end = chunk.length
        val line = chunk.substring(i, end).trimStart()
        if (line.startsWith("[") && line.contains("]:")) return false
        i = end + 1
    }
    return true
}

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var i = text.indexOf(needle)
    while (i >= 0) {
        count++
        i = text.indexOf(needle, i + needle.length)
    }
    return count
}
