-- T16 实时续期凭据事实。V013 首次 Create 响应保持原样；本表仅保存续期。
-- 同键保存规范 Grant JSON 原文以逐字节重放，Token 只在该 JSON 中出现。
-- 不复制会议部署/主体列：每次操作从 meeting 与 V013 核对权威归属。

BEGIN;

SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE);
SELECT pg_advisory_xact_lock(hashtextextended(
    format('%s:%s:%s', current_database(), current_schema(),
        'V014__realtime_token_grant'), 0));

LOCK TABLE ONLY meeting IN ACCESS SHARE MODE;

DO $create_v014$
DECLARE
    grant_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_realtime_token_grant'));
BEGIN
    IF grant_table IS NULL THEN
        CREATE TABLE meeting_realtime_token_grant (
            meeting_id      TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            grant_json      TEXT NOT NULL,
            grant_sha256    BYTEA NOT NULL,
            token_sha256    BYTEA NOT NULL,
            issued_at       TIMESTAMPTZ NOT NULL,
            expires_at      TIMESTAMPTZ NOT NULL,

            CONSTRAINT meeting_realtime_token_grant_pkey
                PRIMARY KEY (meeting_id, idempotency_key),
            CONSTRAINT meeting_realtime_token_grant_token_uq
                UNIQUE (token_sha256),
            CONSTRAINT meeting_realtime_token_grant_meeting_fk
                FOREIGN KEY (meeting_id)
                REFERENCES meeting (meeting_id) ON DELETE RESTRICT,
            CONSTRAINT meeting_realtime_token_grant_key_ck CHECK ((
                length(btrim(meeting_id)) > 0
                AND length(idempotency_key) BETWEEN 16 AND 128
            ) IS TRUE),
            CONSTRAINT meeting_realtime_token_grant_digest_ck CHECK ((
                jsonb_typeof(grant_json::JSONB) = 'object'
                AND grant_json::JSONB ->> 'meetingId' = meeting_id
                AND grant_json::JSONB ->> 'streamId' = meeting_id
                AND length(grant_json::JSONB ->> 'sessionToken') > 0
                AND (grant_json::JSONB ->> 'expiresAt')::TIMESTAMPTZ = expires_at
                AND octet_length(grant_sha256) = 32
                AND grant_sha256 = sha256(convert_to(grant_json, 'UTF8'))
                AND octet_length(token_sha256) = 32
                AND token_sha256 = sha256(convert_to(
                    grant_json::JSONB ->> 'sessionToken', 'UTF8'))
            ) IS TRUE),
            CONSTRAINT meeting_realtime_token_grant_time_ck CHECK ((
                expires_at > issued_at
            ) IS TRUE)
        );
        CREATE INDEX meeting_realtime_token_grant_active_idx
            ON meeting_realtime_token_grant (meeting_id, expires_at);
    ELSE
        -- 仅支持“DDL 已提交、版本未登记”的精确原样重跑；不自动修补半表。
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = grant_table
               AND c.relkind = 'r' AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                   SELECT 1 FROM pg_inherits i
                    WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
            RAISE EXCEPTION 'V014 续期事实必须是独立普通持久表';
        END IF;
        EXECUTE format('LOCK TABLE ONLY %I.%I IN ACCESS SHARE MODE',
            current_schema(), 'meeting_realtime_token_grant');
    END IF;
END
$create_v014$;

DO $validate_v014$
DECLARE
    grant_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_realtime_token_grant'));
    meeting_table OID := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    actual_columns TEXT;
