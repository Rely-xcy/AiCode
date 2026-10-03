package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.ChangeDomain
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.touches
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * 技能启停配置持久化，支持全局 + 项目级两级：
 * - 全局：当前执行环境的 `~/.aicode/skills.json`（跨项目保留）；
 * - 项目级：`workspacePath/.aicode/skills.json`（随工作区走，可 git 追踪）。
 *
 * 格式：`{"disabled": ["skill-a", "skill-b"]}`，仅存「禁用名单」这一个事实；
 * 生效禁用集合 = 全局 + 项目并集。每次读取都从磁盘加载，外部手工编辑即时生效；
 * 名单中不存在的技能名在过滤时天然被忽略，无需清理。
 */
@Singleton
class SkillConfigRepository @Inject constructor(
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/skills.json`；工作区未落定时为 null。 */
    private fun projectFile(): File? = projectAicodeRoot.currentOrNull()?.let { File(it, CONFIG_FILE) }

    /** 当前生效的禁用技能名集合（全局 + 项目并集，归一化为小写）；工作区未落定时只有全局层。 */
    fun disabledNames(): Set<String> {
        val global = readGlobalDisabled()
        val project = projectFile()?.let { readDisabled(it) } ?: emptySet()
        return (global + project).map { it.lowercase() }.toSet()
    }

    /**
     * 在指定作用域的配置中启用/禁用某个技能；工作区未落定时项目级写入被忽略，不落到全局层。
     *
     * @return 是否真的写入了：false 表示项目级配置因工作区未落定被跳过，调用方必须提示用户，
     *   否则开关会自己弹回、用户不知道发生了什么。
     */
    fun setDisabled(name: String, disabled: Boolean, scope: SkillScope): Boolean {
        if (scope == SkillScope.GLOBAL) {
            val names = readGlobalDisabled().toMutableSet()
            if (disabled) names.add(name) else names.remove(name)
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, serializeDisabled(names))
            return true
        }
        val file = projectFile() ?: run {
            FileLogger.w(TAG, "工作区未就绪，忽略项目级技能配置写入：$name")
            return false
        }
        val names = readDisabled(file).toMutableSet()
        if (disabled) names.add(name) else names.remove(name)
        writeDisabled(file, names)
        return true
    }

    /**
     * 备份导出用：该作用域配置文件的原文；文件不存在、读取失败或工作区未落定时为 null。
     *
     * 与 [disabledNames] 读的是同一批文件，区别在于这里要的是原文：恢复时整份写回，不重新序列化，
     * 文件里的未知字段与原始排版都保留。
     */
    fun rawConfig(scope: SkillScope): String? =
        if (scope == SkillScope.GLOBAL) readGlobalRaw()
        else projectFile()?.let { readRaw(it) }

    /**
     * 备份恢复用：整份覆盖该作用域的配置文件——备份里的名单为准，本机多出来的禁用项不删。
     *
     * @return false 表示项目级配置因工作区未落定被跳过（工作区未落定时 [projectFile] 为 null），
     *   调用方不得把它算进导入摘要。
     */
    fun restoreConfig(raw: String, scope: SkillScope): Boolean {
        if (scope == SkillScope.GLOBAL) {
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, raw)
            return true
        }
        val file = projectFile() ?: run {
            FileLogger.w(TAG, "工作区未就绪，忽略项目级技能配置写入")
            return false
        }
        writeRaw(file, raw)
        return true
    }

    private fun readGlobalDisabled(): Set<String> = readGlobalRaw()?.let { parseDisabled(it) } ?: emptySet()

    /** 全局配置文件原文；不存在或读取失败时为 null。 */
    private fun readGlobalRaw(): String? = runCatching {
        if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) fileAccess.readFile(GLOBAL_CONFIG_PATH) else null
    }.getOrElse {
        FileLogger.w(TAG, "读取 $CONFIG_FILE 失败: ${it.message}")
        null
    }

    // ── 外部变更监听：容器内/手工直接增删改技能目录或 skills.json 后，数秒内通知 UI 刷新 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 技能目录或配置文件被外部修改时广播一次。订阅驱动：只在有订阅者（设置页）期间才由
     * [FileChangeHub] 监听技能目录与两个 skills.json，无人订阅时零开销。
     *
     * 判据是「变更域」而不是文件路径：本地模式下技能是宿主路径、远端模式下是服务器路径（全局技能与
     * 全局 skills.json 都在服务器上），路径形态对不上的话远端永远不刷新。
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(SKILLS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace(
            "${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR/$SKILLS_DIR",
            recursive = true,
            domain = ChangeDomain.AICODE_CONFIG
        ),
        fileChangeHub.watchWorkspace(
            "${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR",
            domain = ChangeDomain.AICODE_CONFIG
        )
    ).mapNotNull { batch ->
        if (!batch.touches(ChangeDomain.AICODE_CONFIG)) return@mapNotNull null
        FileLogger.i(TAG, "检测到技能目录或配置变化，已通知刷新")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    companion object {
        private const val TAG = "SkillConfigRepository"
        private const val CONFIG_FILE = "skills.json"
        private const val GLOBAL_CONFIG_PATH = "~/.aicode/$CONFIG_FILE"
        private const val AICODE_DIR = ".aicode"
        private const val SKILLS_DIR = "skills"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "技能配置 JSON 解析失败: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        /** 配置文件原文；文件不存在或读取失败时返回 null。 */
        fun readRaw(file: File): String? {
            if (!file.isFile) return null
            return runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败: ${it.message}")
                null
            }
        }

        fun readDisabled(file: File): Set<String> = readRaw(file)?.let { parseDisabled(it) } ?: emptySet()

        /** 整份写入配置原文（备份恢复）；临时文件 + rename 原子落盘，避免写一半崩溃损坏配置。 */
        internal fun writeRaw(file: File, raw: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(file)) {
                // rename 失败（罕见），回退直接写，避免丢配置
                file.writeText(raw)
            }
        }

        fun writeDisabled(file: File, names: Set<String>) = writeRaw(file, serializeDisabled(names))
    }
}
