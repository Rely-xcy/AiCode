package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ContentWidth
import com.aicode.feature.agent.presentation.AgentAttachment

/**
 * 已发出、但还没在消息列表里就位的用户消息（乐观上屏）。
 *
 * [id] 是发送方预生成的消息 id，同时就是这条消息落库后的主键（见 AIAgentViewModel.executeAgentRequestStream
 * 的 clientMessageId）。因此「气泡该不该退场」是一个 id 相等判断，而不是「文本 + 时间戳」的相似匹配：
 * 后者在同一条文本连发两次时无法区分——先落库的那条会把还在 pending 的那条一并顶掉。
 */
internal data class PendingUserMessage(
    val id: String,
    val text: String,
    val attachments: List<AgentAttachment> = emptyList()
)

/**
 * 过滤出仍需要由乐观气泡承载的条目：已经以别的形态出现过的都剔除——
 * - [persistedIds]：库里已有同 id 的行，说明它已正式上屏（同一 id 不会在气泡与列表里各显示一次）；
 * - [queuedClientMessageIds]：VM 把它入了队，这条改由输入框上方的队列面板承载（气泡与队列面板不会同显同一条）。
 *
 * 两个判据都是 id 集合，全程不做文本比较；纯函数，便于锁住「不重复」这条性质（见 PendingUserMessageTest）。
 */
internal fun resolvePendingUserMessages(
    pending: List<PendingUserMessage>,
    persistedIds: Set<String>,
    queuedClientMessageIds: Set<String>
): List<PendingUserMessage> {
    if (pending.isEmpty()) return pending
    return pending.filterNot { it.id in persistedIds || it.id in queuedClientMessageIds }
}

/**
 * 待落库用户消息的气泡。外观对齐 [AgentMessageItem] 渲染落库用户气泡的那一支（同形状、同配色、
 * 同内边距、同字号行高），落库换手时气泡本身不出现视觉跳变。
 *
 * 刻意不复用 [AgentMessageItem]：
 * 1. 那条路会带出「时间 + 复制 / 回退 / 更多」操作行，而这些按钮都要按消息 id 反查库——pending 的 id
 *    在库里还不存在，点了也是空的；
 * 2. 操作行会把 item 撑高，落库那一刻高度再跳一次。
 *
 * 注意它由调用方挂在「尾巴 item」内，不参与 `chatItems`：item 数量不变，才不会牵动
 * `firstVisibleItemIndex` 的 clamp（见 AIChatPanel 里 `__active__` 的注释）。
 */
@Composable
internal fun PendingUserMessageBubble(pending: PendingUserMessage) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 与落库用户气泡同一套宽度口径：随文字撑开，上限为消息列宽（大屏下消息列已限宽居中）。
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // 纯附件（图片 / 文件）发送时没有正文，此时只出附件行，不画一个空气泡。
        if (pending.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(ChatStyle.bubbleCorner),
                color = chatUserBubbleColor(),
                modifier = Modifier.widthIn(max = maxUserBubbleWidth)
            ) {
                Text(
                    text = pending.text,
                    color = chatUserBubbleTextColor(),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }
        }
        if (pending.attachments.isNotEmpty()) {
            MessageAttachmentList(attachments = pending.attachments)
        }
    }
}
