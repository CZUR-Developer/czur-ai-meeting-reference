-- 冻结 Artifact 删除计划、逐项删除审计，以及 Meeting DELETED 时间等价关系。
--
-- 本迁移只建立删除闭环所需的物理事实，不回填或猜测开发期删除状态。首次执行
-- 如果发现任何 DELETION_PENDING、DELETED 或非空 deleted_at，整事务失败；只有
-- 两张表和 meeting 约束同时精确存在时才允许“DDL 已提交、版本未登记”的原样重跑。

BEGIN;

-- 临时同名表不得遮蔽部署 schema。该 search_path 仅在本事务内生效。
SELECT set_config(
    'search_path', format('%I, pg_temp', current_schema()), TRUE);

-- 同一 database/schema 的 V012 串行执行。锁序固定为 Meeting -> 来源 Workflow ->
-- 来源 Manifest -> 删除计划 -> 删除 item，和后续删除受理事务保持一致。
SELECT pg_advisory_xact_lock(hashtextextended(
    format('%s:%s:%s', current_database(), current_schema(),
        'V012__artifact_deletion_plan'), 0));

LOCK TABLE ONLY meeting IN ACCESS EXCLUSIVE MODE;
LOCK TABLE ONLY workflow_step IN ACCESS SHARE MODE;
LOCK TABLE ONLY meeting_manifest_revision IN ACCESS SHARE MODE;

DO $lock_existing$
DECLARE
    relation_name TEXT;
    relation_oid OID;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'meeting_deletion_plan', 'artifact_deletion_item'
    ]
    LOOP
        relation_oid := to_regclass(format(
            '%I.%I', current_schema(), relation_name));
        IF relation_oid IS NOT NULL THEN
            IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                 WHERE c.oid = relation_oid
                   AND c.relkind = 'r'
                   AND c.relpersistence = 'p'
                   AND NOT c.relispartition
                   AND NOT EXISTS (
                        SELECT 1 FROM pg_inherits i
                         WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
                RAISE EXCEPTION '% 必须是独立普通持久表', relation_name;
            END IF;
            EXECUTE format(
                'LOCK TABLE ONLY %I.%I IN ACCESS EXCLUSIVE MODE',
                current_schema(), relation_name);
        END IF;
    END LOOP;
END
$lock_existing$;

DO $create_v012$
DECLARE
    plan_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_deletion_plan'));
    item_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'artifact_deletion_item'));
    equivalence_constraint OID;
