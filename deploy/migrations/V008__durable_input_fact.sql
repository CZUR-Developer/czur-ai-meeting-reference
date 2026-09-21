-- 建立最终收口前已经由后端完整接管的输入事实。
--
-- durable_input_fact 与 meeting 一对一。它只保存可跨进程恢复的输入材料，
-- 不保存 finalization receipt，也不推进 meeting.input_state；receipt 仍由更高层
-- 的 connection-scoped 协同工作单元在同一事务中生成。

BEGIN;

-- V008 只能建立在已经完成 V001 的 meeting 表上。与 V007 一样，迁移本身不
-- 猜测 search_path 外的同名对象，也不为未发布的中间开发库补回填逻辑。
DO $$
DECLARE
    meeting_table OID;
    meeting_id_column RECORD;
BEGIN
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    IF meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = meeting_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 meeting 普通表，请先执行 V001';
    END IF;

    SELECT a.atttypid, a.attnotnull
      INTO meeting_id_column
      FROM pg_attribute a
     WHERE a.attrelid = meeting_table
       AND a.attname = 'meeting_id'
       AND a.attnum > 0
       AND NOT a.attisdropped;
    IF NOT FOUND
       OR meeting_id_column.atttypid <> 'text'::regtype
       OR NOT meeting_id_column.attnotnull THEN
        RAISE EXCEPTION 'meeting.meeting_id 不符合 V001 物理契约';
    END IF;
END
$$;

-- IF NOT EXISTS 仅覆盖“DDL 已提交、schema_migrations 尚未登记”的精确重跑。
-- 后面的 catalog 校验会拒绝半表、错列或同名弱约束。
CREATE TABLE IF NOT EXISTS durable_input_fact (
    meeting_id              TEXT NOT NULL,
    input_binding           TEXT NOT NULL,
    last_accepted_sequence  BIGINT NOT NULL,
    audio_duration_ms       BIGINT NOT NULL,
    source_language         TEXT NOT NULL,
    target_languages        TEXT[] NOT NULL,
    accepted_at             TIMESTAMPTZ NOT NULL,
    content_digest          TEXT NOT NULL,

    CONSTRAINT durable_input_fact_pkey
        PRIMARY KEY (meeting_id),
    CONSTRAINT durable_input_fact_meeting_fk
        FOREIGN KEY (meeting_id)
        REFERENCES meeting (meeting_id)
        ON DELETE RESTRICT,
    CONSTRAINT durable_input_fact_sequence_ck CHECK (last_accepted_sequence >= 0),
    CONSTRAINT durable_input_fact_duration_ck CHECK (audio_duration_ms >= 0),
    CONSTRAINT durable_input_fact_input_binding_ck CHECK (length(btrim(input_binding)) > 0),
    CONSTRAINT durable_input_fact_source_language_ck CHECK (length(btrim(source_language)) > 0),
    CONSTRAINT durable_input_fact_target_languages_ck CHECK (
            cardinality(target_languages) >= 0
            AND (cardinality(target_languages) = 0 OR array_ndims(target_languages) = 1)
            AND array_position(target_languages, NULL) IS NULL
            AND array_position(target_languages, '') IS NULL
        ),
    CONSTRAINT durable_input_fact_content_digest_ck CHECK (length(btrim(content_digest)) > 0)
);

-- CREATE TABLE IF NOT EXISTS 不会核对已有对象。本段固定列顺序、类型、可空性、
-- 主键、RESTRICT 外键以及全部基本 CHECK，保证精确重跑成功而不接纳弱化结构。
DO $$
DECLARE
    fact_table OID;
    meeting_table OID;
    actual_columns TEXT;
    check_count INTEGER;
BEGIN
    fact_table := to_regclass(format('%I.%I', current_schema(), 'durable_input_fact'));
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    IF fact_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = fact_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION 'durable_input_fact 必须是当前 schema 的普通表';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT
               || ':' || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid
       AND d.adnum = a.attnum
     WHERE a.attrelid = fact_table
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>|input_binding:text:true:<NULL>|'
       'last_accepted_sequence:bigint:true:<NULL>|audio_duration_ms:bigint:true:<NULL>|'
       'source_language:text:true:<NULL>|target_languages:text[]:true:<NULL>|'
       'accepted_at:timestamp with time zone:true:<NULL>|content_digest:text:true:<NULL>' THEN
        RAISE EXCEPTION 'durable_input_fact 列物理契约不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = fact_table
           AND conname = 'durable_input_fact_pkey'
           AND contype = 'p'
           AND convalidated
           AND conkey = ARRAY[1]::SMALLINT[]) THEN
        RAISE EXCEPTION 'durable_input_fact 主键不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = fact_table
           AND conname = 'durable_input_fact_meeting_fk'
           AND contype = 'f'
           AND convalidated
           AND conkey = ARRAY[1]::SMALLINT[]
           AND confrelid = meeting_table
           AND confkey = ARRAY[1]::SMALLINT[]
           AND confdeltype = 'r') THEN
        RAISE EXCEPTION 'durable_input_fact meeting 外键不匹配';
    END IF;

    SELECT count(*)
      INTO check_count
      FROM pg_constraint
     WHERE conrelid = fact_table
       AND contype = 'c'
       AND conname IN (
           'durable_input_fact_sequence_ck',
           'durable_input_fact_duration_ck',
           'durable_input_fact_input_binding_ck',
           'durable_input_fact_source_language_ck',
           'durable_input_fact_target_languages_ck',
           'durable_input_fact_content_digest_ck')
       AND convalidated;
    IF check_count <> 6 THEN
        RAISE EXCEPTION 'durable_input_fact 六个 CHECK 约束缺失或未校验';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = fact_table) <> 8
       OR (SELECT count(*) FROM pg_constraint
            WHERE conrelid = fact_table AND contype = 'c') <> 6 THEN
        RAISE EXCEPTION 'durable_input_fact 约束集合不匹配';
    END IF;
END
$$;

COMMENT ON TABLE durable_input_fact IS
    '最终收口前后端已完整接管的输入事实；不包含 finalization receipt';
COMMENT ON COLUMN durable_input_fact.input_binding IS
    '稳定输入绑定标识，不是连接池或模型厂商任务号';
COMMENT ON COLUMN durable_input_fact.content_digest IS
    '输入内容稳定摘要；编码和算法由应用端口约束';

COMMIT;
