package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 片段来源，优先级从高到低：项目 > 全局 > 本地 > 内置。 */
enum class PromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }

/** 一个编号最终生效的片段。 */
data class PromptFragment(
    val number: Int,
    val title: String,
    val source: PromptFragmentSource,
    val content: String,
    /** 生效层文件；来源为内置时为 null。 */
    val file: File?,
    /** 正文开头的 `<!-- ... -->` 注释，作为列表摘要。 */
    val description: String
) {
    val stableReorderKey: String
        get() = "$title:${content.hashCode()}"

    val body: String
        get() = content.replace(LEADING_COMMENT, "")

    companion object {
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        fun composeContent(description: String, body: String): String =
            if (description.isBlank()) body else "<!-- ${description.trim().replace("-->", "-- >")} -->\n$body"
    }

    /** 生效层是否可写（项目/全局可写，本地/内置只读）。 */
    val editable: Boolean
        get() = source == PromptFragmentSource.PROJECT || source == PromptFragmentSource.GLOBAL
}

/**
 * 提示词片段的四级来源解析：同一编号只生效优先级最高的一层。
 *
 * - 项目：工作区 `.aicode/prompts.custom/`（远程走 [ProjectAicodeRoot] 私有目录）
 * - 全局：`<aicodeDir>/prompts.custom/`
 * - 本地：`<aicodeDir>/prompts/`（启动时从内置释放的副本）
 * - 内置：`assets/prompts/`
 *
 * 编号即身份（文件名前两位数字），决定片段在系统提示词里的顺序。
 */
@Singleton
class PromptFragmentCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {

    private val globalDir: File get() = File(containerInstaller.aicodeDir, CUSTOM_DIR)
    private val localDir: File get() = File(containerInstaller.aicodeDir, LOCAL_DIR)

    /** 某工作区对应的项目层目录；无工作区返回 null。 */
    private fun projectDir(projectRoot: String?): File? =
        projectRoot?.takeIf { it.isNotBlank() }
            ?.let { File(projectAicodeRoot.forPath(it), CUSTOM_DIR) }

    /** 编辑/新增的写入层：有工作区写项目层，否则写全局层。 */
    fun writableDir(projectRoot: String?): File = projectDir(projectRoot) ?: globalDir

