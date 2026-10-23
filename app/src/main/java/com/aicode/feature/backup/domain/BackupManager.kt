package com.aicode.feature.backup.domain

import com.aicode.feature.workspace.domain.model.WorkspaceType
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 备份编排器：从各数据源采集快照并打包，或反向解包还原。
 *
 * 导出：按 [BackupOptions] 采集 → 流式序列化（metadata.json + 各 *.jsonl）→ tar.gz 压缩 → 口令非空则分块 AES-GCM 加密。
 * 导入：口令非空则先流式解密 → 解 tar.gz → 校验 schemaVersion → 分批合并写入各数据源。
 * 全程流式，内存峰值与数据总量解耦（只持有当前分页批次）。
 */
interface BackupManager {
    /**
     * 流式生成备份写入 [output]（调用方负责打开与关闭输出流）。
     * password 为 null 或空时不加密，输出明文 tar.gz。
     *
     * 勾选了 [BackupOptions.loginSecrets] 却没有口令时直接抛 [IllegalArgumentException]：
     * 静默降级成「不含凭据」会让用户以为凭据已经在包里（见 [LoginSecretsPasswordRequiredException]）。
     *
     * @return 导出结果统计：实写了多少工作区 / 文件，以及哪些工作区被跳过（远程工作区、宿主目录不在、
     *   未连接读不到）。导出照样产出能拿到的部分，调用方用这份统计在完成提示里明示「还差什么」。
     */
    suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream): ExportStats

    /**
     * 导出单个会话（无密码 tar.gz）：只含该会话 + 关联消息 + todo，可由 [import] 直接还原。
     * @param output 调用方负责打开与关闭输出流
     */
    suspend fun exportSession(sessionId: String, output: OutputStream)

    /**
     * 流式解包并还原备份文件。
     * @param input 调用方负责打开与关闭输入流
     * @param password 备份未加密时传 null 或空；加密文件必须提供正确口令。
     * @param uncompressedBytes 解压后数据量（[ImportPreview.uncompressedBytes]）：写入前据此做空间预检，
     *   目标分区不足以 [BackupSpace.SAFETY_FACTOR] 的余量容纳时直接失败，不进入写入流程。
     * @param selectedWorkspaces 非 null 时只恢复这些工作区的文件（导入前由 [previewImport] 勾选）；
     *   null 表示全量恢复（备份无工作区数据或旧调用）。
     * @return 还原统计（各数据段条目数）；口令错误/格式不符/版本过高/空间不足时返回失败。
     */
    suspend fun import(
        input: InputStream,
        password: CharArray?,
        uncompressedBytes: Long,
        selectedWorkspaces: Set<String>? = null
    ): Result<RestoreStats>

    /**
     * 导入前预览：校验口令/格式/版本，并读出备份中包含的工作区列表（名称 + 文件数）。
     * 有工作区数据时 UI 应弹出勾选确认（直接覆盖警告）；无数据时导入无需弹窗。
     * @param input 调用方负责打开与关闭输入流
     */
    suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview>

    /** 准备经过完整解密校验的压缩包，调用方负责在预览和恢复结束后删除临时文件。 */
    suspend fun prepareImport(input: InputStream, password: CharArray?): File
}

/** 导入预览结果：备份中的工作区列表（供勾选）与解压后数据量（供空间预检）。 */
data class ImportPreview(
    val workspaces: List<WorkspaceBackupMeta>,
    /** tar 内所有条目声明大小之和；旧格式（snapshot.json）也算在其中。 */
    val uncompressedBytes: Long = 0
) {
    val hasWorkspaceData: Boolean get() = workspaces.isNotEmpty()
}

