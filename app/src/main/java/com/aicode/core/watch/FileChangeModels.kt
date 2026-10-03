package com.aicode.core.watch

import com.aicode.core.util.GitIgnoreMatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 变更所属的监听根：工作区、AI 配置目录（`~/.aicode`），或订阅方指定的其它宿主目录。 */
enum class ChangeRoot { WORKSPACE, AICODE, OTHER }

/** 单条变更的类型。同一路径在一个批量窗口内多次变更会被合并为一条。 */
enum class ChangeKind { CREATED, MODIFIED, DELETED }

/**
 * 变更所属的「域」：决定谁该关心它，**与路径形态无关**。
 *
 * 存在的理由：同一个配置文件在本地的宿主路径与远端服务器上的路径长得完全不同，消费方按文件路径比对
 * 命不中（远端模式下「改了配置不重载」）。改按域标注后，本地/远端两条路由对同一份配置产出同一个域，
 * 消费方只需说「我要 [AICODE_CONFIG]」，不必知道它长什么样。
 *
 * 域由**订阅走的是哪条路**直接标注（见 [FileChangeHub.watchAicode] / [FileChangeHub.watchWorkspace]），
 * 不解析事件里的路径字符串。
 */
enum class ChangeDomain {
    /** AI 配置：`~/.aicode`（本地私有配置目录 / 远端 home 下的同名目录）与项目级 `.aicode` 配置、技能、子代理目录。 */
    AICODE_CONFIG,

    /** 工作区里的普通文件：文件树、工作区扫描这类消费方关心它。 */
    WORKSPACE_FILE,

    /** 其它订阅（如同步引擎的本地镜像目录）；也是未显式标注时的兜底。 */
    OTHER
}

/**
 * 一条文件变更。[containerPath] 是容器视角路径（AI / 终端看到的），供消费方直接使用；
 * 按宿主路径匹配的消费方（如同步引擎）用 [hostPath]。
 *
 * [domain] 是该变更来自哪条监听路由，见 [ChangeDomain]；默认 [ChangeDomain.OTHER]（未标注），
 * 于是新增路由忘了标注时不会误报成别的域。
 */
data class FileChange(
    val root: ChangeRoot,
    val hostPath: String,
    val containerPath: String,
    val kind: ChangeKind,
    val domain: ChangeDomain = ChangeDomain.OTHER
)

/**
 * 一个批量窗口内收集到的变更。[changes] 已按路径去重（同路径只出现一次，kind 已合并）；
 * [truncated] 为 true 表示明细超过窗口上限被截断，仅保证「该范围内有变更」这一信号。
 */
data class FileChangeBatch(
    val changes: List<FileChange>,
    val truncated: Boolean = false
)

/**
 * 这一批里是否有属于 [domain] 的变更。消费方按域认领（如配置类消费方只看 [ChangeDomain.AICODE_CONFIG]），
 * 不再拿文件路径去比——远端模式下事件带的是服务器路径，路径比对必然落空。
 */
fun FileChangeBatch.touches(domain: ChangeDomain): Boolean = changes.any { it.domain == domain }

/**
 * 订阅侧过滤规则：只影响「递归向下时是否进入某目录」与「该目录内事件是否上报」。
 * 判定只针对父路径段、不含最后一段——被剪枝目录自身的增删仍会上报，父目录列表才看得到它。
 *
 * 刻意不含任何内置忽略名单：剪枝依据完全来自订阅方（[ignoredNames] / [extraPatterns]）
 * 与订阅根的 .gitignore（[followGitignore]）。
 */
data class WatchFilter(
    val ignoredNames: Set<String> = emptySet(),
    val extraPatterns: List<String> = emptyList(),
    val followGitignore: Boolean = true
) {
    companion object {
        val DEFAULT = WatchFilter()
    }
}

/** 目录剪枝判定（纯逻辑，便于单测）。 */
class IgnoreRules internal constructor(
    private val ignoredNames: Set<String>,
    private val patterns: List<String>
) {
    /** [relativeParts] 为相对订阅根的路径段（调用方只传父段，不含最后一段）。 */
    fun isIgnoredDir(relativeParts: List<String>): Boolean {
        if (relativeParts.isEmpty()) return false
        if (ignoredNames.isNotEmpty() && relativeParts.any { it in ignoredNames }) return true
        return patterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(patterns, relativeParts)
    }

    companion object {
        fun of(filter: WatchFilter, gitignorePatterns: List<String>): IgnoreRules =
            IgnoreRules(filter.ignoredNames, gitignorePatterns + filter.extraPatterns)
    }
}

/** 仅需「有变更」这一信号的 UI 订阅用；批量窗口已把同一目录的密集变更合并为一次。 */
fun Flow<FileChangeBatch>.asDirtySignal(): Flow<Unit> = map { }

/** 把宿主绝对路径转成相对 [root] 的路径段。 */
internal fun relativePartsOf(root: File, path: File): List<String> {
    val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    val abs = path.absolutePath
    val rel = if (abs == rootPath) "" else abs.removePrefix("$rootPath/")
    return rel.split(File.separatorChar, '/').filter { it.isNotEmpty() }
}

/** 读取订阅根的 .gitignore（去空行与注释、去尾斜杠）；不存在或不可读返回空。 */
internal fun readGitignorePatterns(root: File): List<String> {
    val file = File(root, ".gitignore")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removeSuffix("/") }
    }.getOrDefault(emptyList())
}

/**
 * 目录单层快照：子项名 → 标记。文件记 mtime+size，目录只记类型（目录自身的 mtime 会随其内部
 * 增删而变，纳入比对只会与子目录自己的事件重复上报）。
 */
internal fun snapshotDir(dir: File): Map<String, String> {
    val children = dir.listFiles() ?: return emptyMap()
    val map = HashMap<String, String>(children.size)
    for (child in children) {
        map[child.name] =
            if (child.isDirectory) "d" else "f:${child.lastModified()}:${child.length()}"
    }
    return map
}

/** 对比前后快照，产出子项名 → 变更类型。 */
internal fun diffSnapshot(
    prev: Map<String, String>,
    now: Map<String, String>
): List<Pair<String, ChangeKind>> {
    if (prev.isEmpty() && now.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, ChangeKind>>()
    for ((name, value) in now) {
        val old = prev[name]
        when {
            old == null -> out += name to ChangeKind.CREATED
            old != value -> out += name to ChangeKind.MODIFIED
        }
    }
    for (name in prev.keys) {
        if (name !in now) out += name to ChangeKind.DELETED
    }
    return out
}

/** 同一路径在一个窗口内先后发生多次变更时的合并规则。 */
internal fun mergeKind(prev: ChangeKind, next: ChangeKind): ChangeKind = when {
    prev == next -> prev
    (prev == ChangeKind.CREATED && next == ChangeKind.DELETED) ||
        (prev == ChangeKind.DELETED && next == ChangeKind.CREATED) -> ChangeKind.MODIFIED
    else -> next
}