package com.aicode.feature.agent.domain.tool.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 内置浏览器的 User-Agent 档位。 */
enum class BrowserUserAgent(val key: String) {
    /** 跟随系统：不覆盖 WebView 自带 UA。 */
    SYSTEM("system"),

    /** 桌面版：让站点返回 PC 页面。 */
    DESKTOP("desktop"),

    /** 移动版：强制手机页面（系统 UA 被改乱或需要固定手机版时用）。 */
    MOBILE("mobile");

    companion object {
        fun fromKey(raw: String?): BrowserUserAgent =
            values().firstOrNull { it.key == raw } ?: SYSTEM
    }
}

/**
 * 内置浏览器的 UA 设置。
 *
 * 用 SharedPreferences 而不是 DataStore：WebView 的配置路径
 * （[BrowserManager.configureWebView]）是同步非挂起的，DataStore 只能异步读，
 * 那样就得再引一个"启动时把首帧值灌进内存"的 holder（见 `ExecutionModeHolder`）
 * 和一个额外的启动初始化步骤。这里的数据量只有一个字符串，同步读更直接。
 */
@Singleton
class BrowserUserAgentStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS = "browser_settings"
        const val KEY_USER_AGENT = "user_agent"

        /** 固定到 Chrome 桌面版：用具体版本号而不是 "Chrome/xx.0.0.0"，避免站点按旧版本降级。 */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun current(): BrowserUserAgent = BrowserUserAgent.fromKey(prefs.getString(KEY_USER_AGENT, null))

    fun set(value: BrowserUserAgent) {
        prefs.edit().putString(KEY_USER_AGENT, value.key).apply()
    }

    /**
     * 要写进 [android.webkit.WebSettings.setUserAgentString] 的值。
     *
     * 返回 null 表示"不覆盖"——直接把 null 赋回去会让 WebView 恢复系统默认 UA，
     * 所以从桌面/移动切回跟随系统时不需要另外记原始 UA。
     */
    fun userAgentString(value: BrowserUserAgent = current()): String? = when (value) {
        BrowserUserAgent.SYSTEM -> null
        BrowserUserAgent.DESKTOP -> DESKTOP_UA
        BrowserUserAgent.MOBILE -> MOBILE_UA
    }
}
