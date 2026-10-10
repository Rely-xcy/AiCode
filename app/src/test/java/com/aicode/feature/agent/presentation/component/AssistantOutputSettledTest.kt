package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮输出「是否已落库就位」（[isAssistantOutputSettled]）的判据：底部尾巴气泡连同三个跳动的点
 * 是否该退休，全靠它。
 *
 * 前三例是曾经让「本轮结束后点还在跳」的三种列表末条形态：末条是工具结果行、末条是空正文但有
 * 思考的助手行、末条是后台通知消息——它们都不承载本轮正文，判据必须越过它们去找真正承载
 * 正文 / 思考的那条助手消息（改成「只看列表末尾那条」这三例会全红）。
 *
 * 后三例守住另一侧：正在流式输出、还没落库的内容不能被误判成就位，否则正文会在流式途中整段
 * 隐去、落库后又冒出来（就是本判据要杜绝的空白闪回）。
 */
class AssistantOutputSettledTest {

    private fun user(text: String) = AgentUIMessage(id = "u-$text", role = MessageRole.USER, content = text)

    private fun assistant(content: String, reasoning: String? = null) = AgentUIMessage(
        id = "a-$content-${reasoning.orEmpty()}",
        role = MessageRole.ASSISTANT,
        content = content,
        reasoning = reasoning
    )

    private fun tool(content: String) = AgentUIMessage(id = "t-$content", role = MessageRole.TOOL, content = content)

    private fun backgroundNotification(text: String) = AgentUIMessage(
        id = "n-$text",
        role = MessageRole.USER,
        content = text,
        isBackgroundNotification = true
    )

    /** 末条是工具结果行：本轮正文落在更早那条助手行上，仍算就位。 */
    @Test
    fun lastMessageIsToolRow_isSettled() {
        val messages = listOf(
            user("帮我修一下这个空指针"),
            assistant("我先看下相关代码"),
            tool("读取文件完成")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 末条是「空正文 + 有思考」的助手行（纯工具调用轮）：它承载思考，正文要往前找。 */
    @Test
    fun lastMessageIsBlankContentAssistantWithReasoning_isSettled() {
        val messages = listOf(
            user("帮我修一下这个空指针"),
            assistant("我先看下相关代码"),
            tool("读取文件完成"),
            assistant("", reasoning = "我先确认一下调用方")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = "我先确认一下调用方",
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 末条是后台通知（user 角色、不显示为普通气泡）：不是本轮输出，不作比较对象。 */
    @Test
    fun lastMessageIsBackgroundNotification_isSettled() {
        val messages = listOf(
            user("帮我修一下这个空指针"),
            assistant("我先看下相关代码"),
            backgroundNotification("[后台任务完成] gradle test")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 新一轮刚开流、正文还没落库：最近的承载者是上一轮的助手消息，不能判成就位。 */
    @Test
    fun previousTurnAssistant_isNotSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("上一轮的回复内容"),
            user("第二个问题")
        )
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "这一轮正在输出的正文",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 首轮流式输出、列表里还没有任何助手行：还没东西可交接，不能判成就位。 */
    @Test
    fun firstTurnWhileStreaming_isNotSettled() {
        val messages = listOf(user("第一个问题"))
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "这一轮正在输出的正文",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /**
     * 上一轮是「思考 + 工具调用」的助手行，本轮正在输出正文：末条助手行正文为空，
     * 但正文承载者仍是更早那条，前缀比不中 → 尚未就位（实时正文必须继续摊在尾巴上）。
     * 这条同时钉住「空正文的助手行不能当成正文已落库」——否则正文会在流式途中整段隐去。
     */
    @Test
    fun streamingTextAfterBlankContentAssistant_isNotSettled() {
        val messages = listOf(
            user("帮我修一下这个空指针"),
            assistant("上一轮的回复内容", reasoning = "上一轮的思考"),
            tool("上一轮的工具结果"),
            assistant("", reasoning = "本轮正在思考")
        )
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "本轮正在输出的正文",
                currentReasoning = "本轮正在思考",
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 正文已落库但思考还没落库（思考行是被截断/未回传的另一次流式）：不能判成就位。 */
    @Test
    fun reasoningMismatch_isNotSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("我先看下相关代码", reasoning = "上一轮的思考")
        )
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = "完全不同的另一段思考",
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 正文与思考都已经落库：就位。 */
    @Test
    fun bothTextAndReasoningSettled_isSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("我先看下相关代码", reasoning = "先读文件确认结构")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = "先读文件确认结构",
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 只比头部 20 个字符：流式文本比落库文本短（同一条内容，落库行是它的延续）也算就位。 */
    @Test
    fun shortStreamHeadMatchesLongPersistedContent_isSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("我先看下相关代码，然后修掉这个空指针")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** Final 重写正文后与 retained 相等（normalize 空白）：双向包含判同源，判就位。 */
    @Test
    fun retainedEqualsNormalizedAnchor_isSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("  我先看下相关代码，\n然后修掉。  ")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "我先看下相关代码， 然后修掉。",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** anchor 正文以 retained 开头（Final 比流式累积更长/重写）：同源，判就位。 */
    @Test
    fun anchorStartsWithRetained_isSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("Final 重组后的完整回复正文开头")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "Final 重组后的完整回复",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = null
            )
        )
    }

    /** 失配持续且 isBusy 已转 false 超过强制退休超时：无条件判就位（旧尾巴必须消失）。 */
    @Test
    fun mismatchedIdleBeyondForceRetireTimeout_isSettled() {
        val messages = listOf(
            user("第一个问题"),
            assistant("落库的正文")
        )
        assertTrue(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "与落库完全无关的 retained 旧正文",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = System.currentTimeMillis() - 10_000L
            )
        )
        // 同形状、但超时未到：仍然不算就位（不能让失配的正文提前退休造成空白闪回）。
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "与落库完全无关的 retained 旧正文",
                currentReasoning = null,
                isBusy = false,
                settledFailingSinceMs = System.currentTimeMillis() - 1_000L
            )
        )
        // isBusy 仍为 true（新一轮还在流式）：超时兜底不生效，不能把正在写的内容误判成就位。
        assertFalse(
            isAssistantOutputSettled(
                messages = messages,
                currentText = "与落库完全无关的 retained 旧正文",
                currentReasoning = null,
                isBusy = true,
                settledFailingSinceMs = System.currentTimeMillis() - 10_000L
            )
        )
    }
}
