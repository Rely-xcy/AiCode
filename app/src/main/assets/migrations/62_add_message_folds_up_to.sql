-- 阶段 0（上下文压缩重设计）：摘要块覆盖边界。
-- 仅 isContextSummary=1 的行有意义：foldsUpToTimestamp = 该摘要块覆盖的原文边界
-- （被折叠的最后一条消息的 timestamp）。回放据此裁窗口（边界之前的原文由摘要块链代表，
-- 不再重复回放），compact 不再把 head 标 isCompacted。
ALTER TABLE agent_messages ADD COLUMN foldsUpToTimestamp INTEGER NOT NULL DEFAULT 0;

-- 存量回填（保守）：给「仍在生效的旧摘要块」补边界 = 它之前最近的 isCompacted 普通行的时间戳
-- （旧规则下被该摘要折叠的最后一条）。排除 isContextSummary / isCompactionMarker 的行：
-- 旧规则 markSupersededCompactionRows 回收的旧摘要块也是 isCompacted=1，不排除会把边界
-- 抬到残留块自身的时间戳上（SQLite 实测会多算一截）。多轮折叠的残留对与存活对共享同一
-- 保留区起点，保守回填对残留块给出的边界与存活块相同；残留块本身在读取侧另有兜底
-- （foldsUpToTimestamp = 0 才走旧规则）不会重复回放，见 MessagePersistenceUseCase。
-- 边界取不到（摘要行之前没有 isCompacted 普通行）时保持 0，同样落入读取侧兜底。
-- 标量子查询无行时返回 NULL 而不是不返回行：不套 IFNULL 会直接撞 NOT NULL 约束，
-- 整个迁移（在同一事务里）回滚，升级失败。
UPDATE agent_messages
SET foldsUpToTimestamp = IFNULL((
    SELECT MAX(timestamp) FROM agent_messages m2
    WHERE m2.sessionId = agent_messages.sessionId
      AND m2.isCompacted = 1
      AND m2.isContextSummary = 0
      AND m2.isCompactionMarker = 0
      AND m2.timestamp < agent_messages.timestamp
), 0)
WHERE isContextSummary = 1 AND isCompacted = 0 AND foldsUpToTimestamp = 0;
