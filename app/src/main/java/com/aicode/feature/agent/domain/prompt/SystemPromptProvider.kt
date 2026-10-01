package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.engine.AgentEngine
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 多数 Source 维护内容缓存，避免重复读取与格式化；静态基线片段除外，每次读盘以保证编辑即时生效。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`，可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（模式提醒、子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，主代理提示词只由自定义数字片段组成，
 * 不再注入任何内置来源；此时用 `{{AICODE_*}}` 变量按需取回动态内容。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val agentEngine: AgentEngine,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // 每次都重新读盘：未编辑时字符串一致，KV Cache 照常命中；编辑后立即生效，无需重启。
        override fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) { return null }
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" } }" }
            val content = "可用技能 (skills)（格式为 名称: 何时使用；相关时用 loadSkill 传入名称取完整正文，详见上文「技能」说明）：\n当清单里有与当前任务对口的技能时，在合适的时机主动 `loadSkill` 加载并按其正文行事，让技能辅助你更规范、更高效地完成工作，而不是仅凭默认流程硬做。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 可用子代理清单（仅注入主代理）：让 AI 知道有哪些自定义 agent 可派发。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = entries.joinToString("\n") { entry ->
                "- ${entry.definition.name}: ${entry.definition.description.ifBlank { "（无描述）" }}"
            }
            val content = "可用子代理 (subagents)（格式为 名称: 何时派发；用 `task(action=\"create\", agent=\"名称\", ...)` 派发）：\n" +
                "这些子代理有各自专属的提示词、模型与工具集，任务与某个 agent 对口时优先按名派发，而不是用默认通用子代理。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            val body = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }
            cached = "项目规则 (来自 ~/workspace/${file.second}，务必遵守):\n${body.trim()}"
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val hasWorkspace = ctx.projectRoot.isNotBlank()
            return "当前上下文:\n- 项目根目录: ${if (hasWorkspace) "~/workspace" else "（未选择工作区）"}"
        }
    }

    private inner class CurrentTimeSource : PromptSource {
        override fun build(ctx: AgentContext): String = "[System] 当前本地时间: ${currentDate()}"
    }

    private inner class EngineFragmentSource : PromptSource {
        /**
         * 引擎聚合片段：由 [AgentEngine] 按模块 order 调度各模块本轮的片段。
         * 记忆清单原本在这里读盘，现已迁进 MemoryModule（连同它的会话级缓存）。
         */
        override fun build(ctx: AgentContext): String? = agentEngine.promptFragment(engineContextOf(ctx))
    }

    /**
     * 子代理固定纪律段（角色行 + 硬规则）：走 [AgentEngine.subAgentRules]，与 [EngineFragmentSource] 分开，
     * 因为它不受 `inject` 门禁——关掉 MEMORY 不代表子代理可以凭记忆写 API。
     * 内容在 `prompts/agent/subagent-rules.md`，可用 `prompts.custom/agent/` 同名覆盖。
     */
    private inner class SubAgentRulesSource : PromptSource {
        override fun build(ctx: AgentContext): String? = agentEngine.subAgentRules(engineContextOf(ctx))
    }

    /** 两处引擎调用共用的上下文快照。 */
    private fun engineContextOf(ctx: AgentContext): EngineContext = EngineContext(
        sessionId = ctx.sessionId,
        projectRoot = ctx.projectRoot,
        mode = ctx.mode,
        history = ctx.history,
        isSubAgent = ctx.agentDefinition != null,
        subAgentName = ctx.agentDefinition?.name
    )

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val engineFragmentSource = EngineFragmentSource()
    private val subAgentRulesSource = SubAgentRulesSource()

    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val currentTimeSource = CurrentTimeSource()

    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memoriesContent = engineFragmentSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)

        // 2. Workspace 上下文固定输出（内容已精简，无需快照占位）
        val effectiveWorkspaceContent = workspaceSource.build(agentContext)
        val timeContent = currentTimeSource.build(agentContext)

        // 3. 变量就地展开：片段里写了 {{AICODE_*}} 就替换为真实内容，并跳过下方对应的自动追加，避免重复。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memoriesContent,
            subAgentsContent,
            projectRules,
            effectiveWorkspaceContent,
            currentDate()
        )

        // 4. 组装最终提示词：把稳定不变的重头基线放最前面（享受 KV Cache），变化部分放末尾
        return buildString {
            append(staticContent)

            if (SKILLS_VAR !in rawStatic) skillsContent?.let { append("\n\n"); append(it) }
            if (SUBAGENTS_VAR !in rawStatic) subAgentsContent?.let { append("\n\n"); append(it) }
            if (MEMORY_VAR !in rawStatic) memoriesContent?.let { append("\n\n"); append(it) }
            if (PROJECT_RULES_VAR !in rawStatic) projectRules?.let { append("\n\n"); append(it) }

            if (WORKSPACE_VAR !in rawStatic) {
                append("\n\n")
                append(effectiveWorkspaceContent)
            }
            if (DATE_VAR !in rawStatic) {
                append("\n\n")
                append(timeContent)
            }
        }
    }

    /**
     * [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 生效时：只输出 `prompts.custom/` 顶层的数字片段，
     * 不注入任何内置来源；动态内容仅通过 `{{AICODE_*}}` 变量按需取回。
     */
    private fun buildCustomOnly(ctx: AgentContext): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，系统提示词为空"
            )
            return ""
        }
        return renderVariables(
            content,
            activeSkillsSource.build(ctx),
            engineFragmentSource.build(ctx),
            subAgentListSource.build(ctx),
            projectRuleSource.build(ctx),
            workspaceSource.build(ctx),
            currentDate()
        )
    }

    /**
     * 按子代理定义组装提示词：先注入 [AgentDefinition.inject] 列出的片段，再接固定纪律段，
     * 最后接 agent 自己的提示词（任务相关指令放最后，紧邻对话，位置更有效）。
     * 不注入可用子代理清单（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开，
     * 且展开过的片段不再按 [AgentDefinition.inject] 追加一次（与主代理 build() 同款守卫）。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        // 固定纪律段：不受 inject 门禁，且不靠派发的人每次记得写。
        subAgentRulesSource.build(agentContext)?.let {
            append(it)
            append("\n\n")
        }

        // 守卫判据是**展开前**的正文：正文里自己写了哪个占位符，就说明注入点由作者指定，
        // 下方不再按 inject 追加同一段内容。判据必须取 rawPrompt——展开后的文本里占位符已消失，
        // 用它判会永远为真。（主代理 build() 用 rawStatic 判的是同一件事。）
        val rawPrompt = definition.prompt
        append(
            renderVariables(
                rawPrompt,
                activeSkillsSource.build(agentContext),
                engineFragmentSource.build(agentContext),
                subAgentListSource.build(agentContext),
                projectRuleSource.build(agentContext),
                workspaceSource.build(agentContext),
                currentDate()
            )
        )

        if (InjectPart.SKILLS in definition.inject && SKILLS_VAR !in rawPrompt) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject && MEMORY_VAR !in rawPrompt) {
            engineFragmentSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject && PROJECT_RULES_VAR !in rawPrompt) {
            projectRuleSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }

        // 工作区上下文与主代理同款守卫：正文里写了 {{AICODE_WORKSPACE}} 就已在正文位置展开过，
        // 这里再无条件追加一遍是逐字重复（WorkspaceSource.build 只依赖 projectRoot，内容完全一致）。
        if (WORKSPACE_VAR !in rawPrompt) {
            append("\n\n")
            append(workspaceSource.build(agentContext))
        }
        append("\n\n")
        append(currentTimeSource.build(agentContext))
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memories: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        date: String
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_VAR, memories.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRules.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(DATE_VAR, date)
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_VAR = "{{AICODE_MEMORY}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
    }
}