package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** 一次性读取（非 Flow），用于跨请求重建上下文历史。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    /**
     * 指定会话的短期上下文统计：仍在上下文里的消息条数，以及仍在上下文里的接手摘要份数。
     *
     * 回放上下文时会滤掉 isCompacted 的行，所以「未压缩行数」就是这轮请求实际要带的短期上下文规模；
     * 每次折叠写入一条 isContextSummary 行，但旧摘要会被下一次折叠回收（标 isCompacted），
     * 所以 foldCount 是「当前生效的摘要份数」，不是累计折叠次数。
     */
    @Query(
        """
        SELECT COUNT(*) AS retainedMessages,
               IFNULL(SUM(CASE WHEN isContextSummary = 1 THEN 1 ELSE 0 END), 0) AS foldCount
        FROM agent_messages
        WHERE sessionId = :sessionId AND isCompacted = 0
        """
    )
    suspend fun sessionContextStats(sessionId: String): SessionContextStats

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun deleteMessagesBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** 将指定会话中 cutoff 时间戳之前的所有消息标记为已压缩（isCompacted=1），不再参与上下文回放。聊天页消息流不过滤 isCompacted，历史原文仍照常展示。 */
    @Query("UPDATE agent_messages SET isCompacted = 1 WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun markMessagesCompactedBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把指定会话里除本次新插入的 marker（[keepMarkerId]）与摘要（[keepSummaryId]）之外的 compaction 行全部标为已压缩。
     *
     * 为什么要显式回收：只靠 [markMessagesCompactedBeforeTimestamp] 的「时间戳早于新 marker」判据，
     * 在保留区起点没有前移时（两次折叠之间新增量小于预算富余）标不到旧摘要，上下文里会留下两份
     * 重复且过时的接手说明。旧摘要内容已被新摘要吸收（新摘要是拿旧摘要当 previous-summary 更新出来的），
     * 标掉不丢信息。
     *
     * 判据只用两个标志位：1.12 之前的 legacy 摘要行已由迁移 `17_add_context_summary_flag.sql`
     * 标成 isContextSummary=1，所以这里不做文本前缀匹配——那只会多出一个「恰以 legacy 前缀开头的
     * 普通 assistant 行被误标」的面。
     *
     * 用两个标量 id 而不是 `id NOT IN (:keepIds)`：仓库里没有集合参数展开的先例，且空列表会生成
     * `NOT IN ()` 让约束失效。返回被回收的行数。
     */
    @Query(
        """
        UPDATE agent_messages SET isCompacted = 1
        WHERE sessionId = :sessionId
          AND id != :keepMarkerId AND id != :keepSummaryId
          AND (isContextSummary = 1 OR isCompactionMarker = 1)
        """
    )
    suspend fun markSupersededCompactionRows(
        sessionId: String,
        keepMarkerId: String,
        keepSummaryId: String
    ): Int

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    /** 会话是否已有任何消息（LIMIT 1 快速判断，避免全量读取）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_messages WHERE sessionId = :sessionId LIMIT 1)")
    suspend fun hasMessages(sessionId: String): Boolean

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    /** 写入模型侧模式提醒（仅用户行）：content 保持用户原话，提醒不进正文。 */
    @Query("UPDATE agent_messages SET modelReminder = :reminder WHERE id = :id")
    suspend fun updateModelReminder(id: String, reminder: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把残留的「执行中」工具行（content 以占位标记开头）批量收尾为「已中断」。
     * 用于冷启动：上次进程被杀时正在执行的工具不可能仍在跑，否则其占位行会永久显示转圈。
     * 返回受影响的行数。
     */
    @Query("UPDATE agent_messages SET content = :interruptedContent, isError = 1 WHERE role = :toolRole AND content LIKE :pendingPrefix")
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    /**
     * 跨会话搜索某工作区下的聊天记录：命中用户 / 助手正文，排除已压缩与内部摘要行。
     * [escapedQuery] 已由调用方转义 LIKE 通配符（`!` `%` `_`），配合 SQL 里的 ESCAPE '!'。
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspace(workspacePath: String, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 指定会话中时间戳不早于 [timestamp] 的消息条数（含并列时间戳），供定位时确定所需分页上限。 */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun countMessagesFromTimestamp(sessionId: String, timestamp: Long): Int

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** 分页读取（keyset：按 timestamp,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /**
     * 各会话消息正文占用的字节数（降序取前 [limit] 个），供存储空间页拆解「聊天记录」构成。
     *
     * `LENGTH(CAST(x AS BLOB))` 取的是 UTF-8 字节数——直接 `LENGTH(x)` 对文本返回字符数，中文会少算三分之二。
     * 只统计几个大字段，因此是估算值：不含索引、页对齐与 WAL 开销，必然小于数据库文件本身。
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS title,
               COUNT(*) AS messageCount,
               SUM(
                   LENGTH(CAST(m.content AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolCallsJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolArgs, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.reasoning, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.thinkingBlocksJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.attachmentsJson, '') AS BLOB))
               ) AS bytes
        FROM agent_messages m
        LEFT JOIN chat_sessions s ON s.id = m.sessionId
        GROUP BY m.sessionId
        ORDER BY bytes DESC
        LIMIT :limit
        """
    )
    suspend fun sessionStorageUsage(limit: Int): List<SessionStorageUsage>
}

/** 跨会话聊天记录搜索的命中投影（[AgentMessageDao.searchInWorkspace] 的投影）。 */
data class ChatSearchMatch(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/** 单个会话的短期上下文统计（[AgentMessageDao.sessionContextStats] 的投影）。 */
data class SessionContextStats(
    val retainedMessages: Int,
    val foldCount: Int
)

/** 单个会话的消息占用估算（[AgentMessageDao.sessionStorageUsage] 的投影）。 */
data class SessionStorageUsage(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val bytes: Long
)
