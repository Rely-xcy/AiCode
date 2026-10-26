package com.aicode.feature.agent.domain.tool.vscreen

/**
 * 虚拟屏工具的统一前缀。
 *
 * 这些工具注册进 [com.aicode.feature.agent.domain.tool.ToolRegistry]（走统一权限体系），
 * 但**只在主代理会话可见**：屏的授权与收尾需要单一责任方，子代理会话按此前缀整组过滤
 * （见 AIAgentViewModel 的工具筛选）。
 */
const val VSCREEN_TOOL_PREFIX = "vscreen_"
