package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.semanticColors
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 代码块与行内代码共用字体。系统等宽（FontFamily.Monospace）缺下标字形且不回落，
// 内置 JetBrains Mono NL 经资源加载可走系统 fallback，能显示 ₀-₉ 等下标/上标字符。
private val CodeFontFamily = FontFamily(Font(R.font.jetbrains_mono_nl))

internal class MarkdownRenderCache(
    // 200 条：一屏可见约 8-12 条（超长正文按 2000 字符分块后条目更多），上下翻十几屏就到了两百上下。
    // 容量只决定「滑入视口要不要再解析一次」的成本：未命中也不再改布局（见 MarkdownLiveBlock 的
    // immediate），所以安全阈值远比以前宽。取 200 是沿用 28814c99 的实测结论（80 条时多屏回翻会集体
    // 失效、集体重解析）；该值后来在 36c49854 被改回 80，但那次提交没给理由。
    private val maxEntries: Int = 200
) {
    private val parsedStates = object : LinkedHashMap<String, MarkdownParseState.Success>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState.Success>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(text: String): MarkdownParseState.Success? = parsedStates[text]

    /**
     * 在缓存中寻找是 [text] 前缀的最长解析态。
     * 流式转落库交接瞬间，全文可能比上一帧打字机内容多出尾部字符，直接拿最长前缀 AST 兜底，
     * 确保首帧以排版好的 Markdown 呈现，彻底消灭裸纯文本（- **xxx**）闪现。
     *
     * 前缀不足 [MIN_PREFIX_CHARS] 时不用：缓存是全局 LRU，短前缀很容易是另一条消息的开头
     * （如「好的」与「好的，我来…」），会把别人的内容当成本条的历史渲染出来。
     */
    fun getBestPrefix(text: String): MarkdownParseState.Success? {
        val exact = get(text)
        if (exact != null) return exact
        var bestMatch: MarkdownParseState.Success? = null
        var bestLength = 0
        for ((key, value) in parsedStates) {
            if (key.length < MIN_PREFIX_CHARS) continue
            if (key.length in (bestLength + 1)..text.length && text.startsWith(key)) {
                bestMatch = value
                bestLength = key.length
            }
        }
        return bestMatch
    }

    fun put(state: MarkdownParseState.Success) {
        parsedStates[state.content] = state
    }

    private companion object {
        const val MIN_PREFIX_CHARS = 24
    }
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /** 解析未完成（Loading）时显示的内容；为 null 时回退显示原文纯文本（聊天流式场景）。 */
    loading: (@Composable () -> Unit)? = null,
    /** 长文本虚拟化渲染：正文改用 LazyColumn 逐块渲染（调用方需配合 heightIn 等有限高度约束
     *  才能触发懒加载），避免超大 md 一次性全量组合/测量造成的卡顿。 */
    lazyScroll: Boolean = false,
    /** 文本是否仍在流式增长：流式内容每帧都变，不能走缓存直出，也不写缓存以免挤爆 LRU。 */
    streaming: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    // markdownColor / markdownTypography / markdownPadding / markdownDimens / markdownComponents
    // 都是库提供的 @Composable 工厂函数（内部自带 remember 记忆化），不能也无需再套一层
    // remember（套了会报 “@Composable invocations can only happen from ...”）。直接调用即可。
    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact：正文、列表用 bodySmall，标题降一档，用于思考气泡等次要文本区域
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = CodeFontFamily, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = CodeFontFamily, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    val processed = remember(text) { MarkdownPreprocessor.process(text) }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val mathTransformer = remember(baseTransformer, body.fontSize.value) {
        MathImageTransformer(baseTransformer, body.fontSize.value)
    }

    // 后台预热本条消息里的所有 LaTeX 公式：在 item 首次组合时就把 measure+render 摆到后台算好
    // 写缓存，等快速 fling 掠过时主线程 getOrMeasure 直接命中、不再同步 build drawable（实测单
    // 次高达 113ms，是 fling 抽搐的直接根因）。
    val density = LocalDensity.current
    val textSizePx = with(density) { body.fontSize.toPx() }
    val colorArgb = color.toArgb()
    LaunchedEffect(processed, textSizePx, colorArgb) {
        if (!processed.contains("aicode-math-")) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            MathImageTransformer.prewarm(processed, textSizePx, colorArgb)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        // 定稿消息命中缓存时直接渲染：不创建解析状态机，滑入视口零解析成本。
        // 缓存分支与解析分支是两个独立 composable，各自持有 remember 槽位——切换时旧子树
        // 被 dispose、新子树初始化，不会出现「条件调用 remember 导致槽位错位」。
        val cachedState = if (!streaming) cache?.get(processed)?.takeIf { it.content == processed } else null
        if (cachedState != null) {
            MarkdownSuccessBlock(
                state = cachedState,
                modifier = modifier,
                lazyScroll = lazyScroll,
                mdColors = mdColors,
                mdTypography = mdTypography,
                mdPadding = mdPadding,
                mdDimens = mdDimens,
                highlightsBuilder = highlightsBuilder,
                mathTransformer = mathTransformer,
            )
        } else {
            MarkdownLiveBlock(
                text = text,
                processed = processed,
                color = color,
                modifier = modifier,
                cache = cache,
                loading = loading,
                lazyScroll = lazyScroll,
                streaming = streaming,
                // 定稿消息不做同步解析：Markdown 解析跑在主线程，一屏多条定稿消息同时未命中缓存时
                // 会把帧时间拉爆（beta d6774463 实测严重卡顿）。这里回到异步路径，靠缓存容量兜住
                // 「回翻时集体重新解析」，代价是首次进入视口仍有一次高度变化。
                immediate = false,
                mdColors = mdColors,
                mdTypography = mdTypography,
                mdPadding = mdPadding,
                mdDimens = mdDimens,
                highlightsBuilder = highlightsBuilder,
                mathTransformer = mathTransformer,
            )
        }
    }
}

