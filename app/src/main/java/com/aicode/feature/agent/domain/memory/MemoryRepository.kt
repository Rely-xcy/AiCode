package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /**
     * 记忆被写入 / 编辑 / 删除时发信号。
     *
     * 注入方（MemoryModule）按会话缓存注入内容以保持 system prompt 稳定，
     * 若不知道内容变了，新记忆要等到换会话才生效——主动记忆（模型调工具写）就白写了。
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。kind 非空时只返回该类型。 */
    fun listMemories(projectRoot: String?, kind: MemoryKind? = null): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）
        val deduped = allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
        return if (kind == null) deduped else deduped.filter { it.kind == kind }
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        kind: MemoryKind = MemoryKind.NOTE,
        source: String = "",
        createdAt: Long = 0L
    ): Boolean {
        val saved = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content, kind, source, createdAt)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content, kind, source, createdAt)
            }
        }
        if (saved) notifyChanged()
        return saved
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        val result = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
        if (result is MemoryEditResult.Success) notifyChanged()
        return result
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        val deleted = when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
        if (deleted) notifyChanged()
        return deleted
    }
}
