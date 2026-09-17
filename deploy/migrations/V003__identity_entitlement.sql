-- reference-dev 身份归属和权益预占迁移。
--
-- V001 的 usage_reservation 只能在会议存在后写入，而且没有主体、Grant、
-- 幂等操作和终态事实。本迁移保留所有历史列与历史行，通过 record_format
-- 区分只读兼容事实和 V3 严格状态机；不会把历史记录猜测成某个真实用户、
-- Grant 或会议主 Reservation。

BEGIN;

-- 明确要求 V001/V002 基础表存在，避免在错误 search_path 中静默创建残缺表。
DO $$
BEGIN
    IF to_regclass('meeting') IS NULL
       OR to_regclass('usage_reservation') IS NULL
       OR to_regclass('idempotency_record') IS NULL THEN
        RAISE EXCEPTION '缺少 V001 基础表，请先执行 V001/V002 迁移';
    END IF;
END
$$;

-- 必须在任何 DDL 或历史数据更新之前识别未知状态。事务中的显式异常保证
-- 整个 V003 回滚，不会留下“列已增加、历史事实只迁移一半”的数据库。
DO $$
DECLARE
    unknown_states TEXT;
BEGIN
    SELECT string_agg(DISTINCT coalesce(status, '<NULL>'), ', ' ORDER BY coalesce(status, '<NULL>'))
      INTO unknown_states
      FROM usage_reservation
     WHERE status IS NULL
        OR status NOT IN ('RESERVED', 'COMMITTED', 'CANCELLED');
    IF unknown_states IS NOT NULL THEN
        RAISE EXCEPTION 'usage_reservation 存在未知历史状态，拒绝迁移：%', unknown_states;
    END IF;
END
$$;

-- grant_id 是否存在用来识别首次升级。若 V003 已提交但 schema_migrations
-- 登记失败，再次执行时不会把已经按 V3 写入的真实主体改成历史主体。
DO $$
DECLARE
    first_install BOOLEAN;
BEGIN
    SELECT NOT EXISTS (
        SELECT 1
          FROM pg_attribute
         WHERE attrelid = 'meeting'::regclass
           AND attname = 'grant_id'
           AND NOT attisdropped)
      INTO first_install;

    ALTER TABLE meeting ADD COLUMN IF NOT EXISTS grant_id TEXT;
    ALTER TABLE meeting ADD COLUMN IF NOT EXISTS grant_expires_at TIMESTAMPTZ;
    ALTER TABLE meeting ADD COLUMN IF NOT EXISTS usage_reservation_id TEXT;

    IF first_install THEN
        UPDATE meeting SET subject_scope = 'LEGACY_UNOWNED';
    ELSE
        -- 兼容 V003 已提交、尚未登记期间仍由旧 Java 写入的占位会议；带真实
        -- Grant 或主 Reservation 的新记录绝不会被此条件改写。
        UPDATE meeting
           SET subject_scope = 'LEGACY_UNOWNED'
         WHERE subject_scope = 'meeting'
           AND grant_id IS NULL
           AND grant_expires_at IS NULL
           AND usage_reservation_id IS NULL;
    END IF;
END
$$;

-- 旧 meeting_id 外键和 meeting_id + usage_type 唯一约束要求先有会议，无法
-- 表达“先预占、后在同一事务绑定会议”。保留两个旧列和全部旧值，只解除这
-- 两个前置限制；新绑定关系由 bound_meeting_id 表达。
ALTER TABLE usage_reservation
    ALTER COLUMN meeting_id DROP NOT NULL;
ALTER TABLE usage_reservation
    ALTER COLUMN usage_type DROP NOT NULL;
ALTER TABLE usage_reservation
    ALTER COLUMN quantity DROP NOT NULL;

-- 不能假设外部 PostgreSQL 沿用了 PostgreSQL 自动生成的约束名。按约束的
-- 源表、目标表和列号精确识别旧 meeting_id 外键；只删除这个单列前置外键。
DO $$
DECLARE
    meeting_column SMALLINT;
    foreign_key RECORD;
