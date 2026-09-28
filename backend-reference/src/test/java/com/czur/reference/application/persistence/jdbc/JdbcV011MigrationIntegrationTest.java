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
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V011 最终收口执行事实与 READY-only artifact 的 PostgreSQL 16 门禁。
 *
 * <p>每个测试使用独立随机 schema，覆盖 V001→V011、DDL 已提交但未登记时的精确
 * 重跑、V001 历史行与损坏结构的事务回滚、document/hash 约束，以及 READY metadata
 * 的 PK/UQ/FK/RESTRICT 行为。本类只验证物理契约，不实现 JDBC Store、Admission、
 * Completion、Worker、Manifest 编排或 runtime getter。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV011MigrationIntegrationTest {
    private static final List<String> MIGRATIONS_THROUGH_V011 = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql",
            "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql",
            "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshV011Schema() throws Exception {
        adminDataSource = postgres(null);
        schema = "v011_" + UUID.randomUUID().toString().replace("-", "");
        createSchema();
        dataSource = postgres(schema);
        applyMigrations(11);
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
    void freshV001ThroughV011HasExactColumnsConstraintsAndIndexes() throws Exception {
        assertEquals(List.of(
                        "meeting_id|text|true|null",
                        "canonical_json|text|true|null",
                        "execution_fact_json|jsonb|true|null",
                        "content_sha256|bytea|true|null"),
                columnCatalog("finalization_execution_fact"));
        assertEquals(4L, constraintCount("finalization_execution_fact"));
        assertEquals(2L, validatedCheckCount("finalization_execution_fact"));
        assertEquals(1L, indexCount("finalization_execution_fact"));

        assertEquals(List.of(
                        "artifact_id|text|true|null",
                        "meeting_id|text|true|null",
                        "artifact_type|text|true|null",
                        "media_type|text|true|null",
                        "language|text|false|null",
                        "revision|integer|true|null",
                        "intent_id|text|true|null",
                        "storage_binding|text|true|null",
                        "content_size|bigint|true|null",
                        "content_checksum|text|true|null",
                        "recorded_at|timestamp with time zone|true|CURRENT_TIMESTAMP"),
                columnCatalog("artifact"));
        assertEquals(10L, constraintCount("artifact"));
        assertEquals(5L, validatedCheckCount("artifact"));
        assertEquals(4L, indexCount("artifact"), "PK plus three UQ indexes only");
    }

    @Test
    void exactRerunPreservesExecutionArtifactAndRecordedAt() throws Exception {
        String meetingId = "meeting-rerun";
        createMeeting(meetingId);
        String canonical = canonicalExecution(meetingId);
        insertExecution(meetingId, canonical, canonical, sha256(canonical));
        insertArtifact("artifact-rerun", meetingId, "TRANSCRIPT", "application/json",
                "zh-CN", 1, "intent-rerun", "binding-rerun", 123,
                validChecksum('a'));
        String recordedAt = scalarString(
                "SELECT recorded_at::text FROM artifact WHERE artifact_id = 'artifact-rerun'");
        long artifactTableOid = scalarLong("SELECT 'artifact'::regclass::oid");

        applyMigration("V011__finalization_execution_artifact_ready.sql");

        assertEquals(artifactTableOid, scalarLong("SELECT 'artifact'::regclass::oid"),
                "exact V011 rerun must not drop and recreate artifact");
        assertEquals(recordedAt, scalarString(
                "SELECT recorded_at::text FROM artifact WHERE artifact_id = 'artifact-rerun'"));
        assertEquals(1L, scalarLong("SELECT count(*) FROM artifact"));
        assertEquals(1L, scalarLong("SELECT count(*) FROM finalization_execution_fact"));
        assertArrayEquals(sha256(canonical), queryBytes(
                "SELECT content_sha256 FROM finalization_execution_fact WHERE meeting_id = '"
                        + meetingId + "'"));
    }

    @Test
    void exactRerunDoesNotBlockAnExecutionThenArtifactBusinessTransaction() throws Exception {
        String meetingId = "meeting-lock-order";
        createMeeting(meetingId);
        String canonical = canonicalExecution(meetingId);

        try (Connection writer = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            insertExecution(writer, meetingId, canonical, canonical, sha256(canonical));

            CountDownLatch immediateStart = new CountDownLatch(0);
            CompletableFuture<SQLException> outcome = CompletableFuture.supplyAsync(
                    () -> executeMigrationAfter(immediateStart, migration));
            boolean writerCommitted = false;
            try {
                // execution 的 ACCESS SHARE 与 Writer 的 RowExclusive 兼容，因此迁移
                // 必须能在 Writer 尚未提交时完成。若迁移先持 artifact AX、再请求
                // execution AX，这里会超时，并在 Writer 随后写 artifact 时形成死锁。
                assertEquals(null, outcome.get(5, TimeUnit.SECONDS));
                insertArtifact(writer, "artifact-lock-order", meetingId, "SUMMARY",
                        "application/json", "zh-CN", 1, "intent-lock-order",
                        "binding-lock-order", 7, validChecksum('d'));
                writer.commit();
                writerCommitted = true;
            } finally {
                if (!writerCommitted) {
                    writer.rollback();
                }
                if (!outcome.isDone()) {
                    outcome.get(10, TimeUnit.SECONDS);
                }
                outcome.cancel(true);
            }
        }

        assertEquals(1L, scalarLong(
                "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id = '"
                        + meetingId + "'"));
        assertEquals(1L, scalarLong(
                "SELECT count(*) FROM artifact WHERE artifact_id = 'artifact-lock-order'"));
    }

    @Test
    void exactRerunPinsDeploymentSchemaAheadOfTemporarySameNameTables() throws Exception {
        String meetingId = "meeting-temp-shadow";
        createMeeting(meetingId);
        String canonical = canonicalExecution(meetingId);
        insertExecution(meetingId, canonical, canonical, sha256(canonical));
        insertArtifact("artifact-temp-shadow", meetingId, "TRANSCRIPT", "application/json",
                null, 1, "intent-temp-shadow", "binding-temp-shadow", 9,
                validChecksum('e'));
        long executionOid = scalarLong("SELECT 'finalization_execution_fact'::regclass::oid");
        long artifactOid = scalarLong("SELECT 'artifact'::regclass::oid");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE meeting (marker TEXT);
                    CREATE TEMP TABLE finalization_execution_fact (marker TEXT);
                    CREATE TEMP TABLE artifact (marker TEXT);
                    INSERT INTO artifact(marker) VALUES ('must-not-shadow-v011')
                    """);

            statement.execute(readMigration(
                    "V011__finalization_execution_artifact_ready.sql"));

            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM pg_temp.artifact")) {
                assertTrue(row.next());
                assertEquals(1L, row.getLong(1),
                        "migration must neither inspect nor rewrite the temporary shadow");
            }
        }

        assertEquals(executionOid,
                scalarLong("SELECT 'finalization_execution_fact'::regclass::oid"));
        assertEquals(artifactOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals(1L, scalarLong(
                "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id = '"
                        + meetingId + "'"));
        assertEquals(1L, scalarLong(
                "SELECT count(*) FROM artifact WHERE artifact_id = 'artifact-temp-shadow'"));
    }

    @Test
    void exactRerunRequiresBothV011TablesAndNeverRepairsAPartialPair() throws Exception {
        long readyArtifactOid = scalarLong("SELECT 'artifact'::regclass::oid");
        executeUpdate("DROP TABLE finalization_execution_fact");

        assertThrows(SQLException.class,
                () -> applyMigration("V011__finalization_execution_artifact_ready.sql"));
        assertEquals(readyArtifactOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals(null, scalarString(
                "SELECT to_regclass('finalization_execution_fact')::text"));

        recreateThroughV010();
        long v001ArtifactOid = scalarLong("SELECT 'artifact'::regclass::oid");
        executeUpdate("CREATE TABLE finalization_execution_fact (meeting_id TEXT)");

        assertThrows(SQLException.class,
                () -> applyMigration("V011__finalization_execution_artifact_ready.sql"));
        assertEquals(v001ArtifactOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals(List.of("meeting_id|text|false|null"),
                columnCatalog("finalization_execution_fact"));
    }

    @Test
    void v001RowRejectsMigrationAndRollsBackWithoutChangingOldTable() throws Exception {
        recreateThroughV010();
        String meetingId = "meeting-v001-row";
        createMeeting(meetingId);
        executeUpdate("""
                INSERT INTO artifact (
                    artifact_id, meeting_id, artifact_type, revision,
                    content_type, storage_binding_id, created_at)
                VALUES ('old-artifact', 'meeting-v001-row', 'TRANSCRIPT', 1,
                    'application/json', 'old-binding', CURRENT_TIMESTAMP)
                """);
        long originalOid = scalarLong("SELECT 'artifact'::regclass::oid");

        assertMigrationRejected();

        assertEquals(originalOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals(1L, scalarLong("SELECT count(*) FROM artifact"));
        assertEquals("old-binding", scalarString(
                "SELECT storage_binding_id FROM artifact WHERE artifact_id = 'old-artifact'"));
        assertEquals(null, scalarString("SELECT to_regclass('finalization_execution_fact')::text"));
    }

    @Test
    void nullableUniqueMeetingIdentityCannotReplaceTheV001PrimaryKey() throws Exception {
        recreateThroughV010();
        long originalArtifactOid = scalarLong("SELECT 'artifact'::regclass::oid");
        executeUpdate("""
                ALTER TABLE meeting DROP CONSTRAINT meeting_pkey CASCADE;
                ALTER TABLE meeting ALTER COLUMN meeting_id DROP NOT NULL;
                ALTER TABLE meeting ADD CONSTRAINT meeting_id_uq UNIQUE (meeting_id);
                ALTER TABLE artifact
                    ADD CONSTRAINT artifact_meeting_id_fkey
                    FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                """);

        assertMigrationRejected();

        assertEquals(originalArtifactOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals("text|false", scalarString("""
                SELECT format_type(atttypid, atttypmod) || '|' || attnotnull
                  FROM pg_attribute
                 WHERE attrelid = 'meeting'::regclass AND attname = 'meeting_id'
                """));
        assertEquals(0L, scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'meeting'::regclass AND contype = 'p'
                """));
    }

    @Test
    void migrationLocksBeforeEmptyCheckAndNeverDropsAConcurrentCommittedV001Row()
            throws Exception {
        recreateThroughV010();
        createMeeting("meeting-racing-row");
        long originalArtifactOid = scalarLong("SELECT 'artifact'::regclass::oid");

        try (Connection writer = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement("""
                    INSERT INTO artifact (
                        artifact_id, meeting_id, artifact_type, revision,
                        content_type, storage_binding_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                insert.setString(1, "old-racing-row");
                insert.setString(2, "meeting-racing-row");
                insert.setString(3, "TRANSCRIPT");
                insert.setInt(4, 1);
                insert.setString(5, "application/json");
                insert.setString(6, "old-racing-binding");
                assertEquals(1, insert.executeUpdate());
            }

            long migrationPid = connectionPid(migration);
            CompletableFuture<SQLException> outcome = CompletableFuture.supplyAsync(() -> {
                try (Statement statement = migration.createStatement()) {
                    statement.execute(readMigration(
                            "V011__finalization_execution_artifact_ready.sql"));
                    return null;
                } catch (SQLException failure) {
                    return failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot read V011 migration", failure);
                }
            });
            try {
                awaitBackendWaitingForLock(migrationPid, outcome);
                writer.commit();
                SQLException failure = outcome.get(10, TimeUnit.SECONDS);
                assertTrue(failure != null, "committed V001 row must reject migration");
                assertFalse(failure.getSQLState() == null || failure.getSQLState().isBlank());
            } finally {
                outcome.cancel(true);
            }
        }

        assertEquals(originalArtifactOid, scalarLong("SELECT 'artifact'::regclass::oid"));
        assertEquals(1L, scalarLong(
                "SELECT count(*) FROM artifact WHERE artifact_id = 'old-racing-row'"));
        assertEquals(null, scalarString("SELECT to_regclass('finalization_execution_fact')::text"));
    }

    @Test
    void concurrentV011ExecutionsSerializeAndConvergeOnTheSameExactPair() throws Exception {
        recreateThroughV010();
        CountDownLatch start = new CountDownLatch(1);
        try (Connection firstConnection = dataSource.getConnection();
             Connection secondConnection = dataSource.getConnection()) {
            CompletableFuture<SQLException> first = CompletableFuture.supplyAsync(
                    () -> executeMigrationAfter(start, firstConnection));
            CompletableFuture<SQLException> second = CompletableFuture.supplyAsync(
                    () -> executeMigrationAfter(start, secondConnection));
            start.countDown();
            try {
                assertEquals(null, first.get(10, TimeUnit.SECONDS));
                assertEquals(null, second.get(10, TimeUnit.SECONDS));
            } finally {
                first.cancel(true);
                second.cancel(true);
            }
        }

        assertEquals(4L, constraintCount("finalization_execution_fact"));
        assertEquals(10L, constraintCount("artifact"));
        assertEquals(0L, scalarLong("SELECT count(*) FROM finalization_execution_fact"));
        assertEquals(0L, scalarLong("SELECT count(*) FROM artifact"));
    }

    @Test
    void halfV011WrongColumnWeakCheckWrongForeignKeyAndViewAllFailClosed()
            throws Exception {
        recreateThroughV010();
        executeUpdate("DROP TABLE artifact");
        executeUpdate("""
                CREATE TABLE artifact (
                    artifact_id TEXT NOT NULL PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    artifact_type TEXT NOT NULL,
                    media_type TEXT NOT NULL,
                    language TEXT,
                    revision INTEGER NOT NULL,
                    intent_id TEXT NOT NULL,
                    storage_binding TEXT NOT NULL,
                    content_size BIGINT NOT NULL,
                    content_checksum TEXT NOT NULL,
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        assertMigrationRejected();
        assertEquals(1L, constraintCount("artifact"));

        recreateThroughV010();
        executeUpdate("ALTER TABLE artifact ALTER COLUMN content_type TYPE VARCHAR(255)");
        assertMigrationRejected();
        assertEquals("character varying(255)", scalarString("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                 WHERE attrelid = 'artifact'::regclass AND attname = 'content_type'
                """));

        recreateThroughV010();
        executeUpdate("""
                ALTER TABLE artifact
                    DROP CONSTRAINT artifact_revision_check,
                    ADD CONSTRAINT artifact_revision_check CHECK (true)
                """);
        assertMigrationRejected();
        assertEquals("CHECK (true)", scalarString("""
                SELECT pg_get_constraintdef(oid, TRUE) FROM pg_constraint
                 WHERE conrelid = 'artifact'::regclass
                   AND conname = 'artifact_revision_check'
                """));

        recreateThroughV010();
        executeUpdate("""
                ALTER TABLE artifact
                    DROP CONSTRAINT artifact_meeting_id_fkey,
                    ADD CONSTRAINT artifact_meeting_id_fkey
                        FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                        ON DELETE CASCADE
                """);
        assertMigrationRejected();
        assertEquals("c", scalarString("""
                SELECT confdeltype FROM pg_constraint
                 WHERE conrelid = 'artifact'::regclass
                   AND conname = 'artifact_meeting_id_fkey'
                """));

        recreateThroughV010();
        executeUpdate("DROP TABLE artifact");
        executeUpdate("CREATE VIEW artifact AS SELECT meeting_id AS artifact_id FROM meeting");
        assertMigrationRejected();
        assertEquals("v", scalarString("""
                SELECT relkind FROM pg_class
                 WHERE oid = 'artifact'::regclass
                """));
    }

    @Test
    void executionFactAcceptsCanonicalDocumentAndRejectsShapePrecisionMirrorAndHash()
            throws Exception {
        String meetingId = "meeting-execution";
        createMeeting(meetingId);
        String valid = canonicalExecution(meetingId);
        insertExecution(meetingId, valid, valid, sha256(valid));
        assertEquals(valid, scalarString("""
                SELECT canonical_json FROM finalization_execution_fact
                 WHERE meeting_id = 'meeting-execution'
                """));
        assertConstraintViolation(() -> executeUpdate(
                "DELETE FROM meeting WHERE meeting_id = 'meeting-execution'"));
        executeUpdate("DELETE FROM finalization_execution_fact");

        assertInvalidExecutionDocument(meetingId,
                valid.replace("}", ",\"extra\":true}"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace("finalization-execution-fact/v1", "v0"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace(meetingId, "other-meeting"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace(".123456Z", ".1234567Z"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace("\"title\":null", "\"title\":7"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace("\"productProfile\":{}", "\"productProfile\":[]"));
        assertInvalidExecutionDocument(meetingId,
                valid.replace("\"capabilityPlan\":{}", "\"capabilityPlan\":[]"));
        assertExecutionRejected(meetingId, valid,
                valid.replace("\"title\":null", "\"title\":\"different\""), sha256(valid));
        assertExecutionRejected(meetingId, valid, valid, new byte[32]);
        assertExecutionRejected(meetingId, valid, valid, new byte[31]);
        assertExecutionRejected(meetingId, "not-json", valid, sha256("not-json"));
        assertEquals(0L, scalarLong("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void readyArtifactEnforcesChecksPrimaryUniqueForeignKeyAndRestrict() throws Exception {
        createMeeting("meeting-artifact");
        insertArtifact("artifact-1", "meeting-artifact", "TRANSCRIPT", "application/json",
                null, 1, "intent-1", "binding-1", 0, validChecksum('a'));
        insertArtifact("artifact-2", "meeting-artifact", "TRANSCRIPT", "application/json",
                "zh-CN", 2, "intent-2", "binding-2", 42, validChecksum('b'));
        assertEquals(2L, scalarLong("SELECT count(*) FROM artifact"));

        assertArtifactRejected("artifact-1", "meeting-artifact", "SUMMARY", 1,
                "intent-pk", "binding-pk", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-revision", "meeting-artifact", "TRANSCRIPT", 1,
                "intent-revision", "binding-revision", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-intent", "meeting-artifact", "SUMMARY", 1,
                "intent-1", "binding-intent", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-binding", "meeting-artifact", "SUMMARY", 1,
                "intent-binding", "binding-1", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-fk", "missing-meeting", "SUMMARY", 1,
                "intent-fk", "binding-fk", 1, validChecksum('c'), "en-US");
        assertArtifactRejected(" ", "meeting-artifact", "SUMMARY", 1,
                "intent-blank", "binding-blank", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-language", "meeting-artifact", "SUMMARY", 1,
                "intent-language", "binding-language", 1, validChecksum('c'), "   ");
        assertArtifactRejected("artifact-revision-zero", "meeting-artifact", "SUMMARY", 0,
                "intent-zero", "binding-zero", 1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-size", "meeting-artifact", "SUMMARY", 1,
                "intent-size", "binding-size", -1, validChecksum('c'), "en-US");
        assertArtifactRejected("artifact-checksum", "meeting-artifact", "SUMMARY", 1,
                "intent-checksum", "binding-checksum", 1,
                "sha256:" + "A".repeat(64), "en-US");
        assertArtifactRejected("x".repeat(4097), "meeting-artifact", "SUMMARY", 1,
                "intent-long", "binding-long", 1, validChecksum('c'), "en-US");
        assertConstraintViolation(() -> executeUpdate(
                "DELETE FROM meeting WHERE meeting_id = 'meeting-artifact'"));
    }

    @Test
    void readyArtifactUsesTheExactJava21BlankCharacterSet() throws Exception {
        createMeeting("meeting-java-blank");
        int sequence = 0;
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (!Character.isWhitespace(codePoint)) {
                continue;
            }
            String blank = new String(Character.toChars(codePoint));
            int whitespaceCodePoint = codePoint;
            int current = ++sequence;
            assertThrows(SQLException.class, () -> insertArtifact(
                    blank, "meeting-java-blank", "SUMMARY", "application/json", "zh-CN",
                    current, "intent-blank-" + current, "binding-blank-" + current,
                    1, validChecksum('c')),
                    () -> "Java whitespace must be rejected: U+"
                            + Integer.toHexString(whitespaceCodePoint));
        }

        assertThrows(SQLException.class, () -> insertArtifact(
                "artifact-tab", "meeting-java-blank", "\t", "application/json", "zh-CN",
                100, "intent-tab", "binding-tab", 1, validChecksum('d')));
        assertThrows(SQLException.class, () -> insertArtifact(
                "artifact-lf", "meeting-java-blank", "SUMMARY", "\n", "zh-CN",
                101, "intent-lf", "binding-lf", 1, validChecksum('e')));
        assertThrows(SQLException.class, () -> insertArtifact(
                "artifact-language", "meeting-java-blank", "SUMMARY", "application/json", "\r",
                102, "intent-language", "binding-language", 1, validChecksum('f')));
        assertThrows(SQLException.class, () -> insertArtifact(
                "artifact-intent", "meeting-java-blank", "SUMMARY", "application/json", "zh-CN",
                103, "\u1680", "binding-intent", 1, validChecksum('a')));
        assertThrows(SQLException.class, () -> insertArtifact(
                "artifact-binding", "meeting-java-blank", "SUMMARY", "application/json", "zh-CN",
                104, "intent-binding", "\u3000", 1, validChecksum('b')));

        assertFalse("\u00a0".isBlank(), "NBSP is not Java Character.isWhitespace");
        insertArtifact("artifact-nbsp", "meeting-java-blank", "\u00a0", "application/json",
                "zh-CN", 105, "intent-nbsp", "binding-nbsp", 1, validChecksum('c'));
    }

    @Test
    void rerunRejectsUnloggedPartitionedOrInheritedFactTables() throws Exception {
        executeUpdate("ALTER TABLE artifact SET UNLOGGED");
        assertV011RerunRejected();

        recreateThroughV010();
        applyMigration("V011__finalization_execution_artifact_ready.sql");
        executeUpdate("""
                CREATE TABLE artifact_partition_parent
                    (LIKE artifact INCLUDING DEFAULTS)
                    PARTITION BY HASH (artifact_id);
                ALTER TABLE artifact_partition_parent
                    ATTACH PARTITION artifact
                    FOR VALUES WITH (MODULUS 1, REMAINDER 0)
                """);
        assertEquals("r|true", scalarString("""
                SELECT relkind::text || '|' || relispartition
                  FROM pg_class WHERE oid = 'artifact'::regclass
                """));
        assertV011RerunRejected();

        recreateThroughV010();
        applyMigration("V011__finalization_execution_artifact_ready.sql");
        executeUpdate("CREATE TABLE artifact_shadow () INHERITS (artifact)");
        assertV011RerunRejected();
    }

    private void assertExecutionRejected(
            String meetingId,
            String canonical,
            String json,
            byte[] hash) {
        assertThrows(SQLException.class,
                () -> insertExecution(meetingId, canonical, json, hash));
    }

    private void assertInvalidExecutionDocument(String meetingId, String invalid) {
        assertExecutionRejected(meetingId, invalid, invalid, sha256(invalid));
    }

    private void assertArtifactRejected(
            String artifactId,
            String meetingId,
            String artifactType,
            int revision,
            String intentId,
            String binding,
            long size,
            String checksum,
            String language) {
        assertThrows(SQLException.class, () -> insertArtifact(
                artifactId, meetingId, artifactType, "application/json", language,
                revision, intentId, binding, size, checksum));
    }

    private void assertMigrationRejected() {
        SQLException failure = assertThrows(SQLException.class,
                () -> applyMigration("V011__finalization_execution_artifact_ready.sql"));
        assertFalse(failure.getSQLState() == null || failure.getSQLState().isBlank());
        assertEquals(null, scalarStringUnchecked(
                "SELECT to_regclass('finalization_execution_fact')::text"),
                "failed V011 must roll back execution-fact DDL too");
    }

    private void assertV011RerunRejected() {
        SQLException failure = assertThrows(SQLException.class,
                () -> applyMigration("V011__finalization_execution_artifact_ready.sql"));
        assertFalse(failure.getSQLState() == null || failure.getSQLState().isBlank());
    }

    private long connectionPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    /**
     * 等待迁移会话确实阻塞在顶层表锁，而不是依赖线程调度时序。
     * 如果迁移在 Writer 提交前就结束，用例立即失败，避免偶然的“绿色”竞态测试。
     */
    private void awaitBackendWaitingForLock(
            long backendPid,
            CompletableFuture<SQLException> outcome) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (outcome.isDone()) {
                throw new AssertionError("migration completed before waiting for artifact lock");
            }
            try (Connection connection = adminDataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT wait_event_type
                           FROM pg_stat_activity
                          WHERE pid = ?
                         """)) {
                statement.setLong(1, backendPid);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next() && "Lock".equals(row.getString(1))) {
                        return;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("migration did not wait for artifact lock within 2 seconds");
    }

    private SQLException executeMigrationAfter(CountDownLatch start, Connection connection) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent migration start gate timed out");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(readMigration(
                        "V011__finalization_execution_artifact_ready.sql"));
                return null;
            }
        } catch (SQLException failure) {
            return failure;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot execute concurrent V011 migration", failure);
        }
    }

    private void assertConstraintViolation(SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertTrue(failure.getSQLState().startsWith("23"), failure.getSQLState());
    }

    private void insertExecution(
            String meetingId,
            String canonical,
            String json,
            byte[] hash) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insertExecution(connection, meetingId, canonical, json, hash);
        }
    }

    private void insertExecution(
            Connection connection,
            String meetingId,
            String canonical,
            String json,
            byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO finalization_execution_fact (
                    meeting_id, canonical_json, execution_fact_json, content_sha256)
                VALUES (?, ?, ?::jsonb, ?)
                """)) {
            statement.setString(1, meetingId);
            statement.setString(2, canonical);
            statement.setString(3, json);
            statement.setBytes(4, hash);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertArtifact(
            String artifactId,
            String meetingId,
            String artifactType,
            String mediaType,
            String language,
            int revision,
            String intentId,
            String binding,
            long size,
            String checksum) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insertArtifact(connection, artifactId, meetingId, artifactType, mediaType,
                    language, revision, intentId, binding, size, checksum);
        }
    }

    private void insertArtifact(
            Connection connection,
            String artifactId,
            String meetingId,
            String artifactType,
            String mediaType,
            String language,
            int revision,
            String intentId,
            String binding,
            long size,
            String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact (
                    artifact_id, meeting_id, artifact_type, media_type, language,
                    revision, intent_id, storage_binding, content_size, content_checksum)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, artifactId);
            statement.setString(2, meetingId);
            statement.setString(3, artifactType);
            statement.setString(4, mediaType);
            statement.setString(5, language);
            statement.setInt(6, revision);
            statement.setString(7, intentId);
            statement.setString(8, binding);
            statement.setLong(9, size);
            statement.setString(10, checksum);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void createMeeting(String meetingId) {
        Instant now = Instant.parse("2026-09-28T08:00:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId, "v011-test-deployment", "session-" + meetingId,
                        "profile", 1, MeetingState.CREATED, now, now));
    }

    private static String canonicalExecution(String meetingId) {
        return "{\"specVersion\":\"finalization-execution-fact/v1\","
                + "\"meetingId\":\"" + meetingId + "\","
                + "\"startedAt\":\"2026-09-28T03:02:01.123456Z\","
                + "\"title\":null,\"productProfile\":{},\"capabilityPlan\":{}}";
    }

    private static String validChecksum(char digit) {
        return "sha256:" + Character.toString(digit).repeat(64);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private void recreateThroughV010() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + quoteIdentifier(schema) + " CASCADE");
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigrations(10);
    }

    private void applyMigrations(int count) throws Exception {
        for (String migration : MIGRATIONS_THROUGH_V011.subList(0, count)) {
            applyMigration(migration);
        }
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private List<String> columnCatalog(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT attname || '|' || format_type(atttypid, atttypmod)
                            || '|' || attnotnull || '|'
                            || coalesce(pg_get_expr(d.adbin, d.adrelid), 'null')
                       FROM pg_attribute a
                       LEFT JOIN pg_attrdef d
                         ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                      WHERE a.attrelid = to_regclass(?)
                        AND a.attnum > 0 AND NOT a.attisdropped
                      ORDER BY a.attnum
                     """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                var values = new java.util.ArrayList<String>();
                while (rows.next()) values.add(rows.getString(1));
                return values;
            }
        }
    }

    private long constraintCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_constraint WHERE conrelid = '"
                + table + "'::regclass");
    }

    private long validatedCheckCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_constraint WHERE conrelid = '"
                + table + "'::regclass AND contype = 'c' AND convalidated");
    }

    private long indexCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND tablename = '" + table + "'");
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String scalarString(String sql) throws Exception {
        return scalarStringUnchecked(sql);
    }

    private String scalarStringUnchecked(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("catalog query failed", failure);
        }
    }

    private byte[] queryBytes(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getBytes(1);
        }
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void createSchema() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        String url = requiredEnvironment("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "V011 tests refuse the shared 127.0.0.1:55432 PostgreSQL resource");
        }
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(url);
        postgres.setUser(requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        postgres.setPassword(requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) postgres.setCurrentSchema(currentSchema);
        return postgres;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }

    private static String readMigration(String filename) throws Exception {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
