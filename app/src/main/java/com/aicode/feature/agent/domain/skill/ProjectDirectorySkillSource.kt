package com.aicode.feature.agent.domain.skill

import com.aicode.feature.agent.domain.container.BulkFileSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级技能来源：`<projectRoot>/.aicode/skills/`，随工作区走，可 git 追踪。
 * 以容器路径经 [FileAccessProvider] 访问：本地映射到宿主工作区，远程经 SSH 落到远程工作区。
 */
@Singleton
class ProjectDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val bulk: BulkFileSource
) : SkillSource {

    /** 不启用远程批量读取的构造（单测与本地调用方）。 */
    internal constructor(fileAccess: FileAccessProvider) : this(fileAccess, BulkFileSource.Disabled)

    val skillsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot, bulk)

    override fun listSkillsOrNull(): List<Skill>? = SkillDirectoryScanner.scanOrNull(fileAccess, skillsRoot, bulk)

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}
