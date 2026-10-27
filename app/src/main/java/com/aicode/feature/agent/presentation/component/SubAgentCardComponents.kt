package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `task` 工具的专属卡片宿主：操作用 CompositionLocal 下发，避免参数一路穿过
 * 消息列表、分组与气泡三层组合。
 */
data class SubAgentCardHost(
    val states: Map<String, SubAgentCardState> = emptyMap(),
    val onOpen: (String) -> Unit = {},
    val onStop: (String) -> Unit = {}
)

val LocalSubAgentCardHost = staticCompositionLocalOf<SubAgentCardHost?> { null }

/**
 * 从 `task(create)` 的工具参数与结果里解析出的派发信息（支持单个与批量派发）。
 */
internal data class ParsedSubAgentDispatch(
    val items: List<ParsedSubAgentDispatchItem>,
    val globalWarning: String? = null
) {
    // 兼容单项快捷访问
    val subSessionId: String? get() = items.firstOrNull()?.subSessionId
    val agentName: String? get() = items.firstOrNull()?.agentName
    val description: String get() = items.firstOrNull()?.description.orEmpty()
    val tier: String? get() = items.firstOrNull()?.tier
    val model: String? get() = items.firstOrNull()?.model
    val warning: String? get() = items.firstOrNull()?.warning ?: globalWarning
}