/** 导出数据范围选项；未勾选的段在快照中保持空值，导入时跳过。 */
data class BackupOptions(
    val providers: Boolean = true,
    val remoteConnections: Boolean = true,
    val chatHistory: Boolean = true,
    val mcpServers: Boolean = true,
    val permissionRules: Boolean = true,
    val appSettings: Boolean = true,
    val workspaceFiles: Boolean = false,
    /** 技能与子代理：全局 + 项目两级的技能目录、子代理目录，以及同两级的启停配置。 */
    val skillsAndAgents: Boolean = true,
    /** 面板脚本目录（`~/.aicode/scripts`）。 */
    val panelScripts: Boolean = true,
    /** 长期记忆目录：全局 `~/.aicode/memory/` 与当前工作区的 `.aicode/memory/`。默认关。 */
    val memoryFiles: Boolean = false,
    /** 会话工作状态（`session_briefs` 表）。默认关。 */
    val sessionBriefs: Boolean = false,
    /**
     * 登录凭据：git 令牌、SSH 私钥文件与登录口令、网络备份目标密钥、MCP 授权令牌。默认关。
     *
     * 勾选后**必须**提供非空口令：这些值能直接用于登录，不允许写进明文备份里。
     */
    val loginSecrets: Boolean = false
)

/**
 * 导出结果统计：把「即将备份的工作区」与「实际写进包里的工作区 / 文件数」分开，
 * 并逐条记下被跳过的工作区及原因。UI 的完成提示据此讲清「哪些没带上、为什么」。
 *
 * 只统计「工作区文件」段：其余数据段是整表流式序列化，不存在部分跳过的情形；
 * 因此 [workspaceFilesRequested] 为 false 时下面各项都没有意义，摘要也不该提工作区。
 */
data class ExportStats(
    /** 用户是否勾了「工作区文件」。 */
    val workspaceFilesRequested: Boolean = false,
    /** 候选工作区数（已排除外部本地目录）。 */
    val candidateWorkspaces: Int = 0,
    /** 实际写入了至少一个文件的工作区数。 */
    val exportedWorkspaces: Int = 0,
    /** 实际写入 tar 的工作区文件条目数。 */
    val workspaceFiles: Int = 0,
    /** 被跳过的工作区明细（名字 + 原因），顺序与工作区列表一致。 */
    val skippedWorkspaces: List<WorkspaceSkip> = emptyList(),
    /** 导出时是否处于远程执行模式。 */
    val remoteMode: Boolean = false,
    /** 远程模式下是否已连上服务器；非远程模式恒为 false。 */
    val remoteConnected: Boolean = false,
    /**
     * 本次导出里「本机 Keystore 解不开、按密文原样写进备份」的敏感字段个数（API Key、代理密码、
     * 自定义请求头等）。不为 0 时导出摘要必须明示：这些值只对原设备可用，换机导入后需要重填。
     */
    val undecryptableSecrets: Int = 0
) {
    /** 因远程相关原因被跳过的工作区数。 */
    val skippedRemoteWorkspaces: Int get() = skippedWorkspaces.count { it.reason.isRemote }

    /**
     * 是否要明示「远程工作区文件未包含」：这次勾了工作区文件却一个都没导出，且原因是远程——
     * 要么有远程工作区被跳过，要么远程模式下压根没连上（连工作区列表都读不出来）。
     */
    val remoteWorkspaceMissing: Boolean
        get() = workspaceFilesRequested && workspaceFiles == 0 &&
            (skippedRemoteWorkspaces > 0 || (remoteMode && !remoteConnected))
}

/** 一个被跳过的工作区：名字 + 原因，供导出摘要逐条列出。 */
data class WorkspaceSkip(val name: String, val reason: WorkspaceSkipReason)

/** 导出时跳过某个工作区的原因。 */
enum class WorkspaceSkipReason {
    /** 远程工作区且当前未连接服务器：文件读不到。 */
    REMOTE_NOT_CONNECTED,

    /** 远程工作区但已连接：本次不做远程文件的真导出（远程工作区只做可见性提示）。 */
    REMOTE_UNSUPPORTED,

    /** 内部工作区的宿主目录不存在（被删除 / 被移走 / 尚未落盘）。 */
    HOST_DIR_MISSING,

