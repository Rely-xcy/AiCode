package com.aicode.feature.workspace.presentation.component

import com.aicode.feature.workspace.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区列表的行键：必须唯一，且不能用显示名。
 *
 * 回归点：外部目录名转不出合法名时会回退成 `default`，两个这样的工作区同时在列时，
 * LazyColumn 以 `it.name` 作 key 会直接抛 "Key \"default\" was already used" 崩溃
 * （真机日志见工作区顶层 log.txt，聊天页远程 SSH 模式下点开工作区列表即崩）。
 */
class WorkspaceRowsTest {

    private fun ws(name: String, path: String) = Workspace(name = name, path = path)

    @Test
    fun 同名不同路径_两行且键不同() {
        val rows = distinctWorkspaceRows(
            listOf(
                ws("default", "/srv/a"),
                ws("default", "/srv/b")
            )
        )

        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.rowKey }.toSet().size)
    }

    @Test
    fun 完全相同的重复条目_只留一行() {
        val rows = distinctWorkspaceRows(
            listOf(
                ws("default", "/srv/a"),
                ws("default", "/srv/a")
            )
        )

        assertEquals(1, rows.size)
    }

    @Test
    fun 路径为空时退回名称作键_仍保持唯一() {
        val rows = distinctWorkspaceRows(
            listOf(
                ws("default", ""),
                ws("other", "")
            )
        )

        assertEquals(2, rows.size)
        assertTrue(rows.none { it.rowKey.isBlank() })
        assertEquals(2, rows.map { it.rowKey }.toSet().size)
    }
}
