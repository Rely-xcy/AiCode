package com.aicode.core.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端轮询的纯逻辑：命令拼装、输出解析、能力探测。
 * 这三块决定了「远端变更判得准不准」与「失败时会不会误报」，单独测比连着 SSH 测更可控。
 */
class RemoteFileWatchTest {

    private fun dirLine(dir: String) = "$SCAN_DIR_PREFIX$dir\n"

    private fun resultLine(dir: String, rc: String) = "$SCAN_RESULT_PREFIX$dir\t$rc\n"

    private fun entryLine(name: String, mtime: String = "1700000000", size: String = "12") =
        "./$name|$mtime|$size|regular file\n"

    private fun endLine() = "$SCAN_END_MARKER\n"

    @Test
    fun buildRemoteScanCommand_covers_every_dir_in_one_command_with_quoted_paths() {
        val command = buildRemoteScanCommand(
            listOf(RemoteScanDir("/srv/my proj", 1), RemoteScanDir("/srv/it's", 4)),
            probe = true
        )

        // 一次往返：只有一个能力探测、两个目录块、一个结束标记
        assertEquals(1, command.split("printf 'P\t").size - 1)
        assertEquals(2, command.split("printf 'D\t").size - 1)
        assertEquals(2, command.split("printf 'R\t").size - 1)
        assertTrue(command.endsWith("printf 'END\\n'"))
        // 目录名进 shell 前必须被单引号包住（含空格的路径不加引号会被拆成两个参数）
        assertTrue(command.contains("'/srv/my proj'"))
        assertTrue(command.contains("'/srv/it'\\''s'"))
        // 深度直接进 find，非递归订阅只看直接子项
        assertTrue(command.contains("-maxdepth 1 "))
        assertTrue(command.contains("-maxdepth 4 "))
    }

    @Test
    fun buildRemoteScanCommand_omits_probe_when_not_first_tick() {
        val command = buildRemoteScanCommand(listOf(RemoteScanDir("/srv/p", 1)), probe = false)

        assertFalse(command.contains("printf 'P\t"))
        assertTrue(command.contains("printf 'D\t"))
    }

    @Test
    fun parseRemoteScanOutput_reads_entries_and_strips_relative_prefix() {
        val output = dirLine("/srv/p") + resultLine("/srv/p", "0") +
            entryLine("a.txt", mtime = "1700000001", size = "3") +
            entryLine("src", mtime = "1700000002", size = "4096") +
            endLine()

        val scans = parseRemoteScanOutput(output, setOf("/srv/p"))

        val scan = scans.getValue("/srv/p")
        assertFalse(scan.overflow)
        assertEquals("1700000001|3|regular file", scan.entries["a.txt"])
        assertEquals("1700000002|4096|regular file", scan.entries["src"])
    }

    @Test
    fun parseRemoteScanOutput_keeps_nested_paths_for_recursive_scans() {
        val output = dirLine("/srv/skills") + resultLine("/srv/skills", "0") +
            entryLine("demo/SKILL.md") +
            endLine()

        val scans = parseRemoteScanOutput(output, setOf("/srv/skills"))

        assertEquals(setOf("demo/SKILL.md"), scans.getValue("/srv/skills").entries.keys)
    }

    @Test
    fun parseRemoteScanOutput_drops_dir_whose_find_failed() {
        // cd 失败 / stat 不可用：退出码非 0 且清单为空。若照收，整目录的文件会被判成「都被删了」。
        val output = dirLine("/srv/p") + resultLine("/srv/p", "1") + endLine()

        val scans = parseRemoteScanOutput(output, setOf("/srv/p"))

        assertNull(scans["/srv/p"])
    }

    @Test
    fun parseRemoteScanOutput_drops_everything_when_end_marker_missing() {
        // 命令没跑完（断线 / 超时被杀）：只拿到前半个目录，拿它差分同样会误报删除。
        val output = dirLine("/srv/p") + resultLine("/srv/p", "0") + entryLine("a.txt")

        assertTrue(parseRemoteScanOutput(output, setOf("/srv/p")).isEmpty())
    }

    @Test
    fun parseRemoteScanOutput_ignores_malformed_lines_but_keeps_valid_block() {
        val output = dirLine("/srv/p") + resultLine("/srv/p", "0") +
            entryLine("good.txt") +
            "…[输出过长，已省略中间 12000 个字符]…\n" +
            "./bad|only-three\n" +
            "no-prefix|1700000000|1|regular file\n" +
            "./\n" +
            entryLine("also-good.txt") +
            endLine()

        val entries = parseRemoteScanOutput(output, setOf("/srv/p")).getValue("/srv/p").entries

        assertEquals(setOf("good.txt", "also-good.txt"), entries.keys)
    }

    @Test
    fun parseRemoteScanOutput_caps_window_and_flags_overflow() {
        val body = StringBuilder(dirLine("/big") + resultLine("/big", "0"))
        repeat(REMOTE_SCAN_MAX_ENTRIES + 1) { index -> body.append(entryLine("f${"%03d".format(index)}.txt")) }
        body.append(endLine())

        val scan = parseRemoteScanOutput(body.toString(), setOf("/big")).getValue("/big")

        assertTrue(scan.overflow)
        assertEquals(REMOTE_SCAN_MAX_ENTRIES, scan.entries.size)
        assertTrue(scan.entries.containsKey("f000.txt"))
        assertFalse(scan.entries.containsKey("f${"%03d".format(REMOTE_SCAN_MAX_ENTRIES)}.txt"))
    }

    @Test
    fun parseRemoteScanOutput_skips_dirs_that_were_not_requested() {
        val output = dirLine("/other") + resultLine("/other", "0") + entryLine("a.txt") + endLine()

        assertTrue(parseRemoteScanOutput(output, setOf("/srv/p")).isEmpty())
    }

    @Test
    fun parseRemoteScanOutput_handles_several_dirs_in_one_output() {
        val output = dirLine("/a") + resultLine("/a", "0") + entryLine("x.txt") +
            dirLine("/b") + resultLine("/b", "1") +
            dirLine("/c") + resultLine("/c", "0") + entryLine("y.txt") + endLine()

        val scans = parseRemoteScanOutput(output, setOf("/a", "/b", "/c"))

        assertEquals(setOf("x.txt"), scans.getValue("/a").entries.keys)
        assertNull(scans["/b"])
        assertEquals(setOf("y.txt"), scans.getValue("/c").entries.keys)
    }

    @Test
    fun remoteScanCapabilityOk_requires_stat_fields_and_find() {
        assertTrue(
            remoteScanCapabilityOk(
                "P\t2026-10-03 13:55:10|directory|3452\t/usr/bin/find\nEND\n"
            )
        )
        // stat 缺失（结果行里 find 也没有）→ 停用
        assertFalse(remoteScanCapabilityOk("P\t\t\nEND\n"))
        // find 缺失 → 停用
        assertFalse(remoteScanCapabilityOk("P\t1700000000|4096|directory\t\nEND\n"))
        // stat 的 %F 不支持（段数不对）→ 停用
        assertFalse(remoteScanCapabilityOk("P\t1700000000|4096\t/usr/bin/find\nEND\n"))
        // 连探测行都没有 → 停用
        assertFalse(remoteScanCapabilityOk("END\n"))
    }
}
