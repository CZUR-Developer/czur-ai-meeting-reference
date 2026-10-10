-- MySQL 5.7 首次交付的唯一当前初始化基线，与 PostgreSQL V001 按引擎择一执行。
-- 只在调用方已选定的数据库新增 ai_trans_* 表；不建库、不修改旧表、不关闭外键。
-- InnoDB DDL 会隐式提交；deploy/migrate-mysql.sh 仅在本文件全部成功后登记 V001。
-- 本期支持正常首次初始化和完整基线重跑，不修复半成品或迁移旧测试数据。
--
-- 标识使用 VARBINARY 保存 UTF-8 字节：大小写、Unicode和尾空格均精确比较。
-- 索引/外键中的meeting_id/一般ID/step_key 1024字节，deployment_scope256、subject_scope1024、
-- 编码scope 2048、idempotency_key 256、类型128、operation_scope64、storage_binding3072。
-- 索引均保存完整键，不使用前缀唯一索引；最宽显式索引3072字节。
-- 正文 LONGTEXT 使用 utf8mb4；JSON 与 canonical 原文分列，摘要读取原文。
-- target_languages 使用 JSON 字符串数组；32字节摘要 BINARY(32)，音频 BLOB。
-- DATETIME(6) 一律表示 UTC；初始化与运行时连接都设置会话时区 +00:00。
-- 5.7 不执行 CHECK，不复制 PG 指纹体系；领域及事务工作单元继续负责业务校验。
-- 5.7 的 JSON 无 DEFAULT；写入方须显式提供 metadata（通常为 {}）。
-- media_type、删除计划storage_binding等不索引的正文标识使用LONGTEXT，不缩小原文本边界。
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET SESSION time_zone = '+00:00';

