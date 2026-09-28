-- 固化最终收口执行事实，并把 V001 的 artifact 占位表收紧为 READY-only 元数据表。
--
-- finalization_execution_fact 只保存 T14-09-01 codec 的 canonical JSON、等价 JSONB
-- 镜像及 canonical UTF-8 的 32 字节 SHA-256。artifact 的一行只表示对应产物已经
-- READY；不保存状态、正文、checkpoint、Profile、租约、Manifest、URL、路径、凭据
-- 或任意开放式 metadata。

BEGIN;

-- 固定本次事务的目标 schema，并把 pg_temp 显式放到关系解析顺序的最后。
-- PostgreSQL 默认会在普通 search_path 之前隐式查找临时表；若不显式固定，迁移
-- 会话中的 TEMP meeting/artifact 可能遮蔽部署 schema 的同名表。is_local=true 只在
-- 本事务内生效，COMMIT/ROLLBACK 后恢复连接原 search_path。
SELECT set_config(
    'search_path', format('%I, pg_temp', current_schema()), TRUE);

-- 先按 database/schema/version 串行化两个同时执行的 V011。否则等待旧 artifact
-- OID 的会话可能在另一会话 DROP/重建后继续使用过期 OID。advisory lock 只
-- 序列化本版迁移；真实 Writer 仍由下方 artifact ACCESS EXCLUSIVE 表锁阻断。
SELECT pg_advisory_xact_lock(hashtextextended(
    format('%s:%s:%s', current_database(), current_schema(),
        'V011__finalization_execution_artifact_ready'), 0));

-- 锁必须在 catalog/空表检查之前完成，且按业务写入顺序
-- meeting -> finalization_execution_fact -> artifact 固定取得。execution 只需
-- ACCESS SHARE：它阻止并发 DDL 改变 catalog 身份，但不阻断正常 INSERT/UPDATE。
-- artifact 的 ACCESS EXCLUSIVE 同时保护 V001 检空与 DROP。该顺序避免精确重跑时
-- 与 execution -> artifact 的业务事务形成反向等待环。
LOCK TABLE ONLY meeting IN ACCESS SHARE MODE;
DO $$
BEGIN
    IF to_regclass(format(
            '%I.%I', current_schema(), 'finalization_execution_fact')) IS NOT NULL THEN
        EXECUTE format(
            'LOCK TABLE ONLY %I.%I IN ACCESS SHARE MODE',
            current_schema(), 'finalization_execution_fact');
    END IF;
END
$$;
LOCK TABLE ONLY artifact IN ACCESS EXCLUSIVE MODE;

-- V011 只接受当前 schema 中独立的 meeting 普通持久表，以及以下两种
-- artifact 物理形态：
-- 1. V001 原始表，且一行数据都没有；迁移可以安全 DROP 后按冻结契约重建。
-- 2. 已经完整迁移成 V011 的表；用于“DDL 已提交、版本尚未登记”的精确重跑。
-- 半 V011、错列、弱 CHECK、错误外键、view 或带历史行的 V001 表均关闭式失败。
DO $$
DECLARE
    meeting_table OID;
    artifact_table OID;
    execution_table OID;
    actual_columns TEXT;
    v001_shape BOOLEAN := FALSE;
    v011_shape BOOLEAN := FALSE;
    execution_v011_shape BOOLEAN := FALSE;
