-- 运行中插话标记。仅 USER 行有值：workflow 在工具批次边界（及权限弹窗前 / LLM 流式结束后的检查点）
-- 送达用户插话时写 1，UI 据此把它归入当前轮、不开新轮头（与 isBackgroundNotification 同类处理）。
-- 可空默认 0：历史行与普通用户消息一律为 0。
ALTER TABLE agent_messages ADD COLUMN isInterjection INTEGER NOT NULL DEFAULT 0;
