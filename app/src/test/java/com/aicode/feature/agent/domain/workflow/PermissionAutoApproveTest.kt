package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「工具自己声明的自动批准能否跳过这次 ASK」的判定（[autoApproveSkipsAsk]）。
 *
 * 关键一条：项目级规则读不到的兜底 ASK 不能被短路——那时合并结果里可能缺一条项目级 DENY，
 * 放行等于把项目级规则整体旁路，而未覆写 permissionPolicy 的工具（browser 的写动作、memory 的
 * 写动作）默认就是 AUTO_APPROVE。AUTO 模式下该标记恒为 false，放行照旧。
 *
 * 注：本用例只覆盖 workflow 侧的判定。引擎侧「什么时候产出 rulesUnconfirmed=true」以及
 * remember 的落库契约，测试属 `domain/permission/` 包，见同批交付里列出的待补清单。
 */
class PermissionAutoApproveTest {

    private fun ask(rulesUnconfirmed: Boolean = false) = ToolPermissionPolicyEngine.EvalResult(
        verdict = ToolPermissionPolicyEngine.Verdict.ASK,
        rememberablePatterns = emptyList(),
        rulesUnconfirmed = rulesUnconfirmed
    )

    @Test
    fun `自动批准对普通 ASK 仍然短路`() {
        assertTrue(autoApproveSkipsAsk(ToolPermissionPolicy.AUTO_APPROVE, ask()))
    }

    @Test
    fun `自动批准不得短路项目级规则读不到的 ASK`() {
        assertFalse(autoApproveSkipsAsk(ToolPermissionPolicy.AUTO_APPROVE, ask(rulesUnconfirmed = true)))
    }

    @Test
    fun `声明询问的工具从不短路`() {
        assertFalse(autoApproveSkipsAsk(ToolPermissionPolicy.ASK, ask()))
        assertFalse(autoApproveSkipsAsk(ToolPermissionPolicy.ASK, ask(rulesUnconfirmed = true)))
    }

    @Test
    fun `规则未确认标记默认为 false`() {
        val result = ToolPermissionPolicyEngine.EvalResult(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            emptyList()
        )
        assertFalse(result.rulesUnconfirmed)
    }
}