    /** 目录在但一个文件都没有（含被 .gitignore / 同步忽略清单全部排除的情形）。 */
    EMPTY,

    /** 遍历时抛异常（权限 / IO 错误），拿不到文件列表。 */
    READ_FAILED;

    /** 是否与远程工作区有关：导出摘要据此给出「需要在远程模式下连接后导出」的提示。 */
    val isRemote: Boolean get() = this == REMOTE_NOT_CONNECTED || this == REMOTE_UNSUPPORTED
}

/**
 * 判定一个候选工作区该不该跳过；返回 null 表示可以正常备份。
 *
 * 纯函数：输入只有工作区类型、宿主目录是否存在、远程是否已连接，单测可直接覆盖
 * 「远程未连接 / 远程已连接 / 目录不存在 / 正常」四种情形，不必搭 Room 或真实文件系统。
 *
 * 外部本地工作区在收集候选时就被排除（用户设备上的原始目录不属于 App 私有数据，不纳入备份），
 * 这里返回 null 只是让判定式对三种类型都成立，实际不会走到该分支。
 */
fun classifyWorkspaceSkip(
    type: WorkspaceType,
    hostDirExists: Boolean,
    remoteConnected: Boolean
): WorkspaceSkipReason? = when (type) {
    WorkspaceType.REMOTE ->
        if (remoteConnected) WorkspaceSkipReason.REMOTE_UNSUPPORTED else WorkspaceSkipReason.REMOTE_NOT_CONNECTED

    WorkspaceType.INTERNAL -> if (hostDirExists) null else WorkspaceSkipReason.HOST_DIR_MISSING

    WorkspaceType.EXTERNAL_LOCAL -> null
}