BEGIN
    SELECT oid
      INTO equivalence_constraint
      FROM pg_constraint
     WHERE conrelid = to_regclass(format('%I.%I', current_schema(), 'meeting'))
       AND conname = 'meeting_deleted_at_equivalence_ck';

    -- 三个对象必须处于“全部不存在”或“全部存在”两态。任何半表、半约束都拒绝，
    -- 不使用 IF NOT EXISTS 修补开发期结构。
    IF plan_table IS NOT NULL
       OR item_table IS NOT NULL
       OR equivalence_constraint IS NOT NULL THEN
        IF plan_table IS NULL
           OR item_table IS NULL
           OR equivalence_constraint IS NULL THEN
            RAISE EXCEPTION 'V012 删除物理契约只存在部分对象，拒绝自动修补';
        END IF;
        RETURN;
    END IF;

    -- V012 之前不存在可证明的删除计划。发现旧删除状态时不能猜测计划、来源或时间。
    IF EXISTS (
        SELECT 1 FROM meeting
         WHERE state IN ('DELETION_PENDING', 'DELETED')
            OR manifest_state IN ('DELETION_PENDING', 'DELETED')
            OR deleted_at IS NOT NULL
         LIMIT 1) THEN
        RAISE EXCEPTION 'V012 首次迁移发现无法证明来源的旧删除状态';
    END IF;

    EXECUTE $ddl$
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_deleted_at_equivalence_ck CHECK (
                (state = 'DELETED'
                    AND manifest_state = 'DELETED'
                    AND deleted_at IS NOT NULL
                    AND deleted_at = updated_at)
                OR
                (state <> 'DELETED'
                    AND manifest_state <> 'DELETED'
                    AND deleted_at IS NULL)
            )
    $ddl$;

    EXECUTE $ddl$
        CREATE TABLE meeting_deletion_plan (
            meeting_id                       TEXT NOT NULL,
            accepted_at                      TIMESTAMPTZ NOT NULL,
            source_manifest_revision         INTEGER,
            source_workflow_step_type         TEXT,
            source_workflow_step_key          TEXT,
            source_workflow_checkpoint_type  TEXT,
            source_workflow_content_digest   TEXT,
            target_count                     INTEGER NOT NULL,
            canonical_json                   TEXT NOT NULL,
            deletion_plan_json               JSONB NOT NULL,
            content_sha256                   BYTEA NOT NULL,
            status                           TEXT NOT NULL,
            completed_at                     TIMESTAMPTZ,

            CONSTRAINT meeting_deletion_plan_pkey
                PRIMARY KEY (meeting_id),
            CONSTRAINT meeting_deletion_plan_meeting_fk
                FOREIGN KEY (meeting_id)
                REFERENCES meeting (meeting_id) ON DELETE RESTRICT,
            CONSTRAINT meeting_deletion_plan_manifest_fk
                FOREIGN KEY (meeting_id, source_manifest_revision)
                REFERENCES meeting_manifest_revision
                    (meeting_id, manifest_revision) ON DELETE RESTRICT,
            CONSTRAINT meeting_deletion_plan_workflow_fk
                FOREIGN KEY (
                    meeting_id, source_workflow_step_type,
                    source_workflow_step_key)
                REFERENCES workflow_step (meeting_id, step_type, step_key)
                ON DELETE RESTRICT,

            CONSTRAINT meeting_deletion_plan_source_ck CHECK ((
                (source_workflow_step_type IS NULL
                    AND source_workflow_step_key IS NULL
                    AND source_workflow_checkpoint_type IS NULL
                    AND source_workflow_content_digest IS NULL)
                OR
                (source_workflow_step_type IS NOT NULL
                    AND source_workflow_step_key IS NOT NULL
                    AND source_workflow_checkpoint_type IS NOT NULL
                    AND source_workflow_content_digest IS NOT NULL
                    AND source_workflow_step_type = 'mock-finalization'
                    AND length(translate(source_workflow_step_key,
                        concat(chr(9), chr(10), chr(11), chr(12), chr(13),
                            chr(28), chr(29), chr(30), chr(31), chr(32),
                            chr(5760), chr(8192), chr(8193), chr(8194),
                            chr(8195), chr(8196), chr(8197), chr(8198),
                            chr(8200), chr(8201), chr(8202), chr(8232),
                            chr(8233), chr(8287), chr(12288)), '')) > 0
                    AND octet_length(convert_to(
                        source_workflow_step_key, 'UTF8')) <= 4096
                    AND source_workflow_checkpoint_type =
                        'mock-finalization-checkpoint/v1'
                    AND source_workflow_content_digest ~
                        '^sha256:[0-9a-f]{64}$')
            ) IS TRUE),
            CONSTRAINT meeting_deletion_plan_target_count_ck CHECK (
                target_count >= 0 AND target_count <= 256),
            CONSTRAINT meeting_deletion_plan_document_ck CHECK ((
                jsonb_typeof(deletion_plan_json) = 'object'
                AND deletion_plan_json ?& ARRAY[
                    'specVersion', 'meetingId', 'acceptedAt',
                    'sourceManifestRevision', 'sourceFinalizationWorkflow',
                    'targets']::TEXT[]
                AND (deletion_plan_json - ARRAY[
                    'specVersion', 'meetingId', 'acceptedAt',
                    'sourceManifestRevision', 'sourceFinalizationWorkflow',
                    'targets']::TEXT[]) = '{}'::JSONB
                AND jsonb_typeof(deletion_plan_json -> 'specVersion') = 'string'
                AND deletion_plan_json ->> 'specVersion' =
                    'artifact-deletion-plan/v1'
                AND jsonb_typeof(deletion_plan_json -> 'meetingId') = 'string'
                AND deletion_plan_json ->> 'meetingId' = meeting_id
                AND jsonb_typeof(deletion_plan_json -> 'acceptedAt') = 'string'
                AND deletion_plan_json ->> 'acceptedAt' ~
                    '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'
                AND (deletion_plan_json ->> 'acceptedAt')::TIMESTAMPTZ = accepted_at
                AND (
                    (source_manifest_revision IS NULL
                        AND jsonb_typeof(deletion_plan_json
                            -> 'sourceManifestRevision') = 'null')
                    OR
                    (source_manifest_revision IS NOT NULL
                        AND jsonb_typeof(deletion_plan_json
                            -> 'sourceManifestRevision') = 'number'
                        AND deletion_plan_json ->> 'sourceManifestRevision'
                            ~ '^[1-9][0-9]*$'
                        AND (deletion_plan_json
                            ->> 'sourceManifestRevision')::NUMERIC
                            <= 2147483647::NUMERIC
                        AND (deletion_plan_json
                            ->> 'sourceManifestRevision')::NUMERIC
                            = source_manifest_revision::NUMERIC)
                )
                AND (
                    (source_workflow_step_type IS NULL
                        AND source_workflow_step_key IS NULL
                        AND source_workflow_checkpoint_type IS NULL
                        AND source_workflow_content_digest IS NULL
                        AND jsonb_typeof(deletion_plan_json
                            -> 'sourceFinalizationWorkflow') = 'null')
                    OR
                    (source_workflow_step_type IS NOT NULL
                        AND source_workflow_step_key IS NOT NULL
                        AND source_workflow_checkpoint_type IS NOT NULL
                        AND source_workflow_content_digest IS NOT NULL
                        AND jsonb_typeof(deletion_plan_json
                            -> 'sourceFinalizationWorkflow') = 'object'
                        AND (deletion_plan_json
                            -> 'sourceFinalizationWorkflow') ?& ARRAY[
                                'meetingId', 'stepType', 'stepKey',
                                'checkpointType', 'contentDigest']::TEXT[]
                        AND ((deletion_plan_json
                            -> 'sourceFinalizationWorkflow') - ARRAY[
                                'meetingId', 'stepType', 'stepKey',
                                'checkpointType', 'contentDigest']::TEXT[])
                            = '{}'::JSONB
                        AND jsonb_typeof(deletion_plan_json
                            #> '{sourceFinalizationWorkflow,meetingId}') = 'string'
                        AND jsonb_typeof(deletion_plan_json
                            #> '{sourceFinalizationWorkflow,stepType}') = 'string'
                        AND jsonb_typeof(deletion_plan_json
                            #> '{sourceFinalizationWorkflow,stepKey}') = 'string'
                        AND jsonb_typeof(deletion_plan_json
                            #> '{sourceFinalizationWorkflow,checkpointType}') = 'string'
                        AND jsonb_typeof(deletion_plan_json
                            #> '{sourceFinalizationWorkflow,contentDigest}') = 'string'
                        AND deletion_plan_json
                            #>> '{sourceFinalizationWorkflow,meetingId}' = meeting_id
                        AND deletion_plan_json
                            #>> '{sourceFinalizationWorkflow,stepType}'
                            = source_workflow_step_type
                        AND deletion_plan_json
                            #>> '{sourceFinalizationWorkflow,stepKey}'
                            = source_workflow_step_key
                        AND deletion_plan_json
                            #>> '{sourceFinalizationWorkflow,checkpointType}'
                            = source_workflow_checkpoint_type
                        AND deletion_plan_json
                            #>> '{sourceFinalizationWorkflow,contentDigest}'
                            = source_workflow_content_digest)
                )
                AND jsonb_typeof(deletion_plan_json -> 'targets') = 'array'
                AND jsonb_array_length(deletion_plan_json -> 'targets') = target_count
                AND canonical_json::JSONB = deletion_plan_json
            ) IS TRUE),
            CONSTRAINT meeting_deletion_plan_hash_ck CHECK (
                octet_length(content_sha256) = 32
                AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))
            ),
            CONSTRAINT meeting_deletion_plan_status_ck CHECK (
                (status = 'PENDING' AND completed_at IS NULL)
                OR
                (status = 'COMPLETED' AND completed_at IS NOT NULL
                    AND completed_at >= accepted_at)
            )
        )
    $ddl$;

    EXECUTE $ddl$
        CREATE TABLE artifact_deletion_item (
            meeting_id      TEXT NOT NULL,
            ordinal         INTEGER NOT NULL,
            artifact_id     TEXT NOT NULL,
            artifact_type   TEXT NOT NULL,
            revision        INTEGER NOT NULL,
            status          TEXT NOT NULL,
            storage_binding TEXT,
            deleted_at      TIMESTAMPTZ,

            CONSTRAINT artifact_deletion_item_pkey
                PRIMARY KEY (meeting_id, ordinal),
            CONSTRAINT artifact_deletion_item_plan_fk
                FOREIGN KEY (meeting_id)
                REFERENCES meeting_deletion_plan (meeting_id)
                ON DELETE RESTRICT,
            CONSTRAINT artifact_deletion_item_artifact_id_uq
                UNIQUE (artifact_id),
            CONSTRAINT artifact_deletion_item_revision_uq
                UNIQUE (meeting_id, artifact_type, revision),
            CONSTRAINT artifact_deletion_item_ordinal_ck CHECK (
                ordinal >= 0 AND ordinal < 256),
            CONSTRAINT artifact_deletion_item_text_ck CHECK (
                length(translate(artifact_id,
                    concat(chr(9), chr(10), chr(11), chr(12), chr(13),
                        chr(28), chr(29), chr(30), chr(31), chr(32),
                        chr(5760), chr(8192), chr(8193), chr(8194),
                        chr(8195), chr(8196), chr(8197), chr(8198),
                        chr(8200), chr(8201), chr(8202), chr(8232),
                        chr(8233), chr(8287), chr(12288)), '')) > 0
                AND octet_length(convert_to(artifact_id, 'UTF8')) <= 4096
                AND length(translate(artifact_type,
                    concat(chr(9), chr(10), chr(11), chr(12), chr(13),
                        chr(28), chr(29), chr(30), chr(31), chr(32),
                        chr(5760), chr(8192), chr(8193), chr(8194),
                        chr(8195), chr(8196), chr(8197), chr(8198),
                        chr(8200), chr(8201), chr(8202), chr(8232),
                        chr(8233), chr(8287), chr(12288)), '')) > 0
                AND octet_length(convert_to(artifact_type, 'UTF8')) <= 4096
                AND (storage_binding IS NULL
                    OR (length(translate(storage_binding,
                        concat(chr(9), chr(10), chr(11), chr(12), chr(13),
                            chr(28), chr(29), chr(30), chr(31), chr(32),
                            chr(5760), chr(8192), chr(8193), chr(8194),
                            chr(8195), chr(8196), chr(8197), chr(8198),
                            chr(8200), chr(8201), chr(8202), chr(8232),
                            chr(8233), chr(8287), chr(12288)), '')) > 0
                        AND octet_length(convert_to(
                            storage_binding, 'UTF8')) <= 4096))
            ),
            CONSTRAINT artifact_deletion_item_revision_ck CHECK (revision > 0),
            CONSTRAINT artifact_deletion_item_status_ck CHECK (
                (status = 'PENDING'
                    AND storage_binding IS NULL AND deleted_at IS NULL)
                OR
                (status = 'DELETED'
                    AND storage_binding IS NOT NULL AND deleted_at IS NOT NULL)
            )
        )
    $ddl$;
END
$create_v012$;

-- CREATE 分支和精确重跑分支共同经过同一份完整 catalog 门禁。
DO $validate_v012$
DECLARE
    meeting_table OID := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    workflow_table OID := to_regclass(format('%I.%I', current_schema(), 'workflow_step'));
    manifest_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_manifest_revision'));
    plan_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'meeting_deletion_plan'));
    item_table OID := to_regclass(format(
        '%I.%I', current_schema(), 'artifact_deletion_item'));
    actual_columns TEXT;
BEGIN
    IF plan_table IS NULL OR item_table IS NULL THEN
        RAISE EXCEPTION 'V012 删除表缺失';
    END IF;

    IF EXISTS (
        SELECT expected.table_oid
          FROM (VALUES (plan_table), (item_table)) AS expected(table_oid)
          LEFT JOIN pg_class c ON c.oid = expected.table_oid
         WHERE c.oid IS NULL OR c.relkind <> 'r' OR c.relpersistence <> 'p'
            OR c.relispartition
            OR EXISTS (SELECT 1 FROM pg_inherits i
                        WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)) THEN
        RAISE EXCEPTION 'V012 删除表必须是独立普通持久表';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>') || ':'
               || a.attidentity::TEXT || ':' || a.attgenerated::TEXT,
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = plan_table AND a.attnum > 0 AND NOT a.attisdropped;
    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>::|accepted_at:timestamp with time zone:true:<NULL>::|'
       'source_manifest_revision:integer:false:<NULL>::|'
       'source_workflow_step_type:text:false:<NULL>::|'
       'source_workflow_step_key:text:false:<NULL>::|'
       'source_workflow_checkpoint_type:text:false:<NULL>::|'
       'source_workflow_content_digest:text:false:<NULL>::|'
       'target_count:integer:true:<NULL>::|canonical_json:text:true:<NULL>::|'
       'deletion_plan_json:jsonb:true:<NULL>::|content_sha256:bytea:true:<NULL>::|'
       'status:text:true:<NULL>::|completed_at:timestamp with time zone:false:<NULL>::' THEN
        RAISE EXCEPTION 'meeting_deletion_plan 13 列物理契约不匹配';
    END IF;

    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>') || ':'
               || a.attidentity::TEXT || ':' || a.attgenerated::TEXT,
               '|' ORDER BY a.attnum)
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = item_table AND a.attnum > 0 AND NOT a.attisdropped;
    IF actual_columns IS DISTINCT FROM
       'meeting_id:text:true:<NULL>::|ordinal:integer:true:<NULL>::|'
       'artifact_id:text:true:<NULL>::|artifact_type:text:true:<NULL>::|'
       'revision:integer:true:<NULL>::|status:text:true:<NULL>::|'
       'storage_binding:text:false:<NULL>::|'
       'deleted_at:timestamp with time zone:false:<NULL>::' THEN
        RAISE EXCEPTION 'artifact_deletion_item 8 列物理契约不匹配';
    END IF;

    -- Meeting 等价约束必须具名、已校验，并精确绑定 state/manifest/deleted_at。
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = meeting_table
           AND conname = 'meeting_deleted_at_equivalence_ck'
           AND contype = 'c' AND convalidated
           AND NOT condeferrable AND NOT condeferred
           AND regexp_replace(pg_get_constraintdef(oid, TRUE),
                   '[[:space:]]+', ' ', 'g') =
               $ck$CHECK (state = 'DELETED'::text AND manifest_state = 'DELETED'::text AND deleted_at IS NOT NULL AND deleted_at = updated_at OR state <> 'DELETED'::text AND manifest_state <> 'DELETED'::text AND deleted_at IS NULL)$ck$) THEN
        RAISE EXCEPTION 'meeting DELETED/deleted_at 等价约束不匹配';
    END IF;

    -- 约束数量、结构和动作全部精确。所有 FK 必须显式 RESTRICT、不可延迟、已校验。
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = plan_table) <> 9
       OR (SELECT count(*) FROM pg_constraint WHERE conrelid = item_table) <> 8 THEN
        RAISE EXCEPTION 'V012 删除表约束集合不匹配';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = plan_table
           AND conname = 'meeting_deletion_plan_pkey'
           AND contype = 'p' AND convalidated
           AND NOT condeferrable AND NOT condeferred
           AND conkey = ARRAY[1]::SMALLINT[])
       OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = item_table
           AND conname = 'artifact_deletion_item_pkey'
           AND contype = 'p' AND convalidated
           AND NOT condeferrable AND NOT condeferred
           AND conkey = ARRAY[1, 2]::SMALLINT[])
       OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = item_table
           AND conname = 'artifact_deletion_item_artifact_id_uq'
           AND contype = 'u' AND convalidated
           AND NOT condeferrable AND NOT condeferred
           AND conkey = ARRAY[3]::SMALLINT[])
       OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = item_table
           AND conname = 'artifact_deletion_item_revision_uq'
           AND contype = 'u' AND convalidated
           AND NOT condeferrable AND NOT condeferred
           AND conkey = ARRAY[1, 4, 5]::SMALLINT[]) THEN
        RAISE EXCEPTION 'V012 主键或唯一约束不匹配';
    END IF;

    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('meeting_deletion_plan_meeting_fk', plan_table,
               ARRAY[1]::SMALLINT[], meeting_table, ARRAY[1]::SMALLINT[]),
              ('meeting_deletion_plan_manifest_fk', plan_table,
               ARRAY[1, 3]::SMALLINT[], manifest_table, ARRAY[1, 2]::SMALLINT[]),
              ('meeting_deletion_plan_workflow_fk', plan_table,
               ARRAY[1, 4, 5]::SMALLINT[], workflow_table,
               ARRAY[2, 3, 4]::SMALLINT[]),
              ('artifact_deletion_item_plan_fk', item_table,
               ARRAY[1]::SMALLINT[], plan_table, ARRAY[1]::SMALLINT[]))
               AS expected(constraint_name, child_oid, child_key,
                           parent_oid, parent_key)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = expected.child_oid
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'f'
           AND actual.convalidated
           AND NOT actual.condeferrable
           AND NOT actual.condeferred
           AND actual.conkey = expected.child_key
           AND actual.confrelid = expected.parent_oid
           AND actual.confkey = expected.parent_key
           AND actual.confdeltype = 'r'
           AND actual.confupdtype = 'a'
           AND actual.confmatchtype = 's'
         WHERE actual.oid IS NULL) THEN
        RAISE EXCEPTION 'V012 外键契约不匹配';
    END IF;

    -- PostgreSQL 16 pretty-printer 结果先归一化空白再取 MD5，仅用于紧凑比较完整
    -- CHECK 定义（不是业务内容摘要）。这样同名 CHECK(true)、删减条件或放宽边界
    -- 都不能冒充精确重跑；实际拒绝行为仍由集成测试逐项验证。
    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('meeting_deletion_plan_source_ck',
               '6b3c20a2b1311e2009acf90f22737deb'),
              ('meeting_deletion_plan_target_count_ck',
               'c08d5a142a0b209dd518fc50d8185381'),
              ('meeting_deletion_plan_document_ck',
               '9d75a55627b56e65ff261d32cae80aed'),
              ('meeting_deletion_plan_hash_ck',
               '172f5c48d743ed8f69ba9a1b745880ac'),
              ('meeting_deletion_plan_status_ck',
               '300963b81dfa6fd06962b20e291ae6de'))
               AS expected(constraint_name, definition_digest)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = plan_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c' AND actual.convalidated
           AND NOT actual.condeferrable AND NOT actual.condeferred
         WHERE actual.oid IS NULL
            OR md5(regexp_replace(pg_get_constraintdef(actual.oid, TRUE),
                    '[[:space:]]+', ' ', 'g')) <> expected.definition_digest)
       OR EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('artifact_deletion_item_ordinal_ck',
               'fd48ea1158f7b79807eeeb70d8ddf6d3'),
              ('artifact_deletion_item_text_ck',
               'f42bc9d422de735b25eaa80b0979e198'),
              ('artifact_deletion_item_revision_ck',
               'c89fd39547f9f210c335be16751472e2'),
              ('artifact_deletion_item_status_ck',
               '7d644b2bea16a06d068d8216483deca6'))
               AS expected(constraint_name, definition_digest)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = item_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c' AND actual.convalidated
           AND NOT actual.condeferrable AND NOT actual.condeferred
         WHERE actual.oid IS NULL
            OR md5(regexp_replace(pg_get_constraintdef(actual.oid, TRUE),
                    '[[:space:]]+', ' ', 'g')) <> expected.definition_digest) THEN
        RAISE EXCEPTION 'V012 CHECK 约束缺失、未校验或被弱化';
    END IF;

    -- 不接受表达式/部分/额外索引；计划只有 PK，item 只有 PK 和两个 UQ。
    IF (SELECT count(*) FROM pg_index WHERE indrelid = plan_table) <> 1
       OR (SELECT count(*) FROM pg_index WHERE indrelid = item_table) <> 3
       OR EXISTS (
            SELECT 1 FROM pg_index
             WHERE indrelid IN (plan_table, item_table)
               AND (NOT indisvalid OR NOT indisready OR NOT indislive
                    OR indexprs IS NOT NULL OR indpred IS NOT NULL)) THEN
        RAISE EXCEPTION 'V012 精确索引集合不匹配';
    END IF;
END
$validate_v012$;

COMMENT ON TABLE meeting_deletion_plan IS
    '删除受理时冻结的 canonical Artifact 集合和不可变来源身份';
COMMENT ON TABLE artifact_deletion_item IS
    '按删除计划 ordinal 固定的逐项物理删除审计；不外键引用 READY artifact';
COMMENT ON COLUMN artifact_deletion_item.storage_binding IS
    'Artifact Store 删除成功后返回的不透明 binding；PENDING 时必须为空';

COMMIT;
