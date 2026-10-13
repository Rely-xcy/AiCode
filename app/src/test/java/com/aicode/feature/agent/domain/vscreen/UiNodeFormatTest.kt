package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控件树文本化的纯逻辑测试（不依赖 Android）。
 */
class UiNodeFormatTest {

    private fun node(
        depth: Int = 0,
        className: String = "Button",
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        bounds: String = "0,0-100,100",
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false
    ) = UiNode(
        depth = depth,
        className = className,
        text = text,
        contentDescription = desc,
        viewId = id,
        bounds = bounds,
        clickable = clickable,
        focusable = false,
        editable = editable,
        password = password
    )

    @Test
    fun `节点按层级缩进并带坐标与可点标记`() {
        val text = formatUiNodes(
            listOf(
                node(className = "FrameLayout"),
                node(depth = 1, className = "Button", text = "登录", bounds = "10,20-110,80", clickable = true),
            ),
            limit = 100
        )
        val lines = text.split("\n")
        assertTrue(lines[0].startsWith("[0] FrameLayout"))
        assertTrue("子节点应缩进", lines[1].startsWith(" [1] Button"))
        assertTrue(lines[1].contains("text=\"登录\""))
        assertTrue(lines[1].contains("bounds=10,20-110,80"))
        assertTrue(lines[1].contains("clickable"))
    }

    @Test
    fun `密码框内容被省略但结构保留`() {
        val text = formatUiNodes(
            listOf(node(className = "EditText", text = "secret123", password = true, editable = true)),
            limit = 100
        )
        assertTrue(text.contains("密码框"))
        assertFalse("密码内容不得出现在输出里", text.contains("secret123"))
    }

    @Test
    fun `viewId 只保留资源名部分`() {
        val text = formatUiNodes(
            listOf(node(id = "com.example.app:id/login_button")),
            limit = 100
        )
        assertTrue(text.contains("id=login_button"))
        assertFalse(text.contains("com.example.app"))
    }

    @Test
    fun `达到上限时给出提示`() {
        val nodes = (1..3).map { node(text = "i$it") }
        val text = formatUiNodes(nodes, limit = 3)
        assertTrue(text.contains("节点上限"))
    }

    @Test
    fun `空列表返回明确说明`() {
        assertTrue(formatUiNodes(emptyList(), limit = 10).contains("未取到任何节点"))
    }
}
