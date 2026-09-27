package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取官方提示词文档（`aicode.murk.top/md/...` 的纯 Markdown 直链）。
 *
 * 自定义提示词页要展示的说明必须与官方文档一致，不能靠 App 内手抄一份——抄的那份一改就漂。
 * 因此运行时抓官方 md 并缓存到本地：联网时刷新，离线时用缓存；两者都没有才回退到内置短说明。
 */
@Singleton
class PromptDocsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: okhttp3.OkHttpClient
) {
    private companion object {
        const val TAG = "PromptDocsRepository"
        const val URL = "https://aicode.murk.top/md/guide/custom-prompts.md"

        /** 缓存有效期：文档改动不频繁，7 天足够新。 */
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val cacheFile: File get() = File(File(context.cacheDir, "prompt-docs"), "custom-prompts.md")

    /**
     * 取官方文档正文。
     *
     * @param forceRefresh 忽略缓存有效期，强制联网刷新（用户手动下拉/重试时用）。
     * @return 正文与来源；联网与缓存都拿不到时返回 failure。
     */
    suspend fun load(forceRefresh: Boolean = false): Result<LoadedDocs> = withContext(Dispatchers.IO) {
        val cached = readCache()
        if (!forceRefresh && cached != null && isFresh()) {
            return@withContext Result.success(LoadedDocs(cached, fromCache = true))
        }
        val fetched = runCatching { fetch() }
        fetched.fold(
            onSuccess = { text ->
                writeCache(text)
                Result.success(LoadedDocs(text, fromCache = false))
            },
            onFailure = { error ->
                FileLogger.w(TAG, "拉取官方文档失败: ${error.message}")
                if (cached != null) Result.success(LoadedDocs(cached, fromCache = true))
                else Result.failure(error)
            }
        )
    }

    private fun fetch(): String {
        val request = okhttp3.Request.Builder()
            .url(URL)
            .header("Accept", "text/markdown, text/plain, */*")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "响应为空" }
            return body
        }
    }

    private fun readCache(): String? =
        runCatching { cacheFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun isFresh(): Boolean {
        val file = cacheFile
        return file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
    }

    private fun writeCache(text: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(text)
        }.onFailure { FileLogger.w(TAG, "写入文档缓存失败: ${it.message}") }
    }

    data class LoadedDocs(val text: String, val fromCache: Boolean)
}
