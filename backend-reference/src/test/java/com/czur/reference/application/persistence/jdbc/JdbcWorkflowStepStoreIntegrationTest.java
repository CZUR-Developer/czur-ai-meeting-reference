package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式 PostgreSQL 门禁：只有提供测试数据库环境变量时执行。
 * 每个用例使用随机 schema，迁移和业务行均不会触碰共享 public schema。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcWorkflowStepStoreIntegrationTest {
    private static final Instant T0 = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void registerReplayConflictClaimRenewCompleteAndRetryDead() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            WorkflowStep initial = pending("summary", "main", T0);
            assertEquals(WorkflowStepStore.RegisterStatus.APPLIED, store.register(initial).status());
            assertTrue(readStepId(runtime.dataSource(), initial.key()) > 0,
                    "Store INSERT 省略 step_id 时必须由 V001 identity 生成");
            assertEquals(WorkflowStepStore.RegisterStatus.REPLAYED, store.register(initial).status());
            assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                    store.register(pending("summary", "main", T0.plusSeconds(1))).status());

            WorkflowStepStore.LeaseToken lease = store.claim(request("worker-a", T0, 1))
                    .getFirst().lease();
            assertEquals(T0.plusSeconds(10), lease.expiresAt());
            // 当前 next_attempt_at 已因 LEASED 变为空，注册裁决仍必须使用不可变的
            // initial_available_at，而不能读取可变调度字段或触发空指针。
            assertEquals(WorkflowStepStore.RegisterStatus.REPLAYED,
                    store.register(initial).status());
            assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                    store.register(pending("summary", "main", T0.plusSeconds(1))).status());
            WorkflowStepStore.RenewResult renewed = store.renew(
                    lease, T0.plusSeconds(1), Duration.ofSeconds(10));
            assertEquals(WorkflowStepStore.RenewStatus.RENEWED, renewed.status());
            assertNotEquals(lease.token(), renewed.lease().token());
            assertEquals(2, renewed.lease().leaseVersion());
            assertEquals(T0.plusSeconds(11), renewed.lease().expiresAt());
            assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                    store.complete(lease, T0.plusSeconds(2)).status());
            assertEquals(WorkflowStepStore.CompleteStatus.COMPLETED,
                    store.complete(renewed.lease(), T0.plusSeconds(2)).status());
            assertEquals(WorkflowStepStore.RegisterStatus.REPLAYED,
                    store.register(initial).status());
            assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                    store.register(pending("summary", "main", T0.plusSeconds(1))).status());

            WorkflowStep retry = pending("summary", "retry", T0);
            store.register(retry);
            WorkflowStepStore.LeaseToken retryLease = store.claim(request("worker", T0, 1))
                    .stream().filter(value -> value.step().key().equals(retry.key()))
                    .findFirst().orElseThrow().lease();
            assertEquals(WorkflowStepStore.FailStatus.RETRY_SCHEDULED,
                    store.fail(retryLease, T0.plusSeconds(1), T0.plusSeconds(5),
                            "TEMP", "retry", 2).status());
            WorkflowStepStore.LeaseToken second = store.claim(request("worker", T0.plusSeconds(5), 1))
                    .getFirst().lease();
            assertEquals(WorkflowStepStore.FailStatus.DEAD,
                    store.fail(second, T0.plusSeconds(6), T0.plusSeconds(7),
                            "PERM", "dead", 2).status());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndTwoConnectionsHaveOneWinner() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            WorkflowStep step = pending("summary", "concurrent", T0);
            store.register(step);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                List<Future<List<WorkflowStepStore.ClaimedStep>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String owner = "worker-" + i;
                    futures.add(executor.submit(() -> store.claim(request(owner, T0, 1))));
                }
                int winners = futures.get(0).get().size() + futures.get(1).get().size();
                assertEquals(1, winners);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void databaseTokenClaimedAtAndExpiredOwnerAreAllFenced() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            store.register(pending("summary", "fence", T0));
            WorkflowStepStore.LeaseToken ownerA =
                    store.claim(request("worker-a", T0, 1)).getFirst().lease();

            WorkflowStepStore.LeaseToken forgedToken = new WorkflowStepStore.LeaseToken(
                    ownerA.key(), ownerA.owner(), "forged-token", ownerA.expiresAt(),
                    ownerA.claimedAt(), ownerA.leaseVersion());
            assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                    store.complete(forgedToken, T0.plusSeconds(1)).status());

            WorkflowStepStore.LeaseToken forgedClaimedAt = new WorkflowStepStore.LeaseToken(
                    ownerA.key(), ownerA.owner(), ownerA.token(), ownerA.expiresAt(),
                    T0.plusSeconds(1), ownerA.leaseVersion());
            assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                    store.complete(forgedClaimedAt, T0.plusSeconds(2)).status());

            WorkflowStepStore.LeaseToken ownerB = store.claim(
                    request("worker-b", ownerA.expiresAt(), 1)).getFirst().lease();
            assertNotEquals(ownerA.token(), ownerB.token());
            assertEquals(ownerA.leaseVersion() + 1, ownerB.leaseVersion());
            assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                    store.complete(ownerA, ownerA.expiresAt().plusSeconds(1)).status());
            assertEquals(WorkflowStepStore.CompleteStatus.COMPLETED,
                    store.complete(ownerB, ownerB.claimedAt().plusSeconds(1)).status());
            assertEquals(WorkflowStepStore.CompleteStatus.ALREADY_COMPLETED,
                    store.complete(ownerA, ownerA.expiresAt().plusSeconds(2)).status());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void terminalMarkerUsesPhysicalTwoColumnCasAndSurvivesReclaim() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            WorkflowStep step = pending("summary", "terminal-marker", T0);
            store.register(step);
            WorkflowStepStore.ClaimedStep first =
                    store.claim(request("worker-a", T0, 1)).getFirst();
            WorkflowPhysicalSnapshot before = readWorkflowPhysicalSnapshot(
                    runtime.dataSource(), step.key());
            WorkflowStepStore.PersistLeaseMarkerRequest marker = markerRequest(
                    first.lease(), null, null,
                    "TERMINAL_SUCCESS_V1", "canonical-marker-payload");

            WorkflowStepStore.PersistLeaseMarkerResult persisted =
                    store.persistLeaseMarker(marker);
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.PERSISTED,
                    persisted.status());
            WorkflowPhysicalSnapshot after = readWorkflowPhysicalSnapshot(
                    runtime.dataSource(), step.key());
            assertEquals(before.nonMarkerColumns(), after.nonMarkerColumns(),
                    "marker CAS must not change status, schedule, attempt or any lease fence");
            assertEquals("TERMINAL_SUCCESS_V1", after.errorCode());
            assertEquals("canonical-marker-payload", after.errorSummary());
            assertEquals(first.step().updatedAt(), persisted.step().updatedAt());
            assertEquals(first.step().attempt(), persisted.step().attempt());
            assertEquals(first.step().leaseVersion(), persisted.step().leaseVersion());

            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.REPLAYED,
                    store.persistLeaseMarker(marker).status());
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.REPLAYED,
                    store.recoverLeaseMarker(marker).status());
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.CONFLICT,
                    store.persistLeaseMarker(markerRequest(
                            first.lease(), null, null,
                            "TERMINAL_FAILURE_V1", "different-marker")).status());

            WorkflowStepStore.LeaseToken forged = new WorkflowStepStore.LeaseToken(
                    first.lease().key(), first.lease().owner(), "forged-token",
                    first.lease().expiresAt(), first.lease().claimedAt(),
                    first.lease().leaseVersion());
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.NOT_OWNER,
                    store.persistLeaseMarker(markerRequest(
                            forged, null, null,
                            "TERMINAL_SUCCESS_V1", "canonical-marker-payload")).status());

            WorkflowStepStore.ClaimedStep reclaimed = store.claim(
                    request("worker-b", first.lease().expiresAt(), 1)).getFirst();
            assertEquals("TERMINAL_SUCCESS_V1", reclaimed.step().errorCode());
            assertEquals("canonical-marker-payload", reclaimed.step().errorSummary());
            assertEquals(first.lease().leaseVersion() + 1,
                    reclaimed.lease().leaseVersion());
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.NOT_OWNER,
                    store.recoverLeaseMarker(marker).status());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void commitAcknowledgementUnknownRecoversMarkerUsingFreshReadOnlyConnection()
            throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore setup = runtime.workflowStepStore();
            WorkflowStep step = pending("summary", "commit-unknown", T0);
            setup.register(step);
            WorkflowStepStore.ClaimedStep claimed =
                    setup.claim(request("worker-a", T0, 1)).getFirst();
            WorkflowStepStore.PersistLeaseMarkerRequest marker = markerRequest(
                    claimed.lease(), null, null,
                    "TERMINAL_SUCCESS_V1", "commit-unknown-marker");
            AtomicInteger connections = new AtomicInteger();
            AtomicInteger readOnlyConnections = new AtomicInteger();
            AtomicInteger repeatableReadConnections = new AtomicInteger();
            DataSource faulting = commitUnknownOnce(
                    runtime.dataSource(), connections,
                    readOnlyConnections, repeatableReadConnections);
            JdbcWorkflowStepStore store = new JdbcWorkflowStepStore(faulting);

            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.OUTCOME_UNKNOWN,
                    store.persistLeaseMarker(marker).status());
            assertEquals(1, connections.get(),
                    "unknown outcome must abandon the write connection");
            assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.REPLAYED,
                    store.recoverLeaseMarker(marker).status());
            assertEquals(2, connections.get(),
                    "recovery must acquire a fresh connection");
            assertEquals(1, readOnlyConnections.get());
            assertEquals(1, repeatableReadConnections.get());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void nonExtendingRenewHasSamePublicAndConnectionScopedParameterSemantics()
            throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            WorkflowStep step = pending("summary", "renew-parameter", T0);
            store.register(step);
            WorkflowStepStore.LeaseToken active =
                    store.claim(request("owner", T0, 1)).getFirst().lease();
            WorkflowStep activeBefore = store.find(step.key()).orElseThrow();

            IllegalArgumentException activeFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> store.renew(
                            active, T0.plusSeconds(5), Duration.ofSeconds(5)));
            assertEquals("renew must extend lease expiry", activeFailure.getMessage());
            assertEquals(activeBefore, store.find(step.key()).orElseThrow());

            WorkflowStepStore.LeaseToken missing = new WorkflowStepStore.LeaseToken(
                    new WorkflowStep.Key(
                            "meeting-jdbc-workflow", "summary", "missing-renew-parameter"),
                    "owner", "missing-token", T0.plusSeconds(10), T0, 1);
            assertThrows(IllegalArgumentException.class,
                    () -> store.renew(
                            missing, T0.plusSeconds(5), Duration.ofSeconds(5)));
            assertTrue(store.find(missing.key()).isEmpty());

            try (Connection transaction = runtime.dataSource().getConnection()) {
                transaction.setAutoCommit(false);
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcWorkflowStepStore.renew(
                                transaction, active, T0.plusSeconds(5),
                                Duration.ofSeconds(5)));
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcWorkflowStepStore.renew(
                                transaction, missing, T0.plusSeconds(5),
                                Duration.ofSeconds(5)));
                transaction.rollback();
            }
            assertEquals(activeBefore, store.find(step.key()).orElseThrow());

            assertEquals(WorkflowStepStore.CompleteStatus.COMPLETED,
                    store.complete(active, T0.plusSeconds(1)).status());
            WorkflowStep terminalBefore = store.find(step.key()).orElseThrow();
            assertThrows(IllegalArgumentException.class,
                    () -> store.renew(
                            active, T0.plusSeconds(5), Duration.ofSeconds(5)));
            assertEquals(terminalBefore, store.find(step.key()).orElseThrow());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void connectionScopedOperationsRequireTransactionAndCallerRollbackWins() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            WorkflowStep step = pending("summary", "transaction", T0);
            try (Connection autoCommit = runtime.dataSource().getConnection()) {
                assertTrue(autoCommit.getAutoCommit());
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcWorkflowStepStore.register(autoCommit, step));
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcWorkflowStepStore.find(autoCommit, step.key()));
            }

            try (Connection transaction = runtime.dataSource().getConnection()) {
                transaction.setAutoCommit(false);
                assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                        JdbcWorkflowStepStore.register(transaction, step).status());
                transaction.rollback();
            }
            assertTrue(runtime.workflowStepStore().find(step.key()).isEmpty());

            runtime.workflowStepStore().register(step);
            try (Connection transaction = runtime.dataSource().getConnection()) {
                transaction.setAutoCommit(false);
                WorkflowStepStore.LeaseToken claimed = JdbcWorkflowStepStore.claim(
                        transaction, request("worker", T0, 1)).getFirst().lease();
                assertEquals(T0.plusSeconds(10), claimed.expiresAt());
                WorkflowStepStore.RenewResult renewed = JdbcWorkflowStepStore.renew(
                        transaction, claimed, T0.plusSeconds(1), Duration.ofSeconds(20));
                assertEquals(WorkflowStepStore.RenewStatus.RENEWED, renewed.status());
                assertEquals(T0.plusSeconds(21), renewed.lease().expiresAt());
                transaction.rollback();
            }
            assertEquals(WorkflowStep.Status.PENDING,
                    runtime.workflowStepStore().find(step.key()).orElseThrow().status());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void rejectsLossyTimestampAndRetryBeforeFailureWithoutChangingStep() throws Exception {
        String schema = createSchema();
        try (JdbcDatabaseRuntime runtime = runtime(schema)) {
            insertMeeting(runtime.dataSource(), "meeting-jdbc-workflow");
            JdbcWorkflowStepStore store = runtime.workflowStepStore();
            Instant nanos = Instant.parse("2026-09-22T08:00:00.000000001Z");
            assertThrows(IllegalArgumentException.class,
                    () -> store.register(pending("summary", "nanos", nanos)));

            WorkflowStep step = pending("summary", "retry-order", T0);
            store.register(step);
            WorkflowStepStore.LeaseToken lease =
                    store.claim(request("worker", T0, 1)).getFirst().lease();
            assertThrows(IllegalArgumentException.class,
                    () -> store.fail(lease, T0.plusSeconds(2), T0.plusSeconds(1),
                            "TEMP", "retry", 2));
            assertEquals(WorkflowStep.Status.LEASED,
                    store.find(step.key()).orElseThrow().status());
            assertEquals(0, store.find(step.key()).orElseThrow().attempt());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void closeAndReopenReadsPersistedWorkflowStep() throws Exception {
        String schema = createSchema();
        try {
            JdbcDatabaseRuntime first = runtime(schema);
            try {
                insertMeeting(first.dataSource(), "meeting-jdbc-workflow");
                first.workflowStepStore().register(pending("summary", "restart", T0));
            } finally {
                first.close();
            }
            try (JdbcDatabaseRuntime reopened = runtime(schema)) {
                assertTrue(reopened.workflowStepStore().find(
                        new WorkflowStep.Key("meeting-jdbc-workflow", "summary", "restart"))
                        .isPresent());
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void weakV010StatusCheckFailsReadiness() throws Exception {
        String schema = createSchema();
        try {
            try (Connection connection = DriverManager.getConnection(scopedUrl(schema),
                    required("REFERENCE_TEST_DATABASE_USER"), required("REFERENCE_TEST_DATABASE_PASSWORD"));
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE workflow_step DROP CONSTRAINT workflow_step_status_ck");
                statement.execute("ALTER TABLE workflow_step ADD CONSTRAINT workflow_step_status_ck CHECK (true)");
            }
            DatabaseStartupException failure = assertThrows(
                    DatabaseStartupException.class, () -> runtime(schema));
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    failure.readiness().status());
            assertTrue(failure.readiness().missingMigrations().isEmpty());
        } finally {
            dropSchema(schema);
        }
    }




    private static void registerMigrationVersion(String schema, String version) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                scopedUrl(schema), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO schema_migrations(version) VALUES (?)")) {
            statement.setString(1, version);
            statement.executeUpdate();
        }
    }

    private static WorkflowStep pending(String type, String key, Instant nextAt) {
        return WorkflowStep.pending(
                new WorkflowStep.Key("meeting-jdbc-workflow", type, key),
                "checkpoint-json", "{}", "digest-" + key, nextAt, T0);
    }

    private static WorkflowStepStore.ClaimRequest request(String owner, Instant now, int limit) {
        return new WorkflowStepStore.ClaimRequest(
                Set.of("summary"), owner, now, Duration.ofSeconds(10), limit);
    }

    private static WorkflowStepStore.PersistLeaseMarkerRequest markerRequest(
            WorkflowStepStore.LeaseToken lease,
            String expectedCode,
            String expectedSummary,
            String markerCode,
            String markerSummary) {
        return new WorkflowStepStore.PersistLeaseMarkerRequest(
                lease,
                lease.claimedAt().plusSeconds(1),
                expectedCode,
                expectedSummary,
                markerCode,
                markerSummary);
    }

    private static JdbcDatabaseRuntime runtime(String schema) {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                scopedUrl(schema), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"),
                Duration.ofSeconds(5), Duration.ofSeconds(3), 4, 1));
    }

    private static String createSchema() throws Exception {
        return createSchema(12);
    }

    private static String createSchema(int migrationCount) throws Exception {
        String schema = "workflow_v010_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(baseUrl(), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD")); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        List<String> migrations = List.of(
                "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
                "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
                "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
                "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql");
        try {
            for (int i = 0; i < migrationCount; i++) {
                try (Connection connection = DriverManager.getConnection(scopedUrl(schema),
                        required("REFERENCE_TEST_DATABASE_USER"), required("REFERENCE_TEST_DATABASE_PASSWORD"));
                     Statement statement = connection.createStatement()) {
                    statement.execute(readMigration(migrations.get(i)));
                }
                try (Connection connection = DriverManager.getConnection(scopedUrl(schema),
                        required("REFERENCE_TEST_DATABASE_USER"), required("REFERENCE_TEST_DATABASE_PASSWORD"));
                     PreparedStatement statement = connection.prepareStatement(
                             "INSERT INTO schema_migrations(version) VALUES (?)")) {
                    statement.setString(1, migrations.get(i).substring(0, migrations.get(i).length() - 4));
                    statement.executeUpdate();
                }
            }
            if (migrationCount >= 11) {
                // 需要启动当前 Runtime 的完整/近完整夹具补上 V013；只执行
                // V001 的迁移反例保持原始状态，不让无关表参与该反例。
                try (Connection connection = DriverManager.getConnection(scopedUrl(schema),
                        required("REFERENCE_TEST_DATABASE_USER"),
                        required("REFERENCE_TEST_DATABASE_PASSWORD"));
                     Statement statement = connection.createStatement()) {
                    statement.execute(readMigration("V013__meeting_create_fact.sql"));
                }
                registerMigrationVersion(schema, "V013__meeting_create_fact");
            }
            return schema;
        } catch (Exception failure) {
            dropSchema(schema);
            throw failure;
        }
    }

    private static void insertMeeting(javax.sql.DataSource dataSource, String id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting(meeting_id, deployment_scope, client_session_id,
                       subject_scope, profile_id, profile_revision, state, created_at, updated_at)
                     VALUES (?, 'test', ?, 'test', 'profile', 1, 'CREATED', ?, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, id);
            statement.setObject(3, java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC));
            statement.setObject(4, java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static long readStepId(
            javax.sql.DataSource dataSource,
            WorkflowStep.Key key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT step_id
                       FROM workflow_step
                      WHERE meeting_id = ? AND step_type = ? AND step_key = ?
                     """)) {
            statement.setString(1, key.meetingId());
            statement.setString(2, key.stepType());
            statement.setString(3, key.stepKey());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("workflow step fixture not found");
                }
                long value = rows.getLong(1);
                if (rows.wasNull() || rows.next()) {
                    throw new IllegalStateException("workflow step identity is inconsistent");
                }
                return value;
            }
        }
    }

    private static WorkflowPhysicalSnapshot readWorkflowPhysicalSnapshot(
            DataSource dataSource,
            WorkflowStep.Key key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT (to_jsonb(workflow_step)
                              - 'error_code' - 'error_message')::text,
                            error_code, error_message
                       FROM workflow_step
                      WHERE meeting_id = ? AND step_type = ? AND step_key = ?
                     """)) {
            statement.setString(1, key.meetingId());
            statement.setString(2, key.stepType());
            statement.setString(3, key.stepKey());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("workflow step fixture not found");
                }
                WorkflowPhysicalSnapshot value = new WorkflowPhysicalSnapshot(
                        rows.getString(1), rows.getString(2), rows.getString(3));
                if (rows.next()) {
                    throw new IllegalStateException("workflow step fixture is not unique");
                }
                return value;
            }
        }
    }

    /**
     * Returns a DataSource whose first connection commits successfully and then loses the
     * acknowledgement. Later connections are normal but record the read-only recovery contract.
     */
    private static DataSource commitUnknownOnce(
            DataSource delegate,
            AtomicInteger connections,
            AtomicInteger readOnlyConnections,
            AtomicInteger repeatableReadConnections) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcWorkflowStepStoreIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (!method.getName().equals("getConnection")) {
                            return result;
                        }
                        int index = connections.incrementAndGet();
                        Connection connection = (Connection) result;
                        return Proxy.newProxyInstance(
                                JdbcWorkflowStepStoreIntegrationTest.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                (connectionProxy, connectionMethod, connectionArguments) -> {
                                    try {
                                        if (connectionMethod.getName().equals("commit")
                                                && index == 1) {
                                            connection.commit();
                                            throw new java.sql.SQLException(
                                                    "injected acknowledgement loss", "08006");
                                        }
                                        if (connectionMethod.getName().equals("setReadOnly")
                                                && Boolean.TRUE.equals(connectionArguments[0])) {
                                            readOnlyConnections.incrementAndGet();
                                        }
                                        if (connectionMethod.getName().equals(
                                                "setTransactionIsolation")
                                                && ((Integer) connectionArguments[0])
                                                == Connection.TRANSACTION_REPEATABLE_READ) {
                                            repeatableReadConnections.incrementAndGet();
                                        }
                                        if (connectionMethod.getName().equals("abort")) {
                                            connection.close();
                                            return null;
                                        }
                                        return connectionMethod.invoke(
                                                connection, connectionArguments);
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private record WorkflowPhysicalSnapshot(
            String nonMarkerColumns,
            String errorCode,
            String errorSummary) {
    }

    private static String readMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
        for (Path path : new Path[]{Path.of("deploy", "migrations", filename), Path.of("..", "deploy", "migrations", filename)}) {
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection admin = DriverManager.getConnection(baseUrl(), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD")); Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
        }
    }

    private static String baseUrl() { return System.getenv("REFERENCE_TEST_DATABASE_URL"); }
    private static String scopedUrl(String schema) {
        return baseUrl() + (baseUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