BEGIN
    IF grant_table IS NULL OR meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = grant_table
               AND c.relkind = 'r' AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                   SELECT 1 FROM pg_inherits i
                    WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        RAISE EXCEPTION 'V014 续期事实表物理形态不匹配';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>')
               || ':' || a.attidentity::TEXT || ':' || a.attgenerated::TEXT,
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = grant_table
       AND a.attnum > 0 AND NOT a.attisdropped;

    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>::|idempotency_key:text:true:<NULL>::|'
       'grant_json:text:true:<NULL>::|grant_sha256:bytea:true:<NULL>::|'
       'token_sha256:bytea:true:<NULL>::|'
       'issued_at:timestamp with time zone:true:<NULL>::|'
       'expires_at:timestamp with time zone:true:<NULL>::' THEN
        RAISE EXCEPTION 'V014 续期事实列物理契约不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = grant_table) <> 6
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = grant_table
               AND conname = 'meeting_realtime_token_grant_pkey'
               AND contype = 'p' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[1, 2]::SMALLINT[])
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = grant_table
               AND conname = 'meeting_realtime_token_grant_token_uq'
               AND contype = 'u' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[5]::SMALLINT[])
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = grant_table
               AND conname = 'meeting_realtime_token_grant_meeting_fk'
               AND contype = 'f' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[1]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'r' AND confupdtype = 'a'
               AND confmatchtype = 's') THEN
        RAISE EXCEPTION 'V014 续期事实 PK/UQ/FK 物理契约不匹配';
    END IF;

    -- 完整 PostgreSQL 16 规范定义逐字比对；包含原条件的 OR TRUE 弱约束
    -- 不能借助关键片段搜索混入已登记版本。
    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('meeting_realtime_token_grant_key_ck',
               'CHECK ((length(btrim(meeting_id)) > 0 AND '
               'length(idempotency_key) >= 16 AND '
               'length(idempotency_key) <= 128) IS TRUE)'),
              ('meeting_realtime_token_grant_digest_ck',
               'CHECK ((jsonb_typeof(grant_json::jsonb) = ''object''::text AND '
               '(grant_json::jsonb ->> ''meetingId''::text) = meeting_id AND '
               '(grant_json::jsonb ->> ''streamId''::text) = meeting_id AND '
               'length(grant_json::jsonb ->> ''sessionToken''::text) > 0 AND '
               '((grant_json::jsonb ->> ''expiresAt''::text)::timestamp with time zone) = expires_at AND '
               'octet_length(grant_sha256) = 32 AND '
               'grant_sha256 = sha256(convert_to(grant_json, ''UTF8''::name)) AND '
               'octet_length(token_sha256) = 32 AND '
               'token_sha256 = sha256(convert_to(grant_json::jsonb ->> '
               '''sessionToken''::text, ''UTF8''::name))) IS TRUE)'),
              ('meeting_realtime_token_grant_time_ck',
               'CHECK ((expires_at > issued_at) IS TRUE)'))
               AS expected(constraint_name, definition)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = grant_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c' AND actual.convalidated
           AND NOT actual.condeferrable AND NOT actual.condeferred
           AND pg_get_constraintdef(actual.oid, TRUE) = expected.definition
         WHERE actual.oid IS NULL) THEN
        RAISE EXCEPTION 'V014 续期事实 CHECK 物理契约不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_index WHERE indrelid = grant_table) <> 3
       OR EXISTS (
            SELECT expected.index_name
              FROM (VALUES
                  ('meeting_realtime_token_grant_pkey', '1 2', 2, TRUE, TRUE),
                  ('meeting_realtime_token_grant_token_uq', '5', 1, TRUE, FALSE),
                  ('meeting_realtime_token_grant_active_idx', '1 7', 2, FALSE, FALSE))
                   AS expected(index_name, keys, attributes, unique_index, primary_index)
              LEFT JOIN pg_class index_relation
                ON index_relation.relname = expected.index_name
               AND index_relation.relnamespace =
                   (SELECT relnamespace FROM pg_class WHERE oid = grant_table)
               AND index_relation.relkind = 'i'
               AND index_relation.relpersistence = 'p'
              LEFT JOIN pg_index actual
                ON actual.indexrelid = index_relation.oid
               AND actual.indrelid = grant_table
               AND actual.indkey::TEXT = expected.keys
               AND actual.indnkeyatts = expected.attributes
               AND actual.indnatts = expected.attributes
               AND actual.indisunique = expected.unique_index
               AND actual.indisprimary = expected.primary_index
               AND actual.indimmediate
               AND NOT actual.indnullsnotdistinct
               AND actual.indisvalid AND actual.indisready AND actual.indislive
               AND actual.indexprs IS NULL AND actual.indpred IS NULL
             WHERE actual.indexrelid IS NULL
                OR index_relation.relam <> (
                    SELECT oid FROM pg_am WHERE amname = 'btree')) THEN
        RAISE EXCEPTION 'V014 续期事实精确索引集合不匹配';
    END IF;
END
$validate_v014$;

COMMIT;
