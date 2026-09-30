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
 * 机制说明：内置编号即身份。用户在 App 里改某个片段，落盘为 `prompts.custom/` 下的覆盖副本
 * （文件名沿用内置文件名，如 `00-identity.md`），与手工放文件完全等价（见 [PromptFragmentResolver]）
 * ——所以 App 里改与直接改文件不会打架。
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
            val effective = PromptFragmentResolver.effectiveContent(override, builtinName?.let { readAsset(it) })
            // 副本在却读不出来时已回落内置，但必须留下痕迹：静默回落等于「改了没反应」
            effective.fallbackCause?.let {
                FileLogger.w(TAG, "覆盖副本读不出来，已回落内置: ${override?.name}", it)
            }
            val content = effective.content ?: return@mapNotNull null
            Fragment(
                number = number,
                title = title,
                builtinFile = builtinName,
                overrideFile = override,
                content = content
            )
        }
    }

    /**
     * 写覆盖副本。
     *
     * 文件名由 [overrideFileName] 决定：内置片段沿用内置文件名，自定义新增片段沿用已有的覆盖文件名。
     *
     * 顺序是先原子写新文件、再清掉同编号的旧文件：反过来的话，写失败会把用户原有的覆盖一起弄丢。
     * 内容超过 [PromptFragmentResolver.MAX_FRAGMENT_CHARS] 直接拒绝——静默截断会写出半截提示词，
     * 比报错难查得多。
     */
    fun saveOverride(number: Int, title: String, content: String): Boolean {
        if (content.length > PromptFragmentResolver.MAX_FRAGMENT_CHARS) {
            FileLogger.e(
                TAG,
                "覆盖内容超长已拒绝: $number 共 ${content.length} 字符，上限 ${PromptFragmentResolver.MAX_FRAGMENT_CHARS}"
            )
            return false
        }
        return try {
            if (!customDir.exists()) customDir.mkdirs()
            val target = File(customDir, overrideFileName(number, title))
            writeAtomically(target, content)
            deleteOverridesFor(number, except = target)
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
    }.getOrElse {
        // 内置文件读不出来时该片段会从清单里消失，不能静默
        FileLogger.w(TAG, "读内置片段失败: $name", it)
        null
    }

    /** 删除某编号的覆盖副本，[except] 为本次刚写下的那个文件（不删它）。 */
    private fun deleteOverridesFor(number: Int, except: File? = null): Boolean {
        val files = customDir.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        } ?: return false
        var deleted = false
        files.forEach { file ->
            if (file.absolutePath != except?.absolutePath) deleted = file.delete() || deleted
        }
        return deleted
    }

    /**
     * 原子写：先写临时文件再 rename 覆盖。
     *
     * 直接 writeText 写到一半被打断（进程被杀、磁盘满）会留下半截片段，而片段会被整段拼进系统提示词，
     * 半截规则比旧内容更糟。rename 在同一文件系统内是原子的：要么全新、要么全旧。
     */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(customDir, "${file.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text)
            // 少数文件系统上 rename 覆盖会失败：退回直接写，至少不把内容丢掉
            if (!tmp.renameTo(file)) file.writeText(text)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 覆盖副本的文件名，按优先级：
     * 1. 内置文件名 —— 编号即身份，名字能一眼对上是哪个内置片段；
     * 2. 该编号已有的覆盖文件名 —— 用户自己起的名字（如 `25-额外要求.md`）不该被改写，
     *    否则保存会另起一个文件、旧文件还在，两个同编号文件互相盖，看起来就是「改了没生效」；
     * 3. `<NN>-<标题>.md` —— 兜底。
     *
     * 第 2 步取字典序首个，与 [PromptFragmentResolver.numberedFragments] 实际生效的那个保持一致。
     */
    private fun overrideFileName(number: Int, title: String): String {
        builtinFiles()[number]?.let { return it }
        customDir.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        }?.minByOrNull { it.name }?.let { return it.name }
        return "%02d-%s.md".format(number, sanitizeTitle(title))
    }

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\u4e00-\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    companion object {
        private const val TAG = "PromptFragmentRepository"
        private const val ASSET_DIR = "prompts"
        private const val CUSTOM_DIR = "prompts.custom"
        private const val HELP_READ_MARKER = ".help-read"
    }
}
