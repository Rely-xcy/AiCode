package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置静态片段（`assets/prompts/<NN>-<名称>.md`）的读取与「覆盖」管理。
 *
 * 机制说明：内置编号即身份。用户在 App 里改某个片段，落盘为 `prompts.custom/<NN>-<名称>.md`，
 * 与手工放文件完全等价（见 [PromptFragmentResolver]）——所以 App 里改与直接改文件不会打架。
 *
 * 内置片段清单不硬编码，直接列 `assets/prompts` 顶层带编号的 md，App 升级新增片段自动出现。
 */
@Singleton
class PromptFragmentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {

    /**
     * 一个生效的静态片段。
     *
     * @param number 编号（顺序依据）
     * @param title 名称（取自文件名去掉编号与扩展名）
     * @param builtinFile 内置文件名；自定义新增的片段为 null
     * @param overrideFile 覆盖文件；未覆盖时为 null
     * @param content 生效正文（有覆盖读覆盖，否则读内置）
     */
    data class Fragment(
        val number: Int,
        val title: String,
        val builtinFile: String?,
        val overrideFile: File?,
        val content: String
    ) {
        val isOverridden: Boolean get() = overrideFile != null
    }

    private val customDir: File get() = File(containerInstaller.aicodeDir, CUSTOM_DIR)

    /** 生效的静态片段清单，按编号升序。 */
    fun listFragments(): List<Fragment> {
        val builtin = builtinFiles()
        val builtinNumbers = builtin.keys.sorted()
        val merged = PromptFragmentResolver.mergeStatic(
            builtinNumbers,
            PromptFragmentResolver.numberedFragments(customDir)
        )
        return merged.mapNotNull { (number, override) ->
            val builtinName = builtin[number]
            val title = (override?.nameWithoutExtension ?: builtinName?.removeSuffix(".md"))
                ?.substringAfter('-', missingDelimiterValue = "")
                ?: return@mapNotNull null
            val content = override?.let { readText(it) }
                ?: builtinName?.let { readAsset(it) }
                ?: return@mapNotNull null
            Fragment(
                number = number,
                title = title,
                builtinFile = builtinName,
                overrideFile = override,
                content = content
            )
        }
    }

    /** 只读内置片段（用户可改的那个固定项，默认 00）的生效正文；不存在返回 null。 */
    fun fragment(number: Int): Fragment? = listFragments().firstOrNull { it.number == number }

    /** 写覆盖：同名编号的旧覆盖文件先清掉（编号即身份，同编号只保留一个）。 */
    fun saveOverride(number: Int, title: String, content: String): Boolean {
        return try {
            if (!customDir.exists()) customDir.mkdirs()
            deleteOverridesFor(number)
            File(customDir, "%02d-%s.md".format(number, sanitizeTitle(title))).writeText(content)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "写提示词覆盖失败: $number", e)
            false
        }
    }

    /** 删除覆盖，恢复内置默认内容。 */
    fun deleteOverride(number: Int): Boolean = deleteOverridesFor(number)

    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(customDir)

    /**
     * 「已读使用说明」标记。
     *
     * 用标记文件而不是 DataStore：与 `.no-builtin` 同一路子，位置就在 prompts.custom 下，
     * 用户备份/迁移配置时跟着一起走。
     */
    fun isHelpRead(): Boolean = File(customDir, HELP_READ_MARKER).isFile

    fun markHelpRead() {
        runCatching {
            if (!customDir.exists()) customDir.mkdirs()
            File(customDir, HELP_READ_MARKER).writeText("")
        }.onFailure { FileLogger.w(TAG, "写已读标记失败", it) }
    }

    /** 切换「完全禁用内置提示词」（`.no-builtin` 标记文件）。 */
    fun setBuiltinDisabled(disabled: Boolean): Boolean {
        return try {
            val marker = File(customDir, PromptFragmentResolver.DISABLE_BUILTIN_FILE)
            if (disabled) {
                if (!customDir.exists()) customDir.mkdirs()
                marker.writeText("")
            } else if (marker.isFile) {
                marker.delete()
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换禁用内置提示词失败", e)
            false
        }
    }

    /** `assets/prompts` 顶层带编号的 md：编号 → 文件名。 */
    private fun builtinFiles(): Map<Int, String> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, String>()
        names.filter { it.endsWith(".md") }.sorted().forEach { name ->
            val number = PromptFragmentResolver.parseNumber(name) ?: return@forEach
            byNumber.putIfAbsent(number, name)
        }
        return byNumber
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun deleteOverridesFor(number: Int): Boolean {
        val files = customDir.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        } ?: return false
        var deleted = false
        files.forEach { deleted = it.delete() || deleted }
        return deleted
    }

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    private companion object {
        const val TAG = "PromptFragmentRepository"
        const val ASSET_DIR = "prompts"
        const val CUSTOM_DIR = "prompts.custom"
        const val HELP_READ_MARKER = ".help-read"
    }
}
