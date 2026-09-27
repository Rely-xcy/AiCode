package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取 App 内置的提示词使用说明。
 *
 * 正文来自仓库里的 `docs-site/docs/guide/custom-prompts.md`，构建时由 `syncAiDocs` 复制进
 * `assets/docs/`，因此与官方文档**同源**、随 App 版本一起更新，且完全离线可用。
 *
 * 旧实现是运行时抓 `aicode.murk.top/md/...` 并缓存 7 天，离线时还得回退到 App 内手抄的短说明——
 * 手抄的那份一改就漂，而抓取又让「首次进入必须先读说明」在没网时形同虚设。
 */
@Singleton
class PromptDocsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "PromptDocsRepository"
        const val ASSET_PATH = "docs/guide/custom-prompts.md"
    }

    /** 取内置文档正文；资源缺失或读取失败时返回 failure（调用方回退到内置短说明）。 */
    suspend fun load(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.onFailure {
            FileLogger.w(TAG, "读取内置文档失败 $ASSET_PATH: ${it.message}")
        }
    }
}
