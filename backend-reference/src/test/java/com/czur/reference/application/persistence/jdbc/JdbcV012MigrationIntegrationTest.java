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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * V012 删除计划、逐项审计和 Meeting 删除时间约束的 PostgreSQL 16 门禁。
 *
 * <p>每个用例使用随机 schema。测试覆盖 V001→V012、精确原样重跑、并发迁移、
 * TEMP 遮蔽、首次旧删除事实、半对象、弱约束、错误 FK、非法行和约束行为。本类不
 * 修改 runtime readiness，也不实现删除 Store、受理事务或 Worker。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV012MigrationIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
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
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql");
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-29T08:09:10.123456Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshV012Schema() throws Exception {
        adminDataSource = postgres(null);
        schema = "v012_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigrations(12);
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
    void freshV001ThroughV012HasExactColumnsConstraintsIndexesAndForeignKeys()
            throws Exception {
        assertEquals(List.of(
                "meeting_id|text|true|null||",
                "accepted_at|timestamp with time zone|true|null||",
                "source_manifest_revision|integer|false|null||",
                "source_workflow_step_type|text|false|null||",
                "source_workflow_step_key|text|false|null||",
                "source_workflow_checkpoint_type|text|false|null||",
                "source_workflow_content_digest|text|false|null||",
                "target_count|integer|true|null||",
                "canonical_json|text|true|null||",
                "deletion_plan_json|jsonb|true|null||",
                "content_sha256|bytea|true|null||",
                "status|text|true|null||",
                "completed_at|timestamp with time zone|false|null||"),
                columnCatalog("meeting_deletion_plan"));
        assertEquals(List.of(
                "meeting_id|text|true|null||",
                "ordinal|integer|true|null||",
                "artifact_id|text|true|null||",
                "artifact_type|text|true|null||",
                "revision|integer|true|null||",
                "status|text|true|null||",
                "storage_binding|text|false|null||",
                "deleted_at|timestamp with time zone|false|null||"),
                columnCatalog("artifact_deletion_item"));

        assertEquals(9L, constraintCount("meeting_deletion_plan"));
        assertEquals(8L, constraintCount("artifact_deletion_item"));
        assertEquals(5L, checkCount("meeting_deletion_plan"));
        assertEquals(4L, checkCount("artifact_deletion_item"));
        assertEquals(1L, indexCount("meeting_deletion_plan"));
        assertEquals(3L, indexCount("artifact_deletion_item"));
        assertEquals(4L, scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN (
                    'meeting_deletion_plan_meeting_fk',
                    'meeting_deletion_plan_manifest_fk',
                    'meeting_deletion_plan_workflow_fk',
                    'artifact_deletion_item_plan_fk')
                   AND conrelid IN (
                       'meeting_deletion_plan'::regclass,
                       'artifact_deletion_item'::regclass)
                   AND contype='f' AND convalidated
                   AND NOT condeferrable AND NOT condeferred
                   AND confdeltype='r'
                """));
        assertEquals(0L, scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid='artifact_deletion_item'::regclass
                   AND confrelid='artifact'::regclass
                """));
    }

    @Test
    void exactRerunPreservesOidsLegalRowsAndCanonicalBytes() throws Exception {
        String meetingId = "meeting-rerun";
        createMeeting(meetingId);
        String canonical = canonicalPlan(meetingId, 1, null);
        insertPlan(meetingId, 1, null, canonical, canonical, sha256(canonical),
                "PENDING", null);
        insertItem(meetingId, 0, "artifact-rerun", "transcript.final", 1,
                "PENDING", null, null);
        executeUpdate("""
                UPDATE meeting_deletion_plan
                   SET status='COMPLETED',
                       completed_at='2026-09-29T08:09:11.123456Z'
                 WHERE meeting_id='meeting-rerun'
                """);
        long planOid = relationOid("meeting_deletion_plan");
        long itemOid = relationOid("artifact_deletion_item");

        applyMigration("V012__artifact_deletion_plan.sql");

        assertEquals(planOid, relationOid("meeting_deletion_plan"));
        assertEquals(itemOid, relationOid("artifact_deletion_item"));
        assertEquals(canonical, scalarString(
                "SELECT canonical_json FROM meeting_deletion_plan"));
        assertArrayEquals(sha256(canonical), queryBytes(
                "SELECT content_sha256 FROM meeting_deletion_plan"));
        assertEquals("COMPLETED", scalarString(
                "SELECT status FROM meeting_deletion_plan"));
        assertEquals(1L, scalarLong("SELECT count(*) FROM artifact_deletion_item"));
    }

    @Test
    void concurrentFirstExecutionsSerializeAndConvergeOnOneExactSchema() throws Exception {
        recreateThroughV011();
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
        assertEquals(9L, constraintCount("meeting_deletion_plan"));
        assertEquals(8L, constraintCount("artifact_deletion_item"));
    }

    @Test
    void exactRerunPinsDeploymentSchemaAheadOfTemporaryShadows() throws Exception {
        long planOid = relationOid("meeting_deletion_plan");
        long itemOid = relationOid("artifact_deletion_item");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE meeting (marker TEXT);
                    CREATE TEMP TABLE workflow_step (marker TEXT);
                    CREATE TEMP TABLE meeting_manifest_revision (marker TEXT);
                    CREATE TEMP TABLE meeting_deletion_plan (marker TEXT);
                    CREATE TEMP TABLE artifact_deletion_item (marker TEXT);
                    INSERT INTO meeting_deletion_plan(marker) VALUES ('shadow')
                    """);
            statement.execute(readMigration("V012__artifact_deletion_plan.sql"));
            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM pg_temp.meeting_deletion_plan")) {
                assertTrue(row.next());
                assertEquals(1L, row.getLong(1));
            }
        }
        assertEquals(planOid, relationOid("meeting_deletion_plan"));
        assertEquals(itemOid, relationOid("artifact_deletion_item"));
    }

    @Test
    void firstExecutionRejectsEveryUnprovenLegacyDeletionFactWithoutDdl()
            throws Exception {
        for (String mutation : List.of(
                "UPDATE meeting SET state='DELETION_PENDING'",
                "UPDATE meeting SET manifest_state='DELETION_PENDING'",
                "UPDATE meeting SET state='DELETED', manifest_state='DELETED', "
                        + "deleted_at=updated_at",
                "UPDATE meeting SET deleted_at=updated_at")) {
            recreateThroughV011();
            createMeeting("meeting-legacy");
            executeUpdate(mutation);

            SQLException failure = assertMigrationRejected("P0001");
            assertTrue(failure.getMessage().contains("旧删除状态"));
            assertEquals(null, scalarString(
                    "SELECT to_regclass('meeting_deletion_plan')::text"));
            assertEquals(null, scalarString(
                    "SELECT to_regclass('artifact_deletion_item')::text"));
            assertEquals(0L, scalarLong("""
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid='meeting'::regclass
                       AND conname='meeting_deleted_at_equivalence_ck'
                    """));
        }
    }

    @Test
    void halfObjectsWeakChecksWrongForeignKeysAndExtraIndexesFailClosed()
            throws Exception {
        recreateThroughV011();
        executeUpdate("CREATE TABLE meeting_deletion_plan (marker TEXT)");
        long halfOid = relationOid("meeting_deletion_plan");
        assertMigrationRejected("P0001");
        assertEquals(halfOid, relationOid("meeting_deletion_plan"));
        assertEquals(null, scalarString(
                "SELECT to_regclass('artifact_deletion_item')::text"));

        recreateThroughV011();
        executeUpdate("""
                ALTER TABLE meeting ADD CONSTRAINT meeting_deleted_at_equivalence_ck
                    CHECK (true)
                """);
        assertMigrationRejected("P0001");
        assertEquals("CHECK (true)", scalarString("""
                SELECT pg_get_constraintdef(oid, TRUE) FROM pg_constraint
                 WHERE conrelid='meeting'::regclass
                   AND conname='meeting_deleted_at_equivalence_ck'
                """));

        recreateThroughV011();
        applyMigration("V012__artifact_deletion_plan.sql");
        long exactPlanOid = relationOid("meeting_deletion_plan");
        executeUpdate("""
                ALTER TABLE meeting_deletion_plan
                    DROP CONSTRAINT meeting_deletion_plan_target_count_ck,
                    ADD CONSTRAINT meeting_deletion_plan_target_count_ck
                        CHECK (target_count >= 0)
                """);
        assertMigrationRejected("P0001");
        assertEquals(exactPlanOid, relationOid("meeting_deletion_plan"));
        assertEquals("CHECK (target_count >= 0)", scalarString("""
                SELECT pg_get_constraintdef(oid, TRUE) FROM pg_constraint
                 WHERE conrelid='meeting_deletion_plan'::regclass
                   AND conname='meeting_deletion_plan_target_count_ck'
                """));

        recreateThroughV011();
        applyMigration("V012__artifact_deletion_plan.sql");
        executeUpdate("""
                ALTER TABLE meeting_deletion_plan
                    DROP CONSTRAINT meeting_deletion_plan_meeting_fk,
                    ADD CONSTRAINT meeting_deletion_plan_meeting_fk
                        FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                        ON DELETE CASCADE
                """);
        assertMigrationRejected("P0001");
        assertEquals("c", scalarString("""
                SELECT confdeltype FROM pg_constraint
                 WHERE conrelid='meeting_deletion_plan'::regclass
                   AND conname='meeting_deletion_plan_meeting_fk'
                """));

        recreateThroughV011();
        applyMigration("V012__artifact_deletion_plan.sql");
        executeUpdate("CREATE INDEX unexpected_item_idx ON artifact_deletion_item(status)");
        assertMigrationRejected("P0001");
        assertEquals(4L, indexCount("artifact_deletion_item"));
    }

    @Test
    void planDocumentHashSourceTargetAndStatusConstraintsRejectInvalidRows()
            throws Exception {
        createMeeting("meeting-valid-plan");
        String valid = canonicalPlan("meeting-valid-plan", 0, null);
        insertPlan("meeting-valid-plan", 0, null, valid, valid, sha256(valid),
                "PENDING", null);

        createMeeting("meeting-mirror");
        String mirror = canonicalPlan("meeting-mirror", 0, null);
        assertSqlState("23514", () -> insertPlan(
                "meeting-mirror", 0, null, mirror,
                mirror.replace("meeting-mirror", "another-meeting"),
                sha256(mirror), "PENDING", null));

        createMeeting("meeting-hash");
        String hash = canonicalPlan("meeting-hash", 0, null);
        assertSqlState("23514", () -> insertPlan(
                "meeting-hash", 0, null, hash, hash, new byte[32],
                "PENDING", null));

        createMeeting("meeting-count");
        String count = canonicalPlan("meeting-count", 0, null);
        assertSqlState("23514", () -> insertPlan(
                "meeting-count", 257, null, count, count, sha256(count),
                "PENDING", null));

        createMeeting("meeting-source-partial");
        String source = canonicalPlan("meeting-source-partial", 0, null);
        assertSqlState("23514", () -> executeUpdate("""
                INSERT INTO meeting_deletion_plan (
                    meeting_id, accepted_at, source_workflow_step_type,
                    source_workflow_step_key,
                    target_count, canonical_json, deletion_plan_json,
                    content_sha256, status)
                VALUES ('meeting-source-partial',
                    '2026-09-29T08:09:10.123456Z', 'mock-finalization',
                    'mock-finalize-v1-partial', 0,
                    '%s', '%s'::jsonb, decode('%s','hex'), 'PENDING')
                """.formatted(source, source, hex(sha256(source)))));

        createMeeting("meeting-manifest-fk");
        String manifest = canonicalPlan("meeting-manifest-fk", 0, 7);
        assertSqlState("23503", () -> insertPlan(
                "meeting-manifest-fk", 0, 7, manifest, manifest,
                sha256(manifest), "PENDING", null));

        createMeeting("meeting-status");
        String status = canonicalPlan("meeting-status", 0, null);
        assertSqlState("23514", () -> insertPlan(
                "meeting-status", 0, null, status, status, sha256(status),
                "COMPLETED", null));

        createMeeting("meeting-pending-time");
        String pendingTime = canonicalPlan("meeting-pending-time", 0, null);
        assertSqlState("23514", () -> insertPlan(
                "meeting-pending-time", 0, null, pendingTime, pendingTime,
                sha256(pendingTime), "PENDING", ACCEPTED_AT.plusSeconds(1)));
    }

    @Test
    void sourceWorkflowColumnsAndJsonMembersRejectEveryUnknownProducingCombination()
            throws Exception {
        String digest = "sha256:" + "d".repeat(64);
        for (int mask = 0; mask < 15; mask++) {
            String meetingId = "meeting-source-mask-" + mask;
            createMeeting(meetingId);
            String canonical = canonicalPlanWithWorkflow(
                    meetingId, null, "null");
            String stepType = (mask & 1) == 0 ? null : "mock-finalization";
            String stepKey = (mask & 2) == 0 ? null : "source-step-" + mask;
            String checkpoint = (mask & 4) == 0
                    ? null : "mock-finalization-checkpoint/v1";
            String contentDigest = (mask & 8) == 0 ? null : digest;
            if (mask == 0) {
                insertPlanWithSources(
                        meetingId, null, 0, stepType, stepKey, checkpoint, contentDigest,
                        canonical, canonical, sha256(canonical), "PENDING", null);
            } else {
                assertSqlState("23514", () -> insertPlanWithSources(
                        meetingId, null, 0, stepType, stepKey, checkpoint, contentDigest,
                        canonical, canonical, sha256(canonical), "PENDING", null));
            }
        }

        String completeMeeting = "meeting-source-mask-15";
        String completeKey = "source-step-15";
        createMeeting(completeMeeting);
        insertSourceWorkflow(completeMeeting, completeKey, digest);
        String completeSource = sourceWorkflowJson(
                completeMeeting, completeKey, digest, null);
        String completeCanonical = canonicalPlanWithWorkflow(
                completeMeeting, null, completeSource);
        insertPlanWithSources(
                completeMeeting, null, 0, "mock-finalization", completeKey,
                "mock-finalization-checkpoint/v1", digest,
                completeCanonical, completeCanonical, sha256(completeCanonical),
                "PENDING", null);

        for (String nullField : List.of(
                "meetingId", "stepType", "stepKey", "checkpointType", "contentDigest")) {
            String meetingId = "meeting-source-json-null-" + nullField;
            String stepKey = "source-json-step-" + nullField;
            createMeeting(meetingId);
            insertSourceWorkflow(meetingId, stepKey, digest);
            String invalidSource = sourceWorkflowJson(
                    meetingId, stepKey, digest, nullField);
            String invalidCanonical = canonicalPlanWithWorkflow(
                    meetingId, null, invalidSource);
            assertSqlState("23514", () -> insertPlanWithSources(
                    meetingId, null, 0, "mock-finalization", stepKey,
                    "mock-finalization-checkpoint/v1", digest,
                    invalidCanonical, invalidCanonical, sha256(invalidCanonical),
                    "PENDING", null));
        }
    }

    @Test
    void sourceManifestRevisionNullAndJsonNullUseAnExplicitThreeValueMatrix()
            throws Exception {
        String nullNullMeeting = "meeting-manifest-null-null";
        createMeeting(nullNullMeeting);
        String nullNull = canonicalPlan(nullNullMeeting, 0, null);
        insertPlan(nullNullMeeting, 0, null, nullNull, nullNull, sha256(nullNull),
                "PENDING", null);

        String nullNumberMeeting = "meeting-manifest-null-number";
        createMeeting(nullNumberMeeting);
        String nullNumber = canonicalPlan(nullNumberMeeting, 0, 1);
        assertSqlState("23514", () -> insertPlan(
                nullNumberMeeting, 0, null, nullNumber, nullNumber,
                sha256(nullNumber), "PENDING", null));

        String numberNullMeeting = "meeting-manifest-number-null";
        createMeeting(numberNullMeeting);
        String numberNull = canonicalPlan(numberNullMeeting, 0, null);
        assertSqlState("23514", () -> insertPlan(
                numberNullMeeting, 0, 1, numberNull, numberNull,
                sha256(numberNull), "PENDING", null));

        String mismatchMeeting = "meeting-manifest-number-mismatch";
        createMeeting(mismatchMeeting);
        String mismatch = canonicalPlan(mismatchMeeting, 0, 2);
        assertSqlState("23514", () -> insertPlan(
                mismatchMeeting, 0, 1, mismatch, mismatch,
                sha256(mismatch), "PENDING", null));

        String matchingMeeting = "meeting-manifest-number-match";
        createMeeting(matchingMeeting);
        insertSourceManifest(matchingMeeting, 1);
        String matching = canonicalPlan(matchingMeeting, 0, 1);
        insertPlan(matchingMeeting, 0, 1, matching, matching, sha256(matching),
                "PENDING", null);
    }

    @Test
    void itemEnforcesOrdinalTextRevisionUniquenessAndStatusBindingTime()
            throws Exception {
        String meetingId = "meeting-items";
        createMeeting(meetingId);
        String canonical = canonicalPlan(meetingId, 2, null);
        insertPlan(meetingId, 2, null, canonical, canonical, sha256(canonical),
                "PENDING", null);
        insertItem(meetingId, 0, "artifact-a", "transcript.final", 1,
                "PENDING", null, null);
        insertItem(meetingId, 1, "artifact-b", "summary.structured", 1,
                "DELETED", "binding-b", ACCEPTED_AT.plusSeconds(1));

        assertSqlState("23505", () -> insertItem(
                meetingId, 0, "artifact-c", "outline", 1,
                "PENDING", null, null));
        assertSqlState("23505", () -> insertItem(
                meetingId, 2, "artifact-a", "outline", 1,
                "PENDING", null, null));
        assertSqlState("23505", () -> insertItem(
                meetingId, 2, "artifact-c", "transcript.final", 1,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, -1, "artifact-negative", "outline", 1,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 256, "artifact-too-late", "outline", 1,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "\u3000", "outline", 1,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "x".repeat(4097), "outline", 1,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-zero", "outline", 0,
                "PENDING", null, null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-binding", "outline", 1,
                "DELETED", " ", ACCEPTED_AT));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-pending-binding", "outline", 1,
                "PENDING", "binding", null));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-pending-time", "outline", 1,
                "PENDING", null, ACCEPTED_AT));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-pending-both", "outline", 1,
                "PENDING", "binding", ACCEPTED_AT));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-deleted-no-binding", "outline", 1,
                "DELETED", null, ACCEPTED_AT));
        assertSqlState("23514", () -> insertItem(
                meetingId, 2, "artifact-time", "outline", 1,
                "DELETED", "binding", null));

        // item 不引用 READY metadata；没有 artifact 行仍可保存计划并确认删除。
        assertEquals(0L, scalarLong("SELECT count(*) FROM artifact"));
        executeUpdate("""
                UPDATE artifact_deletion_item
                   SET status='DELETED', storage_binding='binding-a',
                       deleted_at='2026-09-29T08:09:11.123456Z'
                 WHERE meeting_id='meeting-items' AND ordinal=0
                """);
        assertEquals(2L, scalarLong("""
                SELECT count(*) FROM artifact_deletion_item WHERE status='DELETED'
                """));
    }

    @Test
    void javaBlankApproximationRejectsEveryJava21WhitespaceCodePoint()
            throws Exception {
        String meetingId = "meeting-blank";
        createMeeting(meetingId);
        String canonical = canonicalPlan(meetingId, 1, null);
        insertPlan(meetingId, 1, null, canonical, canonical, sha256(canonical),
                "PENDING", null);
        int sequence = 0;
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (!Character.isWhitespace(codePoint)) {
                continue;
            }
            String blank = new String(Character.toChars(codePoint));
            int ordinal = sequence++;
            assertSqlState("23514", () -> insertItem(
                    meetingId, ordinal, blank, "type-" + ordinal, 1,
                    "PENDING", null, null));
        }
        assertFalse("\u00a0".isBlank());
        insertItem(meetingId, sequence, "artifact-nbsp", "\u00a0", 1,
                "PENDING", null, null);
    }

    @Test
    void meetingDeletedStateManifestAndTimestampAreBidirectionallyBound()
            throws Exception {
        createMeeting("meeting-delete-equivalence");
        assertSqlState("23514", () -> executeUpdate("""
                UPDATE meeting SET deleted_at=updated_at
                 WHERE meeting_id='meeting-delete-equivalence'
                """));
        assertSqlState("23514", () -> executeUpdate("""
                UPDATE meeting SET state='DELETED'
                 WHERE meeting_id='meeting-delete-equivalence'
                """));
        assertSqlState("23514", () -> executeUpdate("""
                UPDATE meeting SET manifest_state='DELETED'
                 WHERE meeting_id='meeting-delete-equivalence'
                """));

        executeUpdate("""
                UPDATE meeting
                   SET state='DELETED', manifest_state='DELETED',
                       deleted_at=updated_at
                 WHERE meeting_id='meeting-delete-equivalence'
                """);
        assertEquals("DELETED|DELETED|true", scalarString("""
                SELECT state || '|' || manifest_state || '|'
                       || (deleted_at=updated_at)
                  FROM meeting WHERE meeting_id='meeting-delete-equivalence'
                """));
        assertSqlState("23514", () -> executeUpdate("""
                UPDATE meeting SET updated_at=updated_at + interval '1 second'
                 WHERE meeting_id='meeting-delete-equivalence'
                """));
        assertSqlState("23514", () -> executeUpdate("""
                UPDATE meeting SET state='DELETION_PENDING'
                 WHERE meeting_id='meeting-delete-equivalence'
                """));
    }

    @Test
    void rerunRejectsUnloggedInheritedAndPartitionedDeletionTables() throws Exception {
        executeUpdate("ALTER TABLE artifact_deletion_item SET UNLOGGED");
        assertMigrationRejected("P0001");

        recreateThroughV011();
        applyMigration("V012__artifact_deletion_plan.sql");
        executeUpdate("CREATE TABLE item_child () INHERITS (artifact_deletion_item)");
        assertMigrationRejected("P0001");

        recreateThroughV011();
        applyMigration("V012__artifact_deletion_plan.sql");
        executeUpdate("""
                ALTER TABLE artifact_deletion_item
                    DROP CONSTRAINT artifact_deletion_item_plan_fk;
                ALTER TABLE artifact_deletion_item
                    DROP CONSTRAINT artifact_deletion_item_artifact_id_uq;
                ALTER TABLE artifact_deletion_item
                    DROP CONSTRAINT artifact_deletion_item_revision_uq;
                ALTER TABLE artifact_deletion_item
                    DROP CONSTRAINT artifact_deletion_item_pkey;
                CREATE TABLE item_partition_parent
                    (LIKE artifact_deletion_item INCLUDING ALL)
                    PARTITION BY HASH (meeting_id);
                ALTER TABLE item_partition_parent
                    ATTACH PARTITION artifact_deletion_item
                    FOR VALUES WITH (MODULUS 1, REMAINDER 0)
                """);
        assertMigrationRejected("P0001");
    }

    private SQLException assertMigrationRejected(String sqlState) {
        SQLException failure = assertThrows(SQLException.class,
                () -> applyMigration("V012__artifact_deletion_plan.sql"));
        assertEquals(sqlState, failure.getSQLState());
        return failure;
    }

    private void assertSqlState(String sqlState, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(sqlState, failure.getSQLState());
    }

    private void insertPlan(
            String meetingId,
            int targetCount,
            Integer sourceManifestRevision,
            String canonical,
            String json,
            byte[] hash,
            String status,
            Instant completedAt) throws SQLException {
        insertPlanWithSources(
                meetingId, sourceManifestRevision, targetCount,
                null, null, null, null,
                canonical, json, hash, status, completedAt);
    }

    private void insertPlanWithSources(
            String meetingId,
            Integer sourceManifestRevision,
            int targetCount,
            String sourceWorkflowStepType,
            String sourceWorkflowStepKey,
            String sourceWorkflowCheckpointType,
            String sourceWorkflowContentDigest,
            String canonical,
            String json,
            byte[] hash,
            String status,
            Instant completedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_deletion_plan (
                         meeting_id, accepted_at, source_manifest_revision,
                         source_workflow_step_type, source_workflow_step_key,
                         source_workflow_checkpoint_type,
                         source_workflow_content_digest,
                         target_count, canonical_json, deletion_plan_json,
                         content_sha256, status, completed_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                     """)) {
            statement.setString(1, meetingId);
            statement.setTimestamp(2, Timestamp.from(ACCEPTED_AT));
            statement.setObject(3, sourceManifestRevision);
            statement.setString(4, sourceWorkflowStepType);
            statement.setString(5, sourceWorkflowStepKey);
            statement.setString(6, sourceWorkflowCheckpointType);
            statement.setString(7, sourceWorkflowContentDigest);
            statement.setInt(8, targetCount);
            statement.setString(9, canonical);
            statement.setString(10, json);
            statement.setBytes(11, hash);
            statement.setString(12, status);
            statement.setTimestamp(13,
                    completedAt == null ? null : Timestamp.from(completedAt));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertItem(
            String meetingId,
            int ordinal,
            String artifactId,
            String artifactType,
            int revision,
            String status,
            String storageBinding,
            Instant deletedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO artifact_deletion_item (
                         meeting_id, ordinal, artifact_id, artifact_type,
                         revision, status, storage_binding, deleted_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, meetingId);
            statement.setInt(2, ordinal);
            statement.setString(3, artifactId);
            statement.setString(4, artifactType);
            statement.setInt(5, revision);
            statement.setString(6, status);
            statement.setString(7, storageBinding);
            statement.setTimestamp(8,
                    deletedAt == null ? null : Timestamp.from(deletedAt));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void createMeeting(String meetingId) {
        Instant now = Instant.parse("2026-09-29T08:00:00Z");
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId, "v012-test-deployment", "session-" + meetingId,
                        "profile", 1, MeetingState.CREATED, now, now));
    }

    private void insertSourceWorkflow(
            String meetingId,
            String stepKey,
            String contentDigest) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO workflow_step (
                         meeting_id, step_type, step_key, status, attempt_count,
                         updated_at, checkpoint_type, canonical_payload,
                         content_digest, initial_available_at, next_attempt_at,
                         lease_version)
                     VALUES (?, 'mock-finalization', ?, 'PENDING', 0,
                         ?, 'mock-finalization-checkpoint/v1',
                         'source-checkpoint', ?, ?, ?, 0)
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, stepKey);
            statement.setTimestamp(3, Timestamp.from(ACCEPTED_AT));
            statement.setString(4, contentDigest);
            statement.setTimestamp(5, Timestamp.from(ACCEPTED_AT));
            statement.setTimestamp(6, Timestamp.from(ACCEPTED_AT));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertSourceManifest(String meetingId, int revision) throws SQLException {
        String canonical = "{\"specVersion\":\"meeting-manifest/v1\","
                + "\"meetingId\":\"" + meetingId + "\","
                + "\"manifestRevision\":" + revision + ",\"profile\":{},"
                + "\"title\":null,\"status\":\"READY\","
                + "\"startedAt\":\"2026-09-29T08:00:00Z\","
                + "\"endedAt\":\"2026-09-29T08:01:00Z\","
                + "\"durationMs\":60000,\"languages\":[],"
                + "\"artifacts\":[],\"warnings\":[]}";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_manifest_revision (
                         meeting_id, manifest_revision, previous_revision,
                         canonical_json, manifest_json, content_sha256)
                     VALUES (?, ?, NULL, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, meetingId);
            statement.setInt(2, revision);
            statement.setString(3, canonical);
            statement.setString(4, canonical);
            statement.setBytes(5, sha256(canonical));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String canonicalPlan(
            String meetingId,
            int targetCount,
            Integer sourceManifestRevision) {
        return canonicalPlanWithWorkflow(
                meetingId, targetCount, sourceManifestRevision, "null");
    }

    private static String canonicalPlanWithWorkflow(
            String meetingId,
            Integer sourceManifestRevision,
            String sourceWorkflowJson) {
        return canonicalPlanWithWorkflow(
                meetingId, 0, sourceManifestRevision, sourceWorkflowJson);
    }

    private static String canonicalPlanWithWorkflow(
            String meetingId,
            int targetCount,
            Integer sourceManifestRevision,
            String sourceWorkflowJson) {
        StringBuilder targets = new StringBuilder("[");
        for (int index = 0; index < targetCount; index++) {
            if (index > 0) {
                targets.append(',');
            }
            targets.append("{\"artifactId\":\"artifact-").append(index)
                    .append("\",\"spec\":{")
                    .append("\"artifactId\":\"artifact-").append(index)
                    .append("\",\"meetingId\":\"").append(meetingId)
                    .append("\",\"artifactType\":\"type-").append(index)
                    .append("\",\"mediaType\":\"application/json\",\"language\":null,")
                    .append("\"revision\":1,\"expectedSize\":7,")
                    .append("\"expectedChecksum\":\"sha256:")
                    .append("a".repeat(64)).append("\"}}");
        }
        targets.append(']');
        return "{\"specVersion\":\"artifact-deletion-plan/v1\","
                + "\"meetingId\":\"" + meetingId + "\","
                + "\"acceptedAt\":\"2026-09-29T08:09:10.123456Z\","
                + "\"sourceManifestRevision\":"
                + (sourceManifestRevision == null ? "null" : sourceManifestRevision)
                + ",\"sourceFinalizationWorkflow\":" + sourceWorkflowJson
                + ",\"targets\":" + targets + "}";
    }

    private static String sourceWorkflowJson(
            String meetingId,
            String stepKey,
            String contentDigest,
            String nullField) {
        return "{\"meetingId\":" + jsonStringOrNull(meetingId, "meetingId", nullField)
                + ",\"stepType\":"
                + jsonStringOrNull("mock-finalization", "stepType", nullField)
                + ",\"stepKey\":" + jsonStringOrNull(stepKey, "stepKey", nullField)
                + ",\"checkpointType\":" + jsonStringOrNull(
                        "mock-finalization-checkpoint/v1", "checkpointType", nullField)
                + ",\"contentDigest\":"
                + jsonStringOrNull(contentDigest, "contentDigest", nullField) + "}";
    }

    private static String jsonStringOrNull(
            String value,
            String field,
            String nullField) {
        return field.equals(nullField) ? "null" : "\"" + value + "\"";
    }

    private void recreateThroughV011() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + quoteIdentifier(schema) + " CASCADE");
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigrations(11);
    }

    private void applyMigrations(int count) throws Exception {
        for (String migration : MIGRATIONS.subList(0, count)) {
            applyMigration(migration);
        }
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private SQLException executeMigrationAfter(CountDownLatch start, Connection connection) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent migration start gate timed out");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(readMigration("V012__artifact_deletion_plan.sql"));
                return null;
            }
        } catch (SQLException failure) {
            return failure;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot execute concurrent V012 migration", failure);
        }
    }

    private List<String> columnCatalog(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT attname || '|' || format_type(atttypid, atttypmod)
                            || '|' || attnotnull || '|'
                            || coalesce(pg_get_expr(d.adbin, d.adrelid), 'null')
                            || '|' || attidentity::text || '|' || attgenerated::text
                       FROM pg_attribute a
                       LEFT JOIN pg_attrdef d
                         ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                      WHERE a.attrelid=to_regclass(?)
                        AND a.attnum>0 AND NOT a.attisdropped
                      ORDER BY a.attnum
                     """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    private long constraintCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_constraint WHERE conrelid='"
                + table + "'::regclass");
    }

    private long checkCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_constraint WHERE conrelid='"
                + table + "'::regclass AND contype='c' AND convalidated");
    }

    private long indexCount(String table) throws Exception {
        return scalarLong("SELECT count(*) FROM pg_index WHERE indrelid='"
                + table + "'::regclass");
    }

    private long relationOid(String table) throws Exception {
        return scalarLong("SELECT '" + table + "'::regclass::oid");
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
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
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

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        String url = requiredEnvironment("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "V012 tests refuse the shared 127.0.0.1:55432 PostgreSQL resource");
        }
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(url);
        postgres.setUser(requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        postgres.setPassword(requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
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
