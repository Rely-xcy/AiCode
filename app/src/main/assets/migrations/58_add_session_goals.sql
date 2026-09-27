-- 会话级目标（目标文本 + 里程碑），独立于消息历史，避免被上下文压缩折叠掉。
CREATE TABLE IF NOT EXISTS session_goals (
    sessionId TEXT NOT NULL PRIMARY KEY,
    goalText TEXT NOT NULL,
    milestonesJson TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
