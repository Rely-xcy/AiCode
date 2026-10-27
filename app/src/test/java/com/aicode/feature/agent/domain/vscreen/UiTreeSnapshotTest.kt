package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面指纹的纯逻辑测试：跨 limit 可比、内容变化能识别、覆盖深度不足时不当对照。
 */
class UiTreeSnapshotTest {

    private fun node(
        text: String? = null,
        bounds: String = "0,0-100,100",
        clickable: Boolean = false
    ) = UiNode(
        depth = 0,
        className = "Button",
        text = text,
        contentDescription = null,
        viewId = null,
        bounds = bounds,
        clickable = clickable,
        focusable = false,
        editable = false,
        password = false
    )

    private fun nodes(count: Int, prefix: String = "item"): List<UiNode> =
        (1..count).map { node(text = "$prefix$it") }

    @Test
    fun `节点数相同但内容不同必须算变化`() {
        val before = UiTreeSnapshot.of(listOf(node(text = "登录")), limit = 40)
        val after = UiTreeSnapshot.of(listOf(node(text = "注册")), limit = 40)

        assertEquals(before.nodeCount, after.nodeCount)
        assertNotEquals("节点数一样、内容变了，指纹不能相同", before.hash, after.hash)
        assertFalse(before.sameUiAs(after))
    }

    @Test
    fun `坐标与可点性变化也算变化`() {
        val base = UiTreeSnapshot.of(listOf(node(text = "确定", bounds = "0,0-100,100")), limit = 40)
        val moved = UiTreeSnapshot.of(listOf(node(text = "确定", bounds = "0,0-100,150")), limit = 40)
        val clickable = UiTreeSnapshot.of(
            listOf(node(text = "确定", bounds = "0,0-100,100", clickable = true)),
            limit = 40
        )

        assertNotEquals(base.hash, moved.hash)
        assertNotEquals(base.hash, clickable.hash)
    }

    @Test
    fun `观察与对比读取的前缀相同则指纹相同`() {
        val all = nodes(200)
        val fromObserve = UiTreeSnapshot.of(all, limit = 200)
        val fromCompare = UiTreeSnapshot.of(all.take(UiTreeSnapshot.COMPARE_NODES), limit = UiTreeSnapshot.COMPARE_NODES)

        assertEquals("两种 limit 取的是同一棵树的前缀，必须能直接比较", fromObserve.hash, fromCompare.hash)
        assertTrue(fromObserve.sameUiAs(fromCompare))
    }

    @Test
    fun `截断且不足对比深度的快照不能当对照`() {
        val shortPrefix = UiTreeSnapshot.of(nodes(10), limit = 10)
        assertTrue(shortPrefix.truncated)
        assertFalse("只有 10 个节点的前缀，跟 40 节点的读取不可比", shortPrefix.coversComparePrefix)

        val complete = UiTreeSnapshot.of(nodes(10), limit = 40)
        assertFalse(complete.truncated)
        assertTrue("整棵树本来就只有 10 个节点，属于完整读取", complete.coversComparePrefix)
    }

    @Test
    fun `对比深度之外的节点不进指纹`() {
        val base = nodes(UiTreeSnapshot.COMPARE_NODES)
        val tail = base + node(text = "屏幕下方的变化")
        val before = UiTreeSnapshot.of(base, limit = UiTreeSnapshot.COMPARE_NODES)
        val after = UiTreeSnapshot.of(tail, limit = UiTreeSnapshot.COMPARE_NODES)

        assertNotEquals(before.nodeCount, after.nodeCount)
        assertTrue("第 41 个节点起不进指纹，这是 40 节点上限的既定取舍", before.sameUiAs(after))
    }
}
