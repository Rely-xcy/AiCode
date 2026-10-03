package com.aicode.feature.terminal.domain

import com.aicode.core.util.FileLogger
import com.termux.terminal.SessionBackend
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

private const val TAG = "SshShellBackend"

/**
 * [SessionBackend] backed by an sshj interactive shell channel ([Session.Shell]).
 *
 * Used by [RemoteTerminalSessionManager] so the remote SSH shell drives the Termux
 * [com.termux.terminal.TerminalEmulator] exactly like a local pty would: shell output →
 * emulator, user input → shell stdin, resize → PTY window size change.
 *
 * The [Session.Shell] must already be started (with a PTY allocated) before being wrapped
 * here. [waitForExit] blocks on [Session.Shell.join]; remote shells have no meaningful
 * exit status, so 0 is returned (the emulator appends its own "[Process completed]" notice
 * via [com.termux.terminal.TerminalSession] once the reader thread hits EOF).
 *
 * 因为返回值永远是 0，「断网导致的结束」与「用户敲 exit」在 UI 上长得一模一样；[closedByDisconnect]
 * 把前者单独标出来，供 [RemoteTerminalSessionManager] 打「已断开」。返回值语义（[SessionBackend]
 * 的约定，本地终端共用）不动。
 */
class SshShellBackend(
    private val shell: Session.Shell,
    /** 连接是否仍然活着；由 [RemoteTerminalSessionManager] 接 RemoteSshConnection.isConnected。 */
    private val isConnectionAlive: () -> Boolean = { true }
) : SessionBackend {

    // changeWindowDimensions 走网络 I/O，resize 会被 TerminalView 在主线程触发，需切到后台线程
    private val resizeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SshShellResize") }
    @Volatile private var closed = false

    /**
     * shell 是在「连接已经不在了」的情况下结束的（断网/服务器重启），而不是用户敲了 exit。
     *
     * 在 [waitForExit] 里置位：Termux 的 TermSessionWaiter 线程先拿到它的返回值、再经主线程
     * handler 回调 onSessionFinished，所以标记一定早于回调被读到（@Volatile 保证可见性）。
     */
    @Volatile
    var closedByDisconnect: Boolean = false
        private set

    override fun getInputStream(): InputStream = shell.inputStream

    override fun getOutputStream(): OutputStream = shell.outputStream

    override fun resize(columns: Int, rows: Int) {
        if (closed || resizeExecutor.isShutdown) return
        resizeExecutor.execute {
            runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
                .onFailure { FileLogger.w(TAG, "PTY resize 失败 ${columns}x${rows}", it) }
        }
    }

    override fun waitForExit(): Int {
        val joined = runCatching { shell.join() }
        // join 正常返回 = channel 正常结束；抛异常，或结束时连接已经不在了，都算异常结束（断线）
        if (joined.isFailure || !isConnectionAlive()) {
            joined.exceptionOrNull()?.let { FileLogger.w(TAG, "shell.join 异常", it) }
            closedByDisconnect = true
        }
        return 0
    }

    override fun close() {
        closed = true
        resizeExecutor.shutdownNow()
        // shell.close() 走网络 I/O，不能在主线程同步执行
        Thread({
            runCatching { shell.close() }
                .onFailure { FileLogger.w(TAG, "shell.close 异常", it) }
        }, "SshShellClose").start()
    }
}
