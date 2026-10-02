package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableInputAcceptanceUnitOfWork;
import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V008 与输入接管协同工作单元的 PostgreSQL 门禁。
 *
 * <p>默认跳过；只有显式设置 {@code REFERENCE_TEST_DATABASE_URL/USER/PASSWORD}
 * 才创建随机 schema。每个用例执行 V001～V008，验证数组/时间读回、receipt 重放、
 * 完整事实冲突，以及预检失败时 V008 不会留下候选行。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcDurableInputAcceptanceUnitOfWorkIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql",
            "V008__durable_input_fact.sql");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-22T01:00:00.123456Z");
    private static final String MEETING_ID = "durable-input-acceptance";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "durable_input_acceptance_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (int index = 0; index < MIGRATIONS.size(); index++) {
            String migration = MIGRATIONS.get(index);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO schema_migrations(version) VALUES (?)")) {
                statement.setString(1, migration.substring(0, migration.length() - 4));
                statement.executeUpdate();
            }
        }
        insertPendingMeeting(dataSource);
    }

    @AfterEach
    void dropFreshSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void appliedFactCommitsReceiptAndReadsBackArrayAndTimestamp() throws Exception {
        FinalizationInputFact fact = fact("binding-a", "digest-a");
        AtomicInteger generated = new AtomicInteger();
        DurableInputAcceptanceUnitOfWork.Result result = accept(
                fact, () -> "receipt-a", generated);

        assertEquals(DurableInputFactStore.PersistStatus.APPLIED, result.inputStatus());
        MeetingFinalizationStore.Applied finalization =
                assertInstanceOf(MeetingFinalizationStore.Applied.class, result.finalization());
        assertTrue(finalization.fact().isAccepted());
        assertEquals(1, generated.get());
        assertEquals(fact, readFact());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT target_languages, accepted_at FROM durable_input_fact WHERE meeting_id=?")) {
            statement.setString(1, MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                Object[] languages = (Object[]) rows.getArray(1).getArray();
                assertEquals(List.of("en", "ja"), List.of(languages));
                assertEquals(UPDATED_AT, rows.getTimestamp(2).toInstant());
            }
        }
    }

    @Test
    void equalFactReplayReturnsOriginalReceiptWithoutRegenerating() {
        FinalizationInputFact fact = fact("binding-replay", "digest-replay");
        AtomicInteger generated = new AtomicInteger();
        accept(fact, () -> "receipt-replay", generated);

        DurableInputAcceptanceUnitOfWork.Result replay = accept(
                fact, () -> "should-not-be-used", generated);
        assertEquals(DurableInputFactStore.PersistStatus.REPLAYED, replay.inputStatus());
        MeetingFinalizationStore.Replayed finalization =
                assertInstanceOf(MeetingFinalizationStore.Replayed.class, replay.finalization());
        assertEquals("receipt-replay", finalization.fact().finalizationReceipt());
        assertEquals(1, generated.get());
    }

    @Test
    void differentFactConflictsWithoutReceiptGenerationOrOverwrite() throws Exception {
        FinalizationInputFact original = fact("binding-original", "digest-original");
        AtomicInteger generated = new AtomicInteger();
        accept(original, () -> "receipt-original", generated);

        DurableInputAcceptanceUnitOfWork.Result conflict = accept(
                fact("binding-conflict", "digest-conflict"),
                () -> "should-not-be-used", generated);
        assertEquals(DurableInputFactStore.PersistStatus.CONFLICT, conflict.inputStatus());
        assertInstanceOf(MeetingFinalizationStore.Conflict.class, conflict.finalization());
        assertEquals(1, generated.get());
        assertEquals(original, readFact());
    }

    @Test
    void acceptedMeetingWithoutV008FactConflictsWithoutRepairingTheFact() throws Exception {
        markMeetingAcceptedWithoutDurableFact();
        AtomicInteger generated = new AtomicInteger();

        DurableInputAcceptanceUnitOfWork.Result result = accept(
                fact("binding-legacy", "digest-legacy"),
                () -> "should-not-be-used", generated);
        assertEquals(DurableInputFactStore.PersistStatus.CONFLICT, result.inputStatus());
        MeetingFinalizationStore.Conflict conflict =
                assertInstanceOf(MeetingFinalizationStore.Conflict.class, result.finalization());
        assertEquals(MeetingFinalizationStore.ConflictReason.FACT_INCONSISTENT,
                conflict.reason());
        assertEquals(0, generated.get());
        assertFalse(factExists());
    }

    @Test
    void invalidMeetingStateRollsBackAndLeavesNoDurableFact() throws Exception {
        setMeetingRunning();
        FinalizationInputFact fact = fact("binding-invalid", "digest-invalid");
        AtomicInteger generated = new AtomicInteger();

        DurableInputAcceptanceUnitOfWork.Result result = accept(
                fact, () -> "should-not-be-used", generated);
        assertEquals(DurableInputFactStore.PersistStatus.CONFLICT, result.inputStatus());
        MeetingFinalizationStore.Conflict conflict =
                assertInstanceOf(MeetingFinalizationStore.Conflict.class, result.finalization());
        assertEquals(MeetingFinalizationStore.ConflictReason.INVALID_MEETING_STATE,
                conflict.reason());
        assertEquals(0, generated.get());
        assertFalse(factExists());
    }

    @Test
    void receiptGenerationFailureRollsBackFactAndKeepsPendingMeeting() throws Exception {
        FinalizationInputFact fact = fact("binding-generator-fails", "digest-generator-fails");
        AtomicInteger generated = new AtomicInteger();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> accept(fact, () -> {
                    throw new IllegalStateException("secret-receipt-generator-detail");
                }, generated));
        assertTrue(failure.getMessage().contains("storage=UNAVAILABLE"));
        assertFalse(failure.getMessage().contains("secret-receipt-generator-detail"));
        assertEquals(1, generated.get());
        assertFalse(factExists());
        assertArrayEquals(
                new String[]{"STOPPING", "INPUT_COMPLETE_PENDING", null}, readMeetingFact());
    }

    private DurableInputAcceptanceUnitOfWork.Result accept(
            FinalizationInputFact fact,
            com.czur.reference.application.port.FinalizationReceiptGenerator generator,
            AtomicInteger generated) {
        return new JdbcDurableInputAcceptanceUnitOfWork(
                dataSource,
                () -> {
                    generated.incrementAndGet();
                    return generator.newReceipt();
                }).accept(new DurableInputAcceptanceUnitOfWork.AcceptRequest(MEETING_ID, fact));
    }

    private FinalizationInputFact readFact() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<FinalizationInputFact> fact = JdbcDurableInputFactStore.find(
                        connection, MEETING_ID);
                connection.rollback();
                return fact.orElseThrow();
            } finally {
                connection.close();
            }
        }
    }

    private boolean factExists() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM durable_input_fact WHERE meeting_id=?")) {
            statement.setString(1, MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private String[] readMeetingFact() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, input_state, finalization_receipt_id
                       FROM meeting
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new String[]{rows.getString(1), rows.getString(2), rows.getString(3)};
            }
        }
    }

    private static FinalizationInputFact fact(String binding, String digest) {
        return new FinalizationInputFact(
                MEETING_ID, binding, 7, 1_234, "zh-CN", List.of("en", "ja"),
                UPDATED_AT, digest);
    }

    private void insertPendingMeeting(DataSource source) throws Exception {
        String deployment = "durable-input-deployment";
        String subject = "durable-input-subject";
        Instant createdAt = UPDATED_AT.minusSeconds(20);
        Instant expiresAt = UPDATED_AT.plusSeconds(3_600);
        MeetingGrant grant = new MeetingGrant(
                "grant-durable-input", deployment, subject, "profile-durable-input", 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, createdAt, expiresAt);
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-durable-input", grant, MEETING_ID,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                createdAt.plusSeconds(1), expiresAt.minusSeconds(1));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                deployment, subject, "reserve-key-durable-input", "reserve-digest-durable-input",
                expiresAt, AuthorizedProfileSnapshot.from(grant), reservation);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            JdbcUsageReservationStore.insertOperation(connection, reserve, createdAt.plusSeconds(1));
            connection.commit();
        }
        OwnedMeetingCreationUnitOfWork.Result created =
                new JdbcOwnedMeetingCreationUnitOfWork(
                        source, Clock.fixed(createdAt.plusSeconds(2), ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                deployment, subject, MEETING_ID, "session-durable-input",
                                "create-key-durable-input", "create-digest-durable-input", reserve));
        assertInstanceOf(OwnedMeetingCreationUnitOfWork.Success.class, created);
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting
                        SET state='STOPPING', input_state='INPUT_COMPLETE_PENDING',
                            updated_at=?
                      WHERE meeting_id=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(UPDATED_AT));
            statement.setString(2, MEETING_ID);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setMeetingRunning() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting
                        SET state='RUNNING', updated_at=?
                      WHERE meeting_id=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(UPDATED_AT));
            statement.setString(2, MEETING_ID);
            statement.executeUpdate();
        }
    }

    private void markMeetingAcceptedWithoutDurableFact() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting
                        SET state='FINALIZING', input_state='INPUT_ACCEPTED',
                            finalization_receipt_id='legacy-receipt', updated_at=?
                      WHERE meeting_id=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(UPDATED_AT));
            statement.setString(2, MEETING_ID);
            statement.executeUpdate();
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        postgres.setUser(System.getenv("REFERENCE_TEST_DATABASE_USER"));
        postgres.setPassword(System.getenv("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String readMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
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
}