internal data class ParsedSubAgentDispatchItem(
    val subSessionId: String?,
    val agentName: String?,
    val description: String,
    val tier: String?,
    val model: String?,
    val warning: String?
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * 解析 `task(create)`：支持单个与批量任务派发的双重解析。
 *
 * 结果里 `data` 既可能是对象也可能是被序列化成字符串的对象；
 * 解析不出 id 时卡片仍按参数渲染，只是不能跳转。
 */
internal fun parseSubAgentDispatch(argsJson: String?, content: String?): ParsedSubAgentDispatch? {
    val args = argsJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val action = (args?.get("action") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "create"
    if (action != "create") return null

    val result = content?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val status = (result?.get("status") as? JsonPrimitive)?.contentOrNull
    if (status == "error") return null

    val data = result?.get("data").let { element ->
        when (element) {
            is JsonObject -> element
            is JsonPrimitive -> runCatching { json.parseToJsonElement(element.content).jsonObject }.getOrNull()
            else -> null
        }
    }

    // 1. 尝试从 result 的 subagents 数组解析批量结果
    val resultSubagents = (data?.get("subagents") as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        ParsedSubAgentDispatchItem(
            subSessionId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
            warning = (obj["warning"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        )
    }

    // 2. 尝试从 args 的 tasks 数组解析任务入参
    val argTasks = (args?.get("tasks") as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        val desc = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prompt?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        ParsedSubAgentDispatchItem(
            subSessionId = null,
            agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = desc,
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = null,
            warning = null
        )
    }

    val items: List<ParsedSubAgentDispatchItem> = when {
        !resultSubagents.isNullOrEmpty() -> {
            resultSubagents.mapIndexed { idx, item ->
                val fallbackDesc = argTasks?.getOrNull(idx)?.description
                if (item.description.isBlank() && !fallbackDesc.isNullOrBlank()) {
                    item.copy(description = fallbackDesc)
                } else {
                    item
                }
            }
        }
        !argTasks.isNullOrEmpty() -> argTasks
        else -> {
            // 单个子代理处理
            val agentName = (data?.get("agent") as? JsonPrimitive)?.contentOrNull
                ?: (args?.get("agent") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val description = (args?.get("description") as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: (args?.get("prompt") as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.lineSequence()?.firstOrNull()?.trim().orEmpty()

            val singleItem = ParsedSubAgentDispatchItem(
                subSessionId = (data?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                agentName = agentName,
                description = description,
                tier = (data?.get("tier") as? JsonPrimitive)?.contentOrNull,
                model = (data?.get("model") as? JsonPrimitive)?.contentOrNull,
                warning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            )
            listOf(singleItem)
        }
    }

    if (items.isEmpty()) return null

    return ParsedSubAgentDispatch(
        items = items,
        globalWarning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    )
}

/** 单条派发合并卡片的文案模型：子代理名已回退，告警已去重。 */
internal data class SubAgentSingleSummary(
    val agentName: String,
    val warnings: List<String> = emptyList()
)

/**
 * 汇总单条派发卡片需要的文案。
 *
 * 单条派发时 `data.warning` 会被解析器同时写进任务项与 `globalWarning`（见 parseSubAgentDispatch
 * 的单条分支），两处都渲染就是同一条提示出现两次，所以这里按内容合并。
 *
 * 代理名优先取派发结果（参数或结果的 `agent`）；结果里没带时用子会话实时状态里的名字补上，
 * 两者都没有才退回默认名——否则卡片上只剩一句泛称，看不出是哪个子代理在干活。
 */
internal fun subAgentSingleSummary(
    item: ParsedSubAgentDispatchItem,
    globalWarning: String?,
    defaultAgentName: String,
    liveAgentName: String? = null
): SubAgentSingleSummary = SubAgentSingleSummary(
    agentName = item.agentName?.takeIf { it.isNotBlank() }
        ?: liveAgentName?.takeIf { it.isNotBlank() }
        ?: defaultAgentName,
    warnings = listOfNotNull(
        item.warning?.takeIf { it.isNotBlank() },
        globalWarning?.takeIf { it.isNotBlank() }
    ).distinct()
)

/**
 * 多条派发的状态摘要，形如「运行中 2 · 已完成 1」；同一状态只出一条，按运行中/已完成/失败/已停止排序。
 *
 * 标签由调用方从资源取（纯函数便于单测）；全部为 null（历史回放拿不到实时状态）时返回空串。
 */
internal fun subAgentStatusSummary(
    statuses: List<SubAgentCardStatus?>,
    runningLabel: String,
    completedLabel: String,
    failedLabel: String,
    stoppedLabel: String
): String = listOf(
    SubAgentCardStatus.RUNNING to runningLabel,
    SubAgentCardStatus.COMPLETED to completedLabel,
    SubAgentCardStatus.FAILED to failedLabel,
    SubAgentCardStatus.STOPPED to stoppedLabel
).mapNotNull { (status, label) ->
    statuses.count { it == status }.takeIf { it > 0 }?.let { count -> "$label $count" }
}.joinToString(" · ")

/**
 * 子代理派发卡片：单条与多条都只出一张卡，卡内按行组织，不再嵌套子卡片。
 *
 * 单条：代理名 + 状态药丸 + 箭头 / 任务描述（单行截断）/ 模型与档位（弱化小字）。
 * 多条：卡头一行「子代理任务」+ 派发与状态摘要，下面按行列出各子代理，行间分隔线，
 * 每行可点进对应子会话；告警统一收在卡片底部。
 *
 * 状态药丸的实时状态来自 [LocalSubAgentCardHost]（按子会话 id 索引）：本进程内派发的子代理
 * 随生命周期刷新，历史回放拿不到实时状态时退回静态「已派发」，不渲染假的「运行中」。
 */
@Composable
internal fun SubAgentDispatchCard(dispatch: ParsedSubAgentDispatch) {
    val host = LocalSubAgentCardHost.current
    val items = dispatch.items
    if (items.isEmpty()) return

    val onOpen: (String) -> Unit = { sessionId -> host?.onOpen?.invoke(sessionId) }

    if (items.size == 1) {
        val item = items.first()
        SubAgentSingleDispatchCard(
            item = item,
            globalWarning = dispatch.globalWarning,
            liveState = item.subSessionId?.let { host?.states?.get(it) },
            onOpen = onOpen
        )
        return
    }

    val liveStates = items.map { item -> item.subSessionId?.let { host?.states?.get(it) } }
    val warnings = (
        listOfNotNull(dispatch.globalWarning?.takeIf { it.isNotBlank() }) +
            items.mapNotNull { item -> item.warning?.takeIf { it.isNotBlank() } }
        ).distinct()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubAgentDispatchSummary(count = items.size, liveStates = liveStates)
            items.forEachIndexed { index, item ->
                SubAgentRowDivider()
                SubAgentDispatchRow(
                    index = index + 1,
                    item = item,
                    liveState = liveStates[index],
                    onOpen = onOpen
                )
            }
            warnings.forEach { warning ->
                SubAgentRowDivider()
                Box(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                    SubAgentWarningBanner(warning)
                }
            }
        }
    }
}

/** 卡头：图标 + 标题，副标题是派发数量与各状态计数（无实时状态时只显示数量）。 */
@Composable
private fun SubAgentDispatchSummary(count: Int, liveStates: List<SubAgentCardState?>) {
    val statusSummary = subAgentStatusSummary(
        statuses = liveStates.map { it?.status },
        runningLabel = subAgentStatusLabel(SubAgentCardStatus.RUNNING),
        completedLabel = subAgentStatusLabel(SubAgentCardStatus.COMPLETED),
        failedLabel = subAgentStatusLabel(SubAgentCardStatus.FAILED),
        stoppedLabel = subAgentStatusLabel(SubAgentCardStatus.STOPPED)
    )
    val subtitle = listOfNotNull(
        stringResource(R.string.subagent_dispatch_count, count),
        statusSummary.takeIf { it.isNotEmpty() }
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubAgentGlyph()
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.subagent_dispatch_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 卡片图标：单条卡与多条卡头共用，尺寸与工具行图标一致。 */
@Composable
private fun SubAgentGlyph() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = FeatherIcons.Cpu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * 单条派发：一张卡三行——代理名 + 状态药丸 + 箭头 / 任务描述（单行截断）/ 模型与档位（弱化小字）。
 * 整卡可点进子会话。
 *
 * [liveState] 与任务项同源：完成后在描述下追加结果摘要与改动文件数；为 null（历史回放、
 * 进程重启后）时状态药丸退回静态「已派发」。
 */
@Composable
private fun SubAgentSingleDispatchCard(
    item: ParsedSubAgentDispatchItem,
    globalWarning: String?,
    liveState: SubAgentCardState?,
    onOpen: (String) -> Unit
) {
    val hasSession = item.subSessionId != null
    val summary = subAgentSingleSummary(
        item = item,
        globalWarning = globalWarning,
        defaultAgentName = stringResource(R.string.subagent_card_default_agent),
        liveAgentName = liveState?.agentName
    )
    val meta = modelBadge(item.tier, item.model)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasSession) {
                        Modifier.clickable { onOpen(item.subSessionId!!) }
                    } else Modifier
                )
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                SubAgentGlyph()
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = summary.agentName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        SubAgentStatusChip(liveState?.status)
                        if (hasSession) {
                            Spacer(Modifier.width(Spacing.xs))
                            Icon(
                                imageVector = FeatherIcons.ChevronRight,
                                contentDescription = stringResource(R.string.subagent_card_open),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    meta?.let { badge ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = badge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    liveState?.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = excerpt,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    liveState?.changedFiles?.takeIf { it.isNotEmpty() }?.let { files ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.subagent_card_changed_files, files.size),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            summary.warnings.forEach { warning ->
                Spacer(Modifier.height(Spacing.xs))
                SubAgentWarningBanner(warning)
            }
        }
    }
}

/**
 * 卡内行分隔线：与设置分组的 SettingsDivider 同款（0.5dp、低透明度描边色），只缩进卡片内边距。
 */
@Composable
private fun SubAgentRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.sm),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/**
 * 卡内一行子代理：序号 + 「代理名 · 描述」（单行截断）+ 状态药丸 + 箭头，整行可点进子会话。
 *
 * 序号用 1、2、3 而非 01：行内已有代理名做标识，补零只多占两格宽度，小屏上会把描述挤掉。
 */
@Composable
private fun SubAgentDispatchRow(
    index: Int,
    item: ParsedSubAgentDispatchItem,
    liveState: SubAgentCardState?,
    onOpen: (String) -> Unit
) {
    val hasSession = item.subSessionId != null
    val agentName = item.agentName?.takeIf { it.isNotBlank() }
        ?: liveState?.agentName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.subagent_card_default_agent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasSession) {
                    Modifier.clickable { onOpen(item.subSessionId!!) }
                } else Modifier
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 12.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = subAgentRowLabel(agentName = agentName, description = item.description),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.xs))
        SubAgentStatusChip(liveState?.status)
        if (hasSession) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.subagent_card_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 行文案：代理名用正文色，描述用弱化色，拼成一行交给 ellipsis 截断。 */
@Composable
private fun subAgentRowLabel(agentName: String, description: String): AnnotatedString {
    val descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(agentName)
        if (description.isNotBlank()) {
            withStyle(SpanStyle(color = descriptionColor)) {
                append(" · ")
                append(description)
            }
        }
    }
}

/** 警告/回退提示条。 */
@Composable
private fun SubAgentWarningBanner(warning: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xs),
        color = MaterialTheme.semanticColors.warningContainer.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.semanticColors.warning.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.warning,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.onWarningContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 发出时的状态药丸：指示子代理任务已成功派发。 */
@Composable
private fun SubAgentDispatchedChip() {
    SubAgentStatusPill(
        label = stringResource(R.string.subagent_card_status_dispatched),
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 单项实时状态药丸：随子代理生命周期刷新（运行中/已完成/失败/已停止）。
 * [status] 为 null——历史消息回放、进程重启后拿不到实时状态——退回静态「已派发」，
 * 不渲染可能失真的「运行中」。
 */
@Composable
private fun SubAgentStatusChip(status: SubAgentCardStatus?) {
    if (status == null) {
        SubAgentDispatchedChip()
    } else {
        SubAgentStatusPill(label = subAgentStatusLabel(status), color = subAgentStatusColor(status))
    }
}

/** 状态文案：卡片药丸与多条派发的状态摘要共用同一套。 */
@Composable
private fun subAgentStatusLabel(status: SubAgentCardStatus): String = when (status) {
    SubAgentCardStatus.RUNNING -> stringResource(R.string.subagent_card_status_running)
    SubAgentCardStatus.COMPLETED -> stringResource(R.string.subagent_card_status_completed)
    SubAgentCardStatus.FAILED -> stringResource(R.string.subagent_card_status_failed)
    SubAgentCardStatus.STOPPED -> stringResource(R.string.subagent_card_status_stopped)
}

/** 状态色调：运行中用主色、完成用成功色、失败用错误色、已停止用弱化色。 */
@Composable
private fun subAgentStatusColor(status: SubAgentCardStatus): Color = when (status) {
    SubAgentCardStatus.RUNNING -> MaterialTheme.colorScheme.primary
    SubAgentCardStatus.COMPLETED -> MaterialTheme.semanticColors.success
    SubAgentCardStatus.FAILED -> MaterialTheme.colorScheme.error
    SubAgentCardStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 状态药丸通用形制：色点 + 文本，[color] 决定整体色调。 */
@Composable
private fun SubAgentStatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 档位 + 模型：档位是「轻量/均衡/强力」，模型是实际落地的 model id；inherit 时不显示模型。 */
@Composable
private fun modelBadge(tier: String?, model: String?): String? {
    val tierStr = tierLabel(tier)
    val modelStr = model?.takeIf { it.isNotBlank() && it != "inherit" }
    return when {
        tierStr != null && modelStr != null -> "$tierStr · $modelStr"
        tierStr != null -> tierStr
        modelStr != null -> modelStr
        else -> null
    }
}

@Composable
private fun tierLabel(tier: String?): String? = when (tier?.lowercase()) {
    "light" -> stringResource(R.string.settings_subagent_tier_light)
    "standard" -> stringResource(R.string.settings_subagent_tier_standard)
    "strong" -> stringResource(R.string.settings_subagent_tier_strong)
    else -> null
}
