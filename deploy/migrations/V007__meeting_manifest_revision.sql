-- 建立完整 Manifest 的追加式 revision 事实表。
--
-- canonical_json 保存 MeetingManifestJson 冻结字段顺序产生的 UTF-8 文本，
-- manifest_json 是同一文档的 JSONB 查询镜像，content_sha256 是 canonical
-- 文本的原始 32 字节 SHA-256。三者必须相互一致；数据库不负责重新排序 JSON、
-- 生成 tombstone、回填旧会议或推断 Worker 状态。

BEGIN;

-- V007 只允许建立在完整 V006 物理形态之上。这里显式检查当前 schema，避免
-- search_path 中其它同名表或仅手工登记的迁移版本掩盖缺失前置事实。
DO $$
DECLARE
    meeting_table OID;
    manifest_column RECORD;
    constraint_name TEXT;
BEGIN
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    IF meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = meeting_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 meeting 普通表，请先执行 V001-V006';
    END IF;

    SELECT a.atttypid, a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expr
      INTO manifest_column
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid
       AND d.adnum = a.attnum
     WHERE a.attrelid = meeting_table
       AND a.attname = 'manifest_state'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF NOT FOUND
       OR manifest_column.atttypid <> 'text'::regtype
       OR NOT manifest_column.attnotnull
       OR manifest_column.default_expr IS DISTINCT FROM '''NOT_STARTED''::text' THEN
        RAISE EXCEPTION 'meeting.manifest_state 不符合 V006 物理契约';
    END IF;

    FOREACH constraint_name IN ARRAY ARRAY[
        'meeting_id_text_ck',
        'meeting_state_ck',
        'meeting_manifest_state_ck'
    ]
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint
             WHERE conrelid = meeting_table
               AND conname = constraint_name
               AND contype = 'c'
               AND convalidated) THEN
            RAISE EXCEPTION 'meeting 缺少已校验的 V006 约束：%', constraint_name;
        END IF;
    END LOOP;
END
$$;

-- IF NOT EXISTS 仅用于“DDL 已提交、schema_migrations 尚未登记”的精确重跑。
-- 若存在同名半表或异义对象，下面的 catalog 校验会在同一事务内失败，不会修补
-- 或接纳中间开发结构。
CREATE TABLE IF NOT EXISTS meeting_manifest_revision (
    meeting_id       TEXT NOT NULL,
    manifest_revision INTEGER NOT NULL,
    previous_revision INTEGER,
    canonical_json   TEXT NOT NULL,
    manifest_json    JSONB NOT NULL,
    content_sha256   BYTEA NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT meeting_manifest_revision_pkey
        PRIMARY KEY (meeting_id, manifest_revision),
    CONSTRAINT meeting_manifest_revision_meeting_fk
        FOREIGN KEY (meeting_id)
        REFERENCES meeting (meeting_id)
        ON DELETE RESTRICT,
    CONSTRAINT meeting_manifest_revision_previous_fk
        FOREIGN KEY (meeting_id, previous_revision)
        REFERENCES meeting_manifest_revision (meeting_id, manifest_revision)
        ON DELETE RESTRICT,

    -- revision 1 是唯一根节点；后续 revision 必须精确指向同一会议的前一版。
    CONSTRAINT meeting_manifest_revision_chain_ck CHECK (
        (manifest_revision = 1 AND previous_revision IS NULL)
        OR
        (manifest_revision > 1
            AND previous_revision IS NOT NULL
            AND previous_revision = manifest_revision - 1)
    ),

    -- canonical encoder 固定输出全部 12 个顶层字段（包括显式 null 的 title）。
    -- 嵌套字段的完整协议校验仍由 T14-01 codec 负责；数据库在物理边界关闭根字段、
    -- 会议身份、revision、最终状态以及 canonical/JSONB 镜像的不一致写入。
    CONSTRAINT meeting_manifest_revision_document_ck CHECK (
        jsonb_typeof(manifest_json) = 'object'
        AND manifest_json ?& ARRAY[
            'specVersion', 'meetingId', 'manifestRevision', 'profile', 'title',
            'status', 'startedAt', 'endedAt', 'durationMs', 'languages',
            'artifacts', 'warnings']::TEXT[]
        AND (manifest_json - ARRAY[
            'specVersion', 'meetingId', 'manifestRevision', 'profile', 'title',
            'status', 'startedAt', 'endedAt', 'durationMs', 'languages',
            'artifacts', 'warnings']::TEXT[]) = '{}'::JSONB
        AND jsonb_typeof(manifest_json -> 'meetingId') = 'string'
        AND manifest_json ->> 'meetingId' = meeting_id
        AND jsonb_typeof(manifest_json -> 'manifestRevision') = 'number'
        AND manifest_json ->> 'manifestRevision' ~ '^[1-9][0-9]*$'
        AND (manifest_json ->> 'manifestRevision')::NUMERIC <= 2147483647
        AND (manifest_json ->> 'manifestRevision')::NUMERIC = manifest_revision
        AND jsonb_typeof(manifest_json -> 'status') = 'string'
        AND manifest_json ->> 'status' IN (
            'READY',
            'READY_WITH_WARNINGS',
            'FAILED',
            'DELETION_PENDING',
            'DELETED')
        AND canonical_json::JSONB = manifest_json
    ),

    -- PostgreSQL 16 核心 sha256(bytea) 直接返回 32 字节摘要，不依赖 pgcrypto。
    CONSTRAINT meeting_manifest_revision_hash_ck CHECK (
        octet_length(content_sha256) = 32
        AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))
    )
);

-- CREATE TABLE IF NOT EXISTS 不会核对已有对象。本段对列、主键、外键和三个具名
-- CHECK 做关闭式确认，保证精确重跑成功，而半表、错列或同名弱约束整事务失败。
DO $$
DECLARE
    revision_table OID;
    meeting_table OID;
    actual_columns TEXT;
    check_count INTEGER;
BEGIN
    revision_table := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_manifest_revision'));
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));

    IF revision_table IS NULL
       OR NOT EXISTS (
            SELECT 1
              FROM pg_class
             WHERE oid = revision_table
               AND relkind = 'r') THEN
        RAISE EXCEPTION 'meeting_manifest_revision 必须是当前 schema 的普通表';
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
     WHERE a.attrelid = revision_table
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>|manifest_revision:integer:true:<NULL>|'
       'previous_revision:integer:false:<NULL>|canonical_json:text:true:<NULL>|'
       'manifest_json:jsonb:true:<NULL>|content_sha256:bytea:true:<NULL>|'
       'recorded_at:timestamp with time zone:true:CURRENT_TIMESTAMP' THEN
        RAISE EXCEPTION 'meeting_manifest_revision 列物理契约不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_pkey'
           AND contype = 'p'
           AND convalidated
           AND conkey = ARRAY[1, 2]::SMALLINT[]) THEN
        RAISE EXCEPTION 'meeting_manifest_revision 复合主键不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_meeting_fk'
           AND contype = 'f'
           AND convalidated
           AND conkey = ARRAY[1]::SMALLINT[]
           AND confrelid = meeting_table
           AND confkey = ARRAY[1]::SMALLINT[]
           AND confdeltype = 'r') THEN
        RAISE EXCEPTION 'meeting_manifest_revision meeting 外键不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_previous_fk'
           AND contype = 'f'
           AND convalidated
           AND conkey = ARRAY[1, 3]::SMALLINT[]
           AND confrelid = revision_table
           AND confkey = ARRAY[1, 2]::SMALLINT[]
           AND confdeltype = 'r') THEN
        RAISE EXCEPTION 'meeting_manifest_revision 前序自外键不匹配';
    END IF;

    SELECT count(*)
      INTO check_count
      FROM pg_constraint
     WHERE conrelid = revision_table
       AND contype = 'c'
       AND conname IN (
           'meeting_manifest_revision_chain_ck',
           'meeting_manifest_revision_document_ck',
           'meeting_manifest_revision_hash_ck')
       AND convalidated;
    IF check_count <> 3 THEN
        RAISE EXCEPTION 'meeting_manifest_revision 三个 CHECK 约束缺失或未校验';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = revision_table) <> 6
       OR (SELECT count(*) FROM pg_constraint
            WHERE conrelid = revision_table AND contype = 'c') <> 3 THEN
        RAISE EXCEPTION 'meeting_manifest_revision 约束集合不匹配';
    END IF;

    -- 同名但语义被替换为 CHECK (true) 或仅保留部分条件时必须拒绝。定义文本由
    -- PostgreSQL 16 的 pg_get_constraintdef 生成；运行时 readiness 使用同一组三个
    -- 冻结定义再次执行只读探针，防止登记迁移后发生结构漂移。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_chain_ck'
           AND pg_get_constraintdef(oid, TRUE) = $chain$CHECK (manifest_revision = 1 AND previous_revision IS NULL OR manifest_revision > 1 AND previous_revision IS NOT NULL AND previous_revision = (manifest_revision - 1))$chain$)
       OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_document_ck'
           AND pg_get_constraintdef(oid, TRUE) = $document$CHECK (jsonb_typeof(manifest_json) = 'object'::text AND manifest_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text, 'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text, 'artifacts'::text, 'warnings'::text] AND (manifest_json - ARRAY['specVersion'::text, 'meetingId'::text, 'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text, 'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text, 'artifacts'::text, 'warnings'::text]) = '{}'::jsonb AND jsonb_typeof(manifest_json -> 'meetingId'::text) = 'string'::text AND (manifest_json ->> 'meetingId'::text) = meeting_id AND jsonb_typeof(manifest_json -> 'manifestRevision'::text) = 'number'::text AND (manifest_json ->> 'manifestRevision'::text) ~ '^[1-9][0-9]*$'::text AND ((manifest_json ->> 'manifestRevision'::text)::numeric) <= 2147483647::numeric AND ((manifest_json ->> 'manifestRevision'::text)::numeric) = manifest_revision::numeric AND jsonb_typeof(manifest_json -> 'status'::text) = 'string'::text AND ((manifest_json ->> 'status'::text) = ANY (ARRAY['READY'::text, 'READY_WITH_WARNINGS'::text, 'FAILED'::text, 'DELETION_PENDING'::text, 'DELETED'::text])) AND canonical_json::jsonb = manifest_json)$document$)
       OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = revision_table
           AND conname = 'meeting_manifest_revision_hash_ck'
           AND pg_get_constraintdef(oid, TRUE) = $hash$CHECK (octet_length(content_sha256) = 32 AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))$hash$) THEN
        RAISE EXCEPTION 'meeting_manifest_revision CHECK 定义不匹配';
    END IF;
END
$$;

COMMENT ON TABLE meeting_manifest_revision IS
    '完整 Manifest 的追加式 revision 事实；不保存访问 genTime 或临时签名参数';
COMMENT ON COLUMN meeting_manifest_revision.canonical_json IS
    'MeetingManifestJson 生成的稳定 canonical JSON UTF-8 文本';
COMMENT ON COLUMN meeting_manifest_revision.manifest_json IS
    'canonical_json 的 JSONB 等价镜像，仅用于关系库校验和查询';
COMMENT ON COLUMN meeting_manifest_revision.content_sha256 IS
    'canonical_json UTF-8 字节的原始 32 字节 SHA-256';

COMMIT;
