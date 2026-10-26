package com.aicode.feature.agent.domain.provider

import com.aicode.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 多 Key 切换的失败判定（[Throwable.isKeySwitchFailure]）：默认码表、可配置覆盖与 cause 链追溯。
 */
class KeyFailureClassifierTest {

    private fun httpError(code: Int, body: String = "{}"): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody(null)))

    @Test
    fun default_codes_trigger_switch() {
        DEFAULT_KEY_SWITCH_STATUS_CODES.forEach { code ->
            assertTrue("HTTP $code 应触发切换", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun transient_codes_do_not_trigger_switch() {
        listOf(408, 500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code 不应触发切换", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun custom_codes_override_default() {
        val custom = setOf(400, 418)
        assertTrue(httpError(400).isKeySwitchFailure(custom))
        assertFalse("自定义码表未含 401 时不应切换", httpError(401).isKeySwitchFailure(custom))
    }

    @Test
    fun stream_codes_and_message_fallback() {
        assertTrue(StreamApiException("insufficient_quota", "m").isKeySwitchFailure())
        assertTrue(StreamApiException("rate_limit_exceeded", "m").isKeySwitchFailure())
        assertTrue(IllegalStateException("HTTP 401 from upstream").isKeySwitchFailure())
        assertFalse(IllegalStateException("HTTP 500 Internal Server Error").isKeySwitchFailure())
    }

    @Test
    fun cause_chain_is_followed() {
        val wrapped = IllegalStateException("wrapped", httpError(403))
        assertTrue(wrapped.isKeySwitchFailure())
    }

    // ---------- 分组/模型级失败（channelUnavailableFailure） ----------

    /** 503 + one-api 的「无可用渠道」：能拿到状态码与响应体，并从文案里解析出分组名。 */
    @Test
    fun channel_unavailable_503_with_group_is_detected() {
        val error = httpError(503, CHANNEL_BODY)

        val failure = error.channelUnavailableFailure()

        assertNotNull(failure)
        assertEquals(503, failure!!.statusCode)
        assertEquals("0.15", failure.group)
        assertTrue(failure.detail.contains("No available channel"))
    }

    /** 中文提示（one-api 中文包）同样能识别并解析分组。 */
    @Test
    fun channel_unavailable_chinese_marker_parses_group() {
        val error = httpError(503, """{"error":{"message":"当前分组 default 下对于模型 gpt-4 无可用渠道"}}""")

        assertEquals("default", error.channelUnavailableFailure()?.group)
    }

    /** 只有 503 + 渠道缺失文案才是分组级失败：状态码不对、文案不对、没响应体都不算。 */
    @Test
    fun channel_unavailable_requires_503_and_marker() {
        assertNull("500 不是渠道缺失", httpError(500, CHANNEL_BODY).channelUnavailableFailure())
        assertNull("401 由 Key 级判定负责", httpError(401, CHANNEL_BODY).channelUnavailableFailure())
        assertNull("503 过载不算渠道缺失", httpError(503, """{"error":{"message":"server overloaded"}}""").channelUnavailableFailure())
        assertNull("没有响应体时无法区分", httpError(503, "").channelUnavailableFailure())
    }

    /** 判定只 peek 不消费：后续 enrich / 日志仍能读到完整响应体。 */
    @Test
    fun channel_unavailable_doesNotConsumeErrorBody() {
        val error = httpError(503, CHANNEL_BODY)

        assertNotNull(error.channelUnavailableFailure())

        val body = error.response()?.errorBody()?.string()
        assertTrue("判定后响应体应仍可读全文", body.orEmpty().contains("No available channel"))
    }

    /** 沿 cause 链查找；[EnrichedHttpException] 自身携带状态码与全文，同样能识别。 */
    @Test
    fun channel_unavailable_followsCauseChainAndEnrichedException() {
        val wrapped = IllegalStateException("wrapped", httpError(503, CHANNEL_BODY))
        assertEquals("0.15", wrapped.channelUnavailableFailure()?.group)

        val enriched = EnrichedHttpException("HTTP 503: ...", 503, CHANNEL_BODY, IllegalStateException())
        assertEquals("0.15", enriched.channelUnavailableFailure()?.group)

        val enriched500 = EnrichedHttpException("HTTP 500: ...", 500, CHANNEL_BODY, IllegalStateException())
        assertNull(enriched500.channelUnavailableFailure())
    }

    private companion object {
        const val CHANNEL_BODY =
            """{"error":{"message":"No available channel for model gpt-5.6-sol under group 0.15 (request id: 1)","type":"new_api_error"}}"""
    }
}
