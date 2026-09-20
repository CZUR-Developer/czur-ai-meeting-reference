package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V006 查询读模型的全新 PostgreSQL 16 门禁。
 *
 * <p>每个用例创建隔离 schema，并严格执行 V001→V006。测试只验证字段默认值、
 * 两组状态约束、列表索引和 V006 自身重复执行；不会访问共享 schema、现有 Docker
 * 卷或未发布的中间数据库，也不提前实现查询分页或 Manifest 内容持久化。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV006MigrationIntegrationTest {
    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "v006_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        applyMigration("V003__identity_entitlement.sql");
        applyMigration("V004__meeting_event_envelope.sql");
        applyMigration("V005__meeting_finalization_fact.sql");
        applyMigration("V006__meeting_read_model.sql");
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void newMeetingDefaultsManifestToNotStartedAndAcceptsEveryPublishedState() throws Exception {
        Instant now = Instant.parse("2026-09-20T08:00:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-v006", "deployment", "session-v006", "profile", 1,
                        MeetingState.CREATED, now, now));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT state, manifest_state
                       FROM meeting
                      WHERE meeting_id = 'meeting-v006'
                     """)) {
            assertTrue(rows.next());
            assertEquals("CREATED", rows.getString("state"));
            assertEquals("NOT_STARTED", rows.getString("manifest_state"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (MeetingState state : MeetingState.values()) {
                if (state != MeetingState.UNKNOWN) {
                    assertEquals(1, statement.executeUpdate(
                            "UPDATE meeting SET state = '" + state.name()
                                    + "' WHERE meeting_id = 'meeting-v006'"));
                }
            }
            for (ManifestStatus status : ManifestStatus.values()) {
                assertEquals(1, statement.executeUpdate(
                        "UPDATE meeting SET manifest_state = '" + status.name()
                                + "' WHERE meeting_id = 'meeting-v006'"));
            }
        }

        assertConstraintViolation("""
                UPDATE meeting SET state = 'UNKNOWN'
                 WHERE meeting_id = 'meeting-v006'
                """);
        assertConstraintViolation("""
                UPDATE meeting SET state = 'PROVIDER_PRIVATE_STATE'
                 WHERE meeting_id = 'meeting-v006'
                """);
        assertConstraintViolation("""
                UPDATE meeting SET manifest_state = 'PROVIDER_PRIVATE_STATE'
                 WHERE meeting_id = 'meeting-v006'
                """);

        // 模拟 SQL 已提交但版本登记尚未完成后的安全重跑。
        applyMigration("V006__meeting_read_model.sql");
    }

    @Test
    void ownerListIndexUsesFrozenColumnsAndDescendingKeysetOrder() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT indexdef
                       FROM pg_indexes
                      WHERE schemaname = current_schema()
                        AND tablename = 'meeting'
                        AND indexname = 'meeting_scope_subject_created_idx'
                     """)) {
            assertTrue(rows.next());
            String normalized = rows.getString("indexdef").replaceAll("\\s+", " ");
            assertTrue(normalized.contains(
                    "(deployment_scope, subject_scope, created_at DESC, meeting_id DESC)"));
        }
    }

    @Test
    void databaseEnforcesSharedMeetingIdUtf8Boundary() throws Exception {
        Instant now = Instant.parse("2026-09-20T08:30:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-id-boundary", "deployment", "session-boundary", "profile", 1,
                        MeetingState.CREATED, now, now));

        String maximum = "x".repeat(1024);
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET meeting_id = ?
                      WHERE meeting_id = 'meeting-id-boundary'
                     """)) {
            statement.setString(1, maximum);
            assertEquals(1, statement.executeUpdate());
        }

        assertConstraintViolation("""
                UPDATE meeting SET meeting_id = repeat('x', 1025)
                 WHERE meeting_id = repeat('x', 1024)
                """);
        assertConstraintViolation("""
                UPDATE meeting SET meeting_id = '   '
                 WHERE meeting_id = repeat('x', 1024)
                """);
    }

    private void assertConstraintViolation(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> statement.executeUpdate(sql));
            assertTrue(failure.getSQLState().startsWith("23"));
        }
    }

    private void applyMigration(String filename) throws Exception {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        Path migration = null;
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                migration = candidate;
                break;
            }
        }
        if (migration == null) {
            throw new IllegalStateException("migration file not found: " + filename);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            postgres.setUser(user);
        }
        if (password != null) {
            postgres.setPassword(password);
        }
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
