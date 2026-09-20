-- 为 canonical 会议事件补充协议版本、逻辑流标识和原始规范正文。
--
-- V001 已经在部分环境执行，不能修改原文件。历史行没有可靠的
-- specVersion/streamId/canonicalPayload 来源，因此本迁移故意保留 NULL，
-- 不猜测值；新的 EventLogStore 只允许三个字段同时非空，读取历史 NULL 行时
-- fail closed，交由上层按数据修复流程处理。JSONB payload 保留索引和查询能力，
-- canonical_payload 保存上游已经规范化的原始字符串，用于精确重放与原样返回。

BEGIN;

ALTER TABLE meeting_event
    ADD COLUMN IF NOT EXISTS spec_version TEXT;

ALTER TABLE meeting_event
    ADD COLUMN IF NOT EXISTS stream_id TEXT;

ALTER TABLE meeting_event
    ADD COLUMN IF NOT EXISTS canonical_payload TEXT;

-- 三个 envelope 字段必须成组出现；历史行允许三者同时为空，禁止部分字段有值。
-- 新事件还必须保证原始 canonical 文本可解析且与 JSONB payload 逻辑一致。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting_event'::regclass
           AND conname = 'meeting_event_envelope_group_ck'
    ) THEN
        ALTER TABLE meeting_event
            ADD CONSTRAINT meeting_event_envelope_group_ck
            CHECK (
                (spec_version IS NULL
                    AND stream_id IS NULL
                    AND canonical_payload IS NULL)
                OR (
                    spec_version IS NOT NULL
                    AND stream_id IS NOT NULL
                    AND canonical_payload IS NOT NULL
                    AND length(btrim(spec_version)) > 0
                    AND length(btrim(stream_id)) > 0
                    AND CASE
                        WHEN length(btrim(canonical_payload)) > 0
                        THEN canonical_payload::jsonb = payload
                        ELSE FALSE
                    END
                )
            );
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS meeting_event_stream_sequence_idx
    ON meeting_event (meeting_id, stream_id, event_sequence);

COMMIT;
