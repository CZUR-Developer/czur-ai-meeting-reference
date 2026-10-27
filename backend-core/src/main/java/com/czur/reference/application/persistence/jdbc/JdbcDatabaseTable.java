package com.czur.reference.application.persistence.jdbc;

import java.util.Objects;

/**
 * 当前 V001 的固定会议专用表映射。
 *
 * <p>逻辑表名不带引擎前缀；MySQL 统一使用 {@code ai_trans_} 前缀，包含独立
 * 基线登记表。本枚举只接受封闭的逻辑表，不接收可配置前缀或任意 SQL 标识符，
 * 因而业务调用者不能借映射访问旧业务表。后续 Store 适配应消费同一映射。</p>
 */
public enum JdbcDatabaseTable {
    SCHEMA_MIGRATIONS("schema_migrations"),
    ARTIFACT("artifact"),
    ARTIFACT_DELETION_ITEM("artifact_deletion_item"),
    DURABLE_INPUT_FACT("durable_input_fact"),
    FINAL_USAGE_FACT("final_usage_fact"),
    FINALIZATION_EXECUTION_FACT("finalization_execution_fact"),
    IDEMPOTENCY_RECORD("idempotency_record"),
    IDEMPOTENCY_LOCK("idempotency_lock"),
    MEETING("meeting"),
    MEETING_AUDIO_FRAME("meeting_audio_frame"),
    MEETING_AUDIO_INPUT("meeting_audio_input"),
    MEETING_CREATE_FACT("meeting_create_fact"),
    MEETING_DELETION_PLAN("meeting_deletion_plan"),
    MEETING_EVENT("meeting_event"),
    MEETING_HEARTBEAT("meeting_heartbeat"),
    MEETING_ABANDONMENT("meeting_abandonment"),
    MEETING_FINALIZATION_PENDING("meeting_finalization_pending"),
    MEETING_CLIENT_INPUT("meeting_client_input"),
    MEETING_CLIENT_EVENT("meeting_client_event"),
    MEETING_CLIENT_SUMMARY("meeting_client_summary"),
    MEETING_CLIENT_RESULT("meeting_client_result"),
    MEETING_MANIFEST_REVISION("meeting_manifest_revision"),
    MEETING_REALTIME_TOKEN_GRANT("meeting_realtime_token_grant"),
    MEETING_STREAM("meeting_stream"),
    OUTBOX_EVENT("outbox_event"),
    PLUGIN_EXECUTION("plugin_execution"),
    USAGE_OPERATION("usage_operation"),
    USAGE_RESERVATION("usage_reservation"),
    WORKFLOW_STEP("workflow_step"),
    /** 可选参考人物插件使用；默认会议 readiness 不要求这张表。 */
    SPEAKER_IDENTITY_PERSON("speaker_identity_person");

    private final String logicalName;

    JdbcDatabaseTable(String logicalName) {
        this.logicalName = logicalName;
    }

    /** 返回指定引擎的固定物理表名，只供公共 JDBC SQL 装配使用。 */
    public String physicalName(JdbcDatabaseEngine engine) {
        Objects.requireNonNull(engine, "engine");
        if (!requiredFor(engine)) {
            throw new IllegalArgumentException("idempotency lock table is MySQL-only");
        }
        return engine == JdbcDatabaseEngine.MYSQL ? "ai_trans_" + logicalName : logicalName;
    }

    /** 非 MySQL 分支沿用 advisory lock，不要求或访问 MySQL 专用的辅助行锁表。 */
    public boolean requiredFor(JdbcDatabaseEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return this != IDEMPOTENCY_LOCK || engine == JdbcDatabaseEngine.MYSQL;
    }

    /** 可选插件只在显式装配时核对自己的表，不能阻断未启用它的 CZUR／伙伴宿主。 */
    public boolean requiredForBaseline(JdbcDatabaseEngine engine) {
        return this != SPEAKER_IDENTITY_PERSON && requiredFor(engine);
    }
}
