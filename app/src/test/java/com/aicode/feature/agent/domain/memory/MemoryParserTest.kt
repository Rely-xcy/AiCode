package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun format_and_parse_roundtrip() {
        val name = "user_preference"
        val description = "User prefers dark mode and concise answers"
        val content = "# User Preferences\n- Dark mode: true\n- Conciseness: high"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("user_preference.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertTrue(memory != null)
        assertEquals("user_preference", memory?.name)
        assertEquals("User prefers dark mode and concise answers", memory?.description)
        assertEquals(MemoryScope.GLOBAL, memory?.scope)
        assertEquals(content.trim(), memory?.content)
    }

    @Test
    fun format_escapesSpecialCharactersInYaml() {
        val name = "special:key#name"
        val description = "Description with \"quotes\" and : colons"
        val content = "Memory body"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("special.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.PROJECT)
        assertTrue(memory != null)
        assertEquals("special:key#name", memory?.name)
        assertEquals("Description with \"quotes\" and : colons", memory?.description)
        assertEquals("Memory body", memory?.content)
    }

    @Test
    fun profileKind_roundtripsThroughFrontmatter() {
        val formatted = MemoryParser.format("pref_concise", "Prefers concise answers", "body", MemoryKind.PROFILE)
        assertTrue(formatted.contains("kind: profile"))

        val file = tempFolder.newFile("pref_concise.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertEquals(MemoryKind.PROFILE, memory?.kind)
        assertEquals("Prefers concise answers", memory?.description)
        assertEquals("body", memory?.content)
    }

    @Test
    fun noteKind_doesNotWriteKindField() {
        val formatted = MemoryParser.format("plain", "plain note", "body")
        assertFalse(formatted.contains("kind:"))

        val file = tempFolder.newFile("plain.md")
        file.writeText(formatted)

        assertEquals(MemoryKind.NOTE, MemoryParser.parse(file, MemoryScope.GLOBAL)?.kind)
    }

    @Test
    fun unknownOrMissingKind_fallsBackToNote() {
        val file = tempFolder.newFile("legacy.md")
        file.writeText("---\nname: legacy\ndescription: old file\n---\nbody")
        assertEquals(MemoryKind.NOTE, MemoryParser.parse(file, MemoryScope.GLOBAL)?.kind)

        val weird = tempFolder.newFile("weird.md")
        weird.writeText("---\nname: weird\nkind: something-else\n---\nbody")
        assertEquals(MemoryKind.NOTE, MemoryParser.parse(weird, MemoryScope.GLOBAL)?.kind)
    }

    @Test
    fun pinned_roundtripsThroughFrontmatter() {
        val formatted = MemoryParser.format("profile", "用户画像", "body", pinned = true)
        assertTrue(formatted.contains("pinned: true"))

        val file = tempFolder.newFile("profile.md")
        file.writeText(formatted)

        assertEquals(true, MemoryParser.parse(file, MemoryScope.GLOBAL)?.pinned)
    }

    @Test
    fun pinnedFalse_doesNotWriteField_andMissingOrInvalidDefaultsToFalse() {
        // 不置顶时不写字段：旧文件形态不变
        val formatted = MemoryParser.format("plain", "plain note", "body")
        assertFalse(formatted.contains("pinned:"))

        val file = tempFolder.newFile("plain.md")
        file.writeText(formatted)
        assertEquals(false, MemoryParser.parse(file, MemoryScope.GLOBAL)?.pinned)

        // 缺失该字段的旧文件 → false
        val legacy = tempFolder.newFile("legacy.md")
        legacy.writeText("---\nname: legacy\ndescription: old file\n---\nbody")
        assertEquals(false, MemoryParser.parse(legacy, MemoryScope.GLOBAL)?.pinned)

        // 非法值（非 "true"）也当 false
        val weird = tempFolder.newFile("weird.md")
        weird.writeText("---\nname: weird\npinned: maybe\n---\nbody")
        assertEquals(false, MemoryParser.parse(weird, MemoryScope.GLOBAL)?.pinned)
    }
}
