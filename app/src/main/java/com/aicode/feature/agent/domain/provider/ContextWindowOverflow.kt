package com.aicode.feature.agent.domain.provider

import retrofit2.HttpException

/**
 * 上下文超窗类 400 的识别与「真实上限」解析。
 *
 * 供应商拒掉超窗请求时一律返回 400，但措辞各不相同（`context length` / `prompt is too long` /
 * `maximum number of tokens` / 中文「超出上下文」…），同一批 400 里还混着限流、鉴权与参数错误。
 * 只有先把「超窗」从这批 400 里认出来，才能把模型真实窗口写回元数据、按新窗口重压并重试。
 *
 * 判定分两步，先看状态码再看文案：
 * 1. 只有 400 / 413（或流内明确的超窗错误码）才可能是超窗——429 / 401 / 403 直接排除；
 * 2. 文案必须命中超窗特征，且不命中限流 / 鉴权 / 额度类特征。
 *
 * 宁可宽一点：误判的代价只是多花一次重压 + 一次重试，之后仍会把原始错误如实上报；
 * 而漏判会让用户直接看到无法自愈的 400，正是本功能要修的问题。
 */

/** 沿 cause 链查找的最大深度，与 [RetryPolicy] 里的同类遍历保持一致。 */
private const val MAX_CAUSE_DEPTH = 6

/** 上下文窗口的合理区间：低于 1K 或高于 100M 的「上限」几乎一定是误解析出的其它数字。 */
private const val MIN_PLAUSIBLE_LIMIT = 1_000
private const val MAX_PLAUSIBLE_LIMIT = 100_000_000

/** 流式响应里明确表示上下文超窗的错误码（对齐各 provider 的 SSE error.code）。 */
private val OVERFLOW_STREAM_CODES = setOf(
    "context_window_exceeded",
    "context_length_exceeded",
    "string_above_max_length"
)

/** 超窗文案特征。 */
private val OVERFLOW_PATTERNS = listOf(
    Regex("context[_ ]?length"),
    Regex("context[_ ]?window"),
    Regex("max(?:imum)?[_ ]?context"),
    Regex("maximum number of tokens"),
    Regex("too many tokens"),
    Regex("reduce the length"),
    Regex("prompt is too long"),
    Regex("input is too long"),
    Regex("token limit"),
    Regex("range of input length"),
    Regex("exceed[s]? the maximum[^\\n]{0,80}context"),
    Regex("context[^\\n]{0,80}exceed[s]? the maximum"),
    Regex("超出上下文"),
    Regex("上下文长度"),
    Regex("上下文窗口"),
    Regex("输入超长"),
    Regex("请求超长"),
    Regex("输入长度超过")
)

/**
 * 明确不属于超窗的文案：限流 / 鉴权 / 额度。
 * 这些即使在 400 上出现（部分中转把限流也回 400），也不能当成超窗去重压重试。
 */
private val OVERFLOW_EXCLUSION_PATTERNS = listOf(
    Regex("rate ?limit"),
    Regex("too many requests"),
    Regex("requests? per (?:minute|second|hour|day)"),
    Regex("per minute"),
    Regex("quota"),
    Regex("insufficient"),
    Regex("api key"),
    Regex("unauthori[sz]ed"),
    Regex("authentication"),
    Regex("billing"),
    Regex("payment")
)

/** 兜底判定用的「HTTP 400/413」形态匹配（异常被二次包装、拿不到状态码时用）。 */
private val HTTP_400_LIKE = Regex("\\b(?:http\\s*)?(?:400|413)\\b", RegexOption.IGNORE_CASE)

/**
 * 上限解析的候选句式，按优先级从具体到宽松排列，取第一个命中且在合理区间的值。
 *
 * 顺序很重要：OpenAI 的错误正文里同时出现「上限」与「实际请求量」
 * （`maximum context length is 128000 tokens. However, your messages resulted in 130000 tokens.`），
 * 必须由「上限」句式先命中，不能把 130000 当成窗口。
 */
