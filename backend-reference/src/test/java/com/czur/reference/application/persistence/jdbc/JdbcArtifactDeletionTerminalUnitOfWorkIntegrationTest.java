package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.manifest.ManifestDeletionRevisionFactory;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 删除终态事务的隔离 PostgreSQL 门禁。每次测试创建随机 schema，拒绝共享 55432，
 * 仅通过正式计划、Workflow claim 和终态 UoW 产生业务事实；SQL 只建立最小测试夹具。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactDeletionTerminalUnitOfWorkIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql");
    private static final Instant ACCEPTED =
            Instant.parse("2026-09-29T08:00:00.000001Z");
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;
    private String meeting;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url.contains(":55432")) {
            throw new IllegalStateException("integration test refuses shared 55432 database");
        }
        admin = postgres(null);
        schema = "deletion_terminal_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
        meeting = "mtg_terminal_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, aggregate_version,
                    finalization_receipt_id, input_state, manifest_state, created_at, updated_at)
                VALUES (?, 'deployment-test', ?, 'LEGACY_UNOWNED', 'profile-test', 1,
                    'DELETION_PENDING', 0, 'receipt-terminal', 'INPUT_ACCEPTED',
                    'DELETION_PENDING', ?, ?)
                """)) {
            statement.setString(1, meeting);
            statement.setString(2, "session-" + meeting);
            statement.setTimestamp(3, java.sql.Timestamp.from(ACCEPTED));
            statement.setTimestamp(4, java.sql.Timestamp.from(ACCEPTED));
            statement.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void strictEmptyPlanWithoutManifestCompletesOnce() throws Exception {
        WorkflowStepStore.LeaseToken lease = prepare(List.of());
        ArtifactDeletionTerminalUnitOfWork.Result result = terminal().complete(
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, ACCEPTED.plusSeconds(1)));
        ArtifactDeletionTerminalUnitOfWork.Success success =
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, result);
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.COMPLETED, success.code());
        assertEquals(null, success.deletedManifestRevision());
        assertEquals(1, count("MeetingDeleted"));
        assertEquals(0, countManifest());
        assertEquals(1, countMeeting("DELETED"));

        ArtifactDeletionTerminalUnitOfWork.Result replay = terminal().complete(
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, ACCEPTED.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, replay).code());
        assertEquals(1, count("MeetingDeleted"));
    }

    @Test
    void pendingItemRefusesToPublishDeletedState() throws Exception {
        WorkflowStepStore.LeaseToken lease = prepare(List.of(target("art_terminal_pending", meeting)));
        ArtifactDeletionTerminalUnitOfWork.Result result = terminal().complete(
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, ACCEPTED.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.ErrorCode.ITEM_NOT_CONFIRMED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Failure.class, result).code());
        assertEquals(0, count("MeetingDeleted"));
        assertEquals(1, countMeeting("DELETION_PENDING"));
    }

    @Test
    void allConfirmedItemCompletesWithoutInventingManifest() throws Exception {
        String artifactId = "art_terminal_deleted";
        WorkflowStepStore.LeaseToken lease = prepare(List.of(target(artifactId, meeting)));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE artifact_deletion_item SET status='DELETED', storage_binding='binding-test', deleted_at=?
                 WHERE meeting_id=? AND ordinal=0
                """)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(ACCEPTED));
            statement.setString(2, meeting);
            statement.executeUpdate();
        }
        ArtifactDeletionTerminalUnitOfWork.Success success = assertInstanceOf(
                ArtifactDeletionTerminalUnitOfWork.Success.class,
                terminal().complete(new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        lease, ACCEPTED.plusSeconds(1))));
        assertEquals(null, success.deletedManifestRevision());
        assertEquals(1, countMeeting("DELETED"));
        assertEquals(1, count("MeetingDeleted"));
    }

    @Test
    void existingDeletionPendingManifestGetsOneDeletedRevision() throws Exception {
        MeetingManifest ready = manifest(1, ManifestStatus.READY);
        MeetingManifest pending = ManifestDeletionRevisionFactory.deletionPendingAfter(ready);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcManifestFactStore store = new JdbcManifestFactStore(connection);
            assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                    store.append(new ManifestFactStore.AppendRequest(
                            ready, new ManifestFactStore.ExpectedPreviousRevision.None())).status());
            assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                    store.append(new ManifestFactStore.AppendRequest(
                            pending, new ManifestFactStore.ExpectedPreviousRevision.Present(1))).status());
            connection.commit();
        }
        WorkflowStepStore.LeaseToken lease = prepare(List.of(), 2);
        ArtifactDeletionTerminalUnitOfWork.Success success = assertInstanceOf(
                ArtifactDeletionTerminalUnitOfWork.Success.class,
                terminal().complete(new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        lease, ACCEPTED.plusSeconds(1))));
        assertEquals(3, success.deletedManifestRevision());
        assertEquals(3, countManifest());
        assertEquals(1, countManifestStatus("DELETED"));
        assertEquals(1, count("MeetingDeleted"));

        ArtifactDeletionTerminalUnitOfWork.Result replay = terminal().complete(
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, ACCEPTED.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, replay).code());
        assertEquals(3, countManifest());
        assertEquals(1, count("MeetingDeleted"));
    }

    @Test
    void commitAcknowledgementUnknownIsRecoveredByReadOnlyReplay() throws Exception {
        WorkflowStepStore.LeaseToken lease = prepare(List.of());
        ArtifactDeletionTerminalUnitOfWork.Result unknown =
                new JdbcArtifactDeletionTerminalUnitOfWork(commitUnknownDataSource()).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                                lease, ACCEPTED.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Failure.class, unknown).code());
        ArtifactDeletionTerminalUnitOfWork.Result recovered = terminal().recover(
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        lease, ACCEPTED.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, recovered).code());
        assertEquals(1, count("MeetingDeleted"));
    }

    private WorkflowStepStore.LeaseToken prepare(List<ArtifactDeletionTarget> targets)
            throws Exception {
        return prepare(targets, null);
    }

    private WorkflowStepStore.LeaseToken prepare(
            List<ArtifactDeletionTarget> targets, Integer sourceManifestRevision)
            throws Exception {
        ArtifactDeletionPlan plan = new ArtifactDeletionPlan(
                meeting, ACCEPTED, sourceManifestRevision, null, targets);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    new JdbcArtifactDeletionPlanStore(connection).register(plan).status());
            connection.commit();
        }
        WorkflowStep workflow = ArtifactDeletionPlanJson.workflowStep(plan);
        JdbcWorkflowStepStore store = new JdbcWorkflowStepStore(dataSource);
        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED, store.register(workflow).status());
        return store.claim(new WorkflowStepStore.ClaimRequest(
                java.util.Set.of(ArtifactDeletionPlan.STEP_TYPE), "terminal-test",
                ACCEPTED.plusMillis(1), java.time.Duration.ofMinutes(5), 1))
                .getFirst().lease();
    }

    private JdbcArtifactDeletionTerminalUnitOfWork terminal() {
        return new JdbcArtifactDeletionTerminalUnitOfWork(dataSource);
    }

    /** 提交已落库但 acknowledgement 丢失的连接替身；不改变 55432 或真实资源。 */
    private DataSource commitUnknownDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (source, method, args) -> {
                    Object value = method.invoke(dataSource, args);
                    if (!"getConnection".equals(method.getName())) {
                        return value;
                    }
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(
                            Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            new java.lang.reflect.InvocationHandler() {
                                private boolean thrown;

                                @Override
                                public Object invoke(Object proxy, java.lang.reflect.Method call,
                                        Object[] callArgs) throws Throwable {
                                    if ("commit".equals(call.getName()) && !thrown) {
                                        thrown = true;
                                        call.invoke(connection, callArgs);
                                        throw new java.sql.SQLException("commit acknowledgement unknown");
                                    }
                                    return call.invoke(connection, callArgs);
                                }
                            });
                });
    }

    private int count(String eventType) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM outbox_event WHERE event_type=?")) {
            statement.setString(1, eventType);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private int countManifest() throws Exception {
        return countSql("SELECT count(*) FROM meeting_manifest_revision");
    }

    private int countManifestStatus(String status) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=? AND canonical_json::jsonb->>'status'=?")) {
            statement.setString(1, meeting); statement.setString(2, status);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private int countMeeting(String state) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM meeting WHERE meeting_id=? AND state=?")) {
            statement.setString(1, meeting); statement.setString(2, state);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private int countSql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); }
    }

    private MeetingManifest manifest(int revision, ManifestStatus status) {
        return new MeetingManifest(
                "0.1", meeting, revision,
                new MeetingManifest.Profile("meeting-minutes/v1", 1),
                null, status, ACCEPTED.minusSeconds(30), ACCEPTED,
                30_000, new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of());
    }

    private static ArtifactDeletionTarget target(String artifactId, String meetingId) {
        return ArtifactDeletionTarget.fromSpec(new WriteIntentSpec(
                artifactId, meetingId, "transcript.final", "application/json",
                "zh-CN", 1, 0, CHECKSUM));
    }

    private static PGSimpleDataSource postgres(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) source.setUser(user);
        if (password != null && !password.isBlank()) source.setPassword(password);
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String name) throws Exception {
        if (!"V001__initial_schema.sql".equals(name)) {
            return "SELECT 1";
        }
        Path[] candidates = {Path.of("..", "deploy", "migrations", name), Path.of("deploy", "migrations", name)};
        for (Path candidate : candidates) if (Files.exists(candidate)) return Files.readString(candidate);
        throw new IllegalStateException("migration file not found");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