/**
 * 后台预热：把已定稿的正文按顺序逐条解析进 [cache]，不产生 UI、不参与布局。
 *
 * 未命中缓存时定稿消息走同步解析（[MarkdownLiveBlock] 的 `immediate`），换来「首帧即终态高度」；
 * 而同步解析跑在主线程。预热把这份工作挤到 Dispatchers.Default：会话打开后先把最近窗口的正文
 * 解析好，主线程只在「还没轮到那条」时才付费。
 *
 * 串行推进：一次只挂一个解析状态机，完一条再喂下一条，会话里有几百条消息也不会挂几百个。
 * 队列会随消息增长重建，所以已是缓存的直接跳过；进度按 [resetKey]（会话 id）保留。
 */
@Composable
internal fun MarkdownParseWarmer(
    queue: List<String>,
    cache: MarkdownRenderCache,
    resetKey: Any?,
) {
    // 进度按会话保存：队列随消息增长会重建，从头再走一遍虽然会跳过已缓存的，但每条都要一次重组，
    // 长会话里就是几百次空转。换会话才归零。
    var index by remember(resetKey) { mutableStateOf(0) }
    if (index >= queue.size) return
    val raw = queue[index]
    // 预处理放主线程：MarkdownPreprocessor 的结果缓存是普通 LinkedHashMap（非线程安全），
    // 渲染路径就在主线程调它；异线程调会与主线程竞争。解析（真正的大头）仍然在后台。
    val text = remember(raw) { MarkdownPreprocessor.process(raw) }
    if (text.isEmpty()) {
        LaunchedEffect(text) { index++ }
        return
    }
    if (cache.get(text) != null) {
        LaunchedEffect(text) { index++ }
        return
    }
    // retainState = false：换文本就回 Loading，跟 state 变化推进队列。
    val state = rememberMarkdownState(content = text, retainState = false)
    val current by state.state.collectAsState()
    LaunchedEffect(current) {
        val settled = current
        if (settled is MarkdownParseState.Success) cache.put(settled)
        if (settled !is MarkdownParseState.Loading) index++
    }
}

