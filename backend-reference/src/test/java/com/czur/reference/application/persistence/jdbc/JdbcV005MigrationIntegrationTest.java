package com.czur.reference.application.persistence.jdbc;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V005 meeting-level 输入责任约束的全新 PostgreSQL 16 门禁。
 *
 * <p>参考 Schema 尚未发布，本测试只执行 V001→V005 的干净迁移链；不构造或
 * 兼容任何曾运行未发布中间版本的数据库。测试验证默认 OPEN、receipt 成对
 * 约束、状态枚举和迁移安全重跑。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV005MigrationIntegrationTest {
    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "v005_" + UUID.randomUUID().toString().replace("-", "");
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
    }

    @Test
    void acceptedReceiptIsUniqueAcrossMeetings() throws Exception {
        Instant now = Instant.parse("2026-09-20T02:30:00Z");
        var repository = new JdbcMeetingRepository(dataSource);
        for (String suffix : new String[]{"a", "b"}) {
            repository.save(MeetingAggregate.MeetingSnapshot.legacyUnowned(
                    "meeting-unique-" + suffix, "deployment", "session-unique-" + suffix,
                    "profile", 1, MeetingState.CREATED, now, now));
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE meeting
                       SET input_state = 'INPUT_ACCEPTED',
                           finalization_receipt_id = 'receipt-shared'
                     WHERE meeting_id = 'meeting-unique-a'
                    """));
        }
        assertConstraintViolation("""
                UPDATE meeting
                   SET input_state = 'INPUT_ACCEPTED',
                       finalization_receipt_id = 'receipt-shared'
                 WHERE meeting_id = 'meeting-unique-b'
                """);
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
    void freshMeetingDefaultsToOpenAndReceiptShapeIsDatabaseEnforced() throws Exception {
        Instant now = Instant.parse("2026-09-20T02:00:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-v005", "deployment", "session-v005", "profile", 1,
                        MeetingState.CREATED, now, now));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT input_state, finalization_receipt_id
                       FROM meeting WHERE meeting_id = 'meeting-v005'
                     """)) {
            assertTrue(rows.next());
            assertEquals("INPUT_OPEN", rows.getString(1));
            assertEquals(null, rows.getString(2));
        }

        assertConstraintViolation("""
                UPDATE meeting SET input_state = 'INPUT_ACCEPTED'
                 WHERE meeting_id = 'meeting-v005'
                """);
        assertConstraintViolation("""
                UPDATE meeting SET finalization_receipt_id = 'receipt-invalid'
                 WHERE meeting_id = 'meeting-v005'
                """);
        assertConstraintViolation("""
                UPDATE meeting SET input_state = 'UNKNOWN_INPUT'
                 WHERE meeting_id = 'meeting-v005'
                """);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE meeting
                       SET input_state = 'INPUT_COMPLETE_PENDING'
                     WHERE meeting_id = 'meeting-v005'
                    """));
            assertEquals(1, statement.executeUpdate("""
                    UPDATE meeting
                       SET input_state = 'INPUT_ACCEPTED',
                           finalization_receipt_id = 'receipt-valid'
                     WHERE meeting_id = 'meeting-v005'
                    """));
        }

        applyMigration("V005__meeting_finalization_fact.sql");
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
