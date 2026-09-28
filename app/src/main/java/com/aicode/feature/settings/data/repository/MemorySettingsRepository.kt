package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.memoryDataStore by preferencesDataStore(name = "memory_prefs")

/**
 * 记忆模块自己的开关（每个引擎模块一套开关，互不影响）。
 *
 * 目前一项：长期记忆自动沉淀。
 * **默认关**是刻意的——自动沉淀会调模型、写文件，用户必须自己打开才知道它在工作，
 * 而不是装完就在后台默默记东西（这正是上一版画像被否掉的原因）。
 */
@Singleton
class MemorySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    val autoDistillEnabledFlow: Flow<Boolean> = context.memoryDataStore.data
        .map { it[AUTO_DISTILL_ENABLED_KEY] ?: false }

    /**
     * 同步快照：系统提示词片段是同步拼接的（读不了 DataStore），
     * 而提示词里要不要加「主动记记忆」的规则取决于本开关。
     * 由下面的收集器持续刷新，进程启动后很快就有值。
     */
    @Volatile
    private var autoDistillEnabledSnapshot: Boolean = false

    // 声明在 init 之前：Kotlin 按声明顺序初始化，写在后面 init 里会报「必须初始化」
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            autoDistillEnabledFlow.collect { autoDistillEnabledSnapshot = it }
        }
    }

    fun autoDistillEnabledSync(): Boolean = autoDistillEnabledSnapshot

    suspend fun autoDistillEnabled(): Boolean = autoDistillEnabledFlow.first()

    suspend fun setAutoDistillEnabled(enabled: Boolean) {
        context.memoryDataStore.edit { it[AUTO_DISTILL_ENABLED_KEY] = enabled }
    }

    private companion object {
        val AUTO_DISTILL_ENABLED_KEY = booleanPreferencesKey("auto_distill_enabled")
    }
}
