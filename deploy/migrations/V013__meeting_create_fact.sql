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
        -- 会在同一事务中冻结每一列、约束和索引的完整指纹。
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
        EXECUTE format(
            'LOCK TABLE ONLY %I.%I IN ACCESS SHARE MODE',
            current_schema(), 'meeting_create_fact');
    END IF;
END
$create_v013$;

-- 已有表分支只处理“DDL 已提交但版本未登记”的重跑，绝不能
-- 接受同名错表、半表、弱约束或额外索引。以下探针把物理形状冻结在 V013，
-- 任何失败都发生在本事务内，deploy/migrate.sh 因此不会登记该版本。
DO $validate_v013$
DECLARE
    fact_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_create_fact'));
    meeting_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting'));
    actual_columns TEXT;
BEGIN
    IF fact_table IS NULL
       OR meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = fact_table
               AND c.relkind = 'r'
               AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                   SELECT 1 FROM pg_inherits i
                    WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        RAISE EXCEPTION 'meeting_create_fact 必须是独立普通持久表';
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
     WHERE a.attrelid = fact_table
       AND a.attnum > 0 AND NOT a.attisdropped;

    IF actual_columns IS DISTINCT FROM
       'scope:text:true:<NULL>::|idempotency_key:text:true:<NULL>::|'
       'request_schema_version:integer:true:<NULL>::|request_digest:text:true:<NULL>::|'
       'request_json:text:true:<NULL>::|response_schema_version:integer:true:<NULL>::|'
       'response_status:integer:true:<NULL>::|response_type:text:true:<NULL>::|'
       'response_json:text:true:<NULL>::|response_sha256:bytea:true:<NULL>::|'
       'meeting_id:text:false:<NULL>::|created_at:timestamp with time zone:true:<NULL>::' THEN
        RAISE EXCEPTION 'meeting_create_fact 列物理契约不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = fact_table) <> 7
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = fact_table
               AND conname = 'meeting_create_fact_pkey'
               AND contype = 'p' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[1, 2]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (scope, idempotency_key)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = fact_table
               AND conname = 'meeting_create_fact_meeting_id_uq'
               AND contype = 'u' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[11]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) = 'UNIQUE (meeting_id)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = fact_table
               AND conname = 'meeting_create_fact_meeting_fk'
               AND contype = 'f' AND convalidated
               AND NOT condeferrable AND NOT condeferred
               AND conkey = ARRAY[11]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'r' AND confupdtype = 'a'
               AND confmatchtype = 's'
               AND pg_get_constraintdef(oid, TRUE) =
                   'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT') THEN
        RAISE EXCEPTION 'meeting_create_fact PK/UQ/FK 物理契约不匹配';
    END IF;

    -- 约束名、类型、校验状态和关键谓词必须全部存在；不能用 CHECK (true)
    -- 或删掉成功 meeting_id 与 JSON/哈希绑定条件来冒充 V013。
    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('meeting_create_fact_key_ck',
               'length(btrim(scope))', 'length(btrim(idempotency_key))'),
              ('meeting_create_fact_request_ck',
               'request_schema_version = 1', 'request_digest'),
              ('meeting_create_fact_response_ck',
               'response_schema_version = 1', 'response_sha256'),
              ('meeting_create_fact_outcome_ck',
               'response_type', 'meeting_id'))
               AS expected(constraint_name, required_one, required_two)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = fact_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c'
           AND actual.convalidated
           AND NOT actual.condeferrable AND NOT actual.condeferred
           AND pg_get_constraintdef(actual.oid, TRUE) LIKE
               '%' || expected.required_one || '%'
           AND pg_get_constraintdef(actual.oid, TRUE) LIKE
               '%' || expected.required_two || '%'
         WHERE actual.oid IS NULL) THEN
        RAISE EXCEPTION 'meeting_create_fact CHECK 定义缺失或被弱化';
    END IF;

    IF (SELECT count(*) FROM pg_index WHERE indrelid = fact_table) <> 2
       OR EXISTS (
            SELECT 1 FROM pg_index
             WHERE indrelid = fact_table
               AND (NOT indisvalid OR NOT indisready OR NOT indislive
                    OR indexprs IS NOT NULL OR indpred IS NOT NULL)) THEN
        RAISE EXCEPTION 'meeting_create_fact 精确索引集合不匹配';
    END IF;
END
$validate_v013$;

COMMIT;
