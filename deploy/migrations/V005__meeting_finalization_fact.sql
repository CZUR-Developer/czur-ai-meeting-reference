-- 建立 meeting-level 输入责任事实。
--
-- meeting_stream.input_state 属于实时逻辑流的游标/恢复范围；公开 Stop、查询和
-- finalizationReceipt 需要一个不依赖具体连接或 Worker 进程的会议级事实，因此
-- 在 meeting 上增加 input_state，并与既有 finalization_receipt_id 建立强约束。
-- 参考 Schema 尚未发布，本迁移只定义 V001→最新版本的全新数据库形态，不为
-- 从未发布的中间开发数据做回填或兼容推断。选择新增 V005 而不是重写 V001，
-- 是为了保留仓库中已经提交的迁移链和 migrate.sh 的顺序登记规则。

BEGIN;

ALTER TABLE meeting
    ADD COLUMN IF NOT EXISTS input_state TEXT NOT NULL DEFAULT 'INPUT_OPEN';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting'::regclass
           AND conname = 'meeting_input_state_ck'
    ) THEN
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_input_state_ck
            CHECK (input_state IN (
                'INPUT_OPEN',
                'INPUT_COMPLETE_PENDING',
                'INPUT_ACCEPTED',
                'INPUT_CANCELLED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'meeting'::regclass
           AND conname = 'meeting_finalization_receipt_ck'
    ) THEN
        ALTER TABLE meeting
            ADD CONSTRAINT meeting_finalization_receipt_ck
            CHECK (
                (input_state = 'INPUT_ACCEPTED'
                    AND finalization_receipt_id IS NOT NULL
                    AND length(btrim(finalization_receipt_id)) > 0)
                OR
                (input_state <> 'INPUT_ACCEPTED'
                    AND finalization_receipt_id IS NULL)
            );
    END IF;
END
$$;

-- receipt 是 opaque 的全局事实标识。生成器负责提供高熵候选值，数据库唯一索引
-- 负责在极小概率碰撞或错误装配时 fail closed；未接管会议的 NULL 不参与唯一性。
CREATE UNIQUE INDEX IF NOT EXISTS meeting_finalization_receipt_uq
    ON meeting (finalization_receipt_id)
    WHERE finalization_receipt_id IS NOT NULL;

COMMIT;
