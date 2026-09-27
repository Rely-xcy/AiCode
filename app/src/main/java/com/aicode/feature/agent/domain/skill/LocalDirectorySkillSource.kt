package com.aicode.feature.agent.domain.skill

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局技能来源，随执行模式切换读写位置：
 *
 * - 本地模式：手机私有目录 `filesDir/aicode/skills`（容器内 `/root/.aicode/skills`），跨项目、跨升级保留；
 * - 远程模式：服务器 home 下的 `~/.aicode/skills`（AI 在远程模式写全局技能就落在这里）。
 *
 * 这样「设置 → 技能 → 全局」显示的就是当前模式下 AI 真正读写的那个目录，不再出现
 * "AI 装了全局技能但页面看不到"的错位。
 */
@Singleton
class LocalDirectorySkillSource @Inject constructor(
    private val localFileAccess: LocalFileAccess,
    private val fileAccess: FileAccessProvider,
    private val executionModeHolder: ExecutionModeHolder
) : SkillSource {

    private val remote: Boolean
        get() = executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    /** 当前模式下的全局技能根（容器路径视角）。 */
    val skillsRoot: String
        get() = if (remote) REMOTE_ROOT else LOCAL_ROOT

    /** 当前模式下访问全局技能根的文件访问器。 */
    fun provider(): FileAccessProvider = if (remote) fileAccess else localFileAccess

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(provider(), skillsRoot)

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions

    private companion object {
        /** 本地：容器视角的 App 私有目录。 */
        val LOCAL_ROOT = "${WorkspacePathMapper.AICODE_ROOT}/skills"

        /** 远程：服务器 home 下的全局技能目录（经 provider 展开 ~ 为 remoteHome）。 */
        const val REMOTE_ROOT = "~/.aicode/skills"
    }
}
