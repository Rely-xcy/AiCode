package com.aicode.feature.agent.domain.container

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量读取协议的构造与解析：命令侧的引号 / 注入安全，解析侧对各类畸形输出的取舍。
 * 这里不连远端：命令按字符串校验，解析直接喂文本。
 */
class RemoteSkillBulkCommandsTest {

    private val roots = mapOf(
        "SG" to "/home/u/.aicode/skills",
        "SP" to "/srv/ws/.aicode/skills"
    )

    private fun b64(content: String): String =
        Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))

    private fun fileLines(path: String, content: String, declaredSize: Int = content.toByteArray().size): String =
        "F\t$path\t$declaredSize\n" + b64(content).chunked(76).joinToString("") { "C\t$it\n" }

    // ── 命令构造 ──────────────────────────────────────────

    @Test
    fun command_quotesRemotePathSoInjectionStaysLiteral() {
        val path = "/tmp/it's a \$(touch /tmp/pwned) `whoami`"
        val command = buildRemoteSkillBulkCommand(
            listOf(RemoteBulkRoot("SG", path, 4, RemoteBulkRootKind.SKILLS))
        )

        assertTrue(command.contains(remoteShellQuote(path)))
        // 引号被转义成 '\'' 之后，注入串整体仍在单引号里，不会成为命令替换。
        assertTrue(command.contains("'\"'\"'"))
        assertTrue(command.contains("if [ ! -d " + remoteShellQuote(path) + " ]"))
    }

    @Test
    fun command_carriesProbeDepthsFiltersAndEndMarker() {
        val command = buildRemoteSkillBulkCommand(
            listOf(
                RemoteBulkRoot("SG", "/home/u/.aicode/skills", 4, RemoteBulkRootKind.SKILLS),
                RemoteBulkRoot("AG", "/home/u/.aicode/agents", 1, RemoteBulkRootKind.AGENTS)
            )
        )

        assertTrue(command.startsWith("if ! command -v base64 >/dev/null 2>&1"))
        assertTrue(command.contains("exit $BULK_MISSING_TOOL_EXIT"))
        assertTrue(command.contains("printf 'B\\t%s\\n' 'SG'"))
        assertTrue(command.contains("printf 'B\\t%s\\n' 'AG'"))
        assertTrue(command.contains("-maxdepth 4 -mindepth 1"))
        assertTrue(command.contains("-maxdepth 1 -mindepth 1"))
        assertTrue(command.contains("\\( -iname 'SKILL.md' -o -iname 'CLAUDE.md' \\)"))
        assertTrue(command.contains("-iname '*.md'"))
        // 缺工具的退出码之外，正常路径必须以 END 收尾，否则解析侧整批作废。
        assertTrue(command.endsWith("printf 'END\\n'"))
    }

    @Test
    fun command_emptyRootsStillEndsWithMarker() {
        assertEquals("printf 'END\\n'", buildRemoteSkillBulkCommand(emptyList()).substringAfter("fi; "))
    }

    // ── 解析 ──────────────────────────────────────────────

    @Test
    fun parse_normalOutputReturnsFilesPerTag() {
        val output = buildString {
            append("B\tSG\n")
            append(fileLines("/home/u/.aicode/skills/a/SKILL.md", "---\nname: a\n---\n正文"))
            append(fileLines("/home/u/.aicode/skills/SKILL.md", "根"))
            append("D\t0\n")
            append("B\tSP\n")
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertEquals(setOf("SKILL.md", "a/SKILL.md"), parsed.getValue("SG").keys)
        assertEquals("---\nname: a\n---\n正文", parsed.getValue("SG").getValue("a/SKILL.md"))
        assertEquals("根", parsed.getValue("SG").getValue("SKILL.md"))
        // 读到但没文件：空 map（与「这个根不可用」的缺席不同）。
        assertTrue(parsed.containsKey("SP"))
        assertTrue(parsed.getValue("SP").isEmpty())
    }

    @Test
    fun parse_missingEndMarkerRejectsWholeBatch() {
        val output = "B\tSG\n" + fileLines("/home/u/.aicode/skills/SKILL.md", "x") + "D\t0\n"

        assertNull(parseRemoteSkillBulkOutput(output, roots))
    }

    @Test
    fun parse_failedRootDroppedOthersKept() {
        val output = buildString {
            append("B\tSG\n")
            append(fileLines("/home/u/.aicode/skills/SKILL.md", "x"))
            append("D\t1\n")
            append("B\tSP\n")
            append(fileLines("/srv/ws/.aicode/skills/SKILL.md", "y"))
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertFalse(parsed.containsKey("SG"))
        assertEquals("y", parsed.getValue("SP").getValue("SKILL.md"))
    }

    @Test
    fun parse_sizeMismatchDropsOnlyThatFile() {
        val output = buildString {
            append("B\tSG\n")
            append("F\t/home/u/.aicode/skills/broken.md\t999\n")
            append("C\t").append(b64("短")).append('\n')
            append(fileLines("/home/u/.aicode/skills/SKILL.md", "ok"))
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertEquals(setOf("SKILL.md"), parsed.getValue("SG").keys)
    }

    @Test
    fun parse_dirtyBase64DropsFileInsteadOfGarblingContent() {
        val output = buildString {
            append("B\tSG\n")
            append("F\t/home/u/.aicode/skills/dirty.md\t10\n")
            append("C\t%%%not-base64%%%\n")
            append("F\t/home/u/.aicode/skills/short.md\t10\n")
            append("C\tQUJD\n")
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertTrue(parsed.getValue("SG").isEmpty())
    }

    @Test
    fun parse_emptyFileKeptAsEmptyContent() {
        val output = "B\tSG\nF\t/home/u/.aicode/skills/empty/SKILL.md\t0\nD\t0\nEND\n"

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertEquals("", parsed.getValue("SG").getValue("empty/SKILL.md"))
    }

    @Test
    fun parse_unknownTagAndForeignPathIgnored() {
        val output = buildString {
            append("B\tXX\n")
            append(fileLines("/home/u/.aicode/skills/SKILL.md", "不该出现"))
            append("D\t0\n")
            append("B\tSG\n")
            append(fileLines("/home/u/.aicode/other/SKILL.md", "另一个根"))
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertFalse(parsed.containsKey("XX"))
        assertTrue(parsed.getValue("SG").isEmpty())
    }

    @Test
    fun parse_ignoresForeignLines() {
        val output = buildString {
            append("stderr: 某些提示\n")
            append("B\tSG\n")
            append(fileLines("/home/u/.aicode/skills/SKILL.md", "x"))
            append("\n")
            append("D\t0\n")
            append("END\n")
        }

        val parsed = parseRemoteSkillBulkOutput(output, roots)!!

        assertEquals("x", parsed.getValue("SG").getValue("SKILL.md"))
    }
}