private val LIMIT_PATTERNS = listOf(
    Regex("maximum context length is (\\d+)"),
    Regex("maximum context length of (\\d+)"),
    Regex("maximum context (?:length|window)[^0-9]{0,24}(\\d+)"),
    Regex("max(?:imum)?[_ ]?context[_ ]?(?:length|window|tokens)?[^0-9]{0,24}(\\d+)"),
    Regex("maximum number of tokens allowed[^0-9]{0,12}\\(?(\\d+)"),
    Regex("context[_ ]?(?:length|window)[^0-9]{0,24}(\\d+)"),
    Regex("max_tokens[^0-9]{0,12}(\\d+)"),
    Regex("token limit[^0-9]{0,12}(\\d+)"),
    Regex("limit of (\\d+) tokens"),
    Regex("(\\d+) tokens? (?:maximum|max|allowed|limit)"),
    Regex("(\\d+)\\s*maximum"),
    Regex("\\[1,\\s*(\\d+)\\]")
)

/**
 * 该异常是否为「上下文超窗」类错误。
 *
 * 覆盖 [EnrichedHttpException]（适配器 enrich 后的形态）、原始 [HttpException] 与流内 [StreamApiException]，
 * 并沿 cause 链查找（多 Key 全失败会被包成 [AllKeysFailedException]）。
 */
fun Throwable.isContextWindowOverflowError(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is EnrichedHttpException -> {
                // 已经读到状态码：不是 400/413 就不必再看文案。
                if (current.statusCode != 400 && current.statusCode != 413) return false
                val text = "${current.message.orEmpty()} ${current.upstreamErrorBody.orEmpty()}"
                return matchesOverflow(text)
            }
            is HttpException -> {
                val code = current.code()
                if (code != 400 && code != 413) return false
                if (matchesOverflow(current.message().orEmpty())) return true
            }
            is StreamApiException -> {
                if (current.code?.lowercase() in OVERFLOW_STREAM_CODES) return true
                if (matchesOverflow(current.message.orEmpty())) return true
            }
        }
        current = current.cause
        depth++
    }
    // 兜底：异常被别的类型包了一层、拿不到状态码时，仅按「HTTP 400/413 + 超窗文案」判定。
    val fallback = message.orEmpty()
    return HTTP_400_LIKE.containsMatchIn(fallback) && matchesOverflow(fallback)
}

/**
 * 从错误正文里尽力解析出模型真实的上下文上限（token）。解析失败返回 null——
 * 调用方据此跳过写回，但仍可走重压重试（重压按现有窗口保守压缩）。
 */
fun Throwable.parseContextWindowLimit(): Int? {
    val text = collectOverflowText()
    if (text.isBlank()) return null
    for (pattern in LIMIT_PATTERNS) {
        val match = pattern.find(text) ?: continue
        val value = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
        if (value in MIN_PLAUSIBLE_LIMIT..MAX_PLAUSIBLE_LIMIT) return value
    }
    return null
}

private fun matchesOverflow(raw: String): Boolean {
    if (raw.isBlank()) return false
    val text = raw.lowercase()
    if (OVERFLOW_EXCLUSION_PATTERNS.any { it.containsMatchIn(text) }) return false
    return OVERFLOW_PATTERNS.any { it.containsMatchIn(text) }
}

/**
 * 收集整个 cause 链上的错误正文（message + [EnrichedHttpException.upstreamErrorBody]）。
 * 解析需要尽量完整的原文：可读 detail 被截断时，完整响应体里往往还留着上限数字。
 */
private fun Throwable.collectOverflowText(): String {
    val sb = StringBuilder()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        current.message?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        if (current is EnrichedHttpException) {
            current.upstreamErrorBody?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        }
        current = current.cause
        depth++
    }
    return sb.toString()
}
