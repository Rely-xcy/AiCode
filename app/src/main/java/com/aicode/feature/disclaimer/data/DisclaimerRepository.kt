package com.aicode.feature.disclaimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.disclaimerDataStore by preferencesDataStore(
    name = "disclaimer_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 免责声明的同意状态持久化：首次安装时未同意，用户点「我已阅读并同意」后写入 true，
 * 之后不再弹出（设置-关于页仍可随时查看）。
 */
@Singleton
class DisclaimerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACCEPTED_KEY = booleanPreferencesKey("disclaimer_accepted")
    }

    /** 是否已同意免责声明；从未写过时为 false。 */
    val acceptedFlow: Flow<Boolean> =
        context.disclaimerDataStore.data.map { it[ACCEPTED_KEY] ?: false }

    /** 记录用户已同意。 */
    suspend fun accept() {
        context.disclaimerDataStore.edit { it[ACCEPTED_KEY] = true }
    }
}
