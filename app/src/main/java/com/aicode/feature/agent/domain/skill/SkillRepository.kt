package com.aicode.feature.agent.domain.skill

import com.aicode.core.datastore.ListOrderStore
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 仓库，聚合各 [SkillSource]（全局目录 + 项目目录）提供的技能，
 * 并按 [SkillConfigRepository] 的禁用名单过滤注入清单。
 *
 * 全局技能 `~/.aicode/skills`、项目级技能 `<workspace>/.aicode/skills`，两者都经 [FileAccessProvider]
 * 以容器路径读写，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 */
@Singleton
class SkillRepository @Inject constructor(
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val listOrderStore: ListOrderStore,
    private val bulk: BulkFileSource
) {
    /** 不启用远程批量读取缓存的构造（单测与无远端场景）。 */
    internal constructor(
        globalDirectorySkillSource: GlobalDirectorySkillSource,
        projectDirectorySkillSource: ProjectDirectorySkillSource,
        skillConfigRepository: SkillConfigRepository,
        fileAccess: FileAccessProvider,
        listOrderStore: ListOrderStore
    ) : this(
        globalDirectorySkillSource,
        projectDirectorySkillSource,
        skillConfigRepository,
        fileAccess,
        listOrderStore,
        BulkFileSource.Disabled
    )
    /**
     * 全部技能（含来源作用域），未过滤禁用；同名技能项目级优先（与 MCP 两级配置一致）。
     * 顺序：全局在前、项目在后，各自按用户拖拽排的顺序表排（没排过的按名称）。
     */
    fun listAllSkills(): List<SkillEntry> =
        sortEntries(mergeAll(globalDirectorySkillSource.listSkills(), projectDirectorySkillSource.listSkills()))

    /**
     * 同 [listAllSkills]，但任一来源读取失败时返回 null：注入系统提示词的调用方据此避免把一次
     * 读失败缓存成「本会话没有技能」。
     */
    fun listAllSkillsOrNull(): List<SkillEntry>? {
        val global = globalDirectorySkillSource.listSkillsOrNull() ?: return null
        val project = projectDirectorySkillSource.listSkillsOrNull() ?: return null
        return sortEntries(mergeAll(global, project))
    }

    /** 启用的技能列表（注入系统提示词用），禁用技能被过滤。 */
    fun listSkills(): List<Skill> =
        filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }

    /** 同 [listSkills]，但任一来源读取失败时返回 null（读失败不该被当成「没有技能」缓存下来）。 */
    fun listSkillsOrNull(): List<Skill>? =
        listAllSkillsOrNull()?.let { entries ->
            filterDisabled(entries, skillConfigRepository.disabledNames()).map { it.skill }
        }

    private fun sortEntries(merged: List<SkillEntry>): List<SkillEntry> =
        listOrderStore.sort(
            merged.filter { it.scope == SkillScope.GLOBAL },
            ListOrderStore.KEY_SKILLS_GLOBAL
        ) { it.skill.name } + listOrderStore.sort(
            merged.filter { it.scope == SkillScope.PROJECT },
            ListOrderStore.KEY_SKILLS_PROJECT
        ) { it.skill.name }

    /** 当前被禁用的技能名集合（小写归一化）。批量判断取一次即可，避免逐个重读配置文件。 */
    fun disabledNames(): Set<String> = skillConfigRepository.disabledNames()

    /**
     * 按关键词检索技能：先用 [SkillQueryLexicon] 把关键词切成词，再逐条打分，命中任何一个词就能返回。
     *
     * 为什么要检索引擎而不是让模型看全量清单：技能装到几百个时全量清单在预算里只放得下前几条，
     * 剩下的连名字都不出现，模型只能猜——而 [loadInstructions] 按名字全等匹配，猜必落空。
     *
     * 为什么要打分而不是分档：模型给的常是「逆向 分析」这种多词问法，分档只看「命中没命中」会把
     * 只蹭中一个泛词（「分析」）的技能和名称就叫 `reverse-analysis` 的技能混在同一档里。打分让
     * 「名称命中 > 描述命中 > 正文命中」保持原样（同一词的权重依次递减），同时把词命中数与覆盖率算进去：
     * 全词命中比部分命中高，问法表里的同义词只按降权算命中。
     *
     * 等分时按 [listSkills] 的下标升序（即用户拖拽的顺序）：排序必须稳定，否则同一关键词两次检索得到的
     * 「前 N 条」不一样，模型就拿不到上次看到的名字。
     *
     * @param limit 最多返回几条，仅影响 [SkillSearchResult.matches]，[SkillSearchResult.hitCount] 永远是全量
     * @param budgetMillis 本次扫描的时间预算（毫秒）。用完就返回已扫到的结果并在
     *   [SkillSearchResult.truncatedByBudget] 里标记；不要拿它当「没匹配」用。
     */
    fun searchSkills(
        keyword: String,
        limit: Int,
        budgetMillis: Long = DEFAULT_SEARCH_BUDGET_MS
    ): SkillSearchResult {
        val skills = listSkills()
        val allTokens = SkillQueryLexicon.tokenize(keyword)
        if (allTokens.isEmpty() || limit <= 0) return SkillSearchResult(emptyList(), 0, skills.size)

        // 结构词在构建 variants 之前就剔掉，不能只给 0 分：留下来会算进分母，
        // 把「android kotlin pdf」这种查询的覆盖率拉成 1/3，反而把真正命中的 pdf 挡在门槛外。
        val tokens = if (allTokens.size > 1) allTokens.filterNot { it in STRUCTURAL_TERMS } else allTokens
        if (tokens.isEmpty()) return SkillSearchResult(emptyList(), 0, skills.size)

        // 每个词连同它在问法表里的别名一起扫：别名命中算命中，但权重降一档。
        val variants = tokens.map { term -> term to SkillQueryAliases.aliasesOf(term) }

        // 预算从扫描开始计时，不把 listSkills() 的读盘算进去：读盘阶段不可中断，
        // 把它计入只会让「技能装得多」直接变成「每次必然截断」，反而一条结果都拿不到。
        // 计时用 nanoTime + 循环内检查，不起线程也不跑协程超时取消——那样拿不到已扫到的部分。
        val deadline = System.nanoTime() + budgetMillis.coerceAtLeast(0L) * NANOS_PER_MILLI
        val hits = ArrayList<ScoredSkill>(minOf(skills.size, 32))
        var truncatedByBudget = false
        for (index in skills.indices) {
            if (System.nanoTime() >= deadline) {
                truncatedByBudget = true
                break
            }
            val skill = skills[index]
            val score = scoreSkill(skill, variants)
            if (score > 0.0) hits.add(ScoredSkill(score, index, skill))
        }
        hits.sortWith(compareByDescending<ScoredSkill> { it.score }.thenBy { it.index })

        return SkillSearchResult(
            matches = hits.take(limit).map { it.skill },
            hitCount = hits.size,
            skillCount = skills.size,
            truncatedByBudget = truncatedByBudget
        )
    }

    /**
     * 单个技能对一组检索词的得分；0 表示一个词（含别名）都没命中。
     *
     * 同一词在名称/描述/正文里都出现时只取最高权重：正文里再蹭几次也不会让一条弱相关变强，
     * 否则正文长的大技能会恒赢。
     */
    private fun scoreSkill(skill: Skill, variants: List<Pair<String, List<String>>>): Double {
        val name = skill.name.lowercase()
        val description = skill.description.lowercase()

        // 正文不再参与判定：技能正文动辄几千字，蹭到一个泛词就上榜，是「一搜一大片」的主因。
        // 名称与描述本来就是给人（和检索）看的一句话摘要，拿它当唯一依据才准。
        fun weightOf(term: String): Double {
            if (containsWord(name, term)) return WEIGHT_NAME
            if (containsWord(description, term)) return WEIGHT_DESCRIPTION
            return 0.0
        }

        var total = 0.0
        var matched = 0
        for ((term, aliases) in variants) {
            var best = weightOf(term)
            if (best == 0.0) {
                for (alias in aliases) {
                    val aliasWeight = weightOf(alias)
                    if (aliasWeight > best) best = aliasWeight
                }
                best *= ALIAS_FACTOR
            }
            if (best > 0.0) {
                total += best
                matched++
            }
        }
        if (matched == 0) return 0.0

        // 全词命中说明问法很具体；部分命中要过半才算相关——只要一个词蹭到就返回，
        // 结果就是「抛一段英文进去，半仓技能都被列出来」。
        return if (matched == variants.size) {
            total * ALL_TOKENS_FACTOR
        } else {
            val coverage = matched.toDouble() / variants.size
            if (coverage < MIN_PARTIAL_COVERAGE) return 0.0
            total * (PARTIAL_BASE_FACTOR + PARTIAL_COVERAGE_FACTOR * coverage)
        }
    }

    /**
     * 词边界匹配：英文按字母/数字边界判，中文直接子串。
     *
     * 不能一律用 `contains`——`ui` 会命中 `build`、`guide`，`com` 会命中 `command`，
     * 两个字母的词能蹭到半个技能库。也不能一律用正则 `\b`，它对中文不成立。
     */
    private fun containsWord(text: String, term: String): Boolean {
        if (term.isEmpty()) return false
        if (term.any { it.code > 127 }) return text.contains(term)
        var idx = text.indexOf(term)
        while (idx >= 0) {
            val before = if (idx == 0) null else text[idx - 1]
            val after = if (idx + term.length >= text.length) null else text[idx + term.length]
            val okBefore = before == null || !before.isLetterOrDigit()
            val okAfter = after == null || !after.isLetterOrDigit()
            if (okBefore && okAfter) return true
            idx = text.indexOf(term, idx + 1)
        }
        return false
    }

    /** 读取指定 skill 的完整指令正文；不存在 / 解析失败 / 已被禁用时返回 null。 */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        return projectDirectorySkillSource.loadInstructions(name)
            ?: globalDirectorySkillSource.loadInstructions(name)
    }

    /** 在指定作用域启用/禁用某个技能。@return false 表示项目级配置因工作区未就绪被跳过。 */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope): Boolean =
        skillConfigRepository.setDisabled(name, disabled, scope)

    /**
     * 写入技能文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；返回 null 表示成功。
     *
     * 编辑时写回原目录里的原指令文件（可能叫 CLAUDE.md），改名只改 frontmatter 的 name、不动目录名——
     * 技能正文常按 `~/.aicode/skills/<目录>/run.py` 引用同目录脚本，跟着改名会把这些引用打断。
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = listAllSkills()
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.scope == scope && it.skill.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull {
                it.scope == scope && it.skill.name.equals(old, ignoreCase = true)
            }?.skill?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            triggers = form.triggers,
            instructions = form.instructions
        )

        val provider = fileAccess
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            bulk.invalidate()
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存技能失败: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** 指定作用域的技能根目录（容器路径）。 */
    fun skillsRoot(scope: SkillScope): String =
        if (scope == SkillScope.GLOBAL) {
            globalDirectorySkillSource.skillsRoot
        } else {
            projectDirectorySkillSource.skillsRoot
        }

    /**
     * 从 Markdown 文本导入技能到指定作用域；[fallbackName] 为 frontmatter 缺 name 时的兜底名。
     * 名称非法 / 同名冲突 / 正文为空时整体失败，不落盘。
     */
    fun importMarkdown(text: String, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importMarkdown(fileAccess, skillsRoot(scope), existingNamesIn(scope), text, fallbackName)
            .also { bulk.invalidate() }

    /** 从 zip 输入流导入技能（可含多个技能目录）到指定作用域。 */
    fun importZip(input: InputStream, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importArchive(fileAccess, skillsRoot(scope), existingNamesIn(scope), input, fallbackName)
            .also { bulk.invalidate() }

    /** 指定作用域下已有技能名（小写），供导入查重。 */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        listAllSkills().filter { it.scope == scope }.map { it.skill.name.lowercase() }.toSet()

    /** 删除指定作用域的技能（删除其目录，不可恢复）。返回是否成功。 */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        val entry = listAllSkills().firstOrNull {
            it.skill.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val dirPath = entry.skill.dirPath ?: return false
        val deleted = safeDeleteSkillDir(fileAccess, dirPath)
        if (deleted) bulk.invalidate()
        return deleted
    }

    companion object {
        private const val TAG = "SkillRepository"

        /**
         * 检索打分的权重：名称最可信，描述次之，正文最泛（大段正文里蹭到一个词不算强相关）。
         * 具体数值只决定相对关系：名称命中一个词要能压过多词正文命中。
         */
        private const val WEIGHT_NAME = 10.0
        private const val WEIGHT_DESCRIPTION = 6.0

        /** 部分命中至少要过半才认：只蹭到一个词就返回会让一次检索列出几十条无关技能。 */
        private const val MIN_PARTIAL_COVERAGE = 0.5

        /**
         * 代码结构词：只在多词查询里过滤。这些词几乎每段英文 prompt 里都有，
         * 命中它们说明不了任何「这个技能跟任务相关」。单搜时不过滤，用户可能就是想知道 android 相关技能。
         */
        private val STRUCTURAL_TERMS = setOf(
            "app", "src", "main", "java", "kotlin", "android", "compose", "hilt",
            "room", "gradle", "module", "feature", "domain", "presentation",
            "import", "package", "class", "object", "fun", "val", "var",
            "string", "int", "void", "null", "return", "private", "public", "override"
        )

        /** 问法表别名只算「大概率是同一件事」，拿不到原词那么高的分。 */
        private const val ALIAS_FACTOR = 0.6

        /** 全部词都命中时整体加成：问法越具体，结果越可信。 */
        private const val ALL_TOKENS_FACTOR = 1.4

        /** 部分命中时的降权下限与覆盖率系数：命中一半约 0.7 倍，蹭中一个词接近 0.4 倍。 */
        private const val PARTIAL_BASE_FACTOR = 0.4
        private const val PARTIAL_COVERAGE_FACTOR = 0.6

        /**
         * 单次检索的扫描预算（毫秒）。设备上几百个技能的常见耗时是个位数毫秒，过百毫秒就是卡顿；
         * 宁可返回「已扫到的那部分 + 已截断」，也不让一次工具调用把主线程拖住。
         */
        internal const val DEFAULT_SEARCH_BUDGET_MS = 150L

        private const val NANOS_PER_MILLI = 1_000_000L

        /** 打分的中间结果：保留原下标，用作等分时的稳定次级键。 */
        private data class ScoredSkill(val score: Double, val index: Int, val skill: Skill)

        /** 新建技能时写入的指令文件名。 */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** 名称同时用作目录名，禁掉路径分隔符与保留字符；长度上限防止极端目录名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 目录里已有的指令文件完整路径：SKILL.md 优先，其次 CLAUDE.md（与 [SkillParser.parse] 同一套回退）。 */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(global: List<Skill>, project: List<Skill>): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            fun put(skill: Skill, scope: SkillScope) {
                val previous = byName.put(skill.name.lowercase(), SkillEntry(skill, scope))
                // 覆盖是既定语义（项目级优先），但静默覆盖会让一个技能凭空消失：列表里名字与描述
                // 来自 A、正文却取自 B 时几乎查不出来。留一条带两个来源路径的 warn 才定位得到。
                if (previous != null) {
                    FileLogger.w(
                        TAG,
                        "技能重名，${scope.name} 覆盖 ${previous.scope.name}: " +
                            "${previous.skill.name} (${previous.skill.dirPath ?: "无目录"}) <- " +
                            "${skill.name} (${skill.dirPath ?: "无目录"})"
                    )
                }
            }
            global.forEach { put(it, SkillScope.GLOBAL) }
            project.forEach { put(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** 过滤禁用技能（禁用名单已归一化为小写）。 */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** 仅当目录存在且含 SKILL.md/CLAUDE.md 指令文件时才删除，避免误删非技能目录。 */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
