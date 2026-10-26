package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏模块：把「在不可见屏上自动化测试 app」接进引擎。
 *
 * 只留下一个口子：[promptFragment]——主代理的虚拟屏使用纪律（什么时候开屏、怎么收尾、边界在哪），
 * 内容在 `prompts/agent/virtual-screen.md`，可用 `prompts.custom/agent/virtual-screen.md` 覆盖。
 *
 * 工具不走 `tools()` 口子：vscreen_* 已注册进 ToolRegistry（进统一权限策略/授权弹窗/「始终允许」规则）。
 * 子代理不注入纪律，且 vscreen_* 工具会在子代理会话被过滤掉（授权与收尾需要单一责任方）。
 *
 * [temp] root 分支实验件，不进主线。
 */
@Singleton
class VirtualScreenModule @Inject constructor(
    private val systemPromptProvider: Lazy<SystemPromptProvider>
) : EngineModule {

    override val id = MODULE_ID

    /** 排在上下文类模块之后：虚拟屏是可选能力，不参与压缩/记忆的排序语义。 */
    override val order = 60

    @Volatile
    private var strategyCache: String? = null

    override fun promptFragment(ctx: EngineContext): String? {
        if (ctx.isSubAgent) return null
        return strategy()
    }

    // 工具不走本模块的 tools() 口子：它们已注册进 ToolRegistry（见 AgentModule.provideToolRegistry），
    // 这样才进统一的权限策略体系、授权弹窗与「始终允许」规则（设置 → 工具授权）。
    // 本模块只负责注入使用纪律。

    private fun strategy(): String? {
        strategyCache?.let { return it.ifEmpty { null } }
        val cleaned = readPrompt(STRATEGY_FILE) ?: return null
        strategyCache = cleaned
        return cleaned.ifEmpty { null }
    }

    private fun readPrompt(name: String): String? = try {
        systemPromptProvider.get().resolvePrompt(name).replace(LEADING_COMMENT, "").trim()
    } catch (e: Exception) {
        FileLogger.e(TAG, "读取虚拟屏提示词失败: $name", e)
        null
    }

    private companion object {
        const val MODULE_ID = "vscreen"
        const val TAG = "VirtualScreenModule"
        const val STRATEGY_FILE = "agent/virtual-screen.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
