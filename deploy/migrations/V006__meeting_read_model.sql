-- 建立会议查询所需的最小权威读模型字段和索引。
--
-- meeting.state 继续表示会议生命周期；manifest_state 独立表示最终产物清单的
-- 生成/清理状态。两者不能相互推断，后续查询适配器必须从同一行已提交快照读取。
-- 参考 Schema 尚未发布，本迁移只定义全新 V001→V006 数据库形态，不根据
-- metadata 或历史中间开发库内容猜测 Manifest 状态。

BEGIN;

-- 新会议在 Worker 开始生成最终产物前一律为 NOT_STARTED。该默认值只表达
-- “尚无 Manifest 生成事实”，不表示会议生命周期，也不会生成空 Manifest。
ALTER TABLE meeting
    ADD COLUMN IF NOT EXISTS manifest_state TEXT NOT NULL DEFAULT 'NOT_STARTED';

DO $$
BEGIN
    -- UNKNOWN 只用于公开协议解码的防御性哨兵，不是可持久化生命周期事实。
    -- 其余值必须与 MeetingState 保持同步；新增领域状态时必须显式迁移数据库。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting'::regclass
           AND conname = 'meeting_state_ck'
    ) THEN
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_state_ck
            CHECK (state IN (
                'CREATED',
                'AUTHORIZED',
                'STARTING',
                'RUNNING',
                'STOPPING',
                'FINALIZING',
                'READY',
                'READY_WITH_WARNINGS',
                'FAILED',
                'CANCELLED',
                'DELETION_PENDING',
                'DELETED'));
    END IF;

    -- Manifest 状态与 ManifestStatus 一一对应，但不在此约束中推断或绑定
    -- meeting.state；跨状态推进属于后续应用事务和 Worker 的职责。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting'::regclass
           AND conname = 'meeting_manifest_state_ck'
    ) THEN
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_manifest_state_ck
            CHECK (manifest_state IN (
                'NOT_STARTED',
                'GENERATING',
                'READY',
                'READY_WITH_WARNINGS',
                'FAILED',
                'DELETION_PENDING',
                'DELETED'));
    END IF;
END
$$;

-- 认证列表查询固定按部署范围和主体隔离，再用 created_at + meeting_id 做
-- 降序 keyset 分页。meeting_id 是同一 created_at 下的稳定唯一决胜键。
CREATE INDEX IF NOT EXISTS meeting_scope_subject_created_idx
    ON meeting (
        deployment_scope,
        subject_scope,
        created_at DESC,
        meeting_id DESC);

COMMIT;
