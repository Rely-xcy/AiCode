package com.aicode.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.listOrderDataStore by preferencesDataStore(
    name = "list_order_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 文件类列表（提示词 / 技能 / 子代理）的用户自定义顺序：DataStore 存 `key -> [id...]`。
 *
 * 顺序表只是「覆盖」，不是唯一来源：读取时表内 id 按表序在前，不在表里的按自然序排在最后，
 * 因此删掉条目、新增条目都不需要维护顺序表本身。
 *
 * 各列表的读路径（[com.aicode.feature.agent.domain.skill.SkillRepository] 等）是同步函数，
 * 所以整份顺序表在内存里留一份缓存，落盘后立即回写缓存，读路径不必改成 suspend。
 */
@Singleton
class ListOrderStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val orders = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    init {
        scope.launch {
            context.listOrderDataStore.data.collect { preferences ->
                orders.value = decode(preferences)
            }
        }
    }

    /** 某个列表当前的顺序表；没有记录时返回空表（调用方按自然序展示）。 */
    fun order(key: String): List<String> = orders.value[key].orEmpty()

    suspend fun save(key: String, ids: List<String>) {
        // 先回写缓存再落盘：本进程内的同步读路径立刻就能看到新顺序。
        orders.update { it + (key to ids) }
        context.listOrderDataStore.edit { preferences ->
            preferences[preferenceKey(key)] = ids.joinToString(SEPARATOR)
        }
    }

    /**
     * 按顺序表排序：表内 id 按表序在前，不在表里的保持 [items] 原有相对顺序排在最后。
     * 顺序表为空时原样返回（等价自然序）。
     */
    fun <T> sort(items: List<T>, key: String, idOf: (T) -> String): List<T> {
        val order = order(key)
        if (order.isEmpty()) return items
        val rank = order.withIndex().associate { (index, id) -> id to index }
        return items.sortedBy { rank[idOf(it)] ?: Int.MAX_VALUE }
    }

    private fun preferenceKey(key: String) = stringPreferencesKey("$PREFIX$key")

    private fun decode(preferences: Preferences): Map<String, List<String>> =
        preferences.asMap().entries.mapNotNull { (storedKey, value) ->
            if (!storedKey.name.startsWith(PREFIX)) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            storedKey.name.removePrefix(PREFIX) to raw.split(SEPARATOR).filter { it.isNotBlank() }
        }.toMap()

    companion object {
        const val KEY_SKILLS_GLOBAL = "skills.global"
        const val KEY_SKILLS_PROJECT = "skills.project"
        const val KEY_SUB_AGENTS_GLOBAL = "sub_agents.global"
        const val KEY_SUB_AGENTS_PROJECT = "sub_agents.project"
        const val KEY_PROMPTS_GLOBAL = "prompts.global"
        const val KEY_PROMPTS_PROJECT = "prompts.project"

        private const val PREFIX = "order_"

        /** id 都是文件名或固定短标识，不含换行，用换行拼接即可。 */
        private const val SEPARATOR = "\n"
    }
}
