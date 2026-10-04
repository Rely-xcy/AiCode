package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import java.io.InputStream
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
 * 上传途中取消的语义：原样抛 CancellationException、通道恰丢弃一次、不自动重试。
 *
 * `RemoteSftpFileAccess` 直接依赖 final 的 [RemoteSshConnection]，起真实 SFTP 通道要一台服务器；
 * 它为此开放了一个测试专用次构造，只换「取通道 / 丢通道」两个回调，于是可以在 JVM 上直接驱动
 * 读写缓冲循环。
 *
 * 必须复刻生产路径：`writeStream` 是非 suspend 的 `runBlocking`，直接 cancel 调用方协程不会中断
 * 那个阻塞线程——只有 `runInterruptible` 能把取消桥接进内部子协程，循环才能在下一次
 * `ensureTransferActive()` 处观察到取消。
 */
class RemoteSftpFileAccessCancelTest {

    @Test
    fun `上传途中取消：原样抛 CancellationException、丢弃通道一次、不重试`() = runBlocking {
        val connection = mockk<RemoteSshConnection>(relaxed = true)
        every { connection.config } returns RemoteConnectionConfig(
            host = "example.com",
            username = "dev",
            auth = RemoteAuth.Password("pw"),
            remoteWorkspacePath = "/srv/proj"
        )
        every { connection.remoteHome } returns "/home/dev"

        val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaceRepository.currentPathOrNull() } returns "/srv/proj"

        val remoteFile = mockk<RemoteFile>(relaxed = true)
        val sftp = mockk<SFTPClient>(relaxed = true)
        every { sftp.open(any(), any()) } returns remoteFile

        val invalidateCount = AtomicInteger()
        val writeCount = AtomicInteger()
        val firstWrite = CountDownLatch(1)
        every { remoteFile.write(any(), any(), any(), any()) } answers {
            writeCount.incrementAndGet()
            firstWrite.countDown()
        }

        val fileAccess = RemoteSftpFileAccess(
            connection = connection,
            workspaceRepository = workspaceRepository,
            sftpProvider = { sftp },
            invalidateChannel = { invalidateCount.incrementAndGet() }
        )

        var caught: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                runInterruptible {
                    fileAccess.writeStream("/tmp/out.bin", EndlessInputStream(), overwrite = true)
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
        assertEquals("取消后通道恰丢弃一次", 1, invalidateCount.get())

        val writesAtCancel = writeCount.get()
        delay(200)
        assertEquals("取消后不再重试写", writesAtCancel, writeCount.get())
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
