package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件账本提取：
 *
 * - 跨工具：readFile / writeFile / editFile 的路径，search / list 的目录；
 * - 去重：同一路径多次访问只留一条，操作类型取最后一次；
 * - 排序：按最近访问从新到旧；
 * - search 的第一个定位参数是 pattern，不能被当成目录。
 */
class FileLedgerTest {

    private fun call(name: String, vararg args: Pair<String, String>): ToolCall =
        ToolCall(
            id = "call-${name}-${args.joinToString { it.first }}",
            name = name,
            arguments = args.associate { (key, value) -> key to JsonPrimitive(value) }
        )

    private fun assistant(vararg calls: ToolCall): AgentMessage.AssistantMessage =
        AgentMessage.AssistantMessage(content = "", toolCalls = calls.toList())

    @Test
    fun `跨工具提取去重后按最近访问排序`() {
        val messages = listOf(
            assistant(call("readFile", "path" to "~/workspace/a.kt")),
            assistant(call("writeFile", "path" to "~/workspace/b.kt")),
            assistant(call("editFile", "path" to "~/workspace/b.kt")),
            assistant(call("list", "args" to "-la ~/workspace/app")),
            assistant(call("search", "args" to "-n \"fun main\" ~/workspace/app"))
        )

        val ledger = buildFileLedger(messages)

        // 最近访问在最前；同一目录的 list/search 合并为一条，操作取最后一次（search）
        assertEquals(
            listOf("~/workspace/app", "~/workspace/b.kt", "~/workspace/a.kt"),
            ledger.map { it.path }
        )
        assertEquals(FileLedgerOperation.SEARCH, ledger[0].operation)
        assertEquals(FileLedgerOperation.EDIT, ledger[1].operation)
        assertEquals(FileLedgerOperation.READ, ledger[2].operation)
    }

    @Test
    fun `search 用 -e 给 pattern 时路径不会被丢掉`() {
        val messages = listOf(
            assistant(call("search", "args" to "-e TODO ~/workspace/app"))
        )

        assertEquals(listOf("~/workspace/app"), buildFileLedger(messages).map { it.path })
    }

    @Test
    fun `search 的 pattern 与选项值不会被当成目录`() {        val messages = listOf(
            assistant(
                call(
                    "search",
                    "args" to "-g \"*.kt\" TODO ~/workspace/app"
                )
            )
        )

        val ledger = buildFileLedger(messages)

        assertEquals(listOf("~/workspace/app"), ledger.map { it.path })
    }

    @Test
    fun `工具结果里的路径不参与账本`() {
        val messages = listOf(
            AgentMessage.ToolResultMessage(
                id = "t1",
                toolName = "readFile",
                result = "看到 ~/workspace/ghost.kt 但这不是访问"
            )
        )

        assertTrue(buildFileLedger(messages).isEmpty())
    }

    @Test
    fun `渲染固定小节包含操作类型与路径`() {
        val entries = listOf(
            FileLedgerEntry("~/workspace/b.kt", FileLedgerOperation.EDIT),
            FileLedgerEntry("~/workspace/a.kt", FileLedgerOperation.READ)
        )

        val rendered = renderFileLedger(entries)

        assertTrue(rendered.startsWith(FILE_LEDGER_HEADING))
        assertTrue(rendered.contains("- 改：~/workspace/b.kt"))
        assertTrue(rendered.contains("- 读：~/workspace/a.kt"))
        assertEquals("", renderFileLedger(emptyList()))
    }
}
