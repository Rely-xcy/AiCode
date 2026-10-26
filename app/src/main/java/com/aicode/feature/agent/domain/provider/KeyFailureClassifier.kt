package com.aicode.feature.agent.domain.provider

import com.aicode.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import okio.Buffer
import retrofit2.HttpException

/**
 * 判断一次 LLM 调用失败是否可归因于当前使用的 API Key——即是否应触发多 Key 自动切换。
 *
 * 只认鉴权/权限、计费/额度与限流类失败：HTTP 状态码默认 401/402/403/429（可按提供商覆盖，
 * 见 [AIProviderConfig.keySwitchStatusCodes]），以及流内的额度/鉴权错误码。5xx、408、超时、
 * DNS/连接故障属于服务端或链路问题，换 Key 无益，不应触发切换。
 *
 * [enrichWithHttpErrorBody] 会把 [HttpException] 包成 IllegalStateException 并把原异常挂在
 * cause 上，所以这里要沿 cause 链找状态码，找不到才退回消息文本匹配。
 *
 * 分组/模型级失败（503 +「无可用渠道」）不归这里判，见 [channelUnavailableFailure]。
 */
fun Throwable.isKeySwitchFailure(statusCodes: Set<Int> = DEFAULT_KEY_SWITCH_STATUS_CODES): Boolean {
    // 分组缺少该模型的渠道（503 +「无可用渠道」）不是 Key 坏了：即使自定义码表把 503 算进
    // 切换码，也不能据此冷却/切换 Key。这条证据交给选 Key 环节（见 [channelUnavailableFailure]）。
    if (channelUnavailableFailure() != null) return false
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() in statusCodes) return true
            is StreamApiException -> {
                if (current.code?.lowercase() in KEY_FAILURE_STREAM_CODES) return true
            }
        }
        current = current.cause
        depth++
    }
    val text = message?.lowercase() ?: return false
    // HTTP 状态码的文本兜底同样受 [statusCodes] 约束，否则自定义码表无法真正收窄触发范围
    if (statusCodes.any { text.contains("http $it") }) return true
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

/**
 * 「该 Key 所属分组没有这个模型的渠道」的失败特征（one-api / new-api 常见的
 * `No available channel for model ... under group ...`）。
 *
 * [statusCode] 只会是 503；[group] 是响应体里写明的分组名，[detail] 是去掉 JSON 包裹后的可读文案。
 */
data class ChannelUnavailableFailure(
    val statusCode: Int,
    val group: String?,
    val detail: String
)

/** 只有 503 会被当作「分组没有该模型的渠道」——5xx 里的其它码与过载、网关故障无法区分。 */
private const val CHANNEL_UNAVAILABLE_STATUS_CODE = 503

/** 响应体里表明「分组缺少该模型渠道」的文案标记（one-api 英文/中文两种提示）。 */
private val CHANNEL_UNAVAILABLE_MARKERS = listOf(
    "no available channel",
    "无可用渠道"
)

/** 读响应体做判定时最多看多少字节：错误体都很短，但要挡住把整页 HTML 读进内存。 */
private const val MAX_BODY_PEEK_BYTES = 8_192L

/** 响应体解析失败时，原文截断到该长度再进日志。 */
private const val MAX_CHANNEL_DETAIL_CHARS = 300

/**
 * 判断失败是否为分组/模型级失败：HTTP 503 且响应体说明该分组没有这个模型的渠道。
 *
 * 与 [isKeySwitchFailure] 的分工：那里判「Key 本身不可用」（鉴权/额度/限流），要冷却 Key；
 * 这里判「Key 正常，但它的分组没有该模型的渠道」——既不能冷却 Key，也不该换 Key（另一个分组
 * 同样可能没有），只把这条证据交给选 Key 环节，并给用户一句能看懂的原因。
 *
 * 响应体读不到（已被别处消费、只有状态码）时返回 null：单凭 503 无法与「服务端过载」区分，
 * 此时不做任何标记，交回原有的 5xx 重试路径。判定只读不消费响应体，后续
 * [enrichWithHttpErrorBody] 仍能拿到全文。
 */
fun Throwable.channelUnavailableFailure(): ChannelUnavailableFailure? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val status = when (current) {
            is EnrichedHttpException -> current.statusCode
            is HttpException -> current.code()
            else -> null
        }
        if (status == CHANNEL_UNAVAILABLE_STATUS_CODE) {
            val raw = current.upstreamErrorBodyText()
            if (raw != null && CHANNEL_UNAVAILABLE_MARKERS.any { raw.contains(it, ignoreCase = true) }) {
                val detail = extractHttpErrorDetail(raw)
                    .ifBlank { raw.trim().take(MAX_CHANNEL_DETAIL_CHARS) }
                return ChannelUnavailableFailure(status, groupFromChannelError(detail), detail)
            }
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * 读出错误响应体且**不消费**它：Retrofit 2.11 会把非 2xx 的响应体整体缓冲后挂在这个异常上，
 * 这里按 `peek` 读前 [MAX_BODY_PEEK_BYTES] 字节，后续读 errorBody 仍是全文。
 */
private fun Throwable.upstreamErrorBodyText(): String? = when (this) {
    is EnrichedHttpException -> upstreamErrorBody
    is HttpException -> runCatching {
        val body = response()?.errorBody()
        if (body == null) {
            null
        } else {
            val peeked = body.source().peek()
            peeked.request(MAX_BODY_PEEK_BYTES)
            val available = minOf(MAX_BODY_PEEK_BYTES, peeked.buffer.size)
            if (available <= 0L) {
                null
            } else {
                val copy = Buffer()
                copy.write(peeked, available)
                copy.readUtf8(available)
            }
        }
    }.getOrNull()
    else -> null
}

/** 从可读文案里取分组名：one-api 英文 `under group 0.15`、中文 `分组 default 下`。 */
private fun groupFromChannelError(detail: String): String? {
    val value = GROUP_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(detail)?.groupValues?.getOrNull(1)
    } ?: return null
    return value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '，', '。', '、', '）', '】')
        .takeIf { it.isNotEmpty() }
}

private val GROUP_PATTERNS = listOf(
    Regex("""under\s+group\s+(\S+)""", RegexOption.IGNORE_CASE),
    Regex("""分组\s*(\S+?)\s*下""")
)

private const val MAX_CAUSE_DEPTH = 5

private val KEY_FAILURE_STREAM_CODES = setOf(
    "insufficient_quota",
    "quota_exceeded",
    "usage_limit_reached",
    "usage_not_included",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_api_key",
    "authentication_error",
    "permission_error",
    "permission_denied"
)

private val KEY_FAILURE_MESSAGES = listOf(
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "insufficient_quota",
    "insufficient balance",
    "quota exceeded",
    "authentication_error",
    "permission_denied"
)