CREATE TABLE ai_trans_artifact (
    artifact_id VARBINARY(1024) NOT NULL,
    meeting_id VARBINARY(1024) NOT NULL,
    artifact_type VARBINARY(128) NOT NULL,
    media_type LONGTEXT NOT NULL,
    language LONGTEXT,
    revision INT NOT NULL,
    intent_id VARBINARY(1024) NOT NULL,
    storage_binding VARBINARY(3072) NOT NULL,
    content_size BIGINT NOT NULL,
    content_checksum LONGTEXT NOT NULL,
    recorded_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT ai_trans_artifact_intent_id_uq UNIQUE (intent_id),
    PRIMARY KEY (artifact_id),
    CONSTRAINT ai_trans_artifact_revision_uq UNIQUE (meeting_id, artifact_type, revision),
    CONSTRAINT ai_trans_artifact_storage_binding_uq UNIQUE (storage_binding)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_artifact_deletion_item (
    meeting_id VARBINARY(1024) NOT NULL,
    ordinal INT NOT NULL,
    artifact_id VARBINARY(1024) NOT NULL,
    artifact_type VARBINARY(128) NOT NULL,
    revision INT NOT NULL,
    status VARBINARY(128) NOT NULL,
    storage_binding LONGTEXT,
    deleted_at DATETIME(6),
    CONSTRAINT ai_trans_artifact_deletion_item_artifact_id_uq UNIQUE (artifact_id),
    PRIMARY KEY (meeting_id, ordinal),
    CONSTRAINT ai_trans_artifact_deletion_item_revision_uq UNIQUE (meeting_id, artifact_type, revision)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_durable_input_fact (
    meeting_id VARBINARY(1024) NOT NULL,
    input_binding LONGTEXT NOT NULL,
    last_accepted_sequence BIGINT NOT NULL,
    audio_duration_ms BIGINT NOT NULL,
    source_language LONGTEXT NOT NULL,
    target_languages JSON NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    content_digest LONGTEXT NOT NULL,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_final_usage_fact (
    meeting_id VARBINARY(1024) NOT NULL,
    actual_unit LONGTEXT NOT NULL,
    actual_amount BIGINT NOT NULL,
    source_input_binding LONGTEXT NOT NULL,
    source_input_digest LONGTEXT NOT NULL,
    source_accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_finalization_execution_fact (
    meeting_id VARBINARY(1024) NOT NULL,
    canonical_json LONGTEXT NOT NULL,
    execution_fact_json JSON NOT NULL,
    content_sha256 BINARY(32) NOT NULL,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_idempotency_record (
    subject_scope VARBINARY(2048) NOT NULL,
    idempotency_key VARBINARY(256) NOT NULL,
    request_hash LONGTEXT NOT NULL,
    response_status INT,
    response_body JSON,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6),
    PRIMARY KEY (subject_scope, idempotency_key)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting (
    meeting_id VARBINARY(1024) NOT NULL,
    deployment_scope VARBINARY(256) NOT NULL,
    client_session_id VARBINARY(1024) NOT NULL,
    subject_scope VARBINARY(1024) NOT NULL,
    profile_id LONGTEXT NOT NULL,
    profile_revision INT NOT NULL,
    state VARBINARY(128) NOT NULL,
    aggregate_version BIGINT DEFAULT 0 NOT NULL,
    finalization_receipt_id VARBINARY(1024),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    metadata JSON NOT NULL,
    grant_id LONGTEXT,
    grant_expires_at DATETIME(6),
    usage_reservation_id VARBINARY(1024),
    input_state VARBINARY(128) DEFAULT 'INPUT_OPEN' NOT NULL,
    manifest_state VARBINARY(128) DEFAULT 'NOT_STARTED' NOT NULL,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_audio_frame (
    meeting_id VARBINARY(1024) NOT NULL,
    stream_sequence BIGINT NOT NULL,
    start_position_ms BIGINT NOT NULL,
    duration_ms INT NOT NULL,
    audio BLOB NOT NULL,
    source_generation BIGINT NOT NULL,
    format_revision INT NOT NULL,
    gap_duration_ms_before BIGINT NOT NULL,
    flags INT NOT NULL,
    digest_version SMALLINT NOT NULL,
    content_digest BINARY(32) NOT NULL,
    PRIMARY KEY (meeting_id, stream_sequence)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_audio_input (
    meeting_id VARBINARY(1024) NOT NULL,
    writer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    writer_epoch BIGINT NOT NULL,
    accepted_stream_sequence BIGINT DEFAULT 0 NOT NULL,
    accepted_audio_position_ms BIGINT DEFAULT 0 NOT NULL,
    complete_last_stream_sequence BIGINT,
    complete_last_audio_position_ms BIGINT,
    complete_recording_checksum LONGTEXT,
    complete_recording_size_bytes BIGINT,
    complete_request_id LONGTEXT,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_create_fact (
    scope VARBINARY(2048) NOT NULL,
    idempotency_key VARBINARY(256) NOT NULL,
    request_schema_version INT NOT NULL,
    request_digest LONGTEXT NOT NULL,
    request_json LONGTEXT NOT NULL,
    response_schema_version INT NOT NULL,
    response_status INT NOT NULL,
    response_type LONGTEXT NOT NULL,
    response_json LONGTEXT NOT NULL,
    response_sha256 BINARY(32) NOT NULL,
    meeting_id VARBINARY(1024),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ai_trans_meeting_create_fact_meeting_id_uq UNIQUE (meeting_id),
    PRIMARY KEY (scope, idempotency_key)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_deletion_plan (
    meeting_id VARBINARY(1024) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    source_manifest_revision INT,
    source_workflow_step_type VARBINARY(128),
    source_workflow_step_key VARBINARY(1024),
    source_workflow_checkpoint_type LONGTEXT,
    source_workflow_content_digest LONGTEXT,
    target_count INT NOT NULL,
    canonical_json LONGTEXT NOT NULL,
    deletion_plan_json JSON NOT NULL,
    content_sha256 BINARY(32) NOT NULL,
    status VARBINARY(128) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_event (
    meeting_id VARBINARY(1024) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_id VARBINARY(1024) NOT NULL,
    event_type LONGTEXT NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    spec_version LONGTEXT,
    stream_id VARBINARY(1024),
    canonical_payload LONGTEXT,
    CONSTRAINT ai_trans_meeting_event_id_uq UNIQUE (event_id),
    PRIMARY KEY (meeting_id, event_sequence)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_client_input (
    meeting_id VARBINARY(1024) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    audio_duration_ms BIGINT,
    usage_submitted_at DATETIME(6),
    usage_expected_version BIGINT,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_client_event (
    meeting_id VARBINARY(1024) NOT NULL,
    client_event_id VARBINARY(1024) NOT NULL,
    client_sequence BIGINT NOT NULL,
    event_id VARBINARY(1024) NOT NULL,
    PRIMARY KEY (meeting_id, client_event_id),
    CONSTRAINT ai_trans_meeting_client_event_event_id_uq UNIQUE (event_id),
    CONSTRAINT ai_trans_meeting_client_event_sequence_uq UNIQUE (meeting_id, client_sequence)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_client_summary (
    meeting_id VARBINARY(1024) NOT NULL,
    summary_json LONGTEXT NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (meeting_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_client_result (
    meeting_id VARBINARY(1024) NOT NULL,
    artifact_id VARBINARY(1024),
    manifest_json LONGTEXT NOT NULL,
    PRIMARY KEY (meeting_id),
    CONSTRAINT ai_trans_meeting_client_result_artifact_id_uq UNIQUE (artifact_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_manifest_revision (
    meeting_id VARBINARY(1024) NOT NULL,
    manifest_revision INT NOT NULL,
    previous_revision INT,
    canonical_json LONGTEXT NOT NULL,
    manifest_json JSON NOT NULL,
    content_sha256 BINARY(32) NOT NULL,
    recorded_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (meeting_id, manifest_revision)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_realtime_token_grant (
    meeting_id VARBINARY(1024) NOT NULL,
    idempotency_key VARBINARY(256) NOT NULL,
    grant_json LONGTEXT NOT NULL,
    grant_sha256 BINARY(32) NOT NULL,
    token_sha256 BINARY(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (meeting_id, idempotency_key),
    CONSTRAINT ai_trans_meeting_realtime_token_grant_token_uq UNIQUE (token_sha256)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_meeting_stream (
    meeting_id VARBINARY(1024) NOT NULL,
    stream_id VARBINARY(1024) NOT NULL,
    last_client_seq BIGINT DEFAULT 0 NOT NULL,
    last_server_seq BIGINT DEFAULT 0 NOT NULL,
    input_state VARBINARY(128) DEFAULT 'OPEN' NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (meeting_id, stream_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_outbox_event (
    event_id VARBINARY(1024) NOT NULL,
    aggregate_type LONGTEXT NOT NULL,
    aggregate_id LONGTEXT NOT NULL,
    event_type LONGTEXT NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    status VARBINARY(128) DEFAULT 'PENDING' NOT NULL,
    attempt_count INT DEFAULT 0 NOT NULL,
    lease_owner LONGTEXT,
    lease_until DATETIME(6),
    published_at DATETIME(6),
    last_error LONGTEXT,
    lease_token LONGTEXT,
    lease_version BIGINT DEFAULT 0 NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_plugin_execution (
    execution_id VARBINARY(1024) NOT NULL,
    meeting_id VARBINARY(1024),
    plugin_id LONGTEXT NOT NULL,
    plugin_version LONGTEXT NOT NULL,
    operation LONGTEXT NOT NULL,
    status VARBINARY(128) NOT NULL,
    request_id LONGTEXT,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    error_code LONGTEXT,
    metadata JSON NOT NULL,
    PRIMARY KEY (execution_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_usage_operation (
    deployment_scope VARBINARY(256) NOT NULL,
    subject_scope VARBINARY(1024) NOT NULL,
    operation_scope VARBINARY(64) NOT NULL,
    idempotency_key VARBINARY(256) NOT NULL,
    request_digest LONGTEXT NOT NULL,
    reservation_id VARBINARY(1024) NOT NULL,
    result_body JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (deployment_scope, subject_scope, operation_scope, idempotency_key)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_usage_reservation (
    reservation_id VARBINARY(1024) NOT NULL,
    meeting_id LONGTEXT,
    usage_type LONGTEXT,
    status VARBINARY(128) NOT NULL,
    quantity DECIMAL(20,6),
    provider_ref LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    record_format VARBINARY(128) NOT NULL,
    deployment_scope VARBINARY(256) NOT NULL,
    subject_scope VARBINARY(1024) NOT NULL,
    grant_id LONGTEXT,
    expected_meeting_id LONGTEXT NOT NULL,
    bound_meeting_id VARBINARY(1024),
    bound_at DATETIME(6),
    usage_unit LONGTEXT NOT NULL,
    reserved_amount DECIMAL(20,6) NOT NULL,
    actual_amount DECIMAL(20,6),
    expires_at DATETIME(6),
    version BIGINT NOT NULL,
    committed_at DATETIME(6),
    cancelled_at DATETIME(6),
    cancellation_reason_code LONGTEXT,
    lease_owner LONGTEXT,
    lease_token LONGTEXT,
    lease_until DATETIME(6),
    lease_version BIGINT DEFAULT 0 NOT NULL,
    PRIMARY KEY (reservation_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE ai_trans_workflow_step (
    step_id BIGINT NOT NULL AUTO_INCREMENT,
    meeting_id VARBINARY(1024) NOT NULL,
    step_type VARBINARY(128) NOT NULL,
    step_key VARBINARY(1024) NOT NULL,
    status VARBINARY(128) NOT NULL,
    attempt_count INT DEFAULT 0 NOT NULL,
    lease_owner LONGTEXT,
    lease_until DATETIME(6),
    input_artifact_id LONGTEXT,
    output_artifact_id LONGTEXT,
    error_code LONGTEXT,
    error_message LONGTEXT,
    updated_at DATETIME(6) NOT NULL,
    checkpoint_type LONGTEXT NOT NULL,
    canonical_payload LONGTEXT NOT NULL,
    content_digest LONGTEXT NOT NULL,
    initial_available_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6),
    lease_token LONGTEXT,
    lease_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT ai_trans_workflow_step_key_uq UNIQUE (meeting_id, step_type, step_key),
    PRIMARY KEY (step_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- MySQL 不支持部分索引；保留可空唯一键与必要筛选列。DESC 在5.7不生效，采用普通BTREE。
CREATE INDEX ai_trans_idempotency_expiry_idx ON ai_trans_idempotency_record (expires_at);
CREATE INDEX ai_trans_meeting_event_stream_sequence_idx ON ai_trans_meeting_event (meeting_id, stream_id, event_sequence);
CREATE UNIQUE INDEX ai_trans_meeting_finalization_receipt_uq ON ai_trans_meeting (finalization_receipt_id);
CREATE INDEX ai_trans_meeting_realtime_token_grant_active_idx ON ai_trans_meeting_realtime_token_grant (meeting_id, expires_at);
CREATE UNIQUE INDEX ai_trans_meeting_scope_session_uq ON ai_trans_meeting (deployment_scope, client_session_id);
CREATE INDEX ai_trans_meeting_scope_state_idx ON ai_trans_meeting (deployment_scope, state, updated_at);
CREATE INDEX ai_trans_meeting_scope_subject_created_idx ON ai_trans_meeting (deployment_scope, subject_scope, created_at, meeting_id);
CREATE INDEX ai_trans_outbox_claim_idx ON ai_trans_outbox_event (status, available_at, lease_until);
CREATE INDEX ai_trans_outbox_leased_expiry_idx ON ai_trans_outbox_event (status, lease_until, lease_version);
CREATE INDEX ai_trans_usage_operation_reservation_idx ON ai_trans_usage_operation (reservation_id, created_at);
CREATE INDEX ai_trans_usage_reservation_expired_unbound_idx ON ai_trans_usage_reservation (record_format, status, bound_meeting_id, expires_at, lease_until, reservation_id);
CREATE UNIQUE INDEX ai_trans_usage_reservation_scope_uq ON ai_trans_usage_reservation (deployment_scope, subject_scope, reservation_id);
CREATE INDEX ai_trans_workflow_step_claim_idx ON ai_trans_workflow_step (status, lease_until, updated_at);
CREATE INDEX ai_trans_workflow_step_claim_v010_idx ON ai_trans_workflow_step (step_type, status, next_attempt_at, lease_until, meeting_id, step_key);

-- 所有表与唯一键建完后添加即时外键，包括会议与预占的双向引用。
ALTER TABLE ai_trans_artifact_deletion_item
    ADD CONSTRAINT ai_trans_artifact_deletion_item_plan_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting_deletion_plan (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_artifact
    ADD CONSTRAINT ai_trans_artifact_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_durable_input_fact
    ADD CONSTRAINT ai_trans_durable_input_fact_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_final_usage_fact
    ADD CONSTRAINT ai_trans_final_usage_fact_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_finalization_execution_fact
    ADD CONSTRAINT ai_trans_finalization_execution_fact_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_audio_frame
    ADD CONSTRAINT ai_trans_meeting_audio_frame_input_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting_audio_input (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_audio_input
    ADD CONSTRAINT ai_trans_meeting_audio_input_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_create_fact
    ADD CONSTRAINT ai_trans_meeting_create_fact_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_deletion_plan
    ADD CONSTRAINT ai_trans_meeting_deletion_plan_manifest_fk FOREIGN KEY (meeting_id, source_manifest_revision)
    REFERENCES ai_trans_meeting_manifest_revision (meeting_id, manifest_revision) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_deletion_plan
    ADD CONSTRAINT ai_trans_meeting_deletion_plan_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_deletion_plan
    ADD CONSTRAINT ai_trans_meeting_deletion_plan_workflow_fk FOREIGN KEY (meeting_id, source_workflow_step_type, source_workflow_step_key)
    REFERENCES ai_trans_workflow_step (meeting_id, step_type, step_key) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_event
    ADD CONSTRAINT ai_trans_meeting_event_meeting_id_fkey FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_client_input
    ADD CONSTRAINT ai_trans_meeting_client_input_meeting_id_fkey FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_client_event
    ADD CONSTRAINT ai_trans_meeting_client_event_event_id_fkey FOREIGN KEY (event_id)
    REFERENCES ai_trans_meeting_event (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_manifest_revision
    ADD CONSTRAINT ai_trans_meeting_manifest_revision_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_manifest_revision
    ADD CONSTRAINT ai_trans_meeting_manifest_revision_previous_fk FOREIGN KEY (meeting_id, previous_revision)
    REFERENCES ai_trans_meeting_manifest_revision (meeting_id, manifest_revision) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_realtime_token_grant
    ADD CONSTRAINT ai_trans_meeting_realtime_token_grant_meeting_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_stream
    ADD CONSTRAINT ai_trans_meeting_stream_meeting_id_fkey FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting
    ADD CONSTRAINT ai_trans_meeting_usage_reservation_fk FOREIGN KEY (usage_reservation_id)
    REFERENCES ai_trans_usage_reservation (reservation_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_plugin_execution
    ADD CONSTRAINT ai_trans_plugin_execution_meeting_id_fkey FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_usage_operation
    ADD CONSTRAINT ai_trans_usage_operation_reservation_scope_fk FOREIGN KEY (deployment_scope, subject_scope, reservation_id)
    REFERENCES ai_trans_usage_reservation (deployment_scope, subject_scope, reservation_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_usage_reservation
    ADD CONSTRAINT ai_trans_usage_reservation_bound_meeting_fk FOREIGN KEY (bound_meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_workflow_step
    ADD CONSTRAINT ai_trans_workflow_step_meeting_id_fkey FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_client_event
    ADD CONSTRAINT ai_trans_meeting_client_event_input_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting_client_input (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_client_summary
    ADD CONSTRAINT ai_trans_meeting_client_summary_input_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting_client_input (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ai_trans_meeting_client_result
    ADD CONSTRAINT ai_trans_meeting_client_result_input_fk FOREIGN KEY (meeting_id)
    REFERENCES ai_trans_meeting_client_input (meeting_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 登记表最后创建；此处不插入版本。入口在所有表、索引、即时外键完成后才登记。
CREATE TABLE ai_trans_schema_migrations (
    version VARBINARY(64) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (version)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
