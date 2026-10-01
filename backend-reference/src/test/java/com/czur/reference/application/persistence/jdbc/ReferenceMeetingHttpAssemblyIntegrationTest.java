package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.HttpRequest;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.capability.CapabilityPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每个用例使用独立 schema；测试命令须注入一次性 PostgreSQL 16 实例。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class ReferenceMeetingHttpAssemblyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TOKEN = "local-test-token-0123456789-abcdefgh";
    private static final String SCOPE = "assembly-deployment";
    private static final String SUBJECT = "assembly-subject";
    private String schema;
    private PGSimpleDataSource fixtureDataSource;

    @BeforeEach
    void createOwnSchema() throws Exception {
        schema = "assembly_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        fixtureDataSource = new PGSimpleDataSource();
        fixtureDataSource.setUrl(scopedUrl());
        fixtureDataSource.setUser(user());
        fixtureDataSource.setPassword(password());
    }

    @AfterEach
    void dropOwnSchema() throws Exception {
        if (schema != null) {
            try (Connection connection = admin(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    @Test
    void closedAssemblyUsesRealQueryStopAndT14DeletionPorts() throws Exception {
        migrate(13);
        createOwned("stop");
        createOwned("delete");
        startOwned("stop");
        cancelBeforeStart("delete");
        assertEquals("STARTING", meetingState("mtg_stop"));
        assertEquals(1, count("finalization_execution_fact"));
        assertEquals("CANCELLED", meetingState("mtg_delete"));
        assertEquals(1, count("meeting WHERE meeting_id='mtg_delete' "
                + "AND manifest_state='NOT_STARTED' AND input_state='INPUT_OPEN'"));
        assertEquals(0, count("meeting_manifest_revision"));

        try (var assembly = ReferenceMeetingHttpAssembly.start(config(), CLOCK)) {
            assertTrue(assembly.databaseRuntime().isLive());
            assertTrue(assembly.runtime().isLive());
            var read = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                    assembly.contextResolver().forRead(request("GET", "/v1/meetings/mtg_stop")));
            assertEquals(SCOPE, read.context().deploymentScope());
            assertEquals(200, assembly.runtime().handle(
                    request("GET", "/v1/meetings/mtg_stop"), read.context()).status());
            assertEquals(200, assembly.runtime().handle(
                    request("GET", "/v1/meetings"), read.context()).status());

            var stop = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                    headers("stop-idempotency-123456789"), "");
            assertEquals(202, assembly.runtime().handle(stop, null).status());
            assertEquals(202, assembly.runtime().handle(stop, null).status());
            assertEquals("STOPPING", meetingState("mtg_stop"));

            var deletion = new HttpRequest("DELETE", "/v1/meetings/mtg_delete",
                    headers("delete-idempotency-123456789"), "");
            var deleteContext = assertInstanceOf(
                    AuthenticatedMeetingHttpContextResolver.Ready.class,
                    assembly.contextResolver().forDelete(deletion));
            assertEquals(202, assembly.runtime().handle(
                    deletion, deleteContext.context()).status());
            assertEquals("DELETION_PENDING", meetingState("mtg_delete"));
            assertEquals(1, count("meeting_deletion_plan"));
            assertEquals(1, count("meeting_deletion_plan WHERE target_count=0"));
            assertEquals(1, count("workflow_step"));

            // 任何正文、凭据和幂等头均不能到达 Create 身份、权益、V013 或会话分配。
            for (String body : List.of("", "not JSON", "{}", "{\"secret\":\"x\"}")) {
                for (Map<String, String> auth : List.of(
                        Map.<String, String>of(), headers(null),
                        Map.of("authorization", "Bearer invalid",
                                "idempotency-key", "bad"))) {
                    var create = new HttpRequest("POST", "/v1/meetings", auth, body);
                    var response = assembly.runtime().handle(create, null);
                    assertEquals(501, response.status());
                    assertTrue(response.body().contains("ROUTE_NOT_IMPLEMENTED"));
                }
            }
            assertEquals(0, count("meeting_create_fact"));
            assertEquals(2, count("usage_reservation"));
            assertEquals(2, count("usage_operation"));
            assertEquals(2, count("meeting"));
            assembly.close();
            assembly.close();
            assertFalse(assembly.databaseRuntime().isLive());
            assertFalse(assembly.runtime().isLive());
            assertEquals(503, assembly.runtime().handle(
                    request("GET", "/v1/meetings/mtg_stop"), read.context()).status());
        }
    }

    @Test
    void missingV013AndInvalidPhysicalFactRejectStartup() throws Exception {
        migrate(12);
        var missing = assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(config(), CLOCK));
        assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                missing.readiness().status());
        migrate(13, 13);
        try (Connection connection = fixtureDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE meeting_create_fact");
        }
        var broken = assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(config(), CLOCK));
        assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                broken.readiness().status());
    }

    @Test
    void disabledOrInvalidConfigNeverOpensPoolAndUnavailableDatabaseDoesNotFallback()
            throws Exception {
        Map<String, String> enabled = enabledConfig();
        enabled.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://127.0.0.1:1/unreachable");
        // 开态也必须通过同一数据库 readiness；不能为 Create 回退内存事实源。
        assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(enabled, CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingHttpAssembly.start(invalidRequiredCapability(
                        ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES), CLOCK));
        enabled.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "invalid");
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingHttpAssembly.start(enabled, CLOCK));
        Map<String, String> unreachable = config();
        unreachable.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://127.0.0.1:1/unreachable");
        assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(unreachable, CLOCK));
    }

    @Test
    void enabledCreateUsesV013ForFirstReplayConflictAndRestart() throws Exception {
        migrate(13);
        var request = createRequest("assembly-create-key-12345", "client-session-first");
        String firstBody;
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK)) {
            var first = assembly.runtime().handle(request, null);
            assertEquals(201, first.status());
            assertTrue(first.body().contains("\"state\":\"CREATED\""));
            assertTrue(first.body().contains("wss://meeting.example.invalid/v1/realtime/"));
            firstBody = first.body();
            var replay = assembly.runtime().handle(request, null);
            assertEquals(201, replay.status());
            assertEquals(firstBody, replay.body());
            assertEquals(409, assembly.runtime().handle(createRequest(
                    "assembly-create-key-12345", "different-client-session"), null).status());
            assertEquals(1, count("meeting"));
            assertEquals(1, count("meeting_create_fact"));
            assertEquals(1, count("usage_reservation"));
            assertEquals(1, count("usage_operation"));
            assertEquals(1, count("outbox_event"));
        }
        // 新装配会生成另一随机源和空会话缓存；公开事实仍由 V013 精确重放。
        try (var restarted = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK)) {
            var replay = restarted.runtime().handle(request, null);
            assertEquals(201, replay.status());
            assertEquals(firstBody, replay.body());
            assertEquals(1, count("meeting_create_fact"));
            assertEquals(1, count("usage_reservation"));
        }
    }

    @Test
    void enabledCreateRejectsClientProfileCapabilityAndDeploymentOverrides() throws Exception {
        migrate(13);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK)) {
            var base = createRequest("override-create-key-12345", "client-session-override");
            assertEquals(503, assembly.runtime().handle(new HttpRequest("POST", "/v1/meetings",
                    base.headers(), base.body().replace("assembly-profile", "other-profile")),
                    null).status());
            assertEquals(422, assembly.runtime().handle(new HttpRequest("POST", "/v1/meetings",
                    base.headers(), base.body().replace(
                            "[\"transcript.realtime\"]", "[\"summary.structured\"]")),
                    null).status());
            assertEquals(400, assembly.runtime().handle(new HttpRequest("POST", "/v1/meetings",
                    base.headers(), base.body().replace("\"clientSessionId\":",
                            "\"deploymentScope\":\"other\",\"clientSessionId\":")),
                    null).status());
            assertEquals(0, count("meeting"));
            assertEquals(0, count("usage_reservation"));
            assertEquals(0, count("meeting_create_fact"));
        }
    }

    @Test
    void createJdbcFailureMapsToStableRedactedError() throws Exception {
        migrate(13);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK)) {
            try (Connection connection = fixtureDataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE meeting_create_fact");
            }
            var result = assembly.runtime().handle(
                    createRequest("database-failure-key-12345", "client-session-db"), null);
            assertEquals(503, result.status());
            assertTrue(result.body().contains("CREATE_DEPENDENCY_UNAVAILABLE"));
            assertFalse(result.body().contains("meeting_create_fact"));
            assertFalse(result.body().contains(TOKEN));
            assertEquals(0, count("meeting"));
        }
    }

    @Test
    void requiredAsrBreaksFailBeforeOpeningDatabase() {
        for (String field : List.of(
                ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES)) {
            Map<String, String> invalid = invalidRequiredCapability(field);
            invalid.put(DatabaseRuntimeConfig.DATABASE_URL,
                    "jdbc:postgresql://127.0.0.1:1/unreachable");
            assertThrows(IllegalArgumentException.class,
                    () -> ReferenceMeetingHttpAssembly.start(invalid, CLOCK), field);
        }
    }

    private Map<String, String> enabledConfig() {
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        return enabled;
    }

    private Map<String, String> invalidRequiredCapability(String field) {
        Map<String, String> invalid = enabledConfig();
        if (ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES.equals(field)) {
            invalid.put(field, "transcript.realtime");
        } else {
            invalid.put(field, "summary.structured");
        }
        return invalid;
    }

    private HttpRequest createRequest(String key, String clientSessionId) {
        Map<String, String> createHeaders = headers(key);
        createHeaders.put("content-type", "application/json");
        String body = """
                {"clientSessionId":"%s","profile":{"id":"assembly-profile","revision":1},
                 "client":{"appId":"app","appVersion":"1","contractVersion":"v1","deviceId":"device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":false}}
                """.formatted(clientSessionId);
        return new HttpRequest("POST", "/v1/meetings", createHeaders, body);
    }

    private void createOwned(String suffix) throws Exception {
        String meetingId = "mtg_" + suffix;
        Instant created = NOW.minusSeconds(120);
        MeetingGrant grant = new MeetingGrant("grant-" + suffix, SCOPE, SUBJECT,
                "assembly-profile", 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 3_600_000,
                created.minusSeconds(20), NOW.plusSeconds(600));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix, grant, meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                created.minusSeconds(10), NOW.plusSeconds(500));
        UsageOperationResult reserve = UsageOperationResult.reserve(SCOPE, SUBJECT,
                "reserve-key-" + suffix, "reserve-digest-" + suffix,
                NOW.plusSeconds(600), AuthorizedProfileSnapshot.from(grant), reservation);
        try (Connection connection = fixtureDataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, created.minusSeconds(10)));
            connection.commit();
        }
        var createdResult = new JdbcOwnedMeetingCreationUnitOfWork(
                fixtureDataSource, Clock.fixed(created, ZoneOffset.UTC)).create(
                        new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                SCOPE, SUBJECT, meetingId, "session-" + suffix,
                                "create-key-" + suffix, "create-digest-" + suffix, reserve));
        assertInstanceOf(OwnedMeetingCreationUnitOfWork.Success.class, createdResult);
        assertEquals("CREATED", meetingState(meetingId));
    }

    private void startOwned(String suffix) {
        var trusted = ReferenceMeetingDeploymentConfig.fromEnvironment(config());
        var started = new JdbcMeetingStartExecutionUnitOfWork(
                fixtureDataSource, new CapabilityPlanner()).start(
                        new MeetingStartExecutionUnitOfWork.StartRequest(
                                SCOPE, SUBJECT, "mtg_" + suffix, NOW.minusSeconds(60),
                                "Assembly gate", trusted.profile(),
                                Set.of("transcript.realtime"),
                                trusted.mockCapabilities(), trusted.capabilityLimits()));
        assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class, started);
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                ((MeetingStartExecutionUnitOfWork.Success) started).code());
    }

    private void cancelBeforeStart(String suffix) throws Exception {
        String meetingId = "mtg_" + suffix;
        try (Connection connection = fixtureDataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            assertEquals(MeetingState.CANCELLED,
                    aggregate.apply(MeetingEvent.CANCEL, NOW.minusSeconds(119)));
            JdbcMeetingRepository.updateOwnedExisting(connection, locked, aggregate.snapshot());
            connection.commit();
        }
    }

    private void migrate(int count) throws Exception { migrate(1, count); }

    private void migrate(int first, int last) throws Exception {
        for (int version = first; version <= last; version++) {
            String filename = String.format("V%03d__", version);
            Path directory = Path.of("deploy/migrations");
            if (!Files.isDirectory(directory)) {
                directory = Path.of("../deploy/migrations");
            }
            final String prefix = filename;
            Path migration;
            try (var paths = Files.list(directory)) {
                migration = paths.filter(path -> path.getFileName().toString().startsWith(prefix))
                        .findFirst().orElseThrow();
            }
            try (Connection connection = fixtureDataSource.getConnection();
                 Statement statement = connection.createStatement();
                 PreparedStatement registration = connection.prepareStatement(
                         "INSERT INTO schema_migrations(version) VALUES (?)")) {
                statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
                registration.setString(1, migration.getFileName().toString().replace(".sql", ""));
                assertEquals(1, registration.executeUpdate());
            }
        }
    }

    private Map<String, String> config() {
        Map<String, String> values = new HashMap<>();
        values.put(DatabaseRuntimeConfig.DATABASE_URL, scopedUrl());
        values.put(DatabaseRuntimeConfig.DATABASE_USER, user());
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, password());
        values.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "1000");
        values.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "1000");
        values.put(ReferenceDevIdentityConfig.ENABLED, "true");
        values.put(ReferenceDevIdentityConfig.BEARER_TOKEN, TOKEN);
        values.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, SUBJECT);
        values.put(ReferenceDevIdentityConfig.ROLES, "USER");
        values.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create," + MeetingPermissions.STOP + "," + MeetingPermissions.DELETE);
        values.put(ReferenceDevIdentityConfig.AUDIENCE, "assembly-audience");
        values.put(ReferenceDevIdentityConfig.CLIENT_ID, "assembly-client");
        values.put(ReferenceDevIdentityConfig.ISSUED_AT, "2026-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2027-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.CREDENTIAL_ID, "assembly-credential");
        values.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, SCOPE);
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_ID, "assembly-profile");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REVISION, "1");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_MEETING_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "32000");
        values.put(ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW, "100");
        values.put(ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET,
                "assembly-page-secret-0123456789-abcdefgh");
        values.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN,
                "wss://meeting.example.invalid");
        values.put(ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        return values;
    }

    private HttpRequest request(String method, String path) {
        return new HttpRequest(method, path, headers(null), "");
    }

    private Map<String, String> headers(String key) {
        Map<String, String> result = new HashMap<>();
        result.put("authorization", "Bearer " + TOKEN);
        if (key != null) result.put("idempotency-key", key);
        return result;
    }

    private String meetingState(String meetingId) throws Exception {
        try (Connection connection = fixtureDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM meeting WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private int count(String table) throws Exception {
        try (Connection connection = fixtureDataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(url(), user(), password());
    }

    private String scopedUrl() {
        return url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String url() { return System.getenv("REFERENCE_TEST_DATABASE_URL"); }
    private static String user() { return System.getenv("REFERENCE_TEST_DATABASE_USER"); }
    private static String password() { return System.getenv("REFERENCE_TEST_DATABASE_PASSWORD"); }
}
