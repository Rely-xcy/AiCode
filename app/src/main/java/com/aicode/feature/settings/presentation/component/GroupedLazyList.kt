package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicode.core.theme.Spacing

/** 空态项的固定 key：同一个列表里只会有一项，不需要按分组区分。 */
private const val GROUPED_EMPTY_KEY = "grouped_list_empty"

/**
 * 分组标题项的 key 前缀：条目 key 都以分组前缀开头（见 [listItemKey]），
 * 标题再套一层自己的前缀，才不会撞上某个恰好叫 "header" 的条目。
 */
private const val GROUP_HEADER_KEY_PREFIX = "grouped_header_"

/**
 * 一个分组的渲染数据。
 *
 * @param title 分组标题文案（由调用方给：记忆页带条数，技能页带项目名）。
 * @param keyPrefix 标识分组，同时是组内条目 key 的前缀，跨组同名条目因此不会撞 key。
 * @param items 该组条目；空组整组不渲染（含标题）。
 */
internal class GroupedLazySection<T>(
    val title: String,
    val keyPrefix: String,
    val items: List<T>
)

/**
 * 分组懒加载列表：若干「分组标题 + 组内各行」，行内容由调用方以 [row] 渲染。
 *
 * 记忆与技能的搜索结果页结构相同（分组标题 → 组内行 → 空态），差别只在标题文案与行组件，
 * 骨架收在一处后，任一页调间距或空态位置，另一页跟着一起变，不会各写一套慢慢长歪。
 *
 * 条目数不可控（技能实测 341 条、记忆也上百条），所以整页走 [LazyColumn] 只组合可视区的行；
 * 每行还各带一个左滑容器，一次性组合全部行的代价比普通行更高。
 *
 * 行只负责往 [LazyGroupItem] 里放调用方的 Composable：卡片圆角、行间分隔线与 cardSurface 底色
 * 由它按行位置拼出来，与整块 [SettingsGroup] 装下的分组卡片外观一致。
 *
 * 分组标题、首行与上一组之间的 8dp 间距都在这里补：原先由 Column 的 spacedBy(sm) 提供，
 * 换成懒加载后按项补，两页的留白才一样。
 *
 * @param state 调用方持有：进页滚到顶（空关键词时归零）这类滚动行为要由页面按自己的关键词决定。
 * @param sections 全部分组，顺序即渲染顺序；[items] 为空的分组整组跳过。
 * @param idOf 组内条目的稳定 id，与分组前缀一起组成 LazyColumn 的 item key。
 * @param rowContentType 行类型；两页的行布局互不兼容，用不同的值避免组合槽被跨页复用。
 * @param emptyContent 一个分组都没有时渲染的内容（空态 / 正在读取）。
 * @param row 单行内容渲染。
 */
@Composable
internal fun <T> GroupedLazyList(
    state: LazyListState,
    sections: List<GroupedLazySection<T>>,
    idOf: (T) -> String,
    rowContentType: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = Spacing.sm, bottom = Spacing.xl),
    emptyContent: @Composable () -> Unit,
    row: @Composable (T) -> Unit
) {
    // 空组不占位：一个组都没命中时整页是空态，而不是「标题下面什么都没有」。
    val visibleSections = sections.filter { it.items.isNotEmpty() }
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = contentPadding
    ) {
        if (visibleSections.isEmpty()) {
            item(key = GROUPED_EMPTY_KEY) { emptyContent() }
        }
        visibleSections.forEachIndexed { sectionIndex, section ->
            item(key = listItemKey(GROUP_HEADER_KEY_PREFIX, section.keyPrefix)) {
                if (sectionIndex == 0) {
                    SettingsGroupHeader(text = section.title)
                } else {
                    Box(modifier = Modifier.padding(top = Spacing.sm)) {
                        SettingsGroupHeader(text = section.title)
                    }
                }
            }
            groupedLazyItems(
                items = section.items,
                keyPrefix = section.keyPrefix,
                idOf = idOf,
                contentType = rowContentType,
                row = row
            )
        }
    }
}

/**
 * 往 [LazyListScope] 里放一栏分组行：每条各成一 item，外观由 [LazyGroupItem] 拼成卡片。
 *
 * key 是「分组前缀 + 条目 id」，不拼下标：删掉一条后，其余行的左滑位移状态会错位到别的条目上，
 * 看起来像在删 A、实际删的是 B。
 *
 * 首行补 8dp：分组标题与卡片之间的间距原先来自 Column 的 spacedBy(sm)，懒加载后没有容器帮忙补。
 */
internal fun <T> LazyListScope.groupedLazyItems(
    items: List<T>,
    keyPrefix: String,
    idOf: (T) -> String,
    contentType: String,
    row: @Composable (T) -> Unit
) {
    itemsIndexed(
        items = items,
        key = { _, item -> listItemKey(keyPrefix, idOf(item)) },
        contentType = { _, _ -> contentType }
    ) { index, item ->
        LazyGroupItem(
            index = index,
            lastIndex = items.lastIndex,
            modifier = if (index == 0) Modifier.padding(top = Spacing.sm) else Modifier
        ) {
            row(item)
        }
    }
}
