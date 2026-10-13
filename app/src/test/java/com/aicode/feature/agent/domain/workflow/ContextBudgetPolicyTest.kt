package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import io.mockk.mockk
import kotlinx.serialization.decodeFromString
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
 * 上下文预算的测试：窗口档位、硬线、兜底线、保留最近原文的取值，
 * 以及 agent 工作流侧对它们的应用（兜底截断、摘录与守卫；软精简已随阶段 1 移除）。
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
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(32_000).tier)
        assertEquals(ModelContextPolicy.Tier.STANDARD, ModelContextPolicy.tierFor(64_000).tier)
        assertEquals(ModelContextPolicy.Tier.GENEROUS, ModelContextPolicy.tierFor(128_000).tier)
    }

    @Test
    fun `小窗口不启用硬压缩`() {
        // 只有 <32K（DISABLED）不启用；32K 以上并入 STANDARD（90%），随软精简删除不再有例外档。
        assertEquals(0, ModelContextPolicy.tierFor(16_000).hardThreshold)
        assertEquals(63_000 - 63_000 / 10, ModelContextPolicy.tierFor(63_000).hardThreshold)
    }

    @Test
    fun `硬上限按档位留出 headroom（约窗口的 90%）`() {
        assertEquals(57_600, ModelContextPolicy.tierFor(64_000).hardThreshold)
        assertEquals(115_200, ModelContextPolicy.tierFor(128_000).hardThreshold)
    }

    @Test
    fun `有效硬线取百分比与档位上限的较小值`() {
        val window = 128_000
        val tier = ModelContextPolicy.tierFor(window)
        // 85% × 128k = 108_800 < 档位上限 115_200 → 取百分比
        assertEquals(108_800, minOf(window * 85 / 100, tier.hardThreshold))
        // 50% × 128k = 64_000 < 上限 → 取百分比
        assertEquals(64_000, minOf(window * 50 / 100, tier.hardThreshold))
        // 100% 也会被封在档位上限（~90%）
        assertEquals(115_200, minOf(window * 100 / 100, tier.hardThreshold))
    }

    @Test
    fun `生效阈值与判定同源：压缩模块与界面取同一份`() {
        // 128K 走 GENEROUS：档位上限 115.2K 高于 85% 的 108.8K，按百分比生效
        val generous = ModelContextPolicy.thresholds(128_000, softPercent = 40, hardPercent = 85)
        assertEquals(128_000, generous.contextLimit)
        assertEquals(108_800, generous.hard)
        assertEquals(51_200, generous.soft)
        assertTrue(generous.hardEnabled)

        // 1M 窗口：85% = 850K，档位上限 900K
        val million = ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 85)
        assertEquals(850_000, million.hard)
        assertEquals(400_000, million.soft)

        // 64K 走 STANDARD：85% = 54.4K，档位上限 57.6K → 取百分比
        assertEquals(54_400, ModelContextPolicy.thresholds(64_000, softPercent = 40, hardPercent = 85).hard)
    }

    @Test
    fun `用户把硬线设为 100% 也会被档位上限封顶`() {
        // 1M 窗口封在档位上限 900K（窗口的 90%，给输出与提示词留 headroom）
        assertEquals(900_000, ModelContextPolicy.thresholds(1_000_000, softPercent = 40, hardPercent = 100).hard)
    }

    @Test
    fun `软线始终低于硬线`() {
        // 软线只作只读兼容展示（请求链路已不消费），但取值规则保持封在硬线之下
        val thresholds = ModelContextPolicy.thresholds(128_000, softPercent = 100, hardPercent = 85)
        assertEquals(108_799, thresholds.soft)
        assertTrue(thresholds.soft < thresholds.hard)
    }

    @Test
    fun `32K 档并入标准档：硬线 90% 启用，软线不再消费`() {
        val standard = ModelContextPolicy.thresholds(32_000, softPercent = 40, hardPercent = 85)
        assertTrue(standard.hardEnabled)
        assertEquals(27_200, standard.hard)
        // 软线字段保留为只读兼容（请求链路已不消费），但归一化规则不变：封顶在窗口 90% 下。
        assertEquals(12_800, standard.soft)

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
        // 「实际触发线 850,000（窗口 1,000,000，档位上限 900,000）」：三个数都由这里算出，不另算一套
        val window = 1_000_000
        val thresholds = ModelContextPolicy.thresholds(window, softPercent = 40, hardPercent = 85)

        assertEquals(850_000, thresholds.hard)
        assertEquals(1_000_000, thresholds.contextLimit)
        assertEquals(900_000, ModelContextPolicy.tierFor(window).hardThreshold)
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
    fun `摘录是纯函数：原文不动，重复计算结果一致`() {
        val text = "x".repeat(20_000)
        val excerpt1 = assertNotNull(bulkArgumentExcerptOf(text, "a.txt", limit = 4_000))
        val excerpt2 = assertNotNull(bulkArgumentExcerptOf(text, "a.txt", limit = 4_000))

        // 原文字符串不可变，这里钉住「同一输入永远得到同一输出」（投影确定性 → 缓存友好）
        assertEquals(excerpt1, excerpt2)
        assertTrue(excerpt1.length < text.length, "摘录应该比原文短")
        assertTrue(
            excerpt1.startsWith(BULK_EXCERPT_HEAD),
            "大块正文应换成可读摘录，实际：${excerpt1.take(80)}"
        )
    }

    @Test
    fun `摘录带禁令，模型侧副本没有可直接回写的正文`() {
        val text = "x".repeat(20_000)

        val modelContent = assertNotNull(bulkArgumentExcerptOf(text, "a.txt", limit = 4_000))

        assertTrue(modelContent.contains("严禁"), "摘录要明确禁止回写，实际：$modelContent")
        assertTrue(modelContent.contains("readFile") && modelContent.contains("a.txt"), "摘录必须给出把全文读回来的指针")

        // 指针化产出的摘录，必须正好是执行边界守卫认得的形态（否则守卫漏拦）
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

        val once = assertNotNull(bulkArgumentExcerptOf(text, "a.txt", limit = 4_000))
        val modelContent = once

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

        // 幂等：同一输入重复计算，算出同一份摘录
        assertEquals(once, bulkArgumentExcerptOf(text, "a.txt", limit = 4_000))
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
    fun `旧数据 toolCalls JSON 带 modelArguments 键时忽略并回落原文`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """[{"id":"c1","name":"write","arguments":{"path":"a.txt","content":"正文"},"modelArguments":{"content":"[已省略]"}}]"""

        val decoded = json.decodeFromString<List<ToolCall>>(legacy).single()

        // modelArguments 字段已删除，旧 JSON 里的同名键被 ignoreUnknownKeys 忽略，arguments 原文完整读回
        assertEquals("正文", (decoded.arguments["content"] as JsonPrimitive).content)
    }

    /**
     * 真机那一轮的回归：上一轮 真实 352,982 / 原始估算 523,798，本轮历史形状相同、估算仍是 523,798。
     *
     * 校准把「上一轮确认的高估 170,816」减掉 → 判定 352,982。软精简已移除，这里钉住的是
     * 校准算法本身：判定值必须按校准后的数走，而不是原始估算。
     */
    @Test
    fun `上一轮的高估被校准掉后判定值回到真实水平`() {
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
        assertTrue(maxOf(500_000, calibrated) >= thresholds.soft) // 真实 50 万 → 超过兼容展示线
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