/**
 * 代码块高亮渲染：等价于库的 `MarkdownHighlightedCode`，但在把高亮区间写进 AnnotatedString
 * 之前先校验范围。highlights 引擎对部分内容会给出 start > end 的区间，库原实现直接调用
 * addStyle，AnnotatedString.Range 构造随即抛 "Reversed range is not supported"；该计算跑在
 * 库自己的后台协程里（produceState + Dispatchers.Default），异常直达线程默认处理器，
 * 调用方 try/catch 拦不住，表现为聊天页闪退。越界（end > code.length）同样会导致崩溃，
 * 一并丢弃。
 */
/**
 * 代码高亮结果的进程级缓存。高亮本身跑在 Dispatchers.Default（不卡主线程），但无跨视口
 * 缓存时 item 每次重进视口都会重跑高亮，完成后又触发一次主线程重组（从纯文本切到
 * 高亮文本）。fling 快速滚过大量代码块时这会挤满 Default 线程并制造密集重组。命中缓存
 * 时直接同步拿到结果作为 produceState 初值，既不重算也不闪纯文本。key 含 isDark（主题影响颜色）。
 */
private object CodeHighlightCache {
    private data class Key(val code: String, val language: String?, val dark: Boolean)

    private const val MAX = 128
    private val cache = object : LinkedHashMap<Key, AnnotatedString>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AnnotatedString>?): Boolean = size > MAX
    }

    fun get(code: String, language: String?, dark: Boolean): AnnotatedString? =
        synchronized(cache) { cache[Key(code, language, dark)] }

    fun put(code: String, language: String?, dark: Boolean, value: AnnotatedString) {
        synchronized(cache) { cache[Key(code, language, dark)] = value }
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cached = CodeHighlightCache.get(code, language, isDark)
    val highlighted: AnnotatedString by produceState(
        initialValue = cached ?: AnnotatedString(code),
        code,
        language,
        isDark,
    ) {
        if (CodeHighlightCache.get(code, language, isDark) != null) return@produceState
        value = withContext(Dispatchers.Default) {
            buildHighlightedText(code, language, highlightsBuilder)
        }.also { CodeHighlightCache.put(code, language, isDark, it) }
    }

    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = highlighted,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

private fun buildHighlightedText(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    // 高亮引擎本身也可能抛错（语言解析等），失败时退回纯文本，不让它影响消息渲染。
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        highlightsBuilder.code(code)
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
    }.getOrDefault(emptyList())

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || start >= end || end > code.length) continue
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(spanStyle, start, end)
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}

