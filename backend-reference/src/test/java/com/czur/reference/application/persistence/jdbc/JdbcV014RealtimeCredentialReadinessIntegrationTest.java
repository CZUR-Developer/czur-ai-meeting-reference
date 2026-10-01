package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V014 可选 readiness 的隔离 PostgreSQL 16 门禁。
 *
 * <p>每例只操作随机 schema，明确拒绝共享 55432；同名弱 CHECK、缺登记和错索引
 * 必须关闭式失败，而默认关闭的旧运行时继续以 V013 启动。测试结束删除随机 schema，
 * 不接生产服务或公共数据。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV014RealtimeCredentialReadinessIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql");
    private static final String V014 = "V014__realtime_token_grant.sql";

    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        schema = "realtime_credential_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        for (String migration : MIGRATIONS) {
            apply(migration);
            register(migration);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (schema != null) {
            try (Connection connection = source(null).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void defaultRuntimeStartsWithoutV014AndOptionalGateRequiresRegistration() throws Exception {
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertTrue(runtime.checkReadiness().ready());
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    runtime.checkRealtimeCredentialReadiness().status());
            apply(V014);
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    runtime.checkRealtimeCredentialReadiness().status());
            register(V014);
            assertTrue(runtime.checkRealtimeCredentialReadiness().ready());
            apply(V014); // 版本登记失败后精确原样重跑允许通过。
            assertTrue(runtime.checkRealtimeCredentialReadiness().ready());
        }
    }

    @Test
    void registeredHalfTableAndWeakCheckAreRejectedWithoutRepair() throws Exception {
        execute("CREATE TABLE meeting_realtime_token_grant (meeting_id TEXT)");
        register(V014);
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkRealtimeCredentialReadiness().status());
            assertThrows(SQLException.class, () -> apply(V014));
        }
        execute("DROP TABLE meeting_realtime_token_grant");
        apply(V014);
        execute("""
                ALTER TABLE meeting_realtime_token_grant
                    DROP CONSTRAINT meeting_realtime_token_grant_time_ck;
                ALTER TABLE meeting_realtime_token_grant
                    ADD CONSTRAINT meeting_realtime_token_grant_time_ck
                    CHECK ((TRUE OR expires_at > issued_at) IS TRUE)
                """);
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkRealtimeCredentialReadiness().status());
            assertThrows(SQLException.class, () -> apply(V014));
        }
    }

    @Test
    void wrongIndexFailsOptionalGateWithoutAffectingOldReadiness() throws Exception {
        apply(V014);
        register(V014);
        execute("DROP INDEX meeting_realtime_token_grant_active_idx");
        execute("CREATE INDEX meeting_realtime_token_grant_active_idx "
                + "ON meeting_realtime_token_grant (expires_at, meeting_id)");
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertTrue(runtime.checkReadiness().ready());
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkRealtimeCredentialReadiness().status());
            assertThrows(SQLException.class, () -> apply(V014));
        }
    }

    @Test
    void sameConnectionTemporaryShadowCannotMasqueradeAsPersistedGrantTable() throws Exception {
        apply(V014);
        register(V014);
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            assertTrue(JdbcRealtimeCredentialSchema.matches(connection));
            statement.execute("CREATE TEMP TABLE meeting_realtime_token_grant (fake TEXT)");
            assertFalse(JdbcRealtimeCredentialSchema.matches(connection));
        }
    }

    private JdbcDatabaseRuntime runtime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                scopedUrl(), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"), Duration.ofSeconds(5),
                Duration.ofSeconds(3), 2, 1));
    }

    private String scopedUrl() {
        String url = requiredUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private void apply(String filename) throws Exception {
        execute(readMigration(filename));
    }

    private void register(String filename) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO schema_migrations(version) VALUES (?)")) {
            statement.setString(1, filename.substring(0, filename.length() - 4));
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static PGSimpleDataSource source(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredUrl());
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) {
            source.setCurrentSchema(schema);
        }
        return source;
    }

    private static String readMigration(String name) throws Exception {
        for (Path path : new Path[]{Path.of("deploy", "migrations", name),
                Path.of("..", "deploy", "migrations", name)}) {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found");
    }

    private static String requiredUrl() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        return url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
