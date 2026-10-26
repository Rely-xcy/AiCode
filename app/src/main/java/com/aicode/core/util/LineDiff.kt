package com.aicode.core.util

/**
 * 行级文本差异（LCS）。用于把一次 edit_file 的 old_string→new_string 变更
 * 渲染成「+新增 / −删除 / 上下文」的差异视图，类似 Claude 的 diff 展示。
 *
 * 统一文本格式：每行第 0 列为标记位（`+` 新增 / `-` 删除 / ` ` 上下文），
 * 其余为原始行内容。这样即便代码本身以 +/- 开头也不会歧义——解析时只取首列作标记、
 * 其余 substring(1) 即原文。
 */
object LineDiff {

    enum class LineType { ADD, REMOVE, CONTEXT }

    data class DiffLine(val type: LineType, val text: String)

    /**
     * LCS 表的单元格上限：超过就退回「整段删除 + 整段新增」。
     * dp 是 n×m 的 IntArray，1 万行×1 万行约 400MB，直接 OOM；2M 单元节约 8MB。
     */
    private const val MAX_CELLS = 2_000_000

    /** 计算 [oldText] → [newText] 的行级差异。 */
    fun diff(oldText: String, newText: String): List<DiffLine> {
        val a = oldText.split("\n")
        val b = newText.split("\n")
        val n = a.size
        val m = b.size

        if ((n.toLong() + 1) * (m.toLong() + 1) > MAX_CELLS) {
            return a.map { DiffLine(LineType.REMOVE, it) } + b.map { DiffLine(LineType.ADD, it) }
        }

        // LCS 长度表：dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度。
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(LineType.CONTEXT, a[i])); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine(LineType.REMOVE, a[i])); i++ }
                else -> { out.add(DiffLine(LineType.ADD, b[j])); j++ }
            }
        }
        while (i < n) { out.add(DiffLine(LineType.REMOVE, a[i])); i++ }
        while (j < m) { out.add(DiffLine(LineType.ADD, b[j])); j++ }
        return out
    }

    /** 渲染为统一文本：每行以 `+`/`-`/` ` 起头。 */
    fun toUnified(oldText: String, newText: String): String =
        diff(oldText, newText).joinToString("\n") { line ->
            when (line.type) {
                LineType.ADD -> "+${line.text}"
                LineType.REMOVE -> "-${line.text}"
                LineType.CONTEXT -> " ${line.text}"
            }
        }
}
