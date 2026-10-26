package com.aicode.feature.agent.domain.container

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class RemoteShellCommandsTest {
    private lateinit var root: File
    private lateinit var home: File
    private val hostileName = "space 'quote' \\\n\$(touch injected) \"double\""
    private val text = "space 'quote' \\n literal \\\n\$(touch injected) `touch injected` \"double\" 中文\n\n"

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        root = Files.createTempDirectory("remote-shell-").toFile()
        home = File(root, "home").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun printfRoundTripsTextAndHostilePathsWithoutExecutingThem() {
        val dest = File(root, hostileName)
        for (content in listOf("", "plain", text, "\\\\'\n\n", "%s %b \\c")) {
            val result = runShell(buildRemoteFileWriteCommand(dest.path, content))
            assertEquals(result.output, 0, result.exit)
            assertArrayEquals(content.toByteArray(Charsets.UTF_8), dest.readBytes())
            assertFalse(File(root, "injected").exists())
        }
    }

    @Test
    fun privateWritesAre600BeforePrintfForNewExistingAndEmptyFiles() {
        val dest = File(root, hostileName)
        val mode = File(root, "mode-at-write")
        val interceptPrintf = "printf() { stat -c %a -- ${remoteShellQuote(dest.path)} > ${remoteShellQuote(mode.path)}; command printf \"\$@\"; }; "
        for (content in listOf(text, "replacement", "")) {
            if (dest.exists()) assertEquals(0, runShell("chmod 644 -- ${remoteShellQuote(dest.path)}").exit)
            val result = runShell("umask 022; " + interceptPrintf + buildRemoteFileWriteCommand(dest.path, content, privateFile = true))
            assertEquals(result.output, 0, result.exit)
            assertEquals("600\n", mode.readText())
            assertArrayEquals(content.toByteArray(Charsets.UTF_8), dest.readBytes())
            assertFalse(File(root, "injected").exists())
        }
    }

    @Test
    fun privateWriteStopsBeforeTruncationWhenChmodFails() {
        val dest = File(root, hostileName).apply { writeText("original") }
        val result = runShell("chmod() { return 1; }; " + buildRemoteFileWriteCommand(dest.path, text, privateFile = true))
        assertTrue(result.exit != 0)
        assertEquals("original", dest.readText())
    }

    @Test
    fun privateWriteRejectsSymlinksDirectoriesAndMissingParents() {
        val target = File(root, "target").apply { writeText("original") }
        val link = File(root, hostileName)
        Files.createSymbolicLink(link.toPath(), target.toPath())
        for (dest in listOf(link, home, File(root, "missing/file"))) {
            assertTrue(runShell(buildRemoteFileWriteCommand(dest.path, text, privateFile = true)).exit != 0)
        }
        assertEquals("original", target.readText())
    }

    @Test
    fun docsPreparationQuotesEveryDirectoryAndDeletesOnlyOldMarkdown() {
        val destDir = File(root, hostileName).apply { mkdirs() }
        val oldDoc = File(destDir, "old.md").apply { writeText("old") }
        val keep = File(destDir, "keep.txt").apply { writeText("keep") }
        val subDir = "nested/$hostileName"
        val result = runShell(buildRemoteDocsPreparationCommand(destDir.path, listOf(subDir)))
        assertEquals(result.output, 0, result.exit)
        assertTrue(File(destDir, subDir).isDirectory)
        assertFalse(oldDoc.exists())
        assertEquals("keep", keep.readText())
        assertFalse(File(root, "injected").exists())
        val file = File(destDir, "$subDir/doc ' \\ \$(touch injected).md")
        assertEquals(0, runShell(buildRemoteFileWriteCommand(file.path, text)).exit)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test
    fun builtinFilesRoundTripBytesAreExecutableAndDoNotOverwriteExistingFiles() {
        val dest = File(root, hostileName)
        val bytes = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 1, -1)
        val result = runShell(buildRemoteBuiltinFileCommand(dest.path, Base64.getEncoder().encodeToString(bytes), executable = true))
        assertEquals(result.output, 0, result.exit)
        assertArrayEquals(bytes, dest.readBytes())
        assertTrue(dest.canExecute())
        assertEquals(0, runShell(buildRemoteBuiltinFileCommand(dest.path, "", executable = false)).exit)
        assertArrayEquals(bytes, dest.readBytes())
        assertFalse(File(root, "injected").exists())
    }

    @Test
    fun workspaceCdAndSymlinkKeepHostilePathsLiteralAndPreserveFallback() {
        val workspace = File(root, hostileName).apply { mkdirs() }
        val command = buildRemoteWorkspaceCdCommand(workspace.path + "/") + "; printf %s \"\$PWD\""
        assertArrayEquals(workspace.path.toByteArray(Charsets.UTF_8), runShell(command).bytes)
        val link = File(home, "workspace")
        val linked = runShell(buildRemoteWorkspaceSymlinkCommand(workspace.path))
        assertEquals(linked.output, 0, linked.exit)
        assertEquals("done\n", linked.output)
        assertEquals(workspace.path, Files.readSymbolicLink(link.toPath()).toString())
        val other = File(root, "other").apply { mkdirs() }
        assertArrayEquals(workspace.path.toByteArray(Charsets.UTF_8), runShell(buildRemoteWorkspaceCdCommand(other.path) + "; printf %s \"\$(pwd -P)\"").bytes)
        assertFalse(File(root, "injected").exists())
    }

    @Test
    fun workspaceSymlinkSkipsRealDirectoriesAndDoesNotReportDoneOnFailure() {
        File(home, "workspace").mkdirs()
        val result = runShell(buildRemoteWorkspaceSymlinkCommand(File(root, hostileName).path))
        assertEquals(0, result.exit)
        assertEquals("skip\n", result.output)
        assertFalse(Files.isSymbolicLink(File(home, "workspace").toPath()))
        val failed = runShell("HOME=${remoteShellQuote(File(root, "missing-home").path)}; " + buildRemoteWorkspaceSymlinkCommand(root.path))
        assertTrue(failed.exit != 0)
        assertEquals("", failed.output)
    }

    @Test
    fun credentialRemovalQuotesPathsAndPreservesUnrelatedFiles() {
        val dir = File(root, hostileName).apply { mkdirs() }
        val names = listOf("gitconfig", "gitconfig.credential", "git-credentials", "git-credential-aicode")
        names.forEach { File(dir, it).writeText("test") }
        File(dir, "keep").writeText("keep")
        assertEquals(0, runShell(buildRemoteRemoveCredentialCommand(dir.path)).exit)
        names.forEach { assertFalse(File(dir, it).exists()) }
        assertEquals("keep", File(dir, "keep").readText())
        assertFalse(File(root, "injected").exists())
        File(dir, "gitconfig").mkdirs()
        assertTrue(runShell(buildRemoteRemoveCredentialCommand(dir.path)).exit != 0)
    }

    @Test
    fun gitConfigAndCredentialHelperPreserveLiteralPaths() {
        assumeTrue(runShell("command -v git").exit == 0)
        val dir = File(root, "space 'quote' \\ \$(touch injected) \"double\"").apply { mkdirs() }
        val config = File(root, "config").apply { writeText(buildRemoteGitConfig(dir.path)) }
        val key = "includeIf.gitdir:${dir.path}/.path"
        val parsed = runShell("git config --file ${remoteShellQuote(config.path)} --get ${remoteShellQuote(key)}")
        assertEquals(parsed.output, 0, parsed.exit)
        assertEquals("~/.aicode/gitconfig.credential\n", parsed.output)
        val helper = File(dir, "git-credential-aicode").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' 'username=test-user' 'password=test-value'\n")
            setExecutable(true, true)
        }
        assertTrue(helper.canExecute())
        config.writeText(buildRemoteCredentialConfig(dir.path))
        val result = runShell("printf '%s\\n' 'protocol=https' 'host=invalid.example' '' | GIT_CONFIG_GLOBAL=${remoteShellQuote(config.path)} GIT_TERMINAL_PROMPT=0 git credential fill")
        assertEquals(result.output, 0, result.exit)
        assertTrue(result.output.contains("username=test-user\n"))
        assertTrue(result.output.contains("password=test-value\n"))
        assertFalse(File(root, "injected").exists())
    }

    private fun runShell(command: String): ShellResult {
        val process = ProcessBuilder("/bin/sh", "-c", command).directory(root).redirectErrorStream(true).apply {
            environment()["HOME"] = home.path
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }.start()
        process.outputStream.close()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("shell timed out", finished)
        return ShellResult(process.exitValue(), process.inputStream.readBytes())
    }

    private data class ShellResult(val exit: Int, val bytes: ByteArray) {
        val output: String get() = bytes.toString(Charsets.UTF_8)
    }
}
