package com.aicode.core.watch

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「工作区文件刚被写/删」的信号，由写文件的 AI 工具在成功返回前发出。
 *
 * 为什么需要它：文件树靠文件系统监听重建，本地模式靠 inotify；远程模式（SFTP）完全没有监听，
 * 服务端的改动不会产生宿主事件，所以 AI 刚建的 `app.py` 不会自己出现在文件树里，只能等用户手动刷新。
 * 写入方自己提一下，UI 侧把它并进文件树的重建触发源即可。
 *
 * 用 SharedFlow 而非 StateFlow：只关心「刚写了」这个事件，不关心累计次数；无订阅者时
 * `extraBufferCapacity` 保证 [notifyWritten] 不挂起，工具调用不被 UI 拖住。
 */
@Singleton
class WorkspaceWriteSignal @Inject constructor() {

    private val _writes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** 每次写/删工作区文件成功后发一次。 */
    val writes: SharedFlow<Unit> = _writes.asSharedFlow()

    fun notifyWritten() {
        _writes.tryEmit(Unit)
    }
}
