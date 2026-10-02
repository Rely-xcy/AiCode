package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.effectiveArguments
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import io.mockk.mockk
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextBudgetPolicyTest {

    @Test
    fun `窗口档位按窗口大小分四档`() {
        assertEquals(ModelContextPolicy.Tier.DISABLED, ModelContextPolicy.tierFor(16_000).tier)
        assertEquals(ModelContextPolicy.Tier.SOFT_ONLY, ModelContextPolicy.tierFor(32_000).tier)
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(64_000).tier)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, ModelContextPolicy.tierFor(128_000).tier)
    }

    @Test
    fun `小窗口不启用硬压缩`() {
        assertEquals(0, ModelContextPolicy.tierFor(16_000).hardThreshold)
        assertEquals(0, ModelContextPolicy.tierFor(63_000).hardThreshold)
    }

    @Test
    fun `硬上限按档位留出 headroom`() {
        assertEquals(54_000, ModelContextPolicy.tierFor(64_000).hardThreshold)
        assertEquals(108_000, ModelContextPolicy.tierFor(128_000).hardThreshold)
    }

    @Test
    fun `有效硬线取百分比与档位上限的较小值`() {
        val window = 128_000
        val tier = ModelContextPolicy.tierFor(window)
        // 85% × 128k = 108_800 > 档位上限 108_000 → 取上限
        assertEquals(108_000, minOf(window * 85 / 100, tier.hardThreshold))
        // 50% × 128k = 64_000 < 上限 → 取百分比
        assertEquals(64_000, minOf(window * 50 / 100, tier.hardThreshold))
    }

    @Test
    fun `生效阈值与判定同源：压缩模块与界面取同一份`() {
        // 128K 走 GENEROUS：档位上限 108K 把 85% 的 108.8K 压下来
        val generous = ModelContextPolicy.thresholds(128_000, softPercent = 40, hardPercent = 85)
        assertEquals(128_000, generous.contextLimit)
        assertEquals(108_000, generous.hard)
        assertEquals(51_200, generous.soft)
        assertTrue(generous.hardEnabled)

        // 1M 窗口：85% = 850K，没碰上档位上限 980K
        val million = ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 85)
        assertEquals(850_000, million.hard)
        assertEquals(400_000, million.soft)

        // 64K 走 STANDARD：上限 54K 把 54.4K 压下来
        assertEquals(54_000, ModelContextPolicy.thresholds(64_000, softPercent = 40, hardPercent = 85).hard)
    }

    @Test
    fun `用户把硬线设为 100% 也会被档位上限封顶`() {
        // 1M 窗口仍只到 980K（给输出与提示词留 headroom）
        assertEquals(980_000, ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 100).hard)
    }

    @Test
    fun `软线始终低于硬线`() {
        // 软线也填到 100%：被封在硬线之下，不会抢在硬压缩前面触发
        val thresholds = ModelContextPolicy.thresholds(128_000, softPercent = 100, hardPercent = 85)
        assertEquals(107_999, thresholds.soft)
        assertTrue(thresholds.soft < thresholds.hard)
    }

    @Test
    fun `小窗口不启用硬压缩，软线按窗口 90% 封顶`() {
        val softOnly = ModelContextPolicy.thresholds(32_000, softPercent = 40, hardPercent = 85)
        assertFalse(softOnly.hardEnabled)
        assertEquals(0, softOnly.hard)
        assertEquals(12_800, softOnly.soft)
        // 百分比填得再大也不超过窗口 90%
        assertEquals(28_800, ModelContextPolicy.thresholds(32_000, softPercent = 100, hardPercent = 85).soft)

        val disabled = ModelContextPolicy.thresholds(16_000, softPercent = 40, hardPercent = 85)
        assertFalse(disabled.hardEnabled)
        assertEquals(6_400, disabled.soft)
    }

    @Test
    fun `兜底线固定为窗口的 92%`() {
        assertEquals(92, ModelContextPolicy.GUARD_BUDGET_PERCENT)
        assertEquals(92, ModelContextPolicy.thresholds(128_000, softPercent = 40, hardPercent = 85).guardPercent)
    }

    @Test
    fun `设置页显示的三个数取自同一份策略`() {
        // 「实际触发线 850,000（窗口 1,000,000，档位上限 980,000）」：三个数都由这里算出，不另算一套
        val window = 1_000_000
        val thresholds = ModelContextPolicy.thresholds(window, softPercent = 40, hardPercent = 85)

        assertEquals(850_000, thresholds.hard)
        assertEquals(1_000_000, thresholds.contextLimit)
        assertEquals(980_000, ModelContextPolicy.tierFor(window).hardThreshold)
        assertEquals(400_000, thresholds.soft)
    }

    @Test
    fun `保留最近原文按窗口四分之一且上下限收敛`() {
        assertEquals(32_000, ModelContextPolicy.preserveRecentTokens(128_000))
        // 1M 窗口：25% 是 250k，收敛到上限 60k
        assertEquals(60_000, ModelContextPolicy.preserveRecentTokens(1_000_000))
        // 小窗口：25% 低于下限，收敛到 2k
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(4_000))
    }

    @Test
    fun `软精简在预算内时原样返回`() {
        val messages = listOf(tool("a".repeat(400)))
        val result = compactor().softTrim(messages, targetTokens = 10_000)
        assertSame(messages, result)
    }

    @Test
    fun `软精简只裁到够用就停`() {
        // 两条各 40k 字符（约 10k token），目标是裁掉一条多一点就能落回
        val first = tool("a".repeat(40_000))
        val second = tool("b".repeat(40_000))
        val messages = listOf(first, second)

        val result = compactor().softTrim(messages, targetTokens = 12_000)

        // 从最长的开始裁：第一条被裁短，第二条保持原对象不动
        assertNotNull((result[0] as AgentMessage.ToolResultMessage).modelResult)
        assertSame(second, result[1])
    }

    @Test
    fun `软精简不碰最近三轮的工具内容`() {
        // 历史（三轮以前）：一个大工具输出，应当被精简
        val history = tool("a".repeat(40_000))
        // 最近三轮：每轮「用户消息 + 大工具输出」，都不该被动
        val recent = (1..3).flatMap { round ->
            listOf(
                AgentMessage.UserMessage(content = "第 $round 轮"),
                tool("b".repeat(40_000))
            )
        }
        val messages = listOf(history) + recent

        val result = compactor().softTrim(messages, targetTokens = 1)

        // 只有历史那条被精简，最近三轮的六条一律原对象不动
        assertNotNull((result[0] as AgentMessage.ToolResultMessage).modelResult)
        for (index in 1 until result.size) {
            assertSame(messages[index], result[index], "最近三轮里的第 $index 条不该被软精简")
        }
    }

    @Test
    fun `软精简幂等且不动 UI 用的完整内容`() {
        val original = tool("a".repeat(40_000))
        val compactor = compactor()

        val once = compactor.softTrim(listOf(original), targetTokens = 1)
        val twice = compactor.softTrim(once, targetTokens = 1)

        // 第二次没有可裁内容 → 返回同一引用
        assertSame(once, twice)
        // result（UI 与持久化）始终是完整内容，只有 modelResult 变短
        val trimmed = once[0] as AgentMessage.ToolResultMessage
        assertEquals(40_000, trimmed.result.length)
        assertTrue(trimmed.modelResult!!.length < 40_000)
    }

    @Test
    fun `不碰非工具消息`() {
        val user = AgentMessage.UserMessage(content = "x".repeat(40_000))
        val result = compactor().softTrim(listOf(user), targetTokens = 1)
        assertSame(user, result[0])
    }

    @Test
    fun `兜底截断保证落进预算`() {
        val messages = listOf<AgentMessage>(
            tool("a".repeat(400_000)),
            AgentMessage.UserMessage(content = "问题")
        )
        val result = compactor().enforceWindowLimit(messages, budgetTokens = 1_000)
        assertTrue(TokenEstimator.estimateMessages(result) <= 1_500)
    }

    @Test
    fun `预算内不触发兜底`() {
        val messages = listOf<AgentMessage>(AgentMessage.UserMessage(content = "短问题"))
        assertSame(messages, compactor().enforceWindowLimit(messages, budgetTokens = 1_000))
    }

    @Test
    fun `软精简参数只写模型副本，原文一字不动`() {
        val text = "x".repeat(20_000)
        val original = AgentMessage.AssistantMessage(
            id = "m1",
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "c1",
                    name = "write",
                    arguments = mapOf(
                        "path" to JsonPrimitive("a.txt"),
                        "content" to JsonPrimitive(text)
                    )
                )
            )
        )
        val compactor = compactor()

        val once = compactor.softTrim(listOf(original), targetTokens = 1)
        val trimmedCall = (once[0] as AgentMessage.AssistantMessage).toolCalls.single()

        // arguments（执行 / 界面 / 落库用）保持原文；小字段（path）也不动
        assertEquals(original.toolCalls.single().arguments, trimmedCall.arguments)
        assertEquals(text, (trimmedCall.arguments["content"] as JsonPrimitive).content)
        assertEquals("a.txt", (trimmedCall.arguments["path"] as JsonPrimitive).content)

        // 只有模型那一份被换成占位说明
        val modelContent = (assertNotNull(trimmedCall.modelArguments)["content"] as JsonPrimitive).content
        assertTrue(modelContent.length < text.length, "副本应该比原文短")
        assertTrue(modelContent.startsWith("[已省略"), "大块正文应换成占位说明，实际：$modelContent")
        assertEquals(trimmedCall.modelArguments, trimmedCall.effectiveArguments)

        // 幂等：第二次仍以 arguments 为输入，算出同一份副本，不会越削越短
        assertEquals(once, compactor.softTrim(once, targetTokens = 1))
    }

    @Test
    fun `占位说明带禁令，模型侧副本没有可回写的正文`() {
        val text = "x".repeat(20_000)
        val original = bigWriteFileCall(text)

        val trimmedCall =
            (compactor().softTrim(listOf(original), targetTokens = 1)[0] as AgentMessage.AssistantMessage).toolCalls.single()
        val modelContent = (assertNotNull(trimmedCall.modelArguments)["content"] as JsonPrimitive).content

        // 执行/落库用的原文一字不动，只有模型那一份换成占位说明
        assertEquals(text, (trimmedCall.arguments["content"] as JsonPrimitive).content)
        assertTrue(modelContent.contains("严禁"), "占位说明要明确禁止回写，实际：$modelContent")

        // 软精简产出的占位说明，必须正好是执行边界守卫认得的形态（否则守卫漏拦）
        assertEquals(
            "content",
            omittedBulkArgumentKeyOf(
                mapOf("path" to JsonPrimitive("a.txt"), "content" to JsonPrimitive(modelContent))
            )
        )
    }

    @Test
    fun `执行边界守卫只认整串占位说明，文件里含这句话不算`() {
        val canonical = "[已省略 655 字符，正文不在上下文中，需要时用 read 工具读回]"
        val canonicalNoZhong = "[已省略 655 字符，正文不在上下文，需要时用 read 工具读回]"
        val withBan = omittedContentPlaceholder(655)
        val noBrackets = "已省略 655 字符，正文不在上下文中，需要时用 read 工具读回"

        // 模型照抄占位说明（含改写变体）→ 认出
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(withBan))))
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(canonical))))
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(canonicalNoZhong))))
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(noBrackets))))
        // editFile 的正文类字段同样覆盖
        assertEquals("old_string", omittedBulkArgumentKeyOf(mapOf("old_string" to JsonPrimitive(withBan))))

        // 用户文件里恰好含这句话：整串不等于占位说明，不得拦下
        val userFile = buildString {
            appendLine("# 压缩行为说明")
            appendLine()
            appendLine("参数被精简后会出现 `$canonical` 这样的占位说明，模型必须 read 回来再看。")
            appendLine("函数 foo() 与 bar() 都在这个文件里。")
        }
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(userFile))))
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("请看：$canonical"))))
        // 占位说明后面还跟着真正文：不是「整串占位说明」，不拦
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("$canonical，注意这不是文件内容"))))
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("$canonical\n再看看别的"))))
        // 末尾只多一个句号仍算照抄
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("$canonical。"))))

        // 只查大块正文字段：非 bulk key 上出现这句话不拦（收窄范围，避免误伤 path/command 等）
        assertNull(omittedBulkArgumentKeyOf(mapOf("path" to JsonPrimitive(canonical))))
        assertNull(omittedBulkArgumentKeyOf(mapOf("path" to JsonPrimitive("a.txt"))))
    }

    @Test
    fun `占位符拦截文案要求先 readFile 读回全文`() {
        assertEquals("ARG_OMITTED_BY_COMPACTION", ARG_OMITTED_BY_COMPACTION)

        val rejection = assertNotNull(
            omittedArgumentRejectionOf(
                ToolCall(
                    id = "c1",
                    name = "writeFile",
                    arguments = mapOf(
                        "path" to JsonPrimitive("src/Main.kt"),
                        "content" to JsonPrimitive(omittedContentPlaceholder(12_345))
                    )
                )
            )
        )
        assertTrue(rejection.contains("`content`"), rejection)
        assertTrue(rejection.contains("writeFile"), rejection)
        assertTrue(rejection.contains("readFile"), rejection)
        assertTrue(rejection.contains("src/Main.kt"), rejection)

        // 拿不到 path 时也要给出可执行的下一步
        val noPath = assertNotNull(
            omittedArgumentRejectionOf(
                ToolCall(
                    id = "c2",
                    name = "memory",
                    arguments = mapOf("content" to JsonPrimitive(omittedContentPlaceholder(9)))
                )
            )
        )
        assertTrue(noPath.contains("目标文件"), noPath)

        // 正常参数（哪怕内容里含这句话）不拦
        assertNull(
            omittedArgumentRejectionOf(
                ToolCall(
                    id = "c3",
                    name = "writeFile",
                    arguments = mapOf(
                        "path" to JsonPrimitive("src/Main.kt"),
                        "content" to JsonPrimitive("fun main() { /* 已省略 3 字符，正文不在上下文中 */ }")
                    )
                )
            )
        )
    }

    @Test
    fun `旧数据 toolCalls JSON 缺少 modelArguments 时反序列化回落原文`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """[{"id":"c1","name":"write","arguments":{"path":"a.txt","content":"正文"}}]"""

        val decoded = json.decodeFromString<List<ToolCall>>(legacy).single()

        assertNull(decoded.modelArguments)
        assertEquals("正文", (decoded.effectiveArguments["content"] as JsonPrimitive).content)

        // 带副本重新序列化后仍能读回副本（同一 JSON 列，不需要 DB 迁移）
        val withModel = decoded.copy(modelArguments = mapOf("content" to JsonPrimitive("[已省略 2 字符]")))
        val roundTrip = json.decodeFromString<List<ToolCall>>(json.encodeToString(listOf(withModel))).single()
        assertEquals("[已省略 2 字符]", (roundTrip.effectiveArguments["content"] as JsonPrimitive).content)
        assertEquals("正文", (roundTrip.arguments["content"] as JsonPrimitive).content)
    }

    private fun bigWriteFileCall(text: String) = AgentMessage.AssistantMessage(
        id = "m1",
        content = "",
        toolCalls = listOf(
            ToolCall(
                id = "c1",
                name = "writeFile",
                arguments = mapOf(
                    "path" to JsonPrimitive("a.txt"),
                    "content" to JsonPrimitive(text)
                )
            )
        )
    )

    private fun tool(text: String) = AgentMessage.ToolResultMessage(toolName = "read", result = text)

    private fun compactor() = ContextCompactor(
        agentMessageDao = mockk(relaxed = true),
        systemPromptProvider = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        compactedHistoryArchive = mockk(relaxed = true)
    )
}
