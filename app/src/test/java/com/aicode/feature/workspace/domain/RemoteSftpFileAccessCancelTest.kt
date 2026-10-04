package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传途中取消的语义：原样抛 CancellationException、**通道不丢弃**、不自动重试；
 * 只有真正的传输层异常才丢通道。
 *
 * `RemoteSftpFileAccess` 直接依赖 final 的 [RemoteSshConnection]，起真实 SFTP 通道要一台服务器；
 * 它为此开放了一个测试专用次构造，只换「取通道 / 丢通道」两个回调，于是可以在 JVM 上直接驱动
 * 读写缓冲循环。
 *
 * 必须复刻生产路径：`writeStream` 是非 suspend 的 `runBlocking`，直接 cancel 调用方协程不会中断
 * 那个阻塞线程——只有 `runInterruptible` 能把取消桥接进内部子协程，循环才能在下一次
 * `ensureTransferActive()` 处观察到取消。
 *
 * 反过来说，`runInterruptible` 这条桥是**唯一**能让取消点触发的路径：调用方协程被取消（文件树
 * `stateIn(WhileSubscribed)` 取消旧收集、切页、工作区初始化被取消重来）传不进 `withSftp` 的
 * `runBlocking`——它是不继承调用方 Job 的根协程。那些路径本就不会命中取消点，所以下方用例
 * 刻意用 `runInterruptible` 复刻上传取消，而不是直接 cancel 调用方。
 */
class RemoteSftpFileAccessCancelTest {

    @Test
    fun `上传途中取消：原样抛 CancellationException、不丢弃通道、不重试`() = runBlocking {
        val f = Fixture()
        val firstWrite = CountDownLatch(1)
        every { f.remoteFile.write(any(), any(), any(), any()) } answers {
            f.writeCount.incrementAndGet()
            firstWrite.countDown()
        }

        var caught: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                runInterruptible {
                    f.fileAccess.writeStream("/tmp/out.bin", EndlessInputStream(), overwrite = true)
                }
            } catch (e: Throwable) {
                caught = e
            }
        }

        // 等到写循环真的跑起来再取消，否则取消可能落在进入循环之前、测不到协作式取消点。
        assertTrue("写循环应跑起来", firstWrite.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeoutOrNull(5_000) { job.join() }

        val error = caught
        assertNotNull("取消必须抛出异常，不能被吞掉", error)
        assertTrue("必须原样抛 CancellationException，实际：$error", error is CancellationException)
        assertFalse("取消不该被当成 SFTP 异常", error is SFTPException)
        assertEquals("取消是「这次不做了」不是「连接坏了」，不该丢弃通道", 0, f.invalidateCount.get())

        val writesAtCancel = f.writeCount.get()
        delay(200)
        assertEquals("取消后不再重试写", writesAtCancel, f.writeCount.get())
    }

    @Test
    fun `取消后同一个通道仍可用于下一次操作`() = runBlocking {
        val f = Fixture()
        val firstWrite = CountDownLatch(1)
        every { f.remoteFile.write(any(), any(), any(), any()) } answers { firstWrite.countDown() }
        every { f.sftp.ls(any<String>()) } returns emptyList()

        val job = launch(Dispatchers.IO) {
            runCatching {
                runInterruptible {
                    f.fileAccess.writeStream("/tmp/out.bin", EndlessInputStream(), overwrite = true)
                }
            }
        }
        assertTrue("写循环应跑起来", firstWrite.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeoutOrNull(5_000) { job.join() }

        assertEquals("取消不该丢弃通道", 0, f.invalidateCount.get())

        val entries = f.fileAccess.listFiles("/")
        assertTrue("取消后同一通道仍能列目录（返回空列表）", entries.isEmpty())
        assertEquals("取消后下一次操作也不该丢弃通道", 0, f.invalidateCount.get())
    }

    @Test
    fun `传输层异常仍丢弃通道`() = runBlocking {
        val f = Fixture()
        every { f.sftp.ls(any<String>()) } answers { throw IOException("Connection reset by peer") }

        var caught: Throwable? = null
        try {
            f.fileAccess.listFiles("/")
        } catch (e: Throwable) {
            caught = e
        }

        assertNotNull("传输层异常必须抛出", caught)
        assertTrue("应为 IOException，实际：$caught", caught is IOException)
        assertEquals("传输层异常必须丢弃通道，下次调用重建", 1, f.invalidateCount.get())
    }

    @Test
    fun `业务错误不丢弃通道`() = runBlocking {
        val f = Fixture()
        every { f.sftp.ls(any<String>()) } answers { throw NoSuchFileException("/nope") }

        var caught: Throwable? = null
        try {
            f.fileAccess.listFiles("/")
        } catch (e: Throwable) {
            caught = e
        }

        assertNotNull("业务错误必须抛出", caught)
        assertEquals("文件不存在不是连接坏了，不丢通道", 0, f.invalidateCount.get())
    }

    /** 「取通道 / 丢通道」两个回调换成可观察的桩，其余按生产默认走。 */
    private inner class Fixture {
        val connection: RemoteSshConnection = mockk(relaxed = true)
        val workspaceRepository: WorkspaceRepository = mockk(relaxed = true)
        val remoteFile: RemoteFile = mockk(relaxed = true)
        val sftp: SFTPClient = mockk(relaxed = true)
        val invalidateCount = AtomicInteger()
        val writeCount = AtomicInteger()
        val fileAccess: RemoteSftpFileAccess

        init {
            every { connection.config } returns RemoteConnectionConfig(
                host = "example.com",
                username = "dev",
                auth = RemoteAuth.Password("pw"),
                remoteWorkspacePath = "/srv/proj"
            )
            every { connection.remoteHome } returns "/home/dev"
            every { workspaceRepository.currentPathOrNull() } returns "/srv/proj"
            every { sftp.open(any(), any()) } returns remoteFile
            fileAccess = RemoteSftpFileAccess(
                connection = connection,
                workspaceRepository = workspaceRepository,
                sftpProvider = { sftp },
                invalidateChannel = { invalidateCount.incrementAndGet() }
            )
        }
    }

    /** 永远读得到数据的输入流：循环只在取消时结束，取消必定落在某一次写入之后。 */
    private class EndlessInputStream : InputStream() {
        override fun read(): Int = 'a'.code

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
            return len
        }
    }
}
