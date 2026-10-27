package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.provider.ReasoningProtocol
import com.aicode.feature.agent.domain.provider.ReasoningResolver
import com.aicode.feature.agent.domain.provider.preferredOnLevel
import com.aicode.feature.agent.domain.provider.selectableLevelsForUi
import com.aicode.feature.agent.domain.provider.showsThinkingToggle
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import com.aicode.feature.settings.presentation.component.ModelSelectionSheet
import kotlinx.coroutines.launch

/**
 * 输入区下行的模型切换图标按钮
 */
@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            },
            runModelTest = runModelTest
        )
    }
}

/**
 * 思考强度选择器：图标按钮 → 底部弹层，「思考」总开关 + 只列模型真实支持的档位。
 *
 * 档位集合与「实际生效」全部来自 [ReasoningResolver]，与请求侧完全同源：
 * 最高只到「高」的模型不会再列出「最高」，思考恒开 / 端点不认「关闭」时也会明确提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    capability: ReasoningCapability,
    protocol: ReasoningProtocol,
    model: String,
    onChange: (ReasoningEffort) -> Unit,
    enabled: Boolean
) {
    var showSheet by remember { mutableStateOf(false) }
    // 与实际请求同源：这里算出的「实际生效档位」就是适配器接下来会发出的档位。
    val plan = remember(effort, capability, protocol, model) {
        ReasoningResolver.plan(protocol, model, effort.apiValue, capability)
    }
    val levels = remember(capability) { capability.selectableLevelsForUi() }
    val thinkingOn = effort != ReasoningEffort.NONE
    // 环弧长与滑轨都跟着「实际会生效」的档位走：被降级时画降完那一档，关得掉时是空环。
    val shownEffort = if (thinkingOn) plan.effectiveLevel ?: effort else ReasoningEffort.NONE
    val topLevel = levels.lastOrNull()
    val atTop = topLevel != null && levelIndex(levels, shownEffort) == levels.lastIndex
    // 点闪电跳到最高档后，再点一次回到跳之前那一档。
    var levelBeforeTop by remember(levels) { mutableStateOf<ReasoningEffort?>(null) }
    val sectionLabel = stringResource(R.string.chat_reasoning_effort)

    // 闪电本身就是入口：有可用档位时外圈画强度环，一个档位都没有时只留闪电。
    // 先 clip 再 clickable，否则 ripple 按矩形画，点上去是个方块。
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = sectionLabel }
            .clickable(enabled = enabled) { showSheet = true },
        contentAlignment = Alignment.Center
    ) {
        ReasoningRingBolt(levels = levels, effort = shownEffort, enabled = enabled)
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (topLevel != null) {
                        ReasoningTopLevelButton(
                            atTop = atTop,
                            enabled = enabled,
                            description = stringResource(R.string.chat_reasoning_effort_max),
                            onClick = {
                                if (atTop) {
                                    onChange(
                                        levelBeforeTop
                                            ?: levels.getOrNull(levels.lastIndex - 1)
                                            ?: topLevel
                                    )
                                } else {
                                    levelBeforeTop = effort
                                    onChange(topLevel)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (capability.showsThinkingToggle()) {
                        ReasoningThinkingPill(
                            label = stringResource(R.string.chat_reasoning_toggle),
                            checked = thinkingOn,
                            enabled = enabled,
                            onCheckedChange = { on ->
                                onChange(
                                    if (on) {
                                        capability.preferredOnLevel() ?: ReasoningEffort.MEDIUM
                                    } else {
                                        ReasoningEffort.NONE
                                    }
                                )
                            }
                        )
                    }
                }

                // 想关却关不掉：滑轨不藏，改画「实际会发的那一档」，否则用户会以为思考真停了。
                val cannotDisable = plan.reason == ReasoningPlan.Reason.CANNOT_DISABLE
                if (levels.isNotEmpty() && (thinkingOn || cannotDisable)) {
                    val trackIndex = levelIndex(levels, shownEffort).coerceAtLeast(0)
                    val levelLabel = levels.getOrNull(trackIndex)?.shortLabel() ?: shownEffort.shortLabel()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReasoningLevelTrack(
                            levelCount = levels.size,
                            selectedIndex = trackIndex,
                            enabled = enabled,
                            description = sectionLabel,
                            onPick = { index ->
                                levels.getOrNull(index)?.let { picked ->
                                    if (picked != effort) onChange(picked)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = levelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (thinkingOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            },
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 44.dp)
                        )
                    }
                }

                ReasoningHintLines(plan = plan, capability = capability)
            }
        }
    }
}

/** 弹层顶部的说明行：未识别 / 关不掉 / 被降级 / 端点可能忽略，各自只出现一条。 */
@Composable
private fun ReasoningHintLines(plan: ReasoningPlan, capability: ReasoningCapability) {
    if (!capability.isKnown) {
        ReasoningHint(stringResource(R.string.chat_reasoning_unknown_hint))
    }
    when (plan.reason) {
        ReasoningPlan.Reason.CANNOT_DISABLE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_cannot_disable))
        ReasoningPlan.Reason.ENDPOINT_MAY_IGNORE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_endpoint_weak))
        ReasoningPlan.Reason.CLAMPED_DOWN, ReasoningPlan.Reason.CLAMPED_UP ->
            plan.effectiveLevel?.let {
                ReasoningHint(stringResource(R.string.chat_reasoning_effective, it.withApiLabel()))
            }
        else -> Unit
    }
}

@Composable
private fun ReasoningHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

/** 档位显示名：中文 + 对应接口值，便于对照模型文档与报错文案（如「最高（max）」）。 */
@Composable
private fun ReasoningEffort.withApiLabel(): String =
    stringResource(R.string.common_label_with_api, stringResource(labelRes()), apiValue)

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> com.aicode.R.string.chat_reasoning_effort_none
    ReasoningEffort.MINIMAL -> com.aicode.R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> com.aicode.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aicode.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aicode.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aicode.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aicode.R.string.chat_reasoning_effort_max
}

/**
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 传入 [runModelTest] 时每个模型行提供「测试」按钮（不显示选中小勾）。测试态与结果按 provider+模型
 * 在弹层内持有：跨 provider 的同名模型不会串结果，弹层关闭随作用域取消在途请求。
 */
@Composable
internal fun ModelSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    val testScope = rememberCoroutineScope()
    var testingModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var testResults by remember { mutableStateOf<Map<String, ModelTestResult>>(emptyMap()) }

    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss,
        onTestModel = runModelTest?.let { runner ->
            { provider, model ->
                val key = modelMetadataKey(provider.id, model)
                testingModels = testingModels + key
                testResults = testResults - key
                testScope.launch {
                    val result = runner(provider, model)
                    testResults = testResults + (key to result)
                    testingModels = testingModels - key
                }
            }
        },
        testingModels = testingModels,
        testResults = testResults
    )
}
