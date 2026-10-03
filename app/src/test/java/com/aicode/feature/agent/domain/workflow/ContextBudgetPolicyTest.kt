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

/**
 * 上下文预算的测试：窗口档位、硬/软线、兜底线、保留最近原文的取值，
 * 以及 agent 工作流侧对它们的应用（软精简、兜底截断、只写模型副本、摘录与守卫）。
 *
 * 被测类型是 [ModelContextPolicy]（settings 域）与 [ContextCompactor]：预算规则原先由 agent 域的
 * ContextBudgetPolicy 承载，引擎化重构时并入了 ModelContextPolicy，本文件名沿用至今，
 * 所以类名与被测类型不一致（不是漏改）。策略自身的取值边界由
 * settings 域的 ModelContextPolicyTest 覆盖。
 */
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

    /**
     * 粗筛（H）：阈值线上的工具结果必须仍被削 —— 粗筛不能把它当“太短”跳过。
     *
     * 3001 字符刚过 softTrim 自己的门限（`current.length <= SOFT_TRIM_TOOL_CHARS` 就跳过），
     * 是历史里唯一该被削的一条。
     */
    @Test
    fun `软精简不因粗筛漏掉刚过阈值的工具结果`() {
        val borderline = tool("a".repeat(3_001))
        val messages = listOf(borderline) + recentRounds()

        val result = compactor().softTrim(messages, targetTokens = 1)

        assertNotNull((result[0] as AgentMessage.ToolResultMessage).modelResult, "刚过阈值的工具结果必须仍被软精简")
    }

    /** 粗筛（H）的另一头：阈值（含）以下的工具结果本来就不该动，整体应返回原引用。 */
    @Test
    fun `软精简不碰阈值及以下的工具结果`() {
        val messages = listOf(tool("a".repeat(3_000))) + recentRounds()

        assertSame(messages, compactor().softTrim(messages, targetTokens = 1))
    }

    /** 粗筛（H）：工具参数的阈值与 args 裁剪预算同一口径（SOFT_TRIM_TOOL_CHARS）。 */
    @Test
    fun `软精简约在参数阈值线上的助手消息仍会被缩`() {
        val borderline = bigWriteFileCall("a".repeat(3_001))
        val messages = listOf(borderline) + recentRounds()

        val result = compactor().softTrim(messages, targetTokens = 1)

        val trimmed = result[0] as AgentMessage.AssistantMessage
        assertNotNull(trimmed.toolCalls.single().modelArguments, "刚过阈值的工具参数必须仍被缩")
        // 原文一字不动：执行、UI、落库读的都是 arguments
        assertEquals(3_001, (trimmed.toolCalls.single().arguments["content"] as JsonPrimitive).content.length)
    }

    @Test
    fun `软精简不碰参数阈值及以下的助手消息`() {
        val messages = listOf(bigWriteFileCall("a".repeat(3_000))) + recentRounds()

        assertSame(messages, compactor().softTrim(messages, targetTokens = 1))
    }

    /** 最近三轮的占位消息：把前面的历史挡在保护线之外，自身不会被软精简。 */
    private fun recentRounds(): List<AgentMessage> = (1..3).flatMap { round ->
        listOf(
            AgentMessage.UserMessage(content = "第 $round 轮"),
            tool("b".repeat(400))
        )
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

        // 只有模型那一份被换成摘录（原文照旧能从磁盘读回来，摘录只是为了省 token）
        val modelContent = (assertNotNull(trimmedCall.modelArguments)["content"] as JsonPrimitive).content
        assertTrue(modelContent.length < text.length, "副本应该比原文短")
        assertTrue(
            modelContent.startsWith(BULK_EXCERPT_HEAD),
            "大块正文应换成可读摘录，实际：${modelContent.take(80)}"
        )
        assertEquals(trimmedCall.modelArguments, trimmedCall.effectiveArguments)

        // 幂等：第二次仍以 arguments 为输入，算出同一份副本，不会越削越短
        assertEquals(once, compactor.softTrim(once, targetTokens = 1))
    }

    @Test
    fun `摘录带禁令，模型侧副本没有可直接回写的正文`() {
        val text = "x".repeat(20_000)
        val original = bigWriteFileCall(text)

        val trimmedCall =
            (compactor().softTrim(listOf(original), targetTokens = 1)[0] as AgentMessage.AssistantMessage).toolCalls.single()
        val modelContent = (assertNotNull(trimmedCall.modelArguments)["content"] as JsonPrimitive).content

        // 执行/落库用的原文一字不动，只有模型那一份换成摘录
        assertEquals(text, (trimmedCall.arguments["content"] as JsonPrimitive).content)
        assertTrue(modelContent.contains("严禁"), "摘录要明确禁止回写，实际：$modelContent")
        assertTrue(modelContent.contains("readFile") && modelContent.contains("a.txt"), "摘录必须给出把全文读回来的指针")

        // 软精简产出的摘录，必须正好是执行边界守卫认得的形态（否则守卫漏拦）
        assertEquals(
            "content",
            omittedBulkArgumentKeyOf(
                mapOf("path" to JsonPrimitive("a.txt"), "content" to JsonPrimitive(modelContent))
            )
        )
    }

    @Test
    fun `大块参数换成可读摘录：头尾逐字保留，并写明省略与回读指针`() {
        val first = "fun main() {"
        val last = "// 文件结束"
        val text = buildString {
            appendLine(first)
            repeat(600) { appendLine("    val value$it = \"${ "x".repeat(30) }\"") }
            append(last)
        }
        // 前提（不是对实现的期望）：正文确实越过大块参数闸值，中间确实有一行会被省掉
        val middleLine = "    val value300 = "
        assertTrue(text.length > 4_000)
        assertTrue(text.contains(middleLine))

        val once = compactor().softTrim(listOf(bigWriteFileCall(text)), targetTokens = 1)
        val trimmedCall = (once[0] as AgentMessage.AssistantMessage).toolCalls.single()
        val modelContent = (assertNotNull(trimmedCall.modelArguments)["content"] as JsonPrimitive).content

        // 形态：哨兵 + 一行说明 + 保留的原文 + 一行省略标记 + 保留的原文
        assertTrue(modelContent.startsWith(BULK_EXCERPT_HEAD), modelContent.take(80))
        val excerptLines = modelContent.lines()
        val gapIndex = excerptLines.indexOfFirst { it.startsWith(BULK_EXCERPT_GAP_PREFIX) }
        assertTrue(gapIndex > 1, "摘录里应有一行中段省略标记，实际：${modelContent.take(200)}")

        // 两端必须是原文自己的内容（前缀/后缀关系，逐字），不是改写过的文本
        val head = excerptLines.subList(1, gapIndex).joinToString("\n")
        val tail = excerptLines.subList(gapIndex + 1, excerptLines.size).joinToString("\n")
        assertTrue(text.startsWith(head), "开头必须逐字来自原文")
        assertTrue(text.endsWith(tail), "结尾必须逐字来自原文")
        assertEquals(first, head.lines().first(), "首行要保留")
        assertEquals(last, tail.lines().last(), "末行要保留")
        // 中段真的被省掉：600 行正文只留两端，中间那行不该出现在摘录里
        assertFalse(modelContent.contains(middleLine), "被省略的中段不该出现在摘录里")

        // 省略说明里的数字必须为真：保留字数 + 声称省略的字符数 = 原文字数；
        // 声称省略的行数 = 原文换行数 − 两端保留的换行数
        val omittedClaim = assertNotNull(
            Regex("省略 (\\d+) 行（(\\d+) 字符）").find(modelContent),
            "摘录必须写明省略了多少行/多少字符：${modelContent.take(200)}"
        )
        assertEquals(text.length, head.length + tail.length + omittedClaim.groupValues[2].toInt())
        assertEquals(
            text.count { it == '\n' } - head.count { it == '\n' } - tail.count { it == '\n' },
            omittedClaim.groupValues[1].toInt()
        )

        // 仍是一段以原文内容为主的可读正文，且显著短于原文（调用方的估算记账都假定换过之后更短）
        assertTrue(head.length + tail.length > modelContent.length / 2, "摘录应以保留的原文内容为主")
        assertTrue(modelContent.length * 2 <= text.length, "摘录 ${modelContent.length} / 原文 ${text.length}")

        // 幂等：重复精简仍以 arguments 为输入，算出同一份摘录
        assertEquals(once, compactor().softTrim(once, targetTokens = 1))
    }

    @Test
    fun `摘录省不下内容时退回占位说明`() {
        // 原文刚过调用方给的上限：摘录自己的头尾预算被 limit 压得很小，算出来反而更长
        assertNull(bulkArgumentExcerptOf("a".repeat(260), "a.txt", limit = 200))
        // 正常情况（原文远大于上限）必须给出摘录，否则大块正文就白白丢了可读信息
        assertNotNull(bulkArgumentExcerptOf("a".repeat(20_000), "a.txt", limit = 4_000))
    }

    @Test
    fun `守卫拦得住照抄的摘录，也认得旧的整串占位说明`() {
        val excerpt = assertNotNull(bulkArgumentExcerptOf(longFileText(), "src/Main.kt", limit = 4_000))

        // 模型把整段摘录当正文回写 → 拦
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(excerpt))))
        // 前后顺手加了空白/缩进，仍是照抄
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("  $excerpt\n"))))
        // 摘录后面又接了自己的话：值的开头就是哨兵，照样是「拿摘录当正文」
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("$excerpt\nfun extra() = 1"))))
        // 只抄走中段省略标记（没带首行哨兵）
        val gap = excerpt.lines().single { it.startsWith(BULK_EXCERPT_GAP_PREFIX) }
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(gap))))
        // editFile 的正文类字段同样覆盖
        assertEquals("old_string", omittedBulkArgumentKeyOf(mapOf("old_string" to JsonPrimitive(excerpt))))
        // 历史消息里还在的整段占位说明不能因为改产摘录就认不出
        assertEquals("content", omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(omittedContentPlaceholder(9_999)))))
    }

    @Test
    fun `守卫不误伤正常正文：哨兵与省略标记只是出现在正文里`() {
        // 本仓库自己的源码与测试就写着这些字样，改这类文件必须能过
        val source = buildString {
            appendLine("package com.aicode.feature.agent.domain.workflow")
            appendLine()
            appendLine("// $BULK_EXCERPT_HEAD 是执行边界守卫认的形态")
            appendLine("internal const val BULK_EXCERPT_GAP_PREFIX = \"$BULK_EXCERPT_GAP_PREFIX\"")
            appendLine("fun main() = Unit")
        }
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(source))))
        assertNull(
            omittedArgumentRejectionOf(
                ToolCall(
                    id = "c9",
                    name = "writeFile",
                    arguments = mapOf("path" to JsonPrimitive("src/Main.kt"), "content" to JsonPrimitive(source))
                )
            )
        )

        // 正文里引用一句占位说明（夹在多行文档中间）
        val doc = "# 压缩说明\n\n参数被精简后会出现 `${omittedContentPlaceholder(655)}` 这样的文本。\n"
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive(doc))))

        // 普通正文、非 bulk 字段
        assertNull(omittedBulkArgumentKeyOf(mapOf("content" to JsonPrimitive("fun main() { println(1) }"))))
        assertNull(omittedBulkArgumentKeyOf(mapOf("path" to JsonPrimitive(BULK_EXCERPT_HEAD))))
    }

    @Test
    fun `近期被引用的历史工具内容不按体量优先削`() {
        // 历史里两条体积接近的大块参数：legacy.txt 没人再用，Main.kt 最近三轮还在改
        val legacyCall = bigCall("c-legacy", "legacy.txt", "x".repeat(40_000))
        val pinnedCall = bigCall("c-pinned", "Main.kt", "y".repeat(41_000))
        val messages = listOf(legacyCall, pinnedCall) + recentRoundsTouching("Main.kt")

        // 只要求削掉一丁点（比整份估算少 1 token）：够用就停，所以排序里的第一条会被削，其余不动
        val result = compactor().softTrim(messages, targetTokens = TokenEstimator.estimateMessages(messages) - 1)

        assertNotNull(
            (result[0] as AgentMessage.AssistantMessage).toolCalls.single().modelArguments,
            "没人再用的历史大块参数应当先被削"
        )
        assertSame(messages[1], result[1], "最近三轮还在改的同一文件，不该排在体积前面被削")

        // 只降级不豁免：预算真的不够时，受保护的那些仍要削得动，否则软精简不再收敛
        val squeezed = compactor().softTrim(messages, targetTokens = 1)
        assertNotNull((squeezed[1] as AgentMessage.AssistantMessage).toolCalls.single().modelArguments)
    }

    @Test
    fun `近期被引用的工具结果同样降级到最后`() {
        // 工具结果自身不带路径，靠它的调用 id 反查回当初读写的文件
        val messages = listOf(
            readCall("c-legacy", "legacy.txt"),
            tool("x".repeat(40_000), id = "c-legacy", toolName = "readFile"),
            readCall("c-other", "other.txt"),
            tool("z".repeat(40_000), id = "c-other", toolName = "readFile"),
            readCall("c-pinned", "Main.kt"),
            tool("y".repeat(41_000), id = "c-pinned", toolName = "readFile")
        ) + recentRoundsTouching("Main.kt")

        // 同样只要求削掉一丁点：排序第一条（没人再用的那条）被削，近期引用的那条不动
        val result = compactor().softTrim(messages, targetTokens = TokenEstimator.estimateMessages(messages) - 1)

        assertNotNull((result[1] as AgentMessage.ToolResultMessage).modelResult, "没人再用的历史工具输出应当先被削")
        assertSame(messages[5], result[5], "近期还在用的文件那条结果不该抢先被削")
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

    /**
     * 真机那一轮的回归：上一轮 真实 352,982 / 原始估算 523,798，本轮历史形状相同、估算仍是 523,798。
     *
     * 校准把「上一轮确认的高估 170,816」减掉 → 判定 352,982（窗口 1,000,000 的 35.3%）→ **不触发软精简**。
     * 修复前判定 = max(352,982, 523,798) = 523,798（52.4%）≥ 软线 400,000 → 白跑一轮软精简。
     */
    @Test
    fun `上一轮的高估被校准掉后不再触发软精简`() {
        val real = 352_982
        val estimated = 523_798
        val calibrated = TokenEstimator.calibrated(estimated, baselineEstimate = 523_798, baselineUsage = real)
        // 判定式与模块同源：真实 usage 与校准后的估算取较大值
        val judged = maxOf(real, calibrated)
        val thresholds = ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 85)

        assertEquals(352_982, calibrated)
        assertEquals(352_982, judged)
        assertEquals(400_000, thresholds.soft)
        assertTrue(judged < thresholds.soft)
    }

    /**
     * 反向：上一轮低估（真实 > 估算）时校准**不做任何修正**（绝不上加），
     * 而真实值更高的那一头由判定式的 max 自己兜住 —— 真实超线时照样触发。
     */
    @Test
    fun `上一轮低估时不修正但真实超线仍然触发`() {
        val calibrated = TokenEstimator.calibrated(
            estimated = 300_000,
            baselineEstimate = 300_000,
            baselineUsage = 380_000
        )
        val thresholds = ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 85)

        assertEquals(300_000, calibrated)
        assertTrue(maxOf(380_000, calibrated) < thresholds.soft) // 真实 38 万仍在线下
        assertTrue(maxOf(500_000, calibrated) >= thresholds.soft) // 真实 50 万 → 触发软精简
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

    private fun bigCall(id: String, path: String, text: String) = AgentMessage.AssistantMessage(
        id = "a-$id",
        content = "",
        toolCalls = listOf(
            ToolCall(
                id = id,
                name = "writeFile",
                arguments = mapOf("path" to JsonPrimitive(path), "content" to JsonPrimitive(text))
            )
        )
    )

    private fun readCall(id: String, path: String) = AgentMessage.AssistantMessage(
        id = "a-$id",
        content = "",
        toolCalls = listOf(ToolCall(id = id, name = "readFile", arguments = mapOf("path" to JsonPrimitive(path))))
    )

    /** 最近三轮：每轮一个「用户消息 + 改 [path] 的工具调用 + 结果」，足够撑出近期引用。 */
    private fun recentRoundsTouching(path: String) = (1..3).flatMap { round ->
        listOf(
            AgentMessage.UserMessage(content = "第 $round 轮"),
            AgentMessage.AssistantMessage(
                id = "recent-$round",
                content = "",
                toolCalls = listOf(
                    ToolCall(id = "recent-call-$round", name = "editFile", arguments = mapOf("path" to JsonPrimitive(path)))
                )
            ),
            tool("ok", id = "recent-call-$round", toolName = "editFile")
        )
    }

    /** 多行正文：摘录切点按行对齐，给守卫测试用一份头尾可辨认的文本。 */
    private fun longFileText(): String = buildString {
        appendLine("fun main() {")
        repeat(400) { appendLine("    val value$it = \"${ "x".repeat(30) }\"") }
        append("// 文件结束")
    }

    private fun tool(text: String, id: String = "", toolName: String = "read") =
        AgentMessage.ToolResultMessage(id = id, toolName = toolName, result = text)

    private fun compactor() = ContextCompactor(
        agentMessageDao = mockk(relaxed = true),
        systemPromptProvider = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        compactedHistoryArchive = mockk(relaxed = true)
    )
}
