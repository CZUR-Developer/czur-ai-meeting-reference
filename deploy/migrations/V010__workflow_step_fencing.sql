-- 工作流步骤 checkpoint 与租约栅栏迁移。
--
-- 本迁移依赖 V001 提供的 workflow_step 和 meeting。V001 中尚无足够字段证明
-- checkpoint Definition，因此当旧表已有业务行而 V010 尚未完整落地时，本迁移
-- 选择关闭式失败，不猜测 payload、digest 或初始调度时间。参考实现处于新架构开发期，
-- 不通过占位值兼容无法证明来源的旧事实。

BEGIN;

DO $$
DECLARE
    workflow_table OID;
    meeting_table OID;
    missing_v010_column BOOLEAN;
BEGIN
    workflow_table := to_regclass(format('%I.%I', current_schema(), 'workflow_step'));
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    IF workflow_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class WHERE oid = workflow_table AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 workflow_step 普通表，请先执行 V001';
    END IF;
    IF meeting_table IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM pg_class WHERE oid = meeting_table AND relkind = 'r') THEN
        RAISE EXCEPTION '缺少当前 schema 的 meeting 普通表，请先执行 V001';
    END IF;

    SELECT count(*) <> 7
      INTO missing_v010_column
      FROM pg_attribute
     WHERE attrelid = workflow_table
       AND attname IN ('checkpoint_type', 'canonical_payload', 'content_digest',
                       'initial_available_at', 'next_attempt_at', 'lease_token',
                       'lease_version')
       AND attnum > 0
       AND NOT attisdropped;
    IF missing_v010_column
       AND EXISTS (SELECT 1 FROM workflow_step LIMIT 1) THEN
        RAISE EXCEPTION 'workflow_step 已有无法证明 Definition 的旧行，拒绝 V010 迁移';
    END IF;
END
$$;

ALTER TABLE workflow_step
    ADD COLUMN IF NOT EXISTS checkpoint_type TEXT,
    ADD COLUMN IF NOT EXISTS canonical_payload TEXT,
    ADD COLUMN IF NOT EXISTS content_digest TEXT,
    ADD COLUMN IF NOT EXISTS initial_available_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_token TEXT,
    ADD COLUMN IF NOT EXISTS lease_version BIGINT;

