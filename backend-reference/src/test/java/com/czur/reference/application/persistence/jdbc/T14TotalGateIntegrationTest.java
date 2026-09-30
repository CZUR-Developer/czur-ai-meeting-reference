package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.ExpiredReservationCleanupWorker;
import com.czur.reference.application.outbox.OutboxDispatcher;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-11 total gate: a fresh deploy migration chain survives one real PostgreSQL
 * stop/start in two independent Maven/JVM phases.  The first JVM leaves a pending
 * Outbox event and an expired unbound Reservation in PostgreSQL; the second JVM runs
 * the production Dispatcher and cleanup Worker against those durable facts.
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_TOTAL_STATE_FILE", matches = ".+")
class T14TotalGateIntegrationTest {
    private static final String STATE_ENV = "T14_TOTAL_STATE_FILE";
    private static final String OUTBOX_EVENT_ID = "t14-total-outbox-event";
    private static final String OUTBOX_EVENT_TYPE = "T14TotalEvent";
    private static final String RESERVATION_ID = "reservation-t14-total-cleanup";
    private static final String DEPLOYMENT_SCOPE = "t14-total-deployment";
    private static final String SUBJECT_SCOPE = "t14-total-subject";
    private static final String EXPECTED_MEETING_ID = "meeting-t14-total-cleanup";
    private static final Instant FIXTURE_NOW = Instant.parse("2026-09-30T04:00:00Z");
    private static final Set<String> MIGRATIONS = Set.of(
            "V001__initial_schema", "V002__outbox_lease_fencing",
            "V003__identity_entitlement", "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact", "V006__meeting_read_model",
            "V007__meeting_manifest_revision", "V008__durable_input_fact",
            "V009__final_usage_fact", "V010__workflow_step_fencing",
            "V011__finalization_execution_artifact_ready",
            "V012__artifact_deletion_plan");
    private static final Set<String> STATE_KEYS = Set.of(
            "phase", "pidA", "startA", "pidB", "startB", "postmasterStart",
            "migrationCount", "outboxEventId", "reservationId");
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<String> EMPTY_FACT_TABLES = Set.of(
            "meeting", "outbox_event", "usage_reservation", "workflow_step",
            "meeting_manifest_revision", "finalization_execution_fact", "artifact",
            "meeting_deletion_plan", "artifact_deletion_item");

