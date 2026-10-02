-- 模式提醒从用户消息正文挪到独立列。此前提醒由 StatefulAgentWorkflow 拼在最新一条用户消息的
-- content 末尾（模式变化时注入一次，不进 system，避免打断前缀缓存），结果它成了消息正文的一部分。
-- 现在按「模型可见的那份 / 落库与界面那份」拆开：content 只留用户原话，提醒落在本列，
-- 组装请求时再拼回该条消息的文本（与 toolCalls 里的 modelArguments 同一套对称）。
-- 可空：只有模式变化的那一轮用户消息有值，历史行与其它消息一律为 NULL。
ALTER TABLE agent_messages ADD COLUMN modelReminder TEXT DEFAULT NULL;
