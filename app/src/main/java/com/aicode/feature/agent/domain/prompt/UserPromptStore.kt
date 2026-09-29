package com.aicode.feature.agent.domain.prompt

import com.aicode.core.datastore.ListOrderStore
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自定义提示词的存取。
 *
 * 落盘位置：
 * - 全局：`<aicodeDir>/prompts.custom/user/<id>.md`
 * - 项目：`<项目配置目录>/prompts.custom/user/<id>.md`（目录由 [ProjectAicodeRoot] 解析，
 *   本地/远程两种执行模式都已覆盖，不用自己判断）
 *
 * 文件格式与记忆文件同款：YAML frontmatter（name / position）+ 正文。
 */
@Singleton
class UserPromptStore @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    /** 用户拖拽排过的顺序表；单元测试直接构造本类时不需要（为空即按创建顺序）。 */
    private val listOrderStore: ListOrderStore? = null
) {

    /** 新建一条：id 由这里生成（时间戳 + 随机后缀，保证文件名不冲突）。 */
    fun newPrompt(
        name: String,
        position: UserPromptPosition,
        content: String
    ): UserPrompt = UserPrompt(
        id = "p" + System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(4),
        name = name,
        position = position,
        content = content
    )

    /**
     * 列出某作用域下的用户提示词：用户拖拽排过的顺序优先，没排过的按创建顺序
     * （文件名内嵌时间戳，字典序即创建序）。注入顺序也走这里，所以拖拽调整的顺序会直接反映到注入顺序。
     */
    fun list(scope: UserPromptScope, projectRoot: String?): List<UserPrompt> {
        val dir = userDir(scope, projectRoot) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val prompts = (dir.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList())
            .sortedBy { it.name }
            .mapNotNull { parse(it) }
        return listOrderStore?.sort(prompts, orderKey(scope)) { it.id } ?: prompts
    }

    private fun orderKey(scope: UserPromptScope): String = when (scope) {
        UserPromptScope.GLOBAL -> ListOrderStore.KEY_PROMPTS_GLOBAL
        UserPromptScope.PROJECT -> ListOrderStore.KEY_PROMPTS_PROJECT
    }

    fun save(scope: UserPromptScope, projectRoot: String?, prompt: UserPrompt): Boolean {
        val dir = userDir(scope, projectRoot) ?: return false
        return try {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "${sanitizeId(prompt.id)}.md").writeText(format(prompt))
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存用户提示词失败: ${prompt.id}", e)
            false
        }
    }

    fun delete(scope: UserPromptScope, projectRoot: String?, id: String): Boolean {
        val dir = userDir(scope, projectRoot) ?: return false
        val file = File(dir, "${sanitizeId(id)}.md")
        return file.isFile && file.delete()
    }

    private fun userDir(scope: UserPromptScope, projectRoot: String?): File? = when (scope) {
        UserPromptScope.GLOBAL -> File(File(containerInstaller.aicodeDir, CUSTOM_DIR), USER_DIR)
        UserPromptScope.PROJECT ->
            projectRoot?.takeIf { it.isNotBlank() }
                ?.let { File(File(projectAicodeRoot.forPath(it), CUSTOM_DIR), USER_DIR) }
    }

    private fun parse(file: File): UserPrompt? {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取用户提示词失败: ${file.name}", e)
            return null
        }
        val (frontmatter, body) = splitFrontmatter(text)
        return UserPrompt(
            id = file.nameWithoutExtension,
            name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            position = UserPromptPosition.fromStorage(frontmatter["position"]?.toString()),
            content = body.trim()
        )
    }

    private fun format(prompt: UserPrompt): String =
        "---\nname: ${yamlScalar(prompt.name)}\nposition: ${prompt.position.toStorage()}\n---\n${prompt.content}"

    private fun splitFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized
        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")
        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析用户提示词 frontmatter 失败", e)
            emptyMap()
        }
        return map to rest
    }

    /** 只保留文件名安全字符，防止 id 里出现路径分隔符等。 */
    private fun sanitizeId(id: String): String =
        id.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "prompt" }

    private fun yamlScalar(value: String): String {
        val needsQuote = value.contains(':') || value.contains('#') ||
            value.contains('"') || value.contains('\'') ||
            value.startsWith('-') || value.startsWith(' ') || value.endsWith(' ') ||
            value.contains('\n') || value.isBlank()
        return if (needsQuote) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
    }

    private companion object {
        const val TAG = "UserPromptStore"
        const val CUSTOM_DIR = "prompts.custom"
        const val USER_DIR = "user"
    }
}
