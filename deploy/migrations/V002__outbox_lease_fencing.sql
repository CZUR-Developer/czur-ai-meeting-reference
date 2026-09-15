-- Outbox 租约栅栏字段迁移。
--
-- V001 已经创建 outbox_event。本迁移只增加租约元数据和查询索引，兼容
-- 已有事件；不写入用户、Token、模型密钥、音频或会议正文。lease_version
-- 用于拒绝旧消费者的迟到确认，lease_token 用于区分同一 owner 的不同租约。

BEGIN;

-- 迁移必须在 V001 之后执行。明确失败比在错误的表上静默创建对象更安全。
DO $$
BEGIN
    IF to_regclass('outbox_event') IS NULL THEN
        RAISE EXCEPTION '缺少 outbox_event，请先执行 V001__initial_schema.sql';
    END IF;
END
$$;

-- 如果有人在迁移前手工创建了同名字段，先校验类型，避免 IF NOT EXISTS
-- 掩盖不兼容的 schema。V001 没有这两个字段，正常升级不会触发异常。
DO $$
DECLARE
    token_type   oid;
    version_type oid;
BEGIN
    SELECT atttypid
      INTO token_type
      FROM pg_attribute
     WHERE attrelid = 'outbox_event'::regclass
       AND attname = 'lease_token'
       AND NOT attisdropped;
    IF token_type IS NOT NULL AND token_type <> 'text'::regtype THEN
        RAISE EXCEPTION 'outbox_event.lease_token 类型必须是 TEXT，实际类型 oid=%', token_type;
    END IF;

    SELECT atttypid
      INTO version_type
      FROM pg_attribute
     WHERE attrelid = 'outbox_event'::regclass
       AND attname = 'lease_version'
       AND NOT attisdropped;
    IF version_type IS NOT NULL AND version_type <> 'int8'::regtype THEN
        RAISE EXCEPTION 'outbox_event.lease_version 类型必须是 BIGINT，实际类型 oid=%', version_type;
    END IF;
END
$$;

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS lease_token TEXT;

-- 先以默认值增加字段，再显式设置 NOT NULL，兼容已有 V001 数据和重复执行。
ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS lease_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outbox_event
    ALTER COLUMN lease_version SET DEFAULT 0;

-- 若外部环境曾创建过可空字段，不自动篡改既有业务数据；要求部署方先处理
-- NULL，再设置 NOT NULL，从而避免把未知租约版本伪装成版本 0。
DO $$
DECLARE
    null_count BIGINT;
BEGIN
    SELECT count(*)
      INTO null_count
      FROM outbox_event
     WHERE lease_version IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'outbox_event.lease_version 存在 % 条 NULL，无法安全设置 NOT NULL', null_count;
    END IF;
END
$$;

ALTER TABLE outbox_event
    ALTER COLUMN lease_version SET NOT NULL;

-- PostgreSQL 不支持 ALTER TABLE ADD CONSTRAINT IF NOT EXISTS，因此用约束名
-- 做幂等保护；同名但未校验的约束直接失败，避免把不安全 schema 当成成功。
DO $$
DECLARE
    constraint_def TEXT;
    constraint_ok  BOOLEAN;
BEGIN
    SELECT pg_get_constraintdef(oid), convalidated
      INTO constraint_def, constraint_ok
      FROM pg_constraint
     WHERE conrelid = 'outbox_event'::regclass
       AND conname = 'outbox_lease_version_ck';

    IF constraint_def IS NULL THEN
        ALTER TABLE outbox_event
            ADD CONSTRAINT outbox_lease_version_ck CHECK (lease_version >= 0);
    ELSIF NOT constraint_ok
       OR position('lease_version' IN lower(constraint_def)) = 0
       OR position('>= 0' IN lower(constraint_def)) = 0 THEN
        RAISE EXCEPTION 'outbox_lease_version_ck 已存在但定义不符合 lease_version >= 0：%', constraint_def;
    END IF;
END
$$;

DO $$
DECLARE
    constraint_def TEXT;
    constraint_ok  BOOLEAN;
BEGIN
    SELECT pg_get_constraintdef(oid), convalidated
      INTO constraint_def, constraint_ok
      FROM pg_constraint
     WHERE conrelid = 'outbox_event'::regclass
       AND conname = 'outbox_lease_token_ck';

    IF constraint_def IS NULL THEN
        ALTER TABLE outbox_event
            ADD CONSTRAINT outbox_lease_token_ck
            CHECK (lease_token IS NULL OR length(btrim(lease_token)) > 0);
    ELSIF NOT constraint_ok
       OR position('lease_token' IN lower(constraint_def)) = 0
       OR position('length' IN lower(constraint_def)) = 0 THEN
        RAISE EXCEPTION 'outbox_lease_token_ck 已存在但定义不符合非空 token 约束：%', constraint_def;
    END IF;
END
$$;

-- 过期 LEASED 事件的回收扫描只关注仍持有租约的记录；event_id 主键已经
-- 覆盖 markPublished/markFailed 的定位，条件更新仍必须在应用层校验栅栏。
CREATE INDEX IF NOT EXISTS outbox_leased_expiry_idx
    ON outbox_event (lease_until, lease_version)
    WHERE status = 'LEASED';

COMMENT ON COLUMN outbox_event.lease_token IS
    '每次成功领取生成的随机租约令牌；未领取时为 NULL，禁止使用空白字符串';
COMMENT ON COLUMN outbox_event.lease_version IS
    '租约栅栏版本；每次重新领取成功后单调递增，旧版本不得确认事件';

COMMIT;
