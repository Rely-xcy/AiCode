package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceNotReadyException
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess
) : FileAccessProvider {

    private fun delegate(): FileAccessProvider =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remoteSftpFileAccess
        else localFileAccess

    /**
     * 转发一次调用，并把「工作区尚未落定」换成为模型一眼能看懂的临时性提示。
     *
     * 两种模式的实现都会因工作区未落定抛 [WorkspaceNotReadyException]（远程是路径映射前的
     * currentWorkspaceRoot、本地是 [WorkspacePathMapper] 的根路径解析），原文案只说「未就绪」，
     * 模型容易当成环境坏了去改配置或重建容器；这里统一换成「正在加载，稍后重试即可」，原始原因
     * 放进 cause 供日志排查。
     *
     * 刻意**不等待**（runBlocking 里等 8 秒是不可取的）：[FileAccessProvider] 是同步接口，
     * 等待会占住调用线程与远程 SFTP 互斥锁，一次工具调用最坏 +8 秒；命令/终端等入口已有
     * [com.aicode.feature.workspace.data.repository.WorkspaceRepository.awaitCurrentPathOrNull]
     * 的闸门，文件工具这里快速失败、由调用方（模型）自己重试更划算。
     */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: WorkspaceNotReadyException) {
        throw WorkspaceNotReadyException(WORKSPACE_LOADING_HINT, e)
    }

    override fun readFile(path: String): String = call { delegate().readFile(path) }

    // 惰性序列不在此包装：异常发生在迭代时而不是调用时，包在调用点抓不到（序列在
    // RemoteSftpFileAccess 内自己抛的是同一个 WorkspaceNotReadyException）。
    override fun readLines(path: String): Sequence<String> = delegate().readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        call { delegate().writeFile(path, content, overwrite, encoding) }

    override fun exists(path: String): Boolean = call { delegate().exists(path) }

    override fun isDirectory(path: String): Boolean = call { delegate().isDirectory(path) }

    override fun isFile(path: String): Boolean = call { delegate().isFile(path) }

    override fun fileSize(path: String): Long = call { delegate().fileSize(path) }

    override fun lastModified(path: String): Long = call { delegate().lastModified(path) }

    override fun permissions(path: String): String = call { delegate().permissions(path) }

    override fun listFiles(path: String): List<FileEntry> = call { delegate().listFiles(path) }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        call { delegate().listFilesRecursive(path, maxDepth) }

    override fun readBytes(path: String): ByteArray = call { delegate().readBytes(path) }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        call { delegate().writeBytes(path, bytes, overwrite) }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        call { delegate().writeStream(path, input, overwrite) }

    override fun copyToLocal(path: String): File = call { delegate().copyToLocal(path) }

    override fun delete(path: String) = call { delegate().delete(path) }

    override fun deleteRecursively(path: String) = call { delegate().deleteRecursively(path) }

    override fun rename(path: String, newPath: String) = call { delegate().rename(path, newPath) }

    override fun copy(path: String, newPath: String, overwrite: Boolean) =
        call { delegate().copy(path, newPath, overwrite) }

    override fun move(path: String, newPath: String, overwrite: Boolean) =
        call { delegate().move(path, newPath, overwrite) }

    override fun mkdirs(path: String) = call { delegate().mkdirs(path) }

    override fun parentPath(path: String): String? = call { delegate().parentPath(path) }

    override fun toDisplayPath(path: String): String = call { delegate().toDisplayPath(path) }
}

/** 工作区尚未落定（连接中/初始化未完成）时给调用方的提示：明确说是临时的，别去改配置。 */
private const val WORKSPACE_LOADING_HINT =
    "工作区正在加载中，稍后重试即可（无需修改配置）"
