package com.aicode.feature.agent.domain.container

/**
 * 远端文件写入能力：由持有 agent 会话的一侧实现，注册给 [RemoteSshConnection] 使用。
 *
 * 为什么是「接口 + 事后注册」而不是构造注入：连接层要「优先用 agent 写文件、拿不到就退回 SFTP」，
 * 而 agent 会话本身又要用这条连接去开 exec 通道。构造注入会形成
 * `Connection → Agent → Connection` 的环；用接口把依赖倒到连接这一侧、由实现方在构造完成后
 * 注册回来，环就断了。
 *
 * 约定：**实现方不负责兜底**。会话没就绪、请求失败都直接抛，由调用方决定退回 SFTP——
 * 只有调用方知道这次操作是不是可以慢一点完成。
 */
interface RemoteFileOps {

    /** 建目录，含父目录；已存在且是目录算成功。 */
    suspend fun mkdirs(path: String)

    /** 路径是否存在。 */
    suspend fun exists(path: String): Boolean

    /**
     * 写文件。[executable] 为真时写可执行位。
     * [overwrite] 为假且目标已存在时抛 [java.nio.file.FileAlreadyExistsException]。
     */
    suspend fun write(path: String, bytes: ByteArray, executable: Boolean, overwrite: Boolean)

    /** 删除文件或空目录。 */
    suspend fun remove(path: String)
}
