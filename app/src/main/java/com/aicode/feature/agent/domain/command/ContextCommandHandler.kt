package com.aicode.feature.agent.domain.command

import com.aicode.R
import javax.inject.Inject

/**
 * /context —— 调试用：输出当前会话的上下文状态（估算 token、软/硬/兜底三条阈值线）。
 * 结果以 Markdown 表格作为 AI 气泡输出，与 /usage 同一条显示路径。
 */
class ContextCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "context"
    override val descriptionRes = R.string.slash_command_context_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showContextInfo()
    }
}