package com.aicode.feature.settings.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode

private const val TAG = "RemoteSessionLifecycle"

/**
 * 断开远程 SSH 会话，失败只记日志。
 *
 * 调用方是「切回本地模式」这条路径：模式是否切换成功与连接能否断开是两件事，断开失败不该
 * 冒到 UI。断开会让进行中的远程命令收到 IO 异常，那些调用各自在自己的 catch 里收尾
 * （见 RemoteSshEngine 的 streamExec），这里不做连接级取消，也不等待它们结束。
 *
 * @param onFailure 记录失败的方式；默认写 App 日志，测试里换成收集器。
 */
internal suspend fun RemoteSshConnection.disconnectQuietly(
    onFailure: (String, Throwable) -> Unit = { message, error -> FileLogger.w(TAG, message, error) }
) {
    runCatching { disconnect() }
        .onFailure { onFailure("切回本地模式时断开 SSH 连接失败（已忽略，不影响模式切换）", it) }
}

/**
 * 跟随执行模式启停远程重连监督。
 *
 * [RemoteSshConnection] 的重连监督不看执行模式：只要还留着 config，连接被断开后它会按指数退避
 * 把它重新连回来；它也没有 stop 入口，唯一的停止方式是取消调用
 * [RemoteSshConnection.startSupervisor] 时传入的 scope。因此「只在远程模式下运行」这个判断
 * 只能由启动方持有，收敛在这里。
 *
 * 同一模式的重复回调只生效一次：重复 start 会多挂一个监督循环，变成重复建连；
 * 已停止后再 stop 则是无意义操作（取消一个已取消的 scope）。
 *
 * [stop] 是挂起函数：调用方要在同一个协程里先取消监督、再补断开，两者顺序不能交给两次调度。
 */
internal class RemoteSupervisorGate(
    private val start: () -> Unit,
    private val stop: suspend () -> Unit
) {
    private var lastMode: ExecutionMode? = null
    private var running = false

    suspend fun onMode(mode: ExecutionMode) {
        if (mode == lastMode) return
        lastMode = mode
        when (mode) {
            ExecutionMode.REMOTE_SSH -> if (!running) {
                running = true
                start()
            }

            ExecutionMode.LOCAL_PROOT -> if (running) {
                running = false
                stop()
            }
        }
    }
}
