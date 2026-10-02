-- P2 ASR-only 单逻辑流的持久音频输入。streamId = meetingId。
-- 只保存当前写者/epoch、最高连续接管游标、完整声明和有界帧；不记录历史写者。
-- 模型处理游标、实时业务事件和 finalizationReceipt 属于后续独立事务边界。

BEGIN;

SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE);
SELECT pg_advisory_xact_lock(hashtextextended(
    format('%s:%s:%s', current_database(), current_schema(),
        'V015__durable_audio_input'), 0));

-- deploy/migrate.sh 在 SQL 返回后才登记版本；这里将 V015 DDL 与登记放在同一
-- 事务，避免表已提交却未登记的窗口。脚本随后的 ON CONFLICT 登记是 no-op。
-- 已登记的原样重跑不修改表；未登记但表已存在则 CREATE TABLE 关闭式失败。
DO $migrate_v015$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM schema_migrations
         WHERE version = 'V015__durable_audio_input') THEN
        CREATE TABLE meeting_audio_input (
            meeting_id                        TEXT NOT NULL,
            writer_id                         UUID NOT NULL,
            writer_epoch                      BIGINT NOT NULL,
            accepted_stream_sequence          BIGINT NOT NULL DEFAULT 0,
            accepted_audio_position_ms        BIGINT NOT NULL DEFAULT 0,
            complete_last_stream_sequence     BIGINT,
            complete_last_audio_position_ms   BIGINT,
            complete_recording_checksum       TEXT,
            complete_recording_size_bytes     BIGINT,
            complete_request_id               TEXT,

            CONSTRAINT meeting_audio_input_pkey PRIMARY KEY (meeting_id),
            CONSTRAINT meeting_audio_input_meeting_fk
                FOREIGN KEY (meeting_id) REFERENCES meeting (meeting_id)
                ON DELETE RESTRICT,
            CONSTRAINT meeting_audio_input_cursor_ck CHECK ((
                writer_epoch > 0
                AND accepted_stream_sequence >= 0
                AND accepted_audio_position_ms >= 0
                AND (accepted_stream_sequence > 0
                    OR accepted_audio_position_ms = 0)
            ) IS TRUE),
            CONSTRAINT meeting_audio_input_complete_ck CHECK ((
                (complete_last_stream_sequence IS NULL
                    AND complete_last_audio_position_ms IS NULL
                    AND complete_recording_checksum IS NULL
                    AND complete_recording_size_bytes IS NULL
                    AND complete_request_id IS NULL)
                OR
                (complete_last_stream_sequence > 0
                    AND complete_last_stream_sequence >= accepted_stream_sequence
                    AND complete_last_audio_position_ms >= accepted_audio_position_ms
                    AND ((complete_recording_checksum = ''
                            AND complete_recording_size_bytes = 0)
                        OR (complete_recording_checksum ~ '^[0-9a-f]{64}$'
                            AND complete_recording_size_bytes > 0))
                    AND length(complete_request_id) BETWEEN 1 AND 128
                    AND length(btrim(complete_request_id)) > 0
                    AND strpos(complete_request_id, chr(13)) = 0
                    AND strpos(complete_request_id, chr(10)) = 0)
            ) IS TRUE)
        );

        CREATE TABLE meeting_audio_frame (
            meeting_id                  TEXT NOT NULL,
            stream_sequence             BIGINT NOT NULL,
            start_position_ms           BIGINT NOT NULL,
            duration_ms                 INTEGER NOT NULL,
            audio                       BYTEA NOT NULL,
            source_generation           BIGINT NOT NULL,
            format_revision             INTEGER NOT NULL,
            gap_duration_ms_before      BIGINT NOT NULL,
            flags                       INTEGER NOT NULL,
            digest_version              SMALLINT NOT NULL,
            content_digest              BYTEA NOT NULL,

            CONSTRAINT meeting_audio_frame_pkey
                PRIMARY KEY (meeting_id, stream_sequence),
            CONSTRAINT meeting_audio_frame_input_fk
                FOREIGN KEY (meeting_id) REFERENCES meeting_audio_input (meeting_id)
                ON DELETE RESTRICT,
            CONSTRAINT meeting_audio_frame_shape_ck CHECK ((
                stream_sequence > 0
                AND start_position_ms >= 0
                AND duration_ms BETWEEN 1 AND 200
                AND octet_length(audio) = duration_ms * 32
                AND octet_length(audio) <= 6400
                AND source_generation >= 0
                AND format_revision >= 0
                AND gap_duration_ms_before >= 0
                AND flags >= 0 AND (flags & ~14) = 0
                AND digest_version = 1
                AND octet_length(content_digest) = 32
            ) IS TRUE)
        );

        INSERT INTO schema_migrations (version)
        VALUES ('V015__durable_audio_input');
    END IF;
END
$migrate_v015$;

COMMIT;