BEGIN
    SELECT attnum
      INTO meeting_column
      FROM pg_attribute
     WHERE attrelid = 'usage_reservation'::regclass
       AND attname = 'meeting_id'
       AND NOT attisdropped;

    FOR foreign_key IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'usage_reservation'::regclass
           AND confrelid = 'meeting'::regclass
           AND contype = 'f'
           AND conkey = ARRAY[meeting_column]::SMALLINT[]
    LOOP
        EXECUTE format('ALTER TABLE usage_reservation DROP CONSTRAINT %I', foreign_key.conname);
    END LOOP;
END
$$;

-- 同样按列集合识别 V001 的 meeting_id + usage_type 唯一约束。V3 允许同一
-- 会议未来扩展多个操作事实，主 Reservation 由 meeting 上的显式引用确定。
DO $$
DECLARE
    meeting_column SMALLINT;
    usage_type_column SMALLINT;
    unique_constraint RECORD;
BEGIN
    SELECT attnum INTO meeting_column
      FROM pg_attribute
     WHERE attrelid = 'usage_reservation'::regclass
       AND attname = 'meeting_id' AND NOT attisdropped;
    SELECT attnum INTO usage_type_column
      FROM pg_attribute
     WHERE attrelid = 'usage_reservation'::regclass
       AND attname = 'usage_type' AND NOT attisdropped;

    FOR unique_constraint IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'usage_reservation'::regclass
           AND contype = 'u'
           AND array_length(conkey, 1) = 2
           AND conkey @> ARRAY[meeting_column, usage_type_column]::SMALLINT[]
    LOOP
        EXECUTE format('ALTER TABLE usage_reservation DROP CONSTRAINT %I', unique_constraint.conname);
    END LOOP;
END
$$;

ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS record_format TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS deployment_scope TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS subject_scope TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS grant_id TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS expected_meeting_id TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS bound_meeting_id TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS usage_unit TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS reserved_amount NUMERIC(20, 6);
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS actual_amount NUMERIC(20, 6);
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS version BIGINT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS committed_at TIMESTAMPTZ;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS cancellation_reason_code TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS lease_owner TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS lease_token TEXT;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
ALTER TABLE usage_reservation ADD COLUMN IF NOT EXISTS lease_version BIGINT;

-- 每个 V001 用量行独立回填，不按 meeting_id 聚合，也不挑选所谓“主”记录。
-- 旧 quantity/usage_type/provider_ref 保持原值；canonical 列只复制已有事实。
-- 历史记录没有 Grant 和可靠 expires_at，因此保持 NULL，不伪造业务事实。
UPDATE usage_reservation AS usage
   SET record_format = 'LEGACY_V1',
       deployment_scope = meeting.deployment_scope,
       subject_scope = 'LEGACY_UNOWNED',
       expected_meeting_id = usage.meeting_id,
       bound_meeting_id = usage.meeting_id,
       bound_at = usage.created_at,
       usage_unit = usage.usage_type,
       reserved_amount = usage.quantity,
       actual_amount = CASE WHEN usage.status = 'COMMITTED' THEN usage.quantity ELSE NULL END,
       version = CASE WHEN usage.status = 'COMMITTED' THEN 2 ELSE 1 END,
       committed_at = CASE WHEN usage.status = 'COMMITTED' THEN usage.updated_at ELSE NULL END,
       cancelled_at = CASE WHEN usage.status = 'CANCELLED' THEN usage.updated_at ELSE NULL END,
       cancellation_reason_code = CASE
           WHEN usage.status = 'CANCELLED' THEN 'LEGACY_MIGRATION'
           ELSE NULL
       END,
       lease_version = 0
  FROM meeting
 WHERE usage.record_format IS NULL
   AND meeting.meeting_id = usage.meeting_id;

-- V001 外键保证历史行都能连接 meeting。若外部环境破坏过外键，则拒绝把
-- 缺少归属的行伪装为已迁移记录。
DO $$
DECLARE
    incomplete_count BIGINT;
