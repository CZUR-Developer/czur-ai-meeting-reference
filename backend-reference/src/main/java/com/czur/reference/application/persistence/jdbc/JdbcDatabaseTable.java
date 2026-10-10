package com.czur.reference.application.persistence.jdbc;

import java.util.Objects;

/**
 * 当前 V001 的固定会议专用表映射。
 *
 * <p>PostgreSQL 保留原表名；MySQL 统一使用 {@code ai_trans_} 前缀，包含独立
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
    MEETING("meeting"),
    MEETING_AUDIO_FRAME("meeting_audio_frame"),
    MEETING_AUDIO_INPUT("meeting_audio_input"),
    MEETING_CREATE_FACT("meeting_create_fact"),
    MEETING_DELETION_PLAN("meeting_deletion_plan"),
    MEETING_EVENT("meeting_event"),
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
    WORKFLOW_STEP("workflow_step");

    private final String postgresName;

    JdbcDatabaseTable(String postgresName) {
        this.postgresName = postgresName;
    }

    /** 返回指定引擎的固定物理表名，只供公共 JDBC SQL 装配使用。 */
    public String physicalName(JdbcDatabaseEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return engine == JdbcDatabaseEngine.MYSQL ? "ai_trans_" + postgresName : postgresName;
    }
}