/** 把一份已解析的 Markdown AST 渲染出来；缓存命中与解析完成共用同一条渲染路径。 */
@Composable
private fun MarkdownSuccessBlock(
    state: MarkdownParseState.Success,
    modifier: Modifier,
    lazyScroll: Boolean,
    mdColors: MarkdownColors,
    mdTypography: MarkdownTypography,
    mdPadding: MarkdownPadding,
    mdDimens: MarkdownDimens,
    highlightsBuilder: Highlights.Builder,
    mathTransformer: ImageTransformer,
) {
    val successRenderer: @Composable (
        state: MarkdownParseState.Success,
        components: MarkdownComponents,
        modifier: Modifier
    ) -> Unit = remember(lazyScroll) {
        if (lazyScroll) {
            { s, components, m ->
                LazyColumn(modifier = m) {
                    items(
                        items = s.node.children,
                        key = { node -> node.startOffset },
                        contentType = { node -> node.type }
                    ) { node ->
                        key(node) {
                            MarkdownElement(node, components, s.content)
                        }
                    }
                }
            }
        } else {
            { s, components, m ->
                MarkdownSuccess(state = s, components = components, modifier = m)
            }
        }
    }

    val mdComponents = markdownComponents(
        codeFence = {
            MarkdownCodeFence(
                content = it.content,
                node = it.node,
            ) { code, language, style ->
                SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
            }
        },
        codeBlock = {
            MarkdownCodeBlock(
                content = it.content,
                node = it.node,
            ) { code, language, style ->
                SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
            }
        },
        table = {
            MarkdownTable(
                content = it.content,
                node = it.node,
                style = it.typography.table,
                headerBlock = { content, header, tableWidth, style ->
                    MarkdownTableHeader(
                        content = content,
                        header = header,
                        tableWidth = tableWidth,
                        style = style,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                },
                rowBlock = { content, header, tableWidth, style ->
                    MarkdownTableRow(
                        content = content,
                        header = header,
                        tableWidth = tableWidth,
                        style = style,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                },
            )
        },
    )

    Markdown(
        state = state,
        modifier = modifier,
        colors = mdColors,
        typography = mdTypography,
        padding = mdPadding,
        dimens = mdDimens,
        animations = markdownAnimations(animateTextSize = { this }),
        imageTransformer = mathTransformer,
        components = mdComponents,
        success = successRenderer,
    )
}

/** 解析路径：流式增长或缓存未命中时走 Markdown 状态机，解析成功后写缓存。 */
@Composable
private fun MarkdownLiveBlock(
    text: String,
    processed: String,
    color: Color,
    modifier: Modifier,
    cache: MarkdownRenderCache?,
    loading: (@Composable () -> Unit)?,
    lazyScroll: Boolean,
    streaming: Boolean,
    /**
     * 首帧是否同步解析。
     *
     * 定稿消息传 true：占位（裸文本）与排版后的高度差得远——表格、代码块、图片都会换行高，
     * 先按占位排一版再换回去，就是「消息滑入视口时内容整体位移一下」。同步解析让第一帧就是
     * 终态高度，代价是这个 item 首次出现时解析跑在主线程上；预热（MarkdownParseWarmer）会
     * 把大部分未命中提前吃掉，真正落到这里的很少。
     *
     * 流式文本必须传 false：它每帧都在变，同步解析等于每帧卡主线程。
     */
    immediate: Boolean,
    mdColors: MarkdownColors,
    mdTypography: MarkdownTypography,
    mdPadding: MarkdownPadding,
    mdDimens: MarkdownDimens,
    highlightsBuilder: Highlights.Builder,
    mathTransformer: ImageTransformer,
) {
    val mdState = rememberMarkdownState(content = processed, retainState = true, immediate = immediate)
    val parseState by mdState.state.collectAsState()
    val currentState = parseState
    val parsedState: MarkdownParseState = when {
        currentState is MarkdownParseState.Success && currentState.content == processed -> currentState
        else -> currentState
    }

    // Loading 期间的渲染兜底：优先用上一次成功解析的结果，杜绝裸纯文本闪现。
    val prefixFallback = cache?.getBestPrefix(processed)
    var lastSuccessState by remember { mutableStateOf<MarkdownParseState.Success?>(prefixFallback) }
    if (parsedState is MarkdownParseState.Success) {
        lastSuccessState = parsedState
        // 流式不写缓存：逐字增量会把 LRU 挤爆，反而把定稿消息的缓存全淘掉。
        if (!streaming) cache?.put(parsedState)
    }

    val renderState: MarkdownParseState.Success? = when (parsedState) {
        is MarkdownParseState.Success -> parsedState
        is MarkdownParseState.Loading -> lastSuccessState ?: prefixFallback
        is MarkdownParseState.Error -> null
    }

    if (renderState != null) {
        MarkdownSuccessBlock(
            state = renderState,
            modifier = modifier,
            lazyScroll = lazyScroll,
            mdColors = mdColors,
            mdTypography = mdTypography,
            mdPadding = mdPadding,
            mdDimens = mdDimens,
            highlightsBuilder = highlightsBuilder,
            mathTransformer = mathTransformer,
        )
    } else if (loading != null) {
        loading()
    } else {
        PlainMarkdownText(text = text, color = color, modifier = modifier)
    }
}
