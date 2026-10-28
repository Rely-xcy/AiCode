package com.aicode.feature.workspace.domain.remote.agent

import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentProvisionerTest {

    private val binary = ByteArray(32) { it.toByte() }

    @Test
    fun `x86_64 上传 amd64 并 chmod`() = runBlocking {
        val agentDir = "/opt/aicode/agent"
        val path = "$agentDir/aicode-agent-v1-linux-amd64"
        var remoteSize = 0L
        var uploaded: ByteArray? = null

        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.fileSize(path) } answers { remoteSize }
        every { fileAccess.writeBytes(path, any(), true) } answers {
            uploaded = firstArg<ByteArray>()
            remoteSize = binary.size.toLong()
        }

        val commands = mutableListOf<String>()
        val provisioner = RemoteAgentProvisioner(
            fileAccess = fileAccess,
            runCommand = { command ->
                commands += command
                when {
                    command == "uname -m" -> "x86_64\n"
                    command.startsWith("chmod +x ") -> ""
                    else -> error("意外命令: $command")
                }
            },
            loadBinary = { binary },
            agentDir = agentDir
        )

        val installation = provisioner.ensureInstalled()

        assertEquals(RemoteAgentArch.AMD64, installation.arch)
        assertEquals(path, installation.binaryPath)
        assertTrue(installation.uploaded)
        assertEquals(binary.size, uploaded?.size)
        assertEquals(1, installation.version)
        assertEquals(listOf("uname -m", "chmod +x '$path'"), commands)
        verify(exactly = 1) { fileAccess.writeBytes(path, any(), true) }
    }

    @Test
    fun `aarch64 选 arm64，默认目录用 HOME 展开`() = runBlocking {
        val path = "~/.aicode/agent/aicode-agent-v1-linux-arm64"
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.fileSize(path) } returns binary.size.toLong()

        val commands = mutableListOf<String>()
        val provisioner = RemoteAgentProvisioner(
            fileAccess = fileAccess,
            runCommand = { command ->
                commands += command
                if (command == "uname -m") "aarch64" else ""
            },
            loadBinary = { binary }
        )

        val installation = provisioner.ensureInstalled()

        assertEquals(RemoteAgentArch.ARM64, installation.arch)
        assertFalse("大小一致应跳过上传", installation.uploaded)
        assertEquals(
            listOf(
                "uname -m",
                "chmod +x \"\$HOME/.aicode/agent/aicode-agent-v1-linux-arm64\""
            ),
            commands
        )
        verify(exactly = 0) { fileAccess.writeBytes(any(), any(), any()) }
    }

    @Test
    fun `不支持的架构抛部署异常且不读二进制`() = runBlocking {
        val fileAccess = mockk<FileAccessProvider>()
        val provisioner = RemoteAgentProvisioner(
            fileAccess = fileAccess,
            runCommand = { command -> if (command == "uname -m") "sparc64" else "" },
            loadBinary = { throw AssertionError("架构不支持时不应读取二进制") }
        )

        val error = runCatching { provisioner.ensureInstalled() }.exceptionOrNull()
        assertTrue("应抛 RemoteAgentProvisionException，实际 $error", error is RemoteAgentProvisionException)
    }

    @Test
    fun `上传后大小不符抛异常`() = runBlocking {
        val path = "/opt/aicode/agent/aicode-agent-v1-linux-amd64"
        val fileAccess = mockk<FileAccessProvider>()
        // 写成功但远端读回一直是 0：模拟写入丢失/截断
        every { fileAccess.fileSize(path) } returns 0L
        every { fileAccess.writeBytes(path, any(), true) } answers { }

        val provisioner = RemoteAgentProvisioner(
            fileAccess = fileAccess,
            runCommand = { command -> if (command == "uname -m") "amd64" else "" },
            loadBinary = { binary },
            agentDir = "/opt/aicode/agent"
        )

        val error = runCatching { provisioner.ensureInstalled() }.exceptionOrNull()
        assertTrue("应抛 RemoteAgentProvisionException，实际 $error", error is RemoteAgentProvisionException)
    }

    @Test
    fun `空二进制抛异常`() = runBlocking {
        val fileAccess = mockk<FileAccessProvider>()
        val provisioner = RemoteAgentProvisioner(
            fileAccess = fileAccess,
            runCommand = { command -> if (command == "uname -m") "x86_64" else "" },
            loadBinary = { ByteArray(0) }
        )

        val error = runCatching { provisioner.ensureInstalled() }.exceptionOrNull()
        assertTrue("应抛 RemoteAgentProvisionException，实际 $error", error is RemoteAgentProvisionException)
    }
}
