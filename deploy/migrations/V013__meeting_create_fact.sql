-- 独立保存完整 Create 请求和首次响应事实；旧幂等表及 V001～V012 不变。
-- 本版本只建立物理契约，尚不接入 JDBC 工作单元或 HTTP Create。

BEGIN;

-- 固定部署 schema，避免同名临时表遮蔽；同一 schema 的 V013 串行执行。
SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE);
SELECT pg_advisory_xact_lock(hashtextextended(
    format('%s:%s:%s', current_database(), current_schema(),
        'V013__meeting_create_fact'), 0));

LOCK TABLE ONLY meeting IN ACCESS SHARE MODE;

DO $create_v013$
DECLARE
    fact_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_create_fact'));
BEGIN
    IF fact_table IS NULL THEN
        CREATE TABLE meeting_create_fact (
            scope                   TEXT NOT NULL,
            idempotency_key         TEXT NOT NULL,
            request_schema_version  INTEGER NOT NULL,
            request_digest          TEXT NOT NULL,
            request_json            TEXT NOT NULL,
            response_schema_version INTEGER NOT NULL,
            response_status         INTEGER NOT NULL,
            response_type           TEXT NOT NULL,
            response_json           TEXT NOT NULL,
            response_sha256         BYTEA NOT NULL,
            meeting_id              TEXT,
            created_at              TIMESTAMPTZ NOT NULL,

            CONSTRAINT meeting_create_fact_pkey
                PRIMARY KEY (scope, idempotency_key),
            CONSTRAINT meeting_create_fact_meeting_id_uq
                UNIQUE (meeting_id),
            CONSTRAINT meeting_create_fact_meeting_fk
                FOREIGN KEY (meeting_id)
                REFERENCES meeting (meeting_id) ON DELETE RESTRICT,
            CONSTRAINT meeting_create_fact_key_ck CHECK ((
                length(btrim(scope)) > 0
                AND length(btrim(idempotency_key)) > 0
            ) IS TRUE),
            CONSTRAINT meeting_create_fact_request_ck CHECK ((
                request_schema_version = 1
                AND request_digest ~ '^[0-9a-f]{64}$'
                AND jsonb_typeof(request_json::JSONB) = 'object'
            ) IS TRUE),
            CONSTRAINT meeting_create_fact_response_ck CHECK ((
                response_schema_version = 1
                AND jsonb_typeof(response_json::JSONB) = 'object'
                AND octet_length(response_sha256) = 32
                AND response_sha256 = sha256(convert_to(response_json, 'UTF8'))
            ) IS TRUE),
            CONSTRAINT meeting_create_fact_outcome_ck CHECK ((
                (response_type = 'SUCCESS'
                    AND response_status = 201
                    AND meeting_id IS NOT NULL
                    AND response_json::JSONB ->> 'meetingId' = meeting_id
                    AND response_json::JSONB ->> 'state' = 'CREATED')
                OR
                (response_type = 'FAILURE'
                    AND response_status BETWEEN 400 AND 599
                    AND meeting_id IS NULL)
            ) IS TRUE)
        );
    ELSE
        -- 版本登记失败后的重跑只能接受独立普通持久表；后续 catalog 门禁
        -- 会进一步冻结每一列、约束和索引的完整指纹。
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = fact_table
               AND c.relkind = 'r' AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                   SELECT 1 FROM pg_inherits i
                    WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
            RAISE EXCEPTION 'V013 创建事实必须是独立普通持久表';
        END IF;
    END IF;
END
$create_v013$;

COMMIT;
