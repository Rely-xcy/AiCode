package com.aicode.feature.backup.domain

import com.aicode.feature.settings.data.repository.ExecutionMode

/**
 * 导入后是否要把执行模式切回远程（[ExecutionMode.REMOTE_SSH]）。
 *
 * 为什么要单独判：聊天列表按「当前工作区路径」等值查会话，而远程会话的 workspacePath 是服务器上的路径，
 * 只有处于远程模式时才可见。备份里若记着「导出时是远程模式」，导入后就该回到远程模式，
 * 否则用户在新设备上导入完只会看到一片空白，以为数据没恢复。
 *
 * 只做模式判断，不看本机当前模式，也不发起任何连接：连接由用户自己建立（导入摘要里也会提示）。
 *
 * 判据：
 * - [BackupMetadata.executionMode] 为 null（旧备份 / 没勾应用设置）→ 不动本机模式，保持现状；
 * - 值为 [ExecutionMode.REMOTE_SSH]（枚举名）→ 切远程模式；
 * - 值为 [ExecutionMode.LOCAL_PROOT] 或无法识别的字符串（例如旧版本 App 读到未来新增的模式名）→ 不切，
 *   解析失败一律按「不动」处理，避免把用户锁在一个本机理解不了的模式里。
 */
internal fun shouldRestoreRemoteMode(meta: BackupMetadata): Boolean =
    meta.executionMode
        ?.let { raw -> runCatching { ExecutionMode.valueOf(raw) }.getOrNull() } == ExecutionMode.REMOTE_SSH
