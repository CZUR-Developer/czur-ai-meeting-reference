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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V004 事件 envelope 元数据约束的真实 PostgreSQL 门禁。
 *
 * <p>V004 允许 V001 的历史事件继续保持两列元数据都为 {@code NULL}，但新事件
 * 必须同时写入非空的 {@code spec_version} 和 {@code stream_id}。本测试专门覆盖
 * PostgreSQL {@code CHECK} 约束对 {@code NULL} 的三值逻辑，防止“只有一列为空”
 * 的半迁移数据被错误接受。</p>
 *
 * <p>测试只在显式设置 {@code REFERENCE_TEST_DATABASE_URL} 时运行。每次测试使用
 * 一个随机 schema，不访问调用方的 public schema，也不启动、删除或修改 Docker
 * 数据卷。测试结束后只删除本次创建的 schema。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV004MigrationIntegrationTest {
    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource schemaDataSource;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        adminDataSource = dataSource(null);
        schema = "v004_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quotedIdentifier(schema));
        }
        schemaDataSource = dataSource(schema);
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quotedIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void rejectsHalfMigratedEnvelopeAndAllowsHistoricalAndCanonicalRows() throws Exception {
        applyMigration("V001__initial_schema.sql");

        // 先写入没有 V004 字段的历史事件，验证迁移不能猜测历史 envelope。
        executeUpdate("""
                INSERT INTO meeting (
                    meeting_id, deployment_scope, client_session_id, subject_scope,
                    profile_id, profile_revision, state, created_at, updated_at)
                VALUES (
                    'v004-meeting', 'reference', 'v004-session', 'reference-dev',
                    'default', 1, 'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        executeUpdate("""
                INSERT INTO meeting_event (
                    meeting_id, event_sequence, event_id, event_type, payload, occurred_at)
                VALUES (
                    'v004-meeting', 1, 'v004-history', 'CREATED', '{}'::jsonb,
                    CURRENT_TIMESTAMP)
                """);

        applyMigration("V004__meeting_event_envelope.sql");

        assertTrue(columnExists("meeting_event", "spec_version"));
        assertTrue(columnExists("meeting_event", "stream_id"));
        assertNotNull(queryText("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid = 'meeting_event'::regclass
                   AND conname = 'meeting_event_envelope_pair_ck'
                """));

        // 历史事件保持两列 NULL；这是 V004 明确允许的兼容形态。
        assertEquals(1L, queryLong("""
                SELECT count(*) FROM meeting_event
                 WHERE event_id = 'v004-history'
                   AND spec_version IS NULL AND stream_id IS NULL
                """));

        executeUpdate("""
                INSERT INTO meeting_event (
                    meeting_id, event_sequence, event_id, event_type, payload,
                    occurred_at, spec_version, stream_id)
                VALUES (
                    'v004-meeting', 2, 'v004-canonical', 'UPDATED', '{}'::jsonb,
                    CURRENT_TIMESTAMP, '1.0', 'realtime')
                """);

        assertRejectedHalfEnvelope("v004-half-stream", null, "stream-only");
        assertRejectedHalfEnvelope("v004-half-spec", "1.0", null);
        assertRejectedHalfEnvelope("v004-blank-stream", "1.0", "   ");
        assertRejectedHalfEnvelope("v004-blank-spec", "   ", "realtime");

        // V004 需要可安全重跑；约束和字段不能因重复执行而报错或重复创建。
        applyMigration("V004__meeting_event_envelope.sql");
    }

    private void assertRejectedHalfEnvelope(
            String eventId, String specVersion, String streamId) {
        String spec = sqlLiteral(specVersion);
        String stream = sqlLiteral(streamId);
        SQLException failure = assertThrows(SQLException.class, () -> executeUpdate("""
                INSERT INTO meeting_event (
                    meeting_id, event_sequence, event_id, event_type, payload,
                    occurred_at, spec_version, stream_id)
                VALUES (
                    'v004-meeting', 10, '%s', 'INVALID', '{}'::jsonb,
                    CURRENT_TIMESTAMP, %s, %s)
                """.formatted(eventId, spec, stream)));
        assertEquals("23514", failure.getSQLState(),
                "半迁移 envelope 必须由 CHECK 约束拒绝");
    }

    private void applyMigration(String filename) throws Exception {
        String sql = Files.readString(findMigration(filename), StandardCharsets.UTF_8);
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String queryText(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertNotNull(value);
            return value;
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT 1
                       FROM information_schema.columns
                      WHERE table_schema = '%s'
                        AND table_name = '%s'
                        AND column_name = '%s'
                     """.formatted(schema, table, column))) {
            return rows.next();
        }
    }

    private static Path findMigration(String filename) {
        Path[] candidates = {
                Path.of("..", "deploy", "migrations", filename),
                Path.of("deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到迁移文件：" + filename);
    }

    private static PGSimpleDataSource dataSource(String currentSchema) {
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

    private static String quotedIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String sqlLiteral(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }
}