data class RestoreStats(
    val providers: Int = 0,
    val remoteConnections: Int = 0,
    val remoteMounts: Int = 0,
    val chatSessions: Int = 0,
    val agentMessages: Int = 0,
    val todoItems: Int = 0,
    val mcpServers: Int = 0,
    val globalPermissionRules: Int = 0,
    val workspaceFiles: Int = 0,
    /**
     * 导入时跳过的「安全相关条目」数：git 的 hooks 与 config 能在容器内执行命令，
     * 这两类不从备份还原（其余 `.git` 内容照常恢复）。
     */
    val skippedSecurityEntries: Int = 0,
    /** 本次导入是否真的回写了应用设置段（备份没带时不回写，摘要也就不该说「已覆盖」）。 */
    val settingsRestored: Boolean = false,
    /** 本次导入实际写入的技能文件数（技能目录下的所有文件，含 SKILL.md 与附带的脚本/二进制）。 */
    val skillFiles: Int = 0,
    /** 本次导入实际写入的子代理定义文件数。 */
    val subagentFiles: Int = 0,
    /** 本次导入实际写入的面板脚本文件数。 */
    val panelScriptFiles: Int = 0,
    /** 本次导入是否真的回写了技能 / 子代理的启停配置（备份没带、或项目级因工作区未落定被跳过时为 false）。 */
    val skillsAgentsConfigRestored: Boolean = false,
    /** 本次导入写入的记忆文件数（全局 + 项目两级）。 */
    val memoryFiles: Int = 0,
    /** 本次导入回写的会话工作状态条数。 */
    val sessionBriefs: Int = 0,
    /**
     * 本次导入实际还原的登录凭据项数：git 凭据、SSH 登录密钥、SSH 私钥文件、网络备份目标、MCP 授权令牌。
     * 备份没带 `secrets/` 段时为 0，摘要也就不该提凭据。
     */
    val loginSecrets: Int = 0,
    /**
     * 备份里带了、但没能还原的登录凭据项数（写盘失败、条目格式不认识）。
     * 单独计数而不是只记日志：静默跳过会让用户以为凭据完整回来了。
     */
    val skippedLoginSecrets: Int = 0,
    /**
     * 本次导入是否真的把执行模式从本地切回了远程（[com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH]）。
     * 本机原本就在远程时不计（没发生切换）；为 true 时导入摘要才多一句「远程聊天记录只在远程模式下显示」。
     */
    val remoteModeRestored: Boolean = false,
    /**
     * 导入时跳过的工作区文件数：目标工作区解析不出来（远程模式未连接、工作区目录不可用、
     * 或无法创建同名工作区）时，这些文件只能留在备份里。摘要据此如实提示，
     * 而不是让用户以为「工作区文件全恢复了」。
     */
    val skippedWorkspaceFiles: Int = 0,
    /**
     * 已落库、但本机 Keystore 解不开的敏感字段个数：这些字段是备份里带过来的旧密文（原设备密钥），
     * 在设置里显示为空，需要用户重新填写。导入摘要不管是否为 0 都要输出这一行，
     * 否则用户无法区分「全部可用」与「根本没检查」。
     */
    val undecryptableSecrets: Int = 0
) {
    /** 是否已经写入了任何业务数据：失败提示据此区分「一条都没写」与「写了半套」。 */
    val hasWrittenData: Boolean
        get() = providers + remoteConnections + remoteMounts + chatSessions + agentMessages +
            todoItems + mcpServers + globalPermissionRules + workspaceFiles + skillFiles +
            subagentFiles + panelScriptFiles + memoryFiles + sessionBriefs + loginSecrets > 0

    operator fun plus(other: RestoreStats) = RestoreStats(
        providers = providers + other.providers,
        remoteConnections = remoteConnections + other.remoteConnections,
        remoteMounts = remoteMounts + other.remoteMounts,
        chatSessions = chatSessions + other.chatSessions,
        agentMessages = agentMessages + other.agentMessages,
        todoItems = todoItems + other.todoItems,
        mcpServers = mcpServers + other.mcpServers,
        globalPermissionRules = globalPermissionRules + other.globalPermissionRules,
        workspaceFiles = workspaceFiles + other.workspaceFiles,
        skippedSecurityEntries = skippedSecurityEntries + other.skippedSecurityEntries,
        settingsRestored = settingsRestored || other.settingsRestored,
        skillFiles = skillFiles + other.skillFiles,
        subagentFiles = subagentFiles + other.subagentFiles,
        panelScriptFiles = panelScriptFiles + other.panelScriptFiles,
        skillsAgentsConfigRestored = skillsAgentsConfigRestored || other.skillsAgentsConfigRestored,
        memoryFiles = memoryFiles + other.memoryFiles,
        sessionBriefs = sessionBriefs + other.sessionBriefs,
        remoteModeRestored = remoteModeRestored || other.remoteModeRestored,
        skippedWorkspaceFiles = skippedWorkspaceFiles + other.skippedWorkspaceFiles,
        undecryptableSecrets = undecryptableSecrets + other.undecryptableSecrets,
        loginSecrets = loginSecrets + other.loginSecrets,
        skippedLoginSecrets = skippedLoginSecrets + other.skippedLoginSecrets
    )
}

/**
 * 勾了「包含登录凭据」却没给口令。
 *
 * 单独成类型而不是复用通用 [IllegalArgumentException]：导出入口要把它与「IO 失败」「空间不足」分开，
 * 好给出「去设置导出密码」这种可操作的提示，而不是一句泛泛的失败。
 */
class LoginSecretsPasswordRequiredException : IllegalArgumentException(
    "勾选包含登录凭据时必须设置导出口令"
)

/**
 * 恢复中途失败：携带已经写入的统计，供 UI 如实提示「部分数据已写入」而不是假装成功。
 *
 * 不引入跨表大事务：Room 的整库事务要在一个事务里跑完整条流式导入，事务期间所有写入锁在同一连接上，
 * WAL 无法推进、失败时回滚日志还可能大于可用空间，反而放大「空间不足」的风险；
 * 且各数据段本来就是按 id 幂等 upsert，重新导入即可补齐。
 */
class PartialRestoreException(
    val partialStats: RestoreStats,
    cause: Throwable
) : IllegalStateException("恢复中断：部分数据已写入", cause)