    /** 按编号升序列出所有生效片段。 */
    fun list(projectRoot: String?): List<PromptFragment> {
        val builtin = builtinFiles()
        val project = numberedFragments(projectDir(projectRoot))
        val global = numberedFragments(globalDir)
        val local = numberedFragments(localDir)
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtin.keys)
            addAll(project.keys)
            addAll(global.keys)
            // 本地层也按编号参与：重排会把本地片段改到任意编号上（不再只认内置文件名）。
            addAll(local.keys)
        }
        val all = numbers.sorted().mapNotNull { resolve(it, builtin, project, global, local) }
        // 「完全禁用内置提示词」时列表只保留自定义来源，与注入结果一致。
        return if (isBuiltinDisabled()) {
            all.filter {
                it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL
            }
        } else {
            all
        }
    }

    fun fragment(number: Int, projectRoot: String?): PromptFragment? = resolve(
        number,
        builtinFiles(),
        numberedFragments(projectDir(projectRoot)),
        numberedFragments(globalDir),
        numberedFragments(localDir)
    )

    /** 静态基线正文：所有生效片段去掉前导注释后按编号拼接。 */
    fun renderStatic(projectRoot: String?): String =
        list(projectRoot)
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 「仅自定义片段」模式：只取项目层与全局层的自定义片段正文。 */
    fun renderCustomOnly(projectRoot: String?): String =
        list(projectRoot)
            .filter { it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL }
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 是否存在「完全禁用内置提示词」标记（全局层）。 */
    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(globalDir)

    /**
     * 保存某编号的覆盖。
     *
     * - [target] 指定写入层（项目/全局）；null 表示写到该编号当前生效的层（只读层则回落可写层）。
     * - [target] 为 PROJECT 而没有工作区（未落定的窗口期）时**返回 false 且不写任何层**：回落到全局层
     *   等于把用户选的作用域悄悄改掉——用户以为存到了项目，实际只有全局层多了一份，工作区就绪后
     *   项目层仍然没有这条覆盖。由调用方提示「工作区未就绪」。
     * - [previousNumber] 编辑时若改了编号，传原编号，用于清掉旧编号的覆盖。
     * - 同一编号只保留一份：写入前清掉各可写层里的同编号旧文件。
     */
    fun saveOverride(
        number: Int,
        title: String,
        content: String,
        projectRoot: String?,
        target: PromptFragmentSource? = null,
        previousNumber: Int? = null
    ): Boolean {
        val project = projectDir(projectRoot)
        if (target == PromptFragmentSource.PROJECT && project == null) {
            FileLogger.w(TAG, "项目级提示词覆盖写入被跳过：工作区未落定 (#$number)")
            return false
        }
        val dir = when (target) {
            PromptFragmentSource.PROJECT -> project ?: globalDir
            PromptFragmentSource.GLOBAL -> globalDir
            else -> fragment(number, projectRoot)?.takeIf { it.editable }?.file?.parentFile
                ?: writableDir(projectRoot)
        }
        return try {
            if (previousNumber != null && previousNumber != number) {
                deleteOverridesFor(previousNumber, project)
                deleteOverridesFor(previousNumber, globalDir)
            }
            if (!dir.exists()) dir.mkdirs()
            deleteOverridesFor(number, dir)
            if (project != null && project != dir) deleteOverridesFor(number, project)
            if (globalDir != dir) deleteOverridesFor(number, globalDir)
            File(dir, "%02d-%s.md".format(number, sanitizeTitle(title))).writeText(content)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "写提示词覆盖失败: $number", e)
            false
        }
    }

    /**
     * 按拖拽后的顺序重新编号并落盘：编号集合不变，只把新顺序映射到这些编号上，
     * 从而改变注入顺序（数字越小越靠前）。
     *
     * 每个片段写回它**自己所属的层**（项目 → 工作区 `.aicode/prompts.custom/`、
     * 全局 → `<aicodeDir>/prompts.custom/`、本地 → `<aicodeDir>/prompts/`），
     * 所以各行的来源徽章不会因为拖动而改变。内置片段存在 assets 里、文件名改不了，
     * 一律跳过（由 UI 禁止拖动内置行）。
     *
     * 落盘顺序保证不丢内容：先把这些编号上已有的文件（含被遮挡的同编号文件）整体挪进暂存目录，
     * 再写新内容并校验；任一步失败都把暂存目录里的文件搬回原位，磁盘与操作前一致。
     */
    fun reorder(reordered: List<PromptFragment>, projectRoot: String?): Boolean {
        val project = projectDir(projectRoot)
        val movable = reordered.filter { it.source != PromptFragmentSource.BUILTIN }
        if (movable.isEmpty()) return false
        val numbers = movable.map { it.number }.sorted()
        if (numbers.toSet().size != numbers.size) {
            FileLogger.w(TAG, "提示词重排被跳过：编号重复 $numbers")
            return false
        }
        val writes = movable.mapIndexedNotNull { index, fragment ->
            reorderDir(fragment.source, project)?.let { dir ->
                ReorderWrite(dir, fileName(numbers[index], fragment.title), fragment.content)
            }
        }
        if (writes.size != movable.size) {
            FileLogger.w(TAG, "提示词重排被跳过：存在无法写回的来源")
            return false
        }
        val dirs = listOfNotNull(project, globalDir, localDir).distinct()
        return applyReorder(numbers, writes, dirs)
    }

    /** 重排时各来源可写回的层目录。只影响改编号，与「内容能否编辑」的 [editable] 无关。 */
    private fun reorderDir(source: PromptFragmentSource, project: File?): File? = when (source) {
        PromptFragmentSource.PROJECT -> project
        PromptFragmentSource.GLOBAL -> globalDir
        PromptFragmentSource.LOCAL -> localDir
        PromptFragmentSource.BUILTIN -> null
    }

    /** 一个编号定向目标：写到哪个目录、用什么文件名、写什么内容。 */
    private data class ReorderWrite(val dir: File, val name: String, val content: String)

    private fun fileName(number: Int, title: String): String =
        "%02d-%s.md".format(number, sanitizeTitle(title))

    /**
     * 两阶段落盘：先把涉及的旧文件整体暂存，再写新状态并校验，失败则整体回滚。
     *
     * @param numbers 参与重排的编号集合（顺序无关）。
     * @param writes 目标状态：每个编号最终应有的文件。
     * @param dirs 需要参与清理的目录（项目 / 全局 / 本地）。
     */
    private fun applyReorder(numbers: List<Int>, writes: List<ReorderWrite>, dirs: List<File>): Boolean {
        val staging = File(containerInstaller.aicodeDir, STAGING_DIR)
        val stashed = mutableListOf<Pair<File, File>>()
        val written = mutableListOf<File>()
        fun rollback() {
            written.forEach { it.delete() }
            stashed.forEach { (original, stash) ->
                if (stash.isFile) {
                    original.parentFile?.mkdirs()
                    stash.copyTo(original, overwrite = true)
                }
            }
            staging.deleteRecursively()
        }
        return try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("暂存目录创建失败: ${staging.absolutePath}")
            dirs.forEachIndexed { dirIndex, dir ->
                dir.listFiles().orEmpty().forEach { file ->
                    val number = if (file.isFile) PromptFragmentResolver.parseNumber(file.name) else null
                    if (number == null || number !in numbers) return@forEach
                    val stash = File(File(staging, dirIndex.toString()), file.name)
                    stash.parentFile?.mkdirs()
                    if (!file.renameTo(stash)) throw IOException("暂存失败: ${file.absolutePath}")
                    stashed += file to stash
                }
            }
            writes.forEach { write ->
                if (!write.dir.exists() && !write.dir.mkdirs()) {
                    throw IOException("目录创建失败: ${write.dir.absolutePath}")
                }
                File(write.dir, write.name).writeText(write.content)
                written += File(write.dir, write.name)
            }
            if (!verifyReorder(numbers, writes, dirs)) throw IOException("重排校验未通过")
            staging.deleteRecursively()
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "重排提示词失败，已回滚", e)
            rollback()
            false
        }
    }

    /** 校验：每个参与重排的编号上，只应有目标文件且内容逐字一致。 */
    private fun verifyReorder(numbers: List<Int>, writes: List<ReorderWrite>, dirs: List<File>): Boolean {
        val expected = HashMap<Int, ReorderWrite>()
        writes.forEach { write ->
            val number = PromptFragmentResolver.parseNumber(write.name) ?: return false
            if (expected.put(number, write) != null) return false
        }
        numbers.forEach { number ->
            val write = expected[number] ?: return false
            val target = File(write.dir, write.name)
            if (!target.isFile || target.readText() != write.content) return false
            dirs.forEach { dir ->
                dir.listFiles().orEmpty().forEach { file ->
                    if (file.isFile && PromptFragmentResolver.parseNumber(file.name) == number && file != target) {
                        return false
                    }
                }
            }
        }
        return true
    }

    /**
     * 删除某编号在 [target] 层的覆盖，删后自动回退到下一层。
     *
     * - [target] 指定要删的层（项目层 / 全局层）；本地层与内置层不可删，返回 false。
     * - [target] 为 PROJECT 而没有工作区（未落定的窗口期）时**返回 false 且一层都不删**：此时项目层
     *   解析不到，「生效层」退化成全局层，被删掉的会是全局层那份同编号覆盖——用户看着项目级那条，
     *   项目层的文件反而留了下来。由调用方提示「工作区未就绪」。
     */
    fun deleteOverride(number: Int, projectRoot: String?, target: PromptFragmentSource): Boolean {
        val project = projectDir(projectRoot)
        if (target == PromptFragmentSource.PROJECT && project == null) {
            FileLogger.w(TAG, "项目级提示词覆盖删除被跳过：工作区未落定 (#$number)")
            return false
        }
        val dir = when (target) {
            PromptFragmentSource.PROJECT -> project
            PromptFragmentSource.GLOBAL -> globalDir
            else -> null
        }
        return numberedFragments(dir)[number]?.delete() ?: false
    }

    /** 切换「完全禁用内置提示词」标记（全局层）。 */
    fun setBuiltinDisabled(disabled: Boolean): Boolean {
        return try {
            val marker = File(globalDir, PromptFragmentResolver.DISABLE_BUILTIN_FILE)
            if (disabled) {
                if (!globalDir.exists()) globalDir.mkdirs()
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

    private fun resolve(
        number: Int,
        builtin: Map<Int, String>,
        project: Map<Int, File>,
        global: Map<Int, File>,
        local: Map<Int, File>
    ): PromptFragment? {
        project[number]?.let { return readFragment(it, number, PromptFragmentSource.PROJECT) }
        global[number]?.let { return readFragment(it, number, PromptFragmentSource.GLOBAL) }
        // 本地层按编号解析（不再要求文件名与内置同名），重排到任意编号后仍然可解析。
        local[number]?.let { return readFragment(it, number, PromptFragmentSource.LOCAL) }
        builtin[number]?.let { name ->
            readAsset(name)?.let { content ->
                return PromptFragment(
                    number,
                    titleOf(name),
                    PromptFragmentSource.BUILTIN,
                    content,
                    null,
                    extractDescription(content)
                )
            }
        }
        return null
    }

    private fun readFragment(file: File, number: Int, source: PromptFragmentSource): PromptFragment? =
        readText(file)?.let { content ->
            PromptFragment(number, titleOf(file.name), source, content, file, extractDescription(content))
        }

    /** 目录顶层 `<两位数字>-<名称>.md`，按编号去重（同编号取字典序首个）。 */
    private fun numberedFragments(dir: File?): Map<Int, File> {
        val files = dir?.listFiles() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            PromptFragmentResolver.parseNumber(file.name)?.let { byNumber.putIfAbsent(it, file) }
        }
        return byNumber
    }

    /** `assets/prompts` 顶层带编号的 md：编号 → 文件名。 */
    private fun builtinFiles(): Map<Int, String> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, String>()
        names.filter { it.endsWith(".md") }.sorted().forEach { name ->
            PromptFragmentResolver.parseNumber(name)?.let { byNumber.putIfAbsent(it, name) }
        }
        return byNumber
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun deleteOverridesFor(number: Int, dir: File?): Boolean {
        val files = dir?.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        } ?: return false
        var deleted = false
        files.forEach { deleted = it.delete() || deleted }
        return deleted
    }

    private fun titleOf(fileName: String): String {
        val base = fileName.removeSuffix(".md")
        return base.substringAfter('-', missingDelimiterValue = base)
    }

    /** 正文开头的 `<!-- ... -->` 注释即摘要（内置片段都带一行）。 */
    private fun extractDescription(content: String): String =
        DESCRIPTION.find(content)?.groupValues?.get(1)?.trim().orEmpty()

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    private companion object {
        const val TAG = "PromptFragmentCatalog"
        const val ASSET_DIR = "prompts"
        const val CUSTOM_DIR = "prompts.custom"
        const val LOCAL_DIR = "prompts"
        const val STAGING_DIR = ".reorder-staging"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        val DESCRIPTION = Regex("^\\s*<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * 拖拽落点对应的编号分配：内置片段不参与重排，其余片段按新顺序占用它们原先占据的编号集合，
 * 最后按编号升序返回。
 *
 * 与 [PromptFragmentCatalog.reorder] 共用同一套规则，保证列表乐观更新的编号与落盘结果一致。
 */
internal fun assignReorderNumbers(reordered: List<PromptFragment>): List<PromptFragment> {
    val slots = reordered.filter { it.source != PromptFragmentSource.BUILTIN }.map { it.number }.sorted()
    var next = 0
    return reordered
        .map { fragment ->
            if (fragment.source == PromptFragmentSource.BUILTIN) fragment
            else fragment.copy(number = slots[next++])
        }
        .sortedBy { it.number }
}
