package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.vscreen.VsInputTool
import com.aicode.feature.agent.domain.tool.vscreen.VsObserveTool
import com.aicode.feature.agent.domain.tool.vscreen.VsStartTool
import com.aicode.feature.agent.domain.tool.vscreen.VsStopTool
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏模块：把「在不可见屏上自动化测试 app」接进引擎。
 *
 * 两个口子：
 * 1. [promptFragment]——主代理的虚拟屏使用纪律（什么时候开屏、怎么收尾、边界在哪），
 *    内容在 `prompts/agent/virtual-screen.md`，可用 `prompts.custom/agent/virtual-screen.md` 覆盖；
 * 2. [tools]——本轮额外提供 vscreen_start / vscreen_stop / vscreen_input / vscreen_observe 四个工具。
 *    工具经引擎聚合进主会话工具集（与 `team` 工具同一机制），不必注册进 ToolRegistry。
 *
 * 只给主代理：子代理不操控屏幕（授权与收尾都需要单一责任方）。
 *
 * [temp] root 分支实验件，不进主线。
 */
@Singleton
class VirtualScreenModule @Inject constructor(
    private val vsStartTool: VsStartTool,
    private val vsStopTool: VsStopTool,
    private val vsInputTool: VsInputTool,
    private val vsObserveTool: VsObserveTool,
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

    override fun tools(ctx: EngineContext): List<AgentTool> =
        if (ctx.isSubAgent) {
            emptyList()
        } else {
            listOf(vsStartTool, vsStopTool, vsInputTool, vsObserveTool)
        }

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
