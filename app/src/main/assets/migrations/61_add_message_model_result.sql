-- 软精简投影落库（其一）：TOOL 行增加 modelResult 列，存「喂模型那份」的精简副本。
-- result / content 保持原文，永不改写（界面与落库读原文），与 ToolCall.modelArguments 同一套对称。
-- 目的：投影跨轮次复用——此前它只活在内存态，每轮重建历史即丢失，软精简每轮从头重算，
-- 且 TokenEstimator 只能拿未精简的原文估算。
-- 可空：非 TOOL 行与从未被精简过的历史行一律为 NULL。
-- 不写 DEFAULT NULL，与迁移 58 的可空列写法一致（实体未声明 @ColumnInfo(defaultValue)，
-- 让 PRAGMA 的默认值就是 None，与 Room 期望的「无默认值」逐字对上）。
ALTER TABLE agent_messages ADD COLUMN modelResult TEXT;