-- 不执行业务 UPDATE。空表可以直接收紧；精确重跑时列已经满足同一约束。
ALTER TABLE workflow_step
    ALTER COLUMN checkpoint_type SET NOT NULL,
    ALTER COLUMN canonical_payload SET NOT NULL,
    ALTER COLUMN content_digest SET NOT NULL,
    ALTER COLUMN initial_available_at SET NOT NULL,
    ALTER COLUMN lease_version SET NOT NULL,
    ALTER COLUMN lease_version SET DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'workflow_step'::regclass
           AND conname = 'workflow_step_status_ck') THEN
        ALTER TABLE workflow_step ADD CONSTRAINT workflow_step_status_ck
            CHECK (status IN ('PENDING', 'LEASED', 'COMPLETED', 'DEAD'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'workflow_step'::regclass
           AND conname = 'workflow_step_definition_ck') THEN
        ALTER TABLE workflow_step ADD CONSTRAINT workflow_step_definition_ck
            CHECK (length(btrim(checkpoint_type)) > 0
                   AND length(btrim(canonical_payload)) > 0
                   AND length(btrim(content_digest)) > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'workflow_step'::regclass
           AND conname = 'workflow_step_lease_token_ck') THEN
        ALTER TABLE workflow_step ADD CONSTRAINT workflow_step_lease_token_ck
            CHECK (lease_token IS NULL OR length(btrim(lease_token)) > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'workflow_step'::regclass
           AND conname = 'workflow_step_facts_ck') THEN
        ALTER TABLE workflow_step ADD CONSTRAINT workflow_step_facts_ck
            CHECK (
                lease_version >= 0
                AND (
                    (status = 'PENDING' AND next_attempt_at IS NOT NULL
                     AND lease_owner IS NULL AND lease_until IS NULL
                     AND lease_token IS NULL)
                    OR
                    (status = 'LEASED' AND next_attempt_at IS NULL
                     AND lease_owner IS NOT NULL
                     AND length(btrim(lease_owner)) > 0
                     AND lease_until IS NOT NULL AND lease_token IS NOT NULL
                     AND lease_version > 0 AND lease_until > updated_at)
                    OR
                    (status = 'COMPLETED' AND next_attempt_at IS NULL
                     AND lease_owner IS NULL AND lease_until IS NULL
                     AND lease_token IS NULL
                     AND error_code IS NULL AND error_message IS NULL)
                    OR
                    (status = 'DEAD' AND next_attempt_at IS NULL
                     AND lease_owner IS NULL AND lease_until IS NULL
                     AND lease_token IS NULL
                     AND error_code IS NOT NULL AND error_message IS NOT NULL)
                )
                AND ((error_code IS NULL AND error_message IS NULL)
                     OR (error_code IS NOT NULL AND error_message IS NOT NULL
                         AND length(btrim(error_code)) > 0
                         AND length(btrim(error_message)) > 0))
            );
    END IF;
END
$$;

-- 该索引只优化领取扫描，不改变唯一赢家、租约栅栏或事实完整性；因此它不是
-- JdbcDatabaseRuntime readiness 的可用性门禁。缺失时服务仍可正确运行，但需要
-- 运维通过性能监控发现并修复。
CREATE INDEX IF NOT EXISTS workflow_step_claim_v010_idx
    ON workflow_step (step_type, status, next_attempt_at, lease_until,
                      meeting_id, step_key);

COMMENT ON COLUMN workflow_step.checkpoint_type IS
    '不可覆盖的 checkpoint 业务类型；与 step_type 调度路由分离';
COMMENT ON COLUMN workflow_step.canonical_payload IS
    'Worker 恢复所需的规范 checkpoint 正文';
COMMENT ON COLUMN workflow_step.content_digest IS
    'canonical_payload 的稳定摘要';
COMMENT ON COLUMN workflow_step.initial_available_at IS
    '注册 Definition 中不可覆盖的首次可领取时间';
COMMENT ON COLUMN workflow_step.next_attempt_at IS
    'PENDING 步骤当前最早可领取时间；失败重试时可以变化';
COMMENT ON COLUMN workflow_step.lease_token IS
    '每次领取或续租生成的不透明 fencing token';
COMMENT ON COLUMN workflow_step.lease_version IS
    '每次领取或续租单调递增的 fencing 版本';

-- IF NOT EXISTS 只服务于“迁移 DDL 已提交但版本尚未登记”的精确重跑。
-- 下列 catalog 门禁精确比较 V010 列与完整 CHECK 定义；同名弱约束不会被接纳。
DO $$
DECLARE
    workflow_table OID;
    meeting_table OID;
    actual_columns TEXT;
BEGIN
    workflow_table := to_regclass(format('%I.%I', current_schema(), 'workflow_step'));
    meeting_table := to_regclass(format('%I.%I', current_schema(), 'meeting'));
    SELECT string_agg(
               a.attname || ':' || format_type(a.atttypid, a.atttypmod)
               || ':' || a.attnotnull::TEXT || ':'
               || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
               '|' ORDER BY array_position(
                   ARRAY['checkpoint_type', 'canonical_payload', 'content_digest',
                         'initial_available_at', 'next_attempt_at', 'lease_token',
                         'lease_version']::name[], a.attname))
      INTO actual_columns
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d
        ON d.adrelid = a.attrelid
       AND d.adnum = a.attnum
     WHERE a.attrelid = workflow_table
       AND a.attname IN ('checkpoint_type', 'canonical_payload', 'content_digest',
                         'initial_available_at', 'next_attempt_at', 'lease_token',
                         'lease_version')
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF actual_columns IS DISTINCT FROM
       'checkpoint_type:text:true:<NULL>|canonical_payload:text:true:<NULL>|'
       'content_digest:text:true:<NULL>|'
       'initial_available_at:timestamp with time zone:true:<NULL>|'
       'next_attempt_at:timestamp with time zone:false:<NULL>|'
       'lease_token:text:false:<NULL>|lease_version:bigint:true:0' THEN
        RAISE EXCEPTION 'workflow_step V010 列物理契约不匹配';
    END IF;

    -- Store 的 INSERT 刻意不传 step_id，依赖 V001 的 BY DEFAULT identity 生成值。
    -- attidentity='d' 精确表示 GENERATED BY DEFAULT AS IDENTITY；DROP IDENTITY 后
    -- 即使 bigint/NOT NULL 仍在，也已不可按 Store SQL 写入，必须关闭式失败。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_attribute a
          LEFT JOIN pg_attrdef d
            ON d.adrelid = a.attrelid
           AND d.adnum = a.attnum
         WHERE a.attrelid = workflow_table
           AND a.attname = 'step_id'
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND format_type(a.atttypid, a.atttypmod) = 'bigint'
           AND a.attnotnull
           AND a.attidentity = 'd'
           AND a.attgenerated = ''
           AND d.oid IS NULL) THEN
        RAISE EXCEPTION 'workflow_step.step_id identity 生成契约不匹配';
    END IF;

    IF EXISTS (
        SELECT expected.constraint_name
          FROM (VALUES
              ('workflow_step_status_ck',
               'CHECK (status = ANY (ARRAY[''PENDING''::text, ''LEASED''::text, ''COMPLETED''::text, ''DEAD''::text]))'),
              ('workflow_step_definition_ck',
               'CHECK (length(btrim(checkpoint_type)) > 0 AND length(btrim(canonical_payload)) > 0 AND length(btrim(content_digest)) > 0)'),
              ('workflow_step_lease_token_ck',
               'CHECK (lease_token IS NULL OR length(btrim(lease_token)) > 0)'),
              ('workflow_step_attempt_count_check',
               'CHECK (attempt_count >= 0)'),
              ('workflow_step_facts_ck',
               'CHECK (lease_version >= 0 AND (status = ''PENDING''::text AND next_attempt_at IS NOT NULL AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL OR status = ''LEASED''::text AND next_attempt_at IS NULL AND lease_owner IS NOT NULL AND length(btrim(lease_owner)) > 0 AND lease_until IS NOT NULL AND lease_token IS NOT NULL AND lease_version > 0 AND lease_until > updated_at OR status = ''COMPLETED''::text AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL AND error_code IS NULL AND error_message IS NULL OR status = ''DEAD''::text AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL) AND (error_code IS NULL AND error_message IS NULL OR error_code IS NOT NULL AND error_message IS NOT NULL AND length(btrim(error_code)) > 0 AND length(btrim(error_message)) > 0))'))
               AS expected(constraint_name, expected_definition)
          LEFT JOIN pg_constraint actual
            ON actual.conrelid = workflow_table
           AND actual.conname = expected.constraint_name
           AND actual.contype = 'c'
           AND actual.convalidated
         WHERE actual.oid IS NULL
            OR regexp_replace(
                   pg_get_constraintdef(actual.oid, TRUE),
                   '[[:space:]]+', ' ', 'g') <> expected.expected_definition
    ) THEN
        RAISE EXCEPTION 'workflow_step V010 CHECK 定义不匹配';
    END IF;

    -- register 的 ON CONFLICT 与会议归属依赖 V001 的稳定唯一键和外键。
    -- 不能只验证“存在某个唯一索引/外键”，否则同名弱化结构会在运行期改变
    -- 幂等赢家或允许孤儿 checkpoint。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = workflow_table
           AND conname = 'workflow_step_key_uq'
           AND contype = 'u'
           AND convalidated
           AND conkey = ARRAY[2, 3, 4]::SMALLINT[]
           AND regexp_replace(
                   pg_get_constraintdef(oid, TRUE),
                   '[[:space:]]+', ' ', 'g') =
               'UNIQUE (meeting_id, step_type, step_key)') THEN
        RAISE EXCEPTION 'workflow_step 稳定 Key 唯一约束不匹配';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = workflow_table
           AND conname = 'workflow_step_meeting_id_fkey'
           AND contype = 'f'
           AND convalidated
           AND conkey = ARRAY[2]::SMALLINT[]
           AND confrelid = meeting_table
           AND confkey = ARRAY[1]::SMALLINT[]
           AND confdeltype = 'a'
           AND regexp_replace(
                   pg_get_constraintdef(oid, TRUE),
                   '[[:space:]]+', ' ', 'g') =
               'FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)') THEN
        RAISE EXCEPTION 'workflow_step meeting 外键不匹配';
    END IF;

    -- workflow_step 的 surrogate PK 不参与 Store 的查找、幂等或 fencing 条件，
    -- 因而不作为 V010 readiness 阻断；真正依赖的是上述 step_id identity 可写性、
    -- 业务稳定 Key 唯一约束、meeting 外键和 attempt 非负约束。
END
$$;

COMMIT;
