package com.aicode.feature.agent.domain.provider

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 上下文超窗 400 的识别与上限解析：既要把各家的超窗文案认出来，也不能把限流 / 鉴权 / 参数类 400 误伤。
 */
class ContextWindowOverflowTest {

    private fun httpError(code: Int, body: String): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody(null)))

    /** 适配器 enrich 后的真实形态：可读文案 + 完整上游响应体 + 原始 HttpException 作为 cause。 */
    private fun enriched(code: Int, body: String): EnrichedHttpException =
        EnrichedHttpException("HTTP $code: $body", code, body, httpError(code, body))

    // ---------- 各家的超窗文案都要能认出来，并解析出真实上限 ----------

    @Test
    fun openai_maximumContextLength_parsesLimitAndIgnoresRequestedTokens() {
        val e = enriched(
            400,
            """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 130000 tokens. Please reduce the length of the messages.","code":"context_length_exceeded"}}"""
        )
        assertTrue(e.isContextWindowOverflowError())
        // 正文里同时有「上限 128000」与「实际请求 130000」，必须取前者。
        assertEquals(128_000, e.parseContextWindowLimit())
    }

    @Test
    fun anthropic_promptTooLong_parsesTrailingMaximum() {
        val e = enriched(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 210000 tokens > 200000 maximum"}}"""
        )
        assertTrue(e.isContextWindowOverflowError())
        assertEquals(200_000, e.parseContextWindowLimit())
    }

    @Test
    fun gemini_maximumNumberOfTokensAllowed_parsesParenthesizedLimit() {
        val e = enriched(
            400,
            """{"error":{"code":400,"message":"The input token count (1196265) exceeds the maximum number of tokens allowed (1048576)."}}"""
        )
        assertTrue(e.isContextWindowOverflowError())
        assertEquals(1_048_576, e.parseContextWindowLimit())
    }

    @Test
    fun qwen_rangeOfInputLength_parsesUpperBound() {
        val e = enriched(400, """{"error":{"code":400,"message":"Range of input length should be [1, 129024]"}}""")
        assertTrue(e.isContextWindowOverflowError())
        assertEquals(129_024, e.parseContextWindowLimit())
    }

    @Test
    fun moonshot_tokenLimit_parsesLimit() {
        val e = enriched(
            400,
            """{"error":{"message":"Invalid request: your request exceeded model token limit: 128000"}}"""
        )
        assertTrue(e.isContextWindowOverflowError())
        assertEquals(128_000, e.parseContextWindowLimit())
    }

    @Test
    fun chinese_overflowText_isRecognized() {
        val e = enriched(400, """{"error":{"message":"请求失败：输入超出上下文长度限制"}}""")
        assertTrue(e.isContextWindowOverflowError())
        // 中文文案里没有数字，解析不出上限；调用方据此跳过写回但仍可重压重试。
        assertNull(e.parseContextWindowLimit())
    }

    @Test
    fun overflowWithoutNumber_isRecognizedButLimitIsNull() {
        val e = enriched(400, """{"error":{"message":"This model's maximum context length is exceeded."}}""")
        assertTrue(e.isContextWindowOverflowError())
        assertNull(e.parseContextWindowLimit())
    }

    // ---------- 流内错误码 ----------

    @Test
    fun streamCodeContextWindowExceeded_isRecognized() {
        val e = StreamApiException("context_window_exceeded", "Your input exceeds the context window of this model")
        assertTrue(e.isContextWindowOverflowError())
        assertNull(e.parseContextWindowLimit())
    }

    // ---------- cause 链（多 Key 全失败会再包一层） ----------

    @Test
    fun overflowWrappedInAllKeysFailed_isRecognizedThroughCauseChain() {
        val inner = enriched(
            400,
            """{"error":{"message":"This model's maximum context length is 128000 tokens."}}"""
        )
        val wrapped = AllKeysFailedException("「某供应商」的 3 个 Key 均失败", inner)
        assertTrue(wrapped.isContextWindowOverflowError())
        assertEquals(128_000, wrapped.parseContextWindowLimit())
    }

    // ---------- 不能误伤的 400 / 其它状态码 ----------

    @Test
    fun rateLimit429_isNotOverflow() {
        val e = enriched(429, """{"error":{"message":"Rate limit reached for requests"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }

    @Test
    fun rateLimitTextOn400_isNotOverflow() {
        // 部分中转把限流也回 400：文案里有「tokens」，但带 per minute，不能被当成超窗去重压重试。
        val e = enriched(400, """{"error":{"message":"Too many tokens per minute: limit 5000, please retry later"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }

    @Test
    fun unauthorized401_isNotOverflow() {
        val e = enriched(401, """{"error":{"message":"invalid api key"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }

    @Test
    fun serverError500WithOverflowText_isNotOverflow() {
        val e = enriched(500, """{"error":{"message":"context length exceeded"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }

    @Test
    fun tooManyTools400_isNotOverflow() {
        val e = enriched(400, """{"error":{"message":"Invalid value for tools: exceeds the maximum number of tools (128)"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }

    @Test
    fun maxTokensParamError400_isNotOverflow() {
        // 输出 token 参数超限是参数错误，不是上下文超窗；误判会写错窗口。
        val e = enriched(400, """{"error":{"message":"Invalid parameter: max_tokens exceeds the maximum allowed value 8192"}}""")
        assertFalse(e.isContextWindowOverflowError())
    }
}
