-- 为 canonical 会议事件补充协议版本和逻辑流标识。
--
-- V001 已经在部分环境执行，不能修改原文件。历史行没有可靠的
-- specVersion/streamId 来源，因此本迁移故意保留 NULL，不猜测值；新的
-- EventLogStore 只允许写入非空字段，读取历史 NULL 行时 fail closed，交由
-- 上层按数据修复流程处理。这样既不会伪造事件归属，也不会破坏旧数据库的
-- 结构升级。

BEGIN;

ALTER TABLE meeting_event
    ADD COLUMN IF NOT EXISTS spec_version TEXT;

ALTER TABLE meeting_event
    ADD COLUMN IF NOT EXISTS stream_id TEXT;

-- 两个 envelope 元数据必须成对出现；历史行允许两者同时为空，禁止只有一列
-- 有值的半迁移状态。新写入由 EventLog 构造器和适配器强制非空。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting_event'::regclass
           AND conname = 'meeting_event_envelope_pair_ck'
    ) THEN
        ALTER TABLE meeting_event
            ADD CONSTRAINT meeting_event_envelope_pair_ck
            CHECK (
                (spec_version IS NULL AND stream_id IS NULL)
                OR (length(btrim(spec_version)) > 0 AND length(btrim(stream_id)) > 0)
            );
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS meeting_event_stream_sequence_idx
    ON meeting_event (meeting_id, stream_id, event_sequence);

COMMIT;