    @Test
    void prepareBeforeRestart() throws Exception {
        rejectReservedEndpoint();
        Path state = stateFile();
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("state file already exists");
        }
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "fresh V001-V012 database must pass readiness");
            assertEquals(MIGRATIONS, migrationVersions(runtime.dataSource()));
            assertAllFactTablesExist(runtime.dataSource());
            assertEmptyBusinessFacts(runtime.dataSource());
            seedOutboxEvent(runtime.dataSource());
            seedExpiredReservation(runtime.dataSource());
            assertEquals(OutboxStore.EventStatus.PENDING,
                    new JdbcOutboxStore(runtime.dataSource())
                            .find(OUTBOX_EVENT_ID).orElseThrow().status());
            writeState(state, Map.of(
                    "phase", "PREPARED",
                    "pidA", Long.toString(ProcessHandle.current().pid()),
                    "startA", Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()),
                    "pidB", "UNSET",
                    "startB", "UNSET",
                    "postmasterStart", postmasterStart(runtime.dataSource()).toString(),
                    "migrationCount", Integer.toString(MIGRATIONS.size()),
                    "outboxEventId", OUTBOX_EVENT_ID,
                    "reservationId", RESERVATION_ID));
        }
    }

    @Test
    void verifyAfterRestart() throws Exception {
        rejectReservedEndpoint();
        Map<String, String> expected = readState(stateFile());
        assertEquals("PREPARED", expected.get("phase"));
        assertEquals(OUTBOX_EVENT_ID, expected.get("outboxEventId"));
        assertEquals(RESERVATION_ID, expected.get("reservationId"));
        assertDifferentJvm(expected.get("pidA"), expected.get("startA"));
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "V001-V012 readiness must survive PostgreSQL restart");
            DataSource dataSource = runtime.dataSource();
            Instant before = Instant.parse(expected.get("postmasterStart"));
            Instant after = postmasterStart(dataSource);
            assertTrue(after.isAfter(before), "postmaster start time must advance after restart");
            assertEquals(MIGRATIONS, migrationVersions(dataSource));
            assertEquals(Integer.toString(MIGRATIONS.size()), expected.get("migrationCount"));
            assertAllFactTablesExist(dataSource);
            runOutboxDispatcher(dataSource);
            runExpiredReservationCleanup(dataSource);
            assertEquals(OutboxStore.EventStatus.PUBLISHED,
                    new JdbcOutboxStore(dataSource).find(OUTBOX_EVENT_ID).orElseThrow().status());
            assertEquals(UsageReservation.State.CANCELLED,
                    findReservation(dataSource).orElseThrow().state());
            writeState(stateFile(), Map.of(
                    "phase", "VERIFIED",
                    "pidA", expected.get("pidA"),
                    "startA", expected.get("startA"),
                    "pidB", Long.toString(ProcessHandle.current().pid()),
                    "startB", Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()),
                    "postmasterStart", after.toString(),
                    "migrationCount", Integer.toString(MIGRATIONS.size()),
                    "outboxEventId", OUTBOX_EVENT_ID,
                    "reservationId", RESERVATION_ID));
        }
    }

    private static void seedOutboxEvent(DataSource dataSource) {
        OutboxStore.OutboxEvent event = OutboxStore.OutboxEvent.pending(
                OUTBOX_EVENT_ID,
                "T14Total",
                "aggregate-t14-total",
                OUTBOX_EVENT_TYPE,
                "{\"fixture\":\"t14-total\"}",
                FIXTURE_NOW.minusSeconds(1),
                FIXTURE_NOW.minusSeconds(1));
        OutboxStore.AppendResult result = new JdbcOutboxStore(dataSource).append(event);
        assertEquals(OutboxStore.AppendStatus.APPENDED, result.status());
    }

    private static void seedExpiredReservation(DataSource dataSource) throws Exception {
        MeetingGrant grant = new MeetingGrant(
                "grant-t14-total",
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                "t14-profile",
                1,
                Set.of("transcript.final"),
                Set.of("transcript.final"),
                3_600_000,
                FIXTURE_NOW.minusSeconds(120),
                FIXTURE_NOW.plusSeconds(3_600));
        UsageReservation reservation = UsageReservation.reserved(
                RESERVATION_ID,
                grant,
                EXPECTED_MEETING_ID,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                FIXTURE_NOW.minusSeconds(60),
                FIXTURE_NOW.minusSeconds(10));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                "reserve-key-t14-total",
                "reserve-digest-t14-total",
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            if (!JdbcUsageReservationStore.insertOperation(connection, reserve, FIXTURE_NOW)) {
                throw new IllegalStateException("T14 total reserve fixture already exists");
            }
            connection.commit();
        }
    }

    private static void runOutboxDispatcher(DataSource dataSource) {
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                new JdbcOutboxStore(dataSource),
                List.of(new OutboxDispatcher.HandlerRegistration(
                        OUTBOX_EVENT_TYPE, (event, context) -> {
                            assertEquals(OUTBOX_EVENT_ID, event.eventId());
                            assertEquals(OUTBOX_EVENT_TYPE, event.eventType());
                        })),
                new OutboxDispatcher.Config(
                        "t14-total-dispatcher",
                        java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(1),
                        Duration.ZERO,
                        10,
                        3));
        OutboxDispatcher.RunResult result = dispatcher.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(OutboxDispatcher.ItemStatus.PUBLISHED, result.items().get(0).status());
    }

    private static void runExpiredReservationCleanup(DataSource dataSource) {
        ExpiredReservationStore store = new JdbcExpiredReservationStore(
                dataSource,
                new EntitlementIdGenerator() {
                    @Override
                    public String newGrantId() {
                        return "unused-t14-total-grant";
                    }

                    @Override
                    public String newReservationId() {
                        return "unused-t14-total-reservation";
                    }

                    @Override
                    public String newLeaseToken() {
                        return "t14-total-lease-token";
                    }
                });
        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store,
                T14WorkerCrossJvmTestSupport.entitlementProvider(
                        dataSource, java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC)),
                new ExpiredReservationCleanupWorker.Config(
                        DEPLOYMENT_SCOPE,
                        "t14-total-cleaner",
                        java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(1),
                        10,
                        "t14-total-cleanup:",
                        "EXPIRED_UNBOUND"));
        ExpiredReservationCleanupWorker.RunResult result = worker.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.CANCELLED,
                result.items().get(0).status());
    }

    private static Optional<UsageReservation> findReservation(DataSource dataSource)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return JdbcUsageReservationStore.findReservation(
                    connection, DEPLOYMENT_SCOPE, SUBJECT_SCOPE, RESERVATION_ID);
        }
    }

    private static void assertDifferentJvm(String pidA, String startA) {
        long currentPid = ProcessHandle.current().pid();
        long currentStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertFalse(Long.toString(currentPid).equals(pidA)
                        && Long.toString(currentStart).equals(startA),
                "worker phase must run in a different JVM");
    }

    private static JdbcDatabaseRuntime startRuntime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                required("REFERENCE_TEST_DATABASE_URL"),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), 4, 1));
    }

    private static Set<String> migrationVersions(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT version FROM schema_migrations ORDER BY version");
             ResultSet rows = statement.executeQuery()) {
            Set<String> actual = new HashSet<>();
            while (rows.next()) {
                if (!actual.add(rows.getString(1))) {
                    throw new IllegalStateException("duplicate migration registration");
                }
            }
            return Set.copyOf(actual);
        }
    }

    private static void assertAllFactTablesExist(DataSource dataSource) throws Exception {
        String placeholders = String.join(",", EMPTY_FACT_TABLES.stream().map(value -> "?").toList());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema=current_schema() AND table_name IN ("
                             + placeholders + ")")) {
            int index = 1;
            for (String table : EMPTY_FACT_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
                assertEquals(EMPTY_FACT_TABLES, actual);
            }
        }
    }

    private static void assertEmptyBusinessFacts(DataSource dataSource) throws Exception {
        for (String table : EMPTY_FACT_TABLES) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT count(*) FROM " + quote(table));
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1), "fresh table must be empty: " + table);
            }
        }
    }

    private static Instant postmasterStart(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pg_postmaster_start_time()");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static Path stateFile() {
        Path path = Path.of(required(STATE_ENV));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("state file must be absolute");
        }
        return path.normalize();
    }

    private static void writeState(Path path, Map<String, String> values) throws Exception {
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalArgumentException("state keys are not exact");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IllegalStateException("state parent is unsafe");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("state file is unsafe");
            }
        } else {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        }
        String content = "phase=" + fixedValue(values.get("phase")) + '\n'
                + "pidA=" + fixedValue(values.get("pidA")) + '\n'
                + "startA=" + fixedValue(values.get("startA")) + '\n'
                + "pidB=" + fixedValue(values.get("pidB")) + '\n'
                + "startB=" + fixedValue(values.get("startB")) + '\n'
                + "postmasterStart=" + fixedValue(values.get("postmasterStart")) + '\n'
                + "migrationCount=" + fixedValue(values.get("migrationCount")) + '\n'
                + "outboxEventId=" + fixedValue(values.get("outboxEventId")) + '\n'
                + "reservationId=" + fixedValue(values.get("reservationId")) + '\n';
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Linux acceptance scripts perform the exact mode check.
        }
    }

    private static Map<String, String> readState(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) > 4096) {
            throw new IllegalStateException("state file is unsafe");
        }
        try {
            assertEquals(PRIVATE_PERMISSIONS, Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX development filesystems still receive structural checks.
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("state line is malformed");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!STATE_KEYS.contains(key) || value.isBlank() || values.put(key, value) != null) {
                throw new IllegalStateException("state keys are not exact");
            }
        }
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalStateException("state keys are incomplete");
        }
        return Map.copyOf(values);
    }

    private static String fixedValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("state value is invalid");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void rejectReservedEndpoint() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("reserved database endpoint is forbidden");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
