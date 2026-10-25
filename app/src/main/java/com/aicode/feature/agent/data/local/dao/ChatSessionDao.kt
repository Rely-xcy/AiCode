package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL AND NOT EXISTS (SELECT 1 FROM agent_messages WHERE sessionId = chat_sessions.id) ORDER BY isPinned DESC, updatedAt DESC LIMIT 1")
    suspend fun getEmptyRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 最近更新的根会话（忽略置顶，仅按 updatedAt 降序），供启动时「打开最近会话」。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 指定父会话的全部子会话（子代理），一次性查询。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    suspend fun getSubSessionsByParentOnce(parentId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    /** 单个会话的实时流（根会话与子会话通用）。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ChatSessionEntity?>

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_sessions WHERE workspacePath = :workspacePath")
    suspend fun deleteByWorkspace(workspacePath: String)

    /**
     * 换绑会话的模型/渠道，并把「上次请求的输入 token」清零。
     *
     * 清零是必要的：判定侧取 `max(lastInputTokens, 本地估算)`（见 CompactionModule.beforeLlmCall），
     * 而换模型前那一轮的真实用量是旧模型/旧上下文的值，留着会把新的一轮直接顶过硬压缩线——
     * 白花一次摘要调用，失败时还要弹一张红色卡片。清零后判定退回本地估算。
     */
    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model, lastInputTokens = 0 WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    /**
     * 模式切换的单条原子更新：`modeBeforePlan` 在 SQL 里根据当前值计算，
     * 不再「读整行 → 改两个字段 → 整行 upsert」——那样会覆盖并发写入的 updatedAt/token。
     */
    @Query(
        """
        UPDATE chat_sessions SET
            modeBeforePlan = CASE
                WHEN :newMode = 'PLAN' AND mode != 'PLAN' THEN mode
                WHEN :newMode = 'PLAN' THEN modeBeforePlan
                ELSE NULL
            END,
            mode = :newMode
        WHERE id = :id
        """
    )
    suspend fun updateMode(id: String, newMode: String)

    /** 设定/覆盖 goal：同时重置轮次计数与 token 快照。goal 为 null 表示清除。 */
    @Query("UPDATE chat_sessions SET goal = :goal, goalStatus = :status, goalTurnCount = 0, goalStartInputTokens = :startInput, goalStartOutputTokens = :startOutput WHERE id = :id")
    suspend fun updateGoal(id: String, goal: String?, status: String?, startInput: Int, startOutput: Int)

    @Query("UPDATE chat_sessions SET goalStatus = :status WHERE id = :id")
    suspend fun updateGoalStatus(id: String, status: String?)

    @Query("UPDATE chat_sessions SET goalTurnCount = goalTurnCount + 1 WHERE id = :id")
    suspend fun incrementGoalTurn(id: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)
}