BEGIN
    -- 顶层 LOCK 已在前一条 SQL 结束等待；本 DO 使用新语句快照解析 OID，
    -- catalog 核对不使用等锁前的过期身份。
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    artifact_table := to_regclass(format('%I.%I', current_schema(), 'artifact'));
    execution_table := to_regclass(format(
        '%I.%I', current_schema(), 'finalization_execution_fact'));
    -- V011 的两个新外键依赖 V001 meeting 的第 1 列主键身份。只有
    -- TEXT NOT NULL + 无默认值 + 精确非延迟 meeting_pkey 可以作为父契约；
    -- nullable UNIQUE 等“可被外键引用”的近似结构不是可接受的 V001 基线。
    IF meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = meeting_table
               AND c.relkind = 'r'
               AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                    SELECT 1 FROM pg_inherits i
                     WHERE i.inhrelid = c.oid OR i.inhparent = c.oid))
       OR NOT EXISTS (
            SELECT 1
              FROM pg_attribute a
              LEFT JOIN pg_attrdef d
                ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE a.attrelid = meeting_table
               AND a.attnum = 1
               AND NOT a.attisdropped
               AND a.attname = 'meeting_id'
               AND format_type(a.atttypid, a.atttypmod) = 'text'
               AND a.attnotnull
               AND a.attidentity = ''
               AND a.attgenerated = ''
               AND d.oid IS NULL)
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = meeting_table
               AND conname = 'meeting_pkey'
               AND contype = 'p'
               AND convalidated
               AND NOT condeferrable
               AND NOT condeferred
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (meeting_id)') THEN
        RAISE EXCEPTION 'meeting.meeting_id 不符合 V001 TEXT NOT NULL 主键契约';
    END IF;
    IF artifact_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = artifact_table
               AND c.relkind = 'r'
               AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                    SELECT 1 FROM pg_inherits i
                     WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        RAISE EXCEPTION '取得迁移锁后 artifact 普通持久表身份已变更';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid
       AND d.adnum = a.attnum
     WHERE a.attrelid = artifact_table
       AND a.attnum > 0
       AND NOT a.attisdropped;

    v001_shape := actual_columns =
       'artifact_id:text:true:<NULL>|meeting_id:text:true:<NULL>|'
       'artifact_type:text:true:<NULL>|revision:integer:true:<NULL>|'
       'content_type:text:true:<NULL>|byte_length:bigint:false:<NULL>|'
       'storage_binding_id:text:true:<NULL>|sha256:text:false:<NULL>|'
       'metadata:jsonb:true:''{}''::jsonb|'
       'created_at:timestamp with time zone:true:<NULL>'
       AND (SELECT count(*) FROM pg_constraint
             WHERE conrelid = artifact_table) = 5
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_pkey'
               AND contype = 'p' AND convalidated
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (artifact_id)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_meeting_id_fkey'
               AND contype = 'f' AND convalidated
               AND conkey = ARRAY[2]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'a'
               AND pg_get_constraintdef(oid, TRUE) =
                   'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_revision_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[2, 3, 4]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'UNIQUE (meeting_id, artifact_type, revision)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_revision_check'
               AND contype = 'c' AND convalidated
               AND pg_get_constraintdef(oid, TRUE) = 'CHECK (revision > 0)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_byte_length_check'
               AND contype = 'c' AND convalidated
               AND pg_get_constraintdef(oid, TRUE) =
                   'CHECK (byte_length IS NULL OR byte_length >= 0)');

    v011_shape := actual_columns =
       'artifact_id:text:true:<NULL>|meeting_id:text:true:<NULL>|'
       'artifact_type:text:true:<NULL>|media_type:text:true:<NULL>|'
       'language:text:false:<NULL>|revision:integer:true:<NULL>|'
       'intent_id:text:true:<NULL>|storage_binding:text:true:<NULL>|'
       'content_size:bigint:true:<NULL>|content_checksum:text:true:<NULL>|'
       'recorded_at:timestamp with time zone:true:CURRENT_TIMESTAMP'
       AND (SELECT count(*) FROM pg_constraint
             WHERE conrelid = artifact_table) = 10
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_pkey'
               AND contype = 'p' AND convalidated
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (artifact_id)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_meeting_fk'
               AND contype = 'f' AND convalidated
               AND conkey = ARRAY[2]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'r'
               AND pg_get_constraintdef(oid, TRUE) =
                   'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_revision_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[2, 3, 6]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'UNIQUE (meeting_id, artifact_type, revision)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_intent_id_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[7]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) = 'UNIQUE (intent_id)')
       AND EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_storage_binding_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[8]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) = 'UNIQUE (storage_binding)')
       AND NOT EXISTS (
            SELECT 1
              FROM (VALUES
                  ('artifact_text_ck',
                   $text$CHECK (length(translate(artifact_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(artifact_id, 'UTF8'::name)) <= 4096 AND length(translate(meeting_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(meeting_id, 'UTF8'::name)) <= 4096 AND length(translate(artifact_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(artifact_type, 'UTF8'::name)) <= 4096 AND length(translate(media_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(media_type, 'UTF8'::name)) <= 4096 AND length(translate(intent_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(intent_id, 'UTF8'::name)) <= 4096 AND length(translate(storage_binding, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(storage_binding, 'UTF8'::name)) <= 4096 AND length(translate(content_checksum, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(content_checksum, 'UTF8'::name)) <= 4096)$text$),
                  ('artifact_language_ck',
                   $language$CHECK (language IS NULL OR length(translate(language, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(language, 'UTF8'::name)) <= 4096)$language$),
                  ('artifact_revision_ck', 'CHECK (revision > 0)'),
                  ('artifact_content_size_ck', 'CHECK (content_size >= 0)'),
                  ('artifact_content_checksum_ck',
                   $checksum$CHECK (content_checksum ~ '^sha256:[0-9a-f]{64}$'::text)$checksum$))
                   AS expected(constraint_name, expected_definition)
              LEFT JOIN pg_constraint actual
                ON actual.conrelid = artifact_table
               AND actual.conname = expected.constraint_name
               AND actual.contype = 'c'
               AND actual.convalidated
             WHERE actual.oid IS NULL
                OR regexp_replace(
                       pg_get_constraintdef(actual.oid, TRUE),
                       '[[:space:]]+', ' ', 'g') <> expected.expected_definition);

    -- 两张 V011 表必须同时完整存在才是“DDL 已提交但版本未登记”的精确重跑。
    -- artifact 已迁移但 execution fact 缺失，或首次迁移前已经有任何同名 execution
    -- 对象，都属于人工/半成品结构，不能由 IF NOT EXISTS 静默补齐。
    IF execution_table IS NOT NULL
       AND EXISTS (
            SELECT 1 FROM pg_class c
             WHERE c.oid = execution_table
               AND c.relkind = 'r'
               AND c.relpersistence = 'p'
               AND NOT c.relispartition
               AND NOT EXISTS (
                    SELECT 1 FROM pg_inherits i
                     WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        SELECT string_agg(
                   a.attname || ':' || format_type(a.atttypid, a.atttypmod)
                   || ':' || a.attnotnull::TEXT || ':'
                   || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
                   '|' ORDER BY a.attnum)
          INTO actual_columns
          FROM pg_attribute a
          LEFT JOIN pg_attrdef d
            ON d.adrelid = a.attrelid AND d.adnum = a.attnum
         WHERE a.attrelid = execution_table
           AND a.attnum > 0 AND NOT a.attisdropped;

        execution_v011_shape := actual_columns =
           'meeting_id:text:true:<NULL>|canonical_json:text:true:<NULL>|'
           'execution_fact_json:jsonb:true:<NULL>|content_sha256:bytea:true:<NULL>'
           AND (SELECT count(*) FROM pg_constraint
                 WHERE conrelid = execution_table) = 4
           AND EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conrelid = execution_table
                   AND conname = 'finalization_execution_fact_pkey'
                   AND contype = 'p' AND convalidated
                   AND conkey = ARRAY[1]::SMALLINT[]
                   AND pg_get_constraintdef(oid, TRUE) =
                       'PRIMARY KEY (meeting_id)')
           AND EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conrelid = execution_table
                   AND conname = 'finalization_execution_fact_meeting_fk'
                   AND contype = 'f' AND convalidated
                   AND conkey = ARRAY[1]::SMALLINT[]
                   AND confrelid = meeting_table
                   AND confkey = ARRAY[1]::SMALLINT[]
                   AND confdeltype = 'r'
                   AND pg_get_constraintdef(oid, TRUE) =
                       'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT')
           AND EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conrelid = execution_table
                   AND conname = 'finalization_execution_fact_document_ck'
                   AND contype = 'c' AND convalidated
                   AND regexp_replace(pg_get_constraintdef(oid, TRUE),
                           '[[:space:]]+', ' ', 'g') =
                       $document$CHECK (jsonb_typeof(execution_fact_json) = 'object'::text AND execution_fact_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text] AND (execution_fact_json - ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]) = '{}'::jsonb AND jsonb_typeof(execution_fact_json -> 'specVersion'::text) = 'string'::text AND (execution_fact_json ->> 'specVersion'::text) = 'finalization-execution-fact/v1'::text AND jsonb_typeof(execution_fact_json -> 'meetingId'::text) = 'string'::text AND (execution_fact_json ->> 'meetingId'::text) = meeting_id AND jsonb_typeof(execution_fact_json -> 'startedAt'::text) = 'string'::text AND (execution_fact_json ->> 'startedAt'::text) ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'::text AND (jsonb_typeof(execution_fact_json -> 'title'::text) = ANY (ARRAY['string'::text, 'null'::text])) AND jsonb_typeof(execution_fact_json -> 'productProfile'::text) = 'object'::text AND jsonb_typeof(execution_fact_json -> 'capabilityPlan'::text) = 'object'::text AND canonical_json::jsonb = execution_fact_json)$document$)
           AND EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conrelid = execution_table
                   AND conname = 'finalization_execution_fact_hash_ck'
                   AND contype = 'c' AND convalidated
                   AND pg_get_constraintdef(oid, TRUE) =
                       $hash$CHECK (octet_length(content_sha256) = 32 AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))$hash$);
    END IF;

    IF v011_shape THEN
        IF NOT execution_v011_shape THEN
            RAISE EXCEPTION 'artifact 已是 V011，但 execution fact 缺失或不完整';
        END IF;
        -- 两张表均精确时，重跑不得 DROP、UPDATE 或重写任何事实。
        RETURN;
    END IF;
    IF NOT v001_shape THEN
        RAISE EXCEPTION 'artifact 既不是精确 V001，也不是精确 V011 物理契约';
    END IF;
    IF execution_table IS NOT NULL THEN
        RAISE EXCEPTION '首次 V011 迁移前不允许存在 execution fact 同名对象';
    END IF;
    IF EXISTS (SELECT 1 FROM artifact LIMIT 1) THEN
        RAISE EXCEPTION 'artifact 已有 V001 旧行，无法证明 READY，拒绝 V011 迁移';
    END IF;

    DROP TABLE artifact;
END
$$;

-- IF NOT EXISTS 仅用于 DDL 已提交但 schema_migrations 尚未登记的精确重跑。
CREATE TABLE IF NOT EXISTS finalization_execution_fact (
    meeting_id          TEXT NOT NULL,
    canonical_json      TEXT NOT NULL,
    execution_fact_json JSONB NOT NULL,
    content_sha256      BYTEA NOT NULL,

    CONSTRAINT finalization_execution_fact_pkey
        PRIMARY KEY (meeting_id),
    CONSTRAINT finalization_execution_fact_meeting_fk
        FOREIGN KEY (meeting_id)
        REFERENCES meeting (meeting_id)
        ON DELETE RESTRICT,

    -- codec 固定输出六个根字段。数据库关闭根集合、协议版本、会议身份、开始时间
    -- 微秒上限、可空标题、Profile/Plan 对象类型及 canonical/JSONB 镜像。
    CONSTRAINT finalization_execution_fact_document_ck CHECK (
        jsonb_typeof(execution_fact_json) = 'object'
        AND execution_fact_json ?& ARRAY[
            'specVersion', 'meetingId', 'startedAt', 'title',
            'productProfile', 'capabilityPlan']::TEXT[]
        AND (execution_fact_json - ARRAY[
            'specVersion', 'meetingId', 'startedAt', 'title',
            'productProfile', 'capabilityPlan']::TEXT[]) = '{}'::JSONB
        AND jsonb_typeof(execution_fact_json -> 'specVersion') = 'string'
        AND execution_fact_json ->> 'specVersion' =
            'finalization-execution-fact/v1'
        AND jsonb_typeof(execution_fact_json -> 'meetingId') = 'string'
        AND execution_fact_json ->> 'meetingId' = meeting_id
        AND jsonb_typeof(execution_fact_json -> 'startedAt') = 'string'
        AND execution_fact_json ->> 'startedAt' ~
            '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'
        AND jsonb_typeof(execution_fact_json -> 'title') IN ('string', 'null')
        AND jsonb_typeof(execution_fact_json -> 'productProfile') = 'object'
        AND jsonb_typeof(execution_fact_json -> 'capabilityPlan') = 'object'
        AND canonical_json::JSONB = execution_fact_json
    ),
    CONSTRAINT finalization_execution_fact_hash_ck CHECK (
        octet_length(content_sha256) = 32
        AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))
    )
);

CREATE TABLE IF NOT EXISTS artifact (
    artifact_id       TEXT NOT NULL,
    meeting_id        TEXT NOT NULL,
    artifact_type     TEXT NOT NULL,
    media_type        TEXT NOT NULL,
    language          TEXT,
    revision          INTEGER NOT NULL,
    intent_id         TEXT NOT NULL,
    storage_binding   TEXT NOT NULL,
    content_size      BIGINT NOT NULL,
    content_checksum  TEXT NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT artifact_pkey PRIMARY KEY (artifact_id),
    CONSTRAINT artifact_meeting_fk
        FOREIGN KEY (meeting_id)
        REFERENCES meeting (meeting_id)
        ON DELETE RESTRICT,
    CONSTRAINT artifact_revision_uq
        UNIQUE (meeting_id, artifact_type, revision),
    CONSTRAINT artifact_intent_id_uq UNIQUE (intent_id),
    CONSTRAINT artifact_storage_binding_uq UNIQUE (storage_binding),

    -- 与 Java 21 String.isBlank() 使用同一组 Character.isWhitespace 码点。
    -- PostgreSQL btrim(text) 默认只移除 U+0020，会错放 TAB/LF/CR-only 值。
    CONSTRAINT artifact_text_ck CHECK (
        length(translate(artifact_id, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(artifact_id, 'UTF8')) <= 4096
        AND length(translate(meeting_id, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(meeting_id, 'UTF8')) <= 4096
        AND length(translate(artifact_type, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(artifact_type, 'UTF8')) <= 4096
        AND length(translate(media_type, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(media_type, 'UTF8')) <= 4096
        AND length(translate(intent_id, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(intent_id, 'UTF8')) <= 4096
        AND length(translate(storage_binding, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(storage_binding, 'UTF8')) <= 4096
        AND length(translate(content_checksum, concat(
            chr(9), chr(10), chr(11), chr(12), chr(13),
            chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
            chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
            chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
            chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
        AND octet_length(convert_to(content_checksum, 'UTF8')) <= 4096
    ),
    CONSTRAINT artifact_language_ck CHECK (
        language IS NULL OR (
            length(translate(language, concat(
                chr(9), chr(10), chr(11), chr(12), chr(13),
                chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760),
                chr(8192), chr(8193), chr(8194), chr(8195), chr(8196),
                chr(8197), chr(8198), chr(8200), chr(8201), chr(8202),
                chr(8232), chr(8233), chr(8287), chr(12288)), '')) > 0
            AND octet_length(convert_to(language, 'UTF8')) <= 4096)
    ),
    CONSTRAINT artifact_revision_ck CHECK (revision > 0),
    CONSTRAINT artifact_content_size_ck CHECK (content_size >= 0),
    CONSTRAINT artifact_content_checksum_ck CHECK (
        content_checksum ~ '^sha256:[0-9a-f]{64}$'
    )
);

-- CREATE TABLE IF NOT EXISTS 不验证已有对象。这里再次精确核对两个 V011 表的列、
-- 持久性/分区/继承、全部约束数、PK/FK/UQ conkey/confdeltype、CHECK validated
-- 与完整定义。
DO $$
DECLARE
    meeting_table OID;
    execution_table OID;
    artifact_table OID;
    actual_columns TEXT;
BEGIN
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    execution_table := to_regclass(format(
        '%I.%I', current_schema(), 'finalization_execution_fact'));
    artifact_table := to_regclass(format('%I.%I', current_schema(), 'artifact'));

    IF meeting_table IS NULL OR execution_table IS NULL OR artifact_table IS NULL
       OR EXISTS (
            SELECT 1
              FROM (VALUES (meeting_table), (execution_table), (artifact_table))
                   AS required(relation_oid)
              LEFT JOIN pg_class c ON c.oid = required.relation_oid
             WHERE c.oid IS NULL
                OR c.relkind <> 'r'
                OR c.relpersistence <> 'p'
                OR c.relispartition
                OR EXISTS (
                    SELECT 1 FROM pg_inherits i
                     WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        RAISE EXCEPTION 'V011 事实对象必须是当前 schema 的独立普通持久表';
    END IF;

    IF NOT EXISTS (
            SELECT 1
              FROM pg_attribute a
              LEFT JOIN pg_attrdef d
                ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE a.attrelid = meeting_table
               AND a.attnum = 1
               AND NOT a.attisdropped
               AND a.attname = 'meeting_id'
               AND format_type(a.atttypid, a.atttypmod) = 'text'
               AND a.attnotnull
               AND a.attidentity = ''
               AND a.attgenerated = ''
               AND d.oid IS NULL)
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = meeting_table
               AND conname = 'meeting_pkey'
               AND contype = 'p'
               AND convalidated
               AND NOT condeferrable
               AND NOT condeferred
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (meeting_id)') THEN
        RAISE EXCEPTION 'V011 完成后 meeting.meeting_id 主键契约不匹配';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = execution_table
       AND a.attnum > 0 AND NOT a.attisdropped;
    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>|canonical_json:text:true:<NULL>|'
       'execution_fact_json:jsonb:true:<NULL>|content_sha256:bytea:true:<NULL>' THEN
        RAISE EXCEPTION 'finalization_execution_fact 列物理契约不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid = execution_table) <> 4
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = execution_table
               AND conname = 'finalization_execution_fact_pkey'
               AND contype = 'p' AND convalidated
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (meeting_id)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = execution_table
               AND conname = 'finalization_execution_fact_meeting_fk'
               AND contype = 'f' AND convalidated
               AND conkey = ARRAY[1]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'r'
               AND pg_get_constraintdef(oid, TRUE) =
                   'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT') THEN
        RAISE EXCEPTION 'finalization_execution_fact PK/FK 物理契约不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = execution_table
           AND conname = 'finalization_execution_fact_document_ck'
           AND contype = 'c' AND convalidated
           AND regexp_replace(pg_get_constraintdef(oid, TRUE),
                   '[[:space:]]+', ' ', 'g') =
               $document$CHECK (jsonb_typeof(execution_fact_json) = 'object'::text AND execution_fact_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text] AND (execution_fact_json - ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]) = '{}'::jsonb AND jsonb_typeof(execution_fact_json -> 'specVersion'::text) = 'string'::text AND (execution_fact_json ->> 'specVersion'::text) = 'finalization-execution-fact/v1'::text AND jsonb_typeof(execution_fact_json -> 'meetingId'::text) = 'string'::text AND (execution_fact_json ->> 'meetingId'::text) = meeting_id AND jsonb_typeof(execution_fact_json -> 'startedAt'::text) = 'string'::text AND (execution_fact_json ->> 'startedAt'::text) ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'::text AND (jsonb_typeof(execution_fact_json -> 'title'::text) = ANY (ARRAY['string'::text, 'null'::text])) AND jsonb_typeof(execution_fact_json -> 'productProfile'::text) = 'object'::text AND jsonb_typeof(execution_fact_json -> 'capabilityPlan'::text) = 'object'::text AND canonical_json::jsonb = execution_fact_json)$document$
       ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = execution_table
           AND conname = 'finalization_execution_fact_hash_ck'
           AND contype = 'c' AND convalidated
           AND pg_get_constraintdef(oid, TRUE) =
               $hash$CHECK (octet_length(content_sha256) = 32 AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))$hash$
       ) THEN
        RAISE EXCEPTION 'finalization_execution_fact CHECK 定义不匹配';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = artifact_table
       AND a.attnum > 0 AND NOT a.attisdropped;
    IF actual_columns IS DISTINCT FROM
       'artifact_id:text:true:<NULL>|meeting_id:text:true:<NULL>|'
       'artifact_type:text:true:<NULL>|media_type:text:true:<NULL>|'
       'language:text:false:<NULL>|revision:integer:true:<NULL>|'
       'intent_id:text:true:<NULL>|storage_binding:text:true:<NULL>|'
       'content_size:bigint:true:<NULL>|content_checksum:text:true:<NULL>|'
       'recorded_at:timestamp with time zone:true:CURRENT_TIMESTAMP' THEN
        RAISE EXCEPTION 'artifact V011 列物理契约不匹配';
    END IF;

    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = artifact_table) <> 10
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_pkey'
               AND contype = 'p' AND convalidated
               AND conkey = ARRAY[1]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'PRIMARY KEY (artifact_id)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_meeting_fk'
               AND contype = 'f' AND convalidated
               AND conkey = ARRAY[2]::SMALLINT[]
               AND confrelid = meeting_table
               AND confkey = ARRAY[1]::SMALLINT[]
               AND confdeltype = 'r'
               AND pg_get_constraintdef(oid, TRUE) =
                   'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_revision_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[2, 3, 6]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) =
                   'UNIQUE (meeting_id, artifact_type, revision)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_intent_id_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[7]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) = 'UNIQUE (intent_id)')
       OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = artifact_table
               AND conname = 'artifact_storage_binding_uq'
               AND contype = 'u' AND convalidated
               AND conkey = ARRAY[8]::SMALLINT[]
               AND pg_get_constraintdef(oid, TRUE) = 'UNIQUE (storage_binding)') THEN
        RAISE EXCEPTION 'artifact V011 PK/FK/UQ 物理契约不匹配';
    END IF;

    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('artifact_text_ck',
               $text$CHECK (length(translate(artifact_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(artifact_id, 'UTF8'::name)) <= 4096 AND length(translate(meeting_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(meeting_id, 'UTF8'::name)) <= 4096 AND length(translate(artifact_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(artifact_type, 'UTF8'::name)) <= 4096 AND length(translate(media_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(media_type, 'UTF8'::name)) <= 4096 AND length(translate(intent_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(intent_id, 'UTF8'::name)) <= 4096 AND length(translate(storage_binding, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(storage_binding, 'UTF8'::name)) <= 4096 AND length(translate(content_checksum, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(content_checksum, 'UTF8'::name)) <= 4096)$text$),
              ('artifact_language_ck',
               $language$CHECK (language IS NULL OR length(translate(language, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0 AND octet_length(convert_to(language, 'UTF8'::name)) <= 4096)$language$),
              ('artifact_revision_ck', 'CHECK (revision > 0)'),
              ('artifact_content_size_ck', 'CHECK (content_size >= 0)'),
              ('artifact_content_checksum_ck',
               $checksum$CHECK (content_checksum ~ '^sha256:[0-9a-f]{64}$'::text)$checksum$))
               AS expected(constraint_name, expected_definition)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = artifact_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c'
           AND actual.convalidated
         WHERE actual.oid IS NULL
            OR regexp_replace(pg_get_constraintdef(actual.oid, TRUE),
                   '[[:space:]]+', ' ', 'g') <> expected.expected_definition
    ) THEN
        RAISE EXCEPTION 'artifact V011 CHECK 定义不匹配';
    END IF;
END
$$;

COMMENT ON TABLE finalization_execution_fact IS
    '最终收口 codec 的不可覆盖执行事实；每个会议一个 canonical JSON 赢家';
COMMENT ON TABLE artifact IS
    '已确认 READY 的产物元数据；存在一行即表示对应 storage binding 可发布';
COMMENT ON COLUMN artifact.intent_id IS
    '产物写入 intent 的全局幂等身份；READY 后不可复用到其它 artifact';
COMMENT ON COLUMN artifact.storage_binding IS
    '不含 URL、路径或凭据的稳定存储绑定身份';

COMMIT;
