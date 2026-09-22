-- 建立会议最终用量事实。
--
-- final_usage_fact 是会议级、不可覆盖的最终用量赢家。它只保存由已持久化
-- durable_input_fact 固化出的五个业务字段，不保存 Reservation、receipt 或模型
-- 厂商的计费字段；这些事实由更高层工作单元在同一事务中协调。

BEGIN;

-- V009 依赖 V001 提供的 meeting 表。这里拒绝 search_path 外的同名对象，避免
-- 在错误 schema 上误创建事实表。
DO $$
DECLARE
    meeting_table OID;
    durable_input_table OID;
    meeting_id_column RECORD;
BEGIN
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    durable_input_table := to_regclass(
            format('%I.%I', current_schema(), 'durable_input_fact'));
    IF meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = meeting_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 meeting 普通表，请先执行 V001';
    END IF;
    IF durable_input_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = durable_input_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 durable_input_fact 普通表，请先执行 V008';
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
CREATE TABLE IF NOT EXISTS final_usage_fact (
    meeting_id             TEXT NOT NULL,
    actual_unit            TEXT NOT NULL,
    actual_amount          BIGINT NOT NULL,
    source_input_binding   TEXT NOT NULL,
    source_input_digest    TEXT NOT NULL,
    source_accepted_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT final_usage_fact_pkey
        PRIMARY KEY (meeting_id),
    CONSTRAINT final_usage_fact_meeting_fk
        FOREIGN KEY (meeting_id)
        REFERENCES meeting (meeting_id)
        ON DELETE RESTRICT,
    CONSTRAINT final_usage_fact_actual_unit_ck CHECK (
        actual_unit IN ('MEETING_DURATION_MS')
    ),
    CONSTRAINT final_usage_fact_actual_amount_ck CHECK (actual_amount >= 0),
    CONSTRAINT final_usage_fact_source_input_binding_ck CHECK (
        length(btrim(source_input_binding)) > 0
    ),
    CONSTRAINT final_usage_fact_source_input_digest_ck CHECK (
        length(btrim(source_input_digest)) > 0
    )
);

-- CREATE TABLE IF NOT EXISTS 不会核对已有对象。本段固定列顺序、类型、可空性、
-- 主键、RESTRICT 外键和四个业务 CHECK，保证精确重跑成功而不接纳弱化结构。
DO $$
DECLARE
    fact_table OID;
    meeting_table OID;
    actual_columns TEXT;
    check_count INTEGER;
BEGIN
    fact_table := to_regclass(format('%I.%I', current_schema(), 'final_usage_fact'));
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    IF fact_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = fact_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION 'final_usage_fact 必须是当前 schema 的普通表';
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
       'meeting_id:text:true:<NULL>|actual_unit:text:true:<NULL>|'
       'actual_amount:bigint:true:<NULL>|source_input_binding:text:true:<NULL>|'
       'source_input_digest:text:true:<NULL>|'
       'source_accepted_at:timestamp with time zone:true:<NULL>' THEN
        RAISE EXCEPTION 'final_usage_fact 列物理契约不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = fact_table
           AND conname = 'final_usage_fact_pkey'
           AND contype = 'p'
           AND convalidated
           AND conkey = ARRAY[1]::SMALLINT[]) THEN
        RAISE EXCEPTION 'final_usage_fact 主键不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = fact_table
           AND conname = 'final_usage_fact_meeting_fk'
           AND contype = 'f'
           AND convalidated
           AND conkey = ARRAY[1]::SMALLINT[]
           AND confrelid = meeting_table
           AND confkey = ARRAY[1]::SMALLINT[]
           AND confdeltype = 'r') THEN
        RAISE EXCEPTION 'final_usage_fact meeting 外键不匹配';
    END IF;

    SELECT count(*)
      INTO check_count
      FROM pg_constraint
     WHERE conrelid = fact_table
       AND contype = 'c'
       AND conname IN (
           'final_usage_fact_actual_unit_ck',
           'final_usage_fact_actual_amount_ck',
           'final_usage_fact_source_input_binding_ck',
           'final_usage_fact_source_input_digest_ck')
       AND convalidated;
    IF check_count <> 4 THEN
        RAISE EXCEPTION 'final_usage_fact 四个 CHECK 约束缺失或未校验';
    END IF;

    -- 不能只查 CHECK 定义片段；例如“actual_amount >= 0 OR true”虽然包含
    -- 原片段，却已经把非负金额约束弱化为恒真。规范化空白后要求完整定义相等。
    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('final_usage_fact_actual_unit_ck',
               'CHECK (actual_unit = ''MEETING_DURATION_MS''::text)'),
              ('final_usage_fact_actual_amount_ck',
               'CHECK (actual_amount >= 0)'),
              ('final_usage_fact_source_input_binding_ck',
               'CHECK (length(btrim(source_input_binding)) > 0)'),
              ('final_usage_fact_source_input_digest_ck',
               'CHECK (length(btrim(source_input_digest)) > 0)'))
               AS expected(constraint_name, expected_definition)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = fact_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c'
           AND actual.convalidated
         WHERE actual.oid IS NULL
            OR regexp_replace(
                   pg_get_constraintdef(actual.oid, TRUE),
                   '[[:space:]]+', ' ', 'g') <> expected.expected_definition
    ) THEN
        RAISE EXCEPTION 'final_usage_fact CHECK 定义不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = fact_table) <> 6
       OR (SELECT count(*) FROM pg_constraint
            WHERE conrelid = fact_table AND contype = 'c') <> 4 THEN
        RAISE EXCEPTION 'final_usage_fact 约束集合不匹配';
    END IF;
END
$$;

COMMENT ON TABLE final_usage_fact IS
    '由 durable_input_fact 固化出的会议最终用量事实；每个会议只保留不可覆盖赢家';
COMMENT ON COLUMN final_usage_fact.actual_unit IS
    '标准用量单位；首版只允许 MEETING_DURATION_MS';
COMMENT ON COLUMN final_usage_fact.source_input_binding IS
    '来源 durable input fact 的稳定绑定标识';
COMMENT ON COLUMN final_usage_fact.source_input_digest IS
    '来源 durable input fact 的完整内容摘要';
COMMENT ON COLUMN final_usage_fact.source_accepted_at IS
    '来源 durable input fact 的接管时间；PostgreSQL TIMESTAMPTZ 按微秒保存';

COMMIT;
