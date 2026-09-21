package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V007 Manifest revision 物理事实的 PostgreSQL 16 门禁。
 *
 * <p>每个用例使用独立随机 schema，并严格执行仓库迁移。测试覆盖精确重复执行、
 * 七列/六约束/唯一主键索引、revision 链、canonical JSON 镜像、核心 SHA-256、
 * RESTRICT 外键和损坏开发结构的关闭式失败。它只执行原生 SQL，不实现或伪装
 * T14-03 的 {@code JdbcManifestFactStore}。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV007MigrationIntegrationTest {
    private static final List<String> MIGRATIONS_THROUGH_V007 = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql");
    private static final String MEETING_ID = "mtg_fixture_ready";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshV007Schema() throws Exception {
        adminDataSource = postgres(null);
        schema = "v007_" + UUID.randomUUID().toString().replace("-", "");
        createSchema();
        dataSource = postgres(schema);
        applyThroughV007();
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
    void freshMigrationHasExactPhysicalShapeAndRerunsWithoutBackfill() throws Exception {
        assertEquals(List.of(
                        "meeting_id|text|true|null",
                        "manifest_revision|integer|true|null",
                        "previous_revision|integer|false|null",
                        "canonical_json|text|true|null",
                        "manifest_json|jsonb|true|null",
                        "content_sha256|bytea|true|null",
                        "recorded_at|timestamp with time zone|true|CURRENT_TIMESTAMP"),
                queryStrings("""
                        SELECT attname || '|' || format_type(atttypid, atttypmod)
                               || '|' || attnotnull || '|'
                               || coalesce(pg_get_expr(d.adbin, d.adrelid), 'null')
                          FROM pg_attribute a
                          LEFT JOIN pg_attrdef d
                            ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                         WHERE a.attrelid = 'meeting_manifest_revision'::regclass
                           AND a.attnum > 0 AND NOT a.attisdropped
                         ORDER BY a.attnum
                        """));
        assertEquals(6L, scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'meeting_manifest_revision'::regclass
                """));
        assertEquals(3L, scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'meeting_manifest_revision'::regclass
                   AND contype = 'c' AND convalidated
                """));
        assertEquals(1L, scalarLong("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND tablename = 'meeting_manifest_revision'
                """), "only the composite primary-key index is allowed");

        createMeeting(MEETING_ID);
        assertEquals(0L, scalarLong("SELECT count(*) FROM meeting_manifest_revision"));

        // 模拟 DDL 已提交、迁移版本尚未登记；必须保持数据和物理定义不变。
        applyMigration("V007__meeting_manifest_revision.sql");
        assertEquals(0L, scalarLong("SELECT count(*) FROM meeting_manifest_revision"));
    }

    @Test
    void acceptsCanonicalRevisionChainAndPersistsRawCoreSha256() throws Exception {
        createMeeting(MEETING_ID);
        String revisionOne = canonicalReadyManifest();
        String revisionTwo = revisionOne.replace(
                "\"manifestRevision\":1", "\"manifestRevision\":2");

        insertManifest(1, null, revisionOne, revisionOne, sha256(revisionOne));
        insertManifest(2, 1, revisionTwo, revisionTwo, sha256(revisionTwo));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT canonical_json,
                            manifest_json::text,
                            content_sha256,
                            octet_length(content_sha256),
                            recorded_at IS NOT NULL
                       FROM meeting_manifest_revision
                      WHERE meeting_id = ? AND manifest_revision = 2
                     """)) {
            statement.setString(1, MEETING_ID);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(revisionTwo, row.getString(1));
                assertEquals(
                        MeetingManifestJson.decode(revisionTwo),
                        MeetingManifestJson.decode(row.getString(2)));
                assertArrayEquals(sha256(revisionTwo), row.getBytes(3));
                assertEquals(32, row.getInt(4));
                assertTrue(row.getBoolean(5));
                assertFalse(row.next());
            }
        }
        assertEquals(2L, scalarLong("SELECT count(*) FROM meeting_manifest_revision"));
    }

    @Test
    void rejectsBrokenChainRootShapeIdentityMirrorStatusAndHash() throws Exception {
        createMeeting(MEETING_ID);
        String valid = canonicalReadyManifest();
        String revisionTwo = valid.replace(
                "\"manifestRevision\":1", "\"manifestRevision\":2");

        assertConstraintViolation(() -> insertManifest(
                2, null, revisionTwo, revisionTwo, sha256(revisionTwo)));
        assertConstraintViolation(() -> insertManifest(
                2, 1, revisionTwo, revisionTwo, sha256(revisionTwo)));
        assertConstraintViolation(() -> insertManifest(1, 1, valid, valid, sha256(valid)));

        String missingTitle = valid.replaceFirst(
                "\\\"title\\\":(?:null|\\\"[^\\\"]*\\\"),", "");
        assertFalse(valid.equals(missingTitle), "fixture must contain the canonical title field");
        assertConstraintViolation(() -> insertManifest(
                1, null, missingTitle, missingTitle, sha256(missingTitle)));
        String extraRoot = valid.replace("\"warnings\":[]}", "\"warnings\":[],\"provider\":\"x\"}");
        assertConstraintViolation(() -> insertManifest(
                1, null, extraRoot, extraRoot, sha256(extraRoot)));
        String wrongIdentity = valid.replace(MEETING_ID, "mtg_other_identity");
        assertConstraintViolation(() -> insertManifest(
                1, null, wrongIdentity, wrongIdentity, sha256(wrongIdentity)));
        String nonFinal = valid.replace("\"status\":\"READY\"", "\"status\":\"GENERATING\"");
        assertConstraintViolation(() -> insertManifest(
                1, null, nonFinal, nonFinal, sha256(nonFinal)));
        assertConstraintViolation(() -> insertManifest(
                1, null, valid,
                valid.replace("\"status\":\"READY\"", "\"status\":\"FAILED\""),
                sha256(valid)));
        assertConstraintViolation(() -> insertManifest(1, null, valid, valid, new byte[32]));
        assertConstraintViolation(() -> insertManifest(1, null, valid, valid, new byte[31]));

        assertEquals(0L, scalarLong("SELECT count(*) FROM meeting_manifest_revision"));
    }

    @Test
    void meetingAndPreviousRevisionForeignKeysUseRestrict() throws Exception {
        createMeeting(MEETING_ID);
        String revisionOne = canonicalReadyManifest();
        String revisionTwo = revisionOne.replace(
                "\"manifestRevision\":1", "\"manifestRevision\":2");
        insertManifest(1, null, revisionOne, revisionOne, sha256(revisionOne));
        insertManifest(2, 1, revisionTwo, revisionTwo, sha256(revisionTwo));

        assertConstraintViolation(() -> executeUpdate(
                "DELETE FROM meeting_manifest_revision WHERE meeting_id = '"
                        + MEETING_ID + "' AND manifest_revision = 1"));
        assertConstraintViolation(() -> executeUpdate(
                "DELETE FROM meeting WHERE meeting_id = '" + MEETING_ID + "'"));
    }

    @Test
    void migrationRejectsHalfTableWrongColumnAndSameNameWeakConstraint() throws Exception {
        recreateThroughV006();
        executeUpdate("CREATE TABLE meeting_manifest_revision (meeting_id TEXT NOT NULL)");
        assertMigrationRejected();

        recreateThroughV006();
        executeUpdate("""
                CREATE TABLE meeting_manifest_revision (
                    meeting_id TEXT NOT NULL,
                    manifest_revision BIGINT NOT NULL)
                """);
        assertMigrationRejected();

        recreateThroughV006();
        applyMigration("V007__meeting_manifest_revision.sql");
        executeUpdate("""
                ALTER TABLE meeting_manifest_revision
                    DROP CONSTRAINT meeting_manifest_revision_chain_ck,
                    ADD CONSTRAINT meeting_manifest_revision_chain_ck CHECK (true)
                """);
        assertMigrationRejected();
    }

    private void assertMigrationRejected() {
        SQLException failure = assertThrows(
                SQLException.class,
                () -> applyMigration("V007__meeting_manifest_revision.sql"));
        assertFalse(failure.getSQLState() == null || failure.getSQLState().isBlank());
    }

    private void assertConstraintViolation(SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertTrue(failure.getSQLState().startsWith("23"), failure.getSQLState());
    }

    private void insertManifest(
            int revision,
            Integer previousRevision,
            String canonicalJson,
            String manifestJson,
            byte[] sha256) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_manifest_revision (
                         meeting_id, manifest_revision, previous_revision,
                         canonical_json, manifest_json, content_sha256)
                     VALUES (?, ?, ?, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, MEETING_ID);
            statement.setInt(2, revision);
            if (previousRevision == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, previousRevision);
            }
            statement.setString(4, canonicalJson);
            statement.setString(5, manifestJson);
            statement.setBytes(6, sha256);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void createMeeting(String meetingId) {
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId,
                        "v007-test-deployment",
                        "session-" + meetingId,
                        "profile",
                        1,
                        MeetingState.CREATED,
                        now,
                        now));
    }

    private String canonicalReadyManifest() throws Exception {
        return MeetingManifestJson.encode(MeetingManifestJson.decode(readFixture(
                "meeting-manifest.ready.json")));
    }

    private static byte[] sha256(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            // 保留一个可读断言，避免测试环境错误地返回其它摘要长度或算法别名。
            assertEquals(64, HexFormat.of().formatHex(digest).length());
            return digest;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private void recreateThroughV006() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + quoteIdentifier(schema) + " CASCADE");
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS_THROUGH_V007.subList(0, 6)) {
            applyMigration(migration);
        }
    }

    private void applyThroughV007() throws Exception {
        for (String migration : MIGRATIONS_THROUGH_V007) {
            applyMigration(migration);
        }
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new java.util.ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }

    private int executeUpdate(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private void createSchema() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
    }

    private static String readMigration(String filename) throws Exception {
        return readFirstExisting(
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename));
    }

    private static String readFixture(String filename) throws Exception {
        return readFirstExisting(
                Path.of("contracts", "fixtures", filename),
                Path.of("..", "contracts", "fixtures", filename));
    }

    private static String readFirstExisting(Path... candidates) throws Exception {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("required test file not found");
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

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