BEGIN
    SELECT count(*)
      INTO incomplete_count
      FROM usage_reservation
     WHERE record_format IS NULL
        OR deployment_scope IS NULL
        OR subject_scope IS NULL
        OR expected_meeting_id IS NULL
        OR usage_unit IS NULL
        OR reserved_amount IS NULL
        OR version IS NULL
        OR lease_version IS NULL;
    IF incomplete_count > 0 THEN
        RAISE EXCEPTION 'usage_reservation 有 % 条记录无法安全建立 V3 兼容事实', incomplete_count;
    END IF;
END
$$;

ALTER TABLE usage_reservation ALTER COLUMN record_format SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN deployment_scope SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN subject_scope SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN expected_meeting_id SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN usage_unit SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN reserved_amount SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN version SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN lease_version SET NOT NULL;
ALTER TABLE usage_reservation ALTER COLUMN lease_version SET DEFAULT 0;

-- 新格式同时约束范围、绑定事实、终态事实、时间顺序和版本。LEGACY_V1
-- 只保留可证明的旧事实；严格业务读取必须按 record_format='V3' 选择。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'usage_reservation'::regclass
           AND conname = 'usage_reservation_v3_ck') THEN
        ALTER TABLE usage_reservation
            ADD CONSTRAINT usage_reservation_v3_ck CHECK (
                record_format IN ('LEGACY_V1', 'V3')
                AND length(btrim(deployment_scope)) > 0
                AND length(btrim(subject_scope)) > 0
                AND length(btrim(expected_meeting_id)) > 0
                AND length(btrim(usage_unit)) > 0
                AND reserved_amount >= 0
                AND version >= 0
                AND lease_version >= 0
                AND ((bound_meeting_id IS NULL AND bound_at IS NULL)
                     OR (bound_meeting_id = expected_meeting_id AND bound_at IS NOT NULL))
                AND ((record_format = 'LEGACY_V1'
                    -- 历史格式没有 Grant/expiry，但所有从 V001 已有列复制出的
                    -- 事实仍受确定性约束，迁移后不能任意改坏或混入 V3 终态。
                    AND subject_scope = 'LEGACY_UNOWNED'
                    AND grant_id IS NULL
                    AND expires_at IS NULL
                    AND meeting_id IS NOT NULL
                    AND expected_meeting_id = meeting_id
                    AND bound_meeting_id = meeting_id
                    AND bound_at = created_at
                    AND usage_unit = usage_type
                    AND reserved_amount = quantity
                    AND lease_owner IS NULL
                    AND lease_token IS NULL
                    AND lease_until IS NULL
                    AND lease_version = 0
                    AND (
                        (status = 'RESERVED'
                         AND actual_amount IS NULL
                         AND committed_at IS NULL
                         AND cancelled_at IS NULL
                         AND cancellation_reason_code IS NULL
                         AND version = 1)
                        OR
                        (status = 'COMMITTED'
                         AND actual_amount = quantity
                         AND committed_at = updated_at
                         AND cancelled_at IS NULL
                         AND cancellation_reason_code IS NULL
                         AND version = 2)
                        OR
                        (status = 'CANCELLED'
                         AND actual_amount IS NULL
                         AND committed_at IS NULL
                         AND cancelled_at = updated_at
                         AND cancellation_reason_code = 'LEGACY_MIGRATION'
                         AND version = 1)
                    ))
                OR (record_format = 'V3'
                    AND grant_id IS NOT NULL
                    AND length(btrim(grant_id)) > 0
                    AND usage_unit = 'MEETING_DURATION_MS'
                    AND reserved_amount > 0
                    AND expires_at IS NOT NULL
                    AND expires_at > created_at
                    AND updated_at >= created_at
                    AND (bound_at IS NULL
                         OR (bound_at >= created_at AND bound_at < expires_at))
                    AND (
                        (status = 'RESERVED'
                         AND actual_amount IS NULL
                         AND committed_at IS NULL
                         AND cancelled_at IS NULL
                         AND cancellation_reason_code IS NULL
                         AND ((bound_meeting_id IS NULL AND version = 0)
                              OR (bound_meeting_id IS NOT NULL AND version >= 1)))
                        OR
                        (status = 'COMMITTED'
                         AND bound_meeting_id IS NOT NULL
                         AND actual_amount IS NOT NULL
                         AND actual_amount >= 0
                         AND committed_at IS NOT NULL
                         AND committed_at >= bound_at
                         AND cancelled_at IS NULL
                         AND cancellation_reason_code IS NULL
                         AND version >= 2)
                        OR
                        (status = 'CANCELLED'
                         AND bound_meeting_id IS NULL
                         AND actual_amount IS NULL
                         AND committed_at IS NULL
                         AND cancelled_at IS NOT NULL
                         AND cancelled_at >= created_at
                         AND cancellation_reason_code ~ '^[A-Z0-9_]+$'
                         AND version >= 1)
                    )
                    AND (
                        (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
                        OR
                        (status = 'RESERVED'
                         AND bound_meeting_id IS NULL
                         AND length(btrim(lease_owner)) > 0
                         AND length(btrim(lease_token)) > 0
                         AND lease_until IS NOT NULL
                         AND lease_version > 0)
                    )
                ))
            );
    END IF;
END
$$;

-- 绑定外键均延迟到事务提交检查，使 bind、Meeting、会议幂等和 Outbox 可以
-- 在同一工作单元中按实现需要排序；expected_meeting_id 故意不建外键，因为
-- 预占时会议尚不存在。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'usage_reservation'::regclass
           AND conname = 'usage_reservation_bound_meeting_fk') THEN
        ALTER TABLE usage_reservation
            ADD CONSTRAINT usage_reservation_bound_meeting_fk
            FOREIGN KEY (bound_meeting_id) REFERENCES meeting (meeting_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'meeting'::regclass
           AND conname = 'meeting_usage_reservation_fk') THEN
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_usage_reservation_fk
            FOREIGN KEY (usage_reservation_id) REFERENCES usage_reservation (reservation_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS usage_reservation_scope_uq
    ON usage_reservation (deployment_scope, subject_scope, reservation_id);
CREATE INDEX IF NOT EXISTS usage_reservation_expired_unbound_idx
    ON usage_reservation (expires_at, lease_until, reservation_id)
    WHERE record_format = 'V3' AND status = 'RESERVED' AND bound_meeting_id IS NULL;

-- 权益幂等结果独立于只编码 MeetingCommandResult 的 idempotency_record。
-- 四列主键就是冻结的幂等范围；不同 deployment/subject 可复用同一业务键。
CREATE TABLE IF NOT EXISTS usage_operation (
    deployment_scope TEXT NOT NULL,
    subject_scope    TEXT NOT NULL,
    operation_scope  TEXT NOT NULL,
    idempotency_key  TEXT NOT NULL,
    request_digest   TEXT NOT NULL,
    reservation_id  TEXT NOT NULL,
    result_body      JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (deployment_scope, subject_scope, operation_scope, idempotency_key),
    CONSTRAINT usage_operation_reservation_scope_fk
        FOREIGN KEY (deployment_scope, subject_scope, reservation_id)
        REFERENCES usage_reservation (deployment_scope, subject_scope, reservation_id),
    CONSTRAINT usage_operation_scope_ck
        CHECK (operation_scope IN ('usage.reserve.v1', 'usage.commit.v1', 'usage.cancel.v1')),
    CONSTRAINT usage_operation_text_ck CHECK (
        length(btrim(deployment_scope)) > 0
        AND length(btrim(subject_scope)) > 0
        AND length(btrim(idempotency_key)) > 0
        AND length(btrim(request_digest)) > 0
    )
);

CREATE INDEX IF NOT EXISTS usage_operation_reservation_idx
    ON usage_operation (reservation_id, created_at);

COMMENT ON COLUMN usage_reservation.record_format IS
    'LEGACY_V1 为只读历史兼容事实；V3 执行 reference-dev 严格 Reservation 状态机';
COMMENT ON COLUMN meeting.subject_scope IS
    'LEGACY_UNOWNED 表示 V001/V002 或未认证兼容入口创建，不能由普通用户认领';
COMMENT ON TABLE usage_operation IS
    'reserve/commit/cancel 的稳定幂等结果；不复用会议命令幂等编码器';

COMMIT;
