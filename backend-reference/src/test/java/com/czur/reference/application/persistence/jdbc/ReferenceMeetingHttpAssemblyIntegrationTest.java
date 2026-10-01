package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingJdkHttpService;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.HttpRequest;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.CreateMeetingFactStore;
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
import com.czur.reference.domain.capability.CapabilityDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每个用例使用独立 schema；测试命令须注入一次性 PostgreSQL 16 实例。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class ReferenceMeetingHttpAssemblyIntegrationTest {
    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
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
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void internalHealthUsesSameJdbcAssemblyAcrossDatabaseOutageAndRecovery()
            throws Exception {
        assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(config(), CLOCK),
                "unmigrated database cannot create a ready HTTP assembly");
        migrate(13);
        String containerId = System.getenv("T15_HEALTH_GATE_CONTAINER_ID");
        String runId = System.getenv("T15_HEALTH_GATE_RUN_ID");
        assertTrue(containerId.matches("[0-9a-f]{64}"));
        assertTrue(runId.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+"));
        assertEquals(containerId + "|" + runId, dockerInspect(containerId),
                "only this gate's labelled container may be interrupted");
        try (var assembly = ReferenceMeetingHttpAssembly.start(config(), CLOCK);
             var server = assembly.newJdkHttpServer(new InetSocketAddress(
                     InetAddress.getByName("127.0.0.1"), 0))) {
            server.start();
            int port = server.address().getPort();
            String live = exchange(port, "GET", MeetingJdkHttpServer.LIVENESS_PATH, "");
            String ready = exchange(port, "GET", MeetingJdkHttpServer.READINESS_PATH, "");
            assertEquals(200, status(live));
            assertEquals(200, status(ready));
            assertTrue(live.contains("{\"status\":\"UP\"}"));
            assertTrue(ready.contains("{\"status\":\"UP\"}"));
            assertFalse(ready.contains(url()));
            assertFalse(ready.contains(password()));
            assertFalse(ready.contains(TOKEN));

            assertEquals(405, status(exchange(port, "POST",
                    MeetingJdkHttpServer.READINESS_PATH, "")));
            assertEquals(405, status(exchange(port, "HEAD",
                    MeetingJdkHttpServer.LIVENESS_PATH, "")));
            assertEquals(400, status(exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH + "?probe=1", "")));
            assertEquals(400, status(exchange(port, "GET",
                    MeetingJdkHttpServer.LIVENESS_PATH, "unexpected")));
            for (String path : List.of("/internal/health/readiness/",
                    "/internal/health/readiness-extra", "/internal/health/liveness-extra")) {
                assertEquals(404, status(exchange(port, "GET", path, "")), path);
            }
            assertEquals(401, status(exchange(port, "GET", "/v1/meetings", "")));
            assertEquals(501, status(exchange(port, "POST", "/v1/meetings", "{}")));
            assertEquals(0, count("meeting"));
            assertEquals(0, count("meeting_create_fact"));
            assertEquals(0, count("usage_reservation"));
            assertEquals(0, count("workflow_step"));
            assertEquals(13, count("schema_migrations"));

            boolean stopped = false;
            try {
                assertEquals(containerId + "|" + runId, dockerInspect(containerId));
                assertTrue(url().contains(dockerOutput("port", containerId, "5432/tcp")));
                docker("stop", "--time", "10", containerId);
                stopped = true;
                String unavailable = exchange(port, "GET",
                        MeetingJdkHttpServer.READINESS_PATH, "");
                assertEquals(503, status(unavailable));
                assertTrue(unavailable.contains("{\"status\":\"DOWN\"}"));
                assertFalse(unavailable.contains(url()));
                assertFalse(unavailable.contains(password()));
                assertEquals(200, status(exchange(port, "GET",
                        MeetingJdkHttpServer.LIVENESS_PATH, "")));
            } finally {
                if (stopped) {
                    docker("start", containerId);
                    assertEquals(containerId + "|" + runId, dockerInspect(containerId));
                    assertTrue(url().contains(dockerOutput("port", containerId, "5432/tcp")),
                            "container restart must preserve the bound random host port");
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            int recovered;
            do {
                recovered = status(exchange(port, "GET",
                        MeetingJdkHttpServer.READINESS_PATH, ""));
                if (recovered == 200) break;
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
            assertEquals(200, recovered, "readiness must recover without reassembly");
            assertEquals(0, count("meeting"));
            assertEquals(0, count("meeting_create_fact"));
            assertEquals(13, count("schema_migrations"));

            assembly.close();
            assertThrows(IllegalStateException.class,
                    () -> assembly.newJdkHttpServer(new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), 0)));
            assertEquals(503, status(exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH, "")));
            assertEquals(200, status(exchange(port, "GET",
                    MeetingJdkHttpServer.LIVENESS_PATH, "")),
                    "liveness only describes the still-accepting network entry");
            server.close();
            assertThrows(IOException.class, () -> exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH, ""));
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void pausedDatabaseReadinessUsesPgjdbcSocketBoundAndRecoversOnSamePool()
            throws Exception {
        migrate(13);
        String containerId = System.getenv("T15_HEALTH_GATE_CONTAINER_ID");
        String runId = System.getenv("T15_HEALTH_GATE_RUN_ID");
        assertTrue(containerId.matches("[0-9a-f]{64}"));
        assertTrue(runId.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+"));
        assertEquals(containerId + "|" + runId, dockerInspect(containerId));
        String applicationName = "t15_pause_" + schema;
        Map<String, String> settings = config();
        settings.put(DatabaseRuntimeConfig.DATABASE_URL,
                scopedUrl() + "&ApplicationName=" + applicationName);
        settings.put(DatabaseRuntimeConfig.DATABASE_SOCKET_TIMEOUT_SECONDS, "1");
        settings.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "5000");
        settings.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "4000");
        assertEquals(0, applicationConnections(applicationName));
        try (var assembly = ReferenceMeetingHttpAssembly.start(settings, CLOCK);
             var server = assembly.newJdkHttpServer(new InetSocketAddress(
                     InetAddress.getByName("127.0.0.1"), 0))) {
            assertEquals("1", ((HikariDataSource) assembly.databaseRuntime().dataSource())
                    .getDataSourceProperties().getProperty("socketTimeout"),
                    "the one assembly pool must receive the validated pgjdbc property");
            server.start();
            int port = server.address().getPort();
            assertEquals(200, status(exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH, "")));
            assertEquals(200, status(exchange(port, "GET",
                    MeetingJdkHttpServer.LIVENESS_PATH, "")));
            assertTrue(applicationConnections(applicationName) > 0);

            // 借出连接后再 pause；以下直接 JDBC 读不经过 Hikari 借出/校验。
            try (Connection borrowed = assembly.databaseRuntime().dataSource().getConnection();
                 PreparedStatement directProbe = borrowed.prepareStatement("SELECT 1")) {
                try (var row = directProbe.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(1, row.getInt(1));
                }
                ExecutorService client = Executors.newSingleThreadExecutor();
                boolean paused = false;
                try {
                    assertEquals(containerId + "|" + runId, dockerInspect(containerId));
                    assertTrue(url().contains(dockerOutput("port", containerId, "5432/tcp")));
                    docker("pause", containerId);
                    paused = true;
                    assertEquals("true", dockerOutput("container", "inspect", "--format",
                            "{{.State.Paused}}", containerId));

                    long directStarted = System.nanoTime();
                    Future<SQLException> directRead = client.submit(() -> {
                        try (var row = directProbe.executeQuery()) {
                            return null;
                        } catch (SQLException failure) {
                            return failure;
                        }
                    });
                    SQLException readFailure = assertInstanceOf(SQLException.class,
                            directRead.get(4, TimeUnit.SECONDS));
                    assertTrue(Duration.ofNanos(System.nanoTime() - directStarted)
                            .compareTo(Duration.ofSeconds(4)) < 0);
                    Throwable cause = readFailure;
                    while (cause != null && !(cause instanceof SocketTimeoutException)) {
                        cause = cause.getCause();
                    }
                    assertInstanceOf(SocketTimeoutException.class, cause,
                            "borrowed connection must fail on pgjdbc socket read timeout");

                    long started = System.nanoTime();
                    Future<String> readiness = client.submit(() -> exchange(port, "GET",
                            MeetingJdkHttpServer.READINESS_PATH, ""));
                    Thread.sleep(100);
                    assertFalse(readiness.isDone(), "paused socket read should still be in flight");
                    long liveStarted = System.nanoTime();
                    assertEquals(200, status(exchange(port, "GET",
                            MeetingJdkHttpServer.LIVENESS_PATH, "")));
                    assertTrue(Duration.ofNanos(System.nanoTime() - liveStarted)
                            .compareTo(Duration.ofSeconds(2)) < 0,
                            "liveness must not wait for the blocked database read");
                    String unavailable = readiness.get(6, TimeUnit.SECONDS);
                    assertEquals(503, status(unavailable),
                            "readiness must return a real HTTP 503, not just time out the client");
                    assertTrue(Duration.ofNanos(System.nanoTime() - started)
                            .compareTo(Duration.ofSeconds(6)) < 0);
                    assertTrue(unavailable.contains("{\"status\":\"DOWN\"}"));
                    assertFalse(unavailable.contains(url()));
                    assertFalse(unavailable.contains(password()));
                    assertFalse(unavailable.contains(TOKEN));
                } finally {
                    try {
                        if (paused) {
                            docker("unpause", containerId);
                            assertEquals("false", dockerOutput("container", "inspect", "--format",
                                    "{{.State.Paused}}", containerId));
                        }
                    } finally {
                        client.shutdownNow();
                        assertTrue(client.awaitTermination(12, TimeUnit.SECONDS));
                    }
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            int recovered;
            do {
                recovered = status(exchange(port, "GET",
                        MeetingJdkHttpServer.READINESS_PATH, ""));
                if (recovered == 200) break;
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
            assertEquals(200, recovered, "the same assembly and pool must recover after unpause");
            assertEquals(13, count("schema_migrations"));
            assertEquals(0, count("meeting"));
            var closed = server.shutdown(Duration.ofSeconds(3));
            assertTrue(closed.clean());
            assertEquals(0, closed.liveWorkers());
            assembly.close();
            assertFalse(assembly.databaseRuntime().isLive());
            assertThrows(IOException.class, () -> exchange(port, "GET",
                    MeetingJdkHttpServer.LIVENESS_PATH, ""));
        }
        awaitNoApplicationConnections(applicationName);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void boundedNetworkOwnerCleansUniquePoolAfterBindFailureAndNormalStop()
            throws Exception {
        migrate(13);
        String applicationName = "t15_shutdown_" + schema;
        Map<String, String> settings = config();
        settings.put(DatabaseRuntimeConfig.DATABASE_URL,
                scopedUrl() + "&ApplicationName=" + applicationName);
        assertEquals(0, applicationConnections(applicationName));
        try (ServerSocket occupied = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            assertThrows(IOException.class, () -> ReferenceMeetingJdkHttpService.start(
                    settings, CLOCK, new InetSocketAddress("127.0.0.1",
                            occupied.getLocalPort())));
        }
        awaitNoApplicationConnections(applicationName);

        try (var service = ReferenceMeetingJdkHttpService.start(settings, CLOCK,
                new InetSocketAddress("127.0.0.1", 0))) {
            int port = service.address().getPort();
            assertEquals(200, status(exchange(port, "GET",
                    MeetingJdkHttpServer.LIVENESS_PATH, "")));
            assertTrue(applicationConnections(applicationName) > 0,
                    "the one assembly must hold a live JDBC pool while serving");
            var closed = service.shutdown(Duration.ofSeconds(3));
            assertTrue(closed.clean());
            assertTrue(closed.network().clean());
            assertTrue(closed.resourcesClosed());
            assertEquals(closed, service.shutdown(Duration.ZERO));
            assertThrows(IOException.class, () -> exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH, ""));
        }
        awaitNoApplicationConnections(applicationName);
        assertEquals(0, count("meeting"));
        assertEquals(0, count("meeting_create_fact"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void realHttpCreateAndTrustedV2StartPreserveAsrOnlyFacts() throws Exception {
        String applicationName = "t15_create_http_" + schema;
        Map<String, String> settings = config();
        settings.put(DatabaseRuntimeConfig.DATABASE_URL,
                scopedUrl() + "&ApplicationName=" + applicationName);
        int candidatePort;
        try (ServerSocket reserved = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            candidatePort = reserved.getLocalPort();
        }
        assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingJdkHttpService.start(settings, CLOCK,
                        new InetSocketAddress("127.0.0.1", candidatePort)));
        awaitNoApplicationConnections(applicationName);
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress("127.0.0.1", candidatePort));
        }

        migrate(13);
        try (var disabled = ReferenceMeetingHttpAssembly.start(settings, CLOCK);
             var network = disabled.newJdkHttpServer(
                     new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            assertNetworkError(exchange(network.address().getPort(), "POST",
                    "/v1/meetings", "not JSON", ""), 501, "ROUTE_NOT_IMPLEMENTED");
            assertEquals(0, count("meeting"));
            assertEquals(0, count("meeting_create_fact"));
            assertEquals(0, count("usage_reservation"));
            assertEquals(0, count("workflow_step"));
        }
        awaitNoApplicationConnections(applicationName);

        Map<String, String> enabled = new HashMap<>(settings);
        enabled.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        var trusted = ReferenceMeetingDeploymentConfig.fromEnvironment(enabled);
        String key = "network-create-key-123456789";
        String clientSession = "client-session-secret-sentinel";
        StringBuilder logs = new StringBuilder();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                synchronized (logs) {
                    logs.append(formatter.formatMessage(record)).append('\n');
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK);
             var network = assembly.newJdkHttpServer(
                     new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String missingAuth = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, clientSession).body(), "Content-Type: application/json\r\n"
                            + "Idempotency-Key: " + key + "\r\n");
            assertNetworkError(missingAuth, 401, "AUTHENTICATION_REQUIRED");
            String invalidToken = "invalid-token-0123456789-abcdefgh";
            String rejectedToken = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, clientSession).body(),
                    networkHeaders(invalidToken, key, true));
            assertNetworkError(rejectedToken, 401, "AUTHENTICATION_FAILED");
            String badJson = exchange(port, "POST", "/v1/meetings", "not JSON",
                    networkHeaders(TOKEN, "bad-json-key-123456789", true));
            assertNetworkError(badJson, 400, "INVALID_REQUEST");
            assertEquals(0, count("meeting"));

            String first = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, clientSession).body(), networkHeaders(TOKEN, key, true));
            assertEquals(201, status(first));
            assertTrue(responseBody(first).contains("\"state\":\"CREATED\""));
            String meetingId = createdMeetingId(assembly.databaseRuntime().dataSource(), key);
            assertTrue(responseBody(first).contains("\"meetingId\":\"" + meetingId + "\""));
            String replay = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, clientSession).body(), networkHeaders(TOKEN, key, true));
            assertEquals(201, status(replay));
            assertTrue(MessageDigest.isEqual(
                    responseBody(first).getBytes(StandardCharsets.UTF_8),
                    responseBody(replay).getBytes(StandardCharsets.UTF_8)),
                    "same-key Create replay must preserve public response bytes");
            String conflictingCreate = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, "different-session").body(),
                    networkHeaders(TOKEN, key, true));
            assertNetworkError(conflictingCreate, 409, "IDEMPOTENCY_CONFLICT");
            assertEquals(1, count("meeting"));
            assertEquals(1, count("meeting_create_fact"));
            assertEquals(1, count("usage_reservation"));
            assertEquals(1, count("usage_operation"));

            String path = "/v1/meetings/" + meetingId;
            String rejectedStop = exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, "direct-stop-key-123456789", false));
            assertNetworkError(rejectedStop, 409, "INVALID_MEETING_STATE");
            assertEquals("CREATED", meetingState(meetingId));
            assertEquals(0, count("finalization_execution_fact"));
            assertEquals(0, count("workflow_step"));
            assertEquals(200, status(exchange(port, "GET", path, "",
                    networkHeaders(TOKEN, null, false))));
            String ownerList = exchange(port, "GET", "/v1/meetings", "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(ownerList));
            assertTrue(responseBody(ownerList).contains(meetingId));

            CreateMeetingFactStore.Record createFact = createFact(
                    assembly.databaseRuntime().dataSource(), meetingId);
            assertEquals(Set.of("transcript.realtime"),
                    createFact.request().requestSnapshot().requestedCapabilities());
            assertEquals(Set.of("transcript.realtime"),
                    createFact.response().grantedCapabilities());
            assertEquals(1, createFact.response().capabilityDecisions().size());
            CapabilityDecision decision = createFact.response().capabilityDecisions().getFirst();
            assertEquals("transcript.realtime", decision.capability());
            assertEquals(CapabilityDecision.Action.ENABLE, decision.action());
            assertEquals(List.of("mock.transcript"), decision.affectedWorkflows());
            assertEquals(List.of("transcript.final"), decision.producedArtifacts());

            var started = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                    new JdbcMeetingStartExecutionUnitOfWork(
                            assembly.databaseRuntime().dataSource(), new CapabilityPlanner())
                            .startCompleteCreate(new MeetingStartExecutionUnitOfWork.StartRequest(
                                    SCOPE, SUBJECT, meetingId, NOW, null, trusted.profile(),
                                    Set.of("transcript.realtime"), trusted.mockCapabilities(),
                                    trusted.capabilityLimits())));
            assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, started.code());
            assertEquals(MeetingState.STARTING, started.meeting().state());
            FinalizationExecutionFact execution = executionFact(
                    assembly.databaseRuntime().dataSource(), meetingId);
            assertEquals(1, count("finalization_execution_fact"));
            assertEquals(createFact.response().capabilityDecisions(),
                    execution.capabilityPlan().decisions());
            assertEquals(List.of("mock.transcript"),
                    execution.capabilityPlan().decisions().getFirst().affectedWorkflows());
            assertEquals(List.of("transcript.final"),
                    execution.capabilityPlan().decisions().getFirst().producedArtifacts());

            Map<String, String> other = new HashMap<>(settings);
            String otherToken = "other-subject-token-0123456789-abcdefgh";
            other.put(ReferenceDevIdentityConfig.BEARER_TOKEN, otherToken);
            other.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, "other-subject");
            try (var foreign = ReferenceMeetingHttpAssembly.start(other, CLOCK);
                 var foreignNetwork = foreign.newJdkHttpServer(
                         new InetSocketAddress("127.0.0.1", 0))) {
                foreignNetwork.start();
                int foreignPort = foreignNetwork.address().getPort();
                assertNetworkError(exchange(foreignPort, "GET", path, "",
                        networkHeaders(otherToken, null, false)), 403, "ACCESS_DENIED");
                String foreignList = exchange(foreignPort, "GET", "/v1/meetings", "",
                        networkHeaders(otherToken, null, false));
                assertEquals(200, status(foreignList));
                assertFalse(responseBody(foreignList).contains(meetingId));
            }
            Map<String, String> noDelete = new HashMap<>(settings);
            String readOnlyToken = "read-only-token-0123456789-abcdefgh";
            noDelete.put(ReferenceDevIdentityConfig.BEARER_TOKEN, readOnlyToken);
            noDelete.put(ReferenceDevIdentityConfig.PERMISSIONS,
                    "meeting:create," + MeetingPermissions.STOP);
            try (var readOnly = ReferenceMeetingHttpAssembly.start(noDelete, CLOCK);
                 var readOnlyNetwork = readOnly.newJdkHttpServer(
                         new InetSocketAddress("127.0.0.1", 0))) {
                readOnlyNetwork.start();
                assertNetworkError(exchange(readOnlyNetwork.address().getPort(),
                        "DELETE", path, "",
                        networkHeaders(readOnlyToken, "delete-denied-key-123456789", false)),
                        403, "PERMISSION_DENIED");
            }
            assertEquals(0, count("meeting_deletion_plan"));
            assertEquals("STARTING", meetingState(meetingId));
            String invalidPath = exchange(port, "GET", path + "/unexpected", "",
                    networkHeaders(TOKEN, null, false));
            assertNetworkError(invalidPath, 404, "ROUTE_NOT_FOUND");
            for (String response : List.of(missingAuth, rejectedToken, badJson,
                    conflictingCreate, rejectedStop, invalidPath)) {
                assertFalse(response.contains(TOKEN));
                assertFalse(response.contains(invalidToken));
                assertFalse(response.contains(password()));
                assertFalse(response.contains(url()));
            }
            synchronized (logs) {
                String text = logs.toString();
                assertFalse(text.contains(TOKEN));
                assertFalse(text.contains(invalidToken));
                assertFalse(text.contains(otherToken));
                assertFalse(text.contains(readOnlyToken));
                assertFalse(text.contains(password()));
                assertFalse(text.contains(url()));
                assertFalse(text.contains(key));
                assertFalse(text.contains(clientSession));
            }
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
        } finally {
            root.removeHandler(capture);
        }
        awaitNoApplicationConnections(applicationName);
    }

    private int applicationConnections(String applicationName) throws Exception {
        try (Connection connection = admin();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")) {
            statement.setString(1, applicationName);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void awaitNoApplicationConnections(String applicationName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (applicationConnections(applicationName) != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, applicationConnections(applicationName),
                "failed start or clean stop must leave no JDBC pool connection");
    }

    private static String networkHeaders(String token, String key, boolean json) {
        StringBuilder result = new StringBuilder("Authorization: Bearer ").append(token)
                .append("\r\n");
        if (key != null) {
            result.append("Idempotency-Key: ").append(key).append("\r\n");
        }
        if (json) {
            result.append("Content-Type: application/json\r\n");
        }
        return result.toString();
    }

    private static String responseBody(String response) {
        int boundary = response.indexOf("\r\n\r\n");
        assertTrue(boundary >= 0, "HTTP response must contain a header/body boundary");
        return response.substring(boundary + 4);
    }

    private static void assertNetworkError(String response, int expectedStatus,
                                           String expectedCode) {
        assertEquals(expectedStatus, status(response));
        JsonNode root;
        try {
            root = ERROR_JSON.readTree(responseBody(response));
        } catch (JsonProcessingException invalid) {
            throw new AssertionError("HTTP error body must be JSON");
        }
        assertTrue(root != null && root.isObject(), "HTTP error root must be an object");
        assertEquals(2, root.size(), "HTTP error root must have two fields");
        assertTrue("0.1".equals(root.path("specVersion").textValue()),
                "HTTP error specVersion must be stable");
        JsonNode error = root.path("error");
        assertTrue(error.isObject(), "HTTP error payload must be an object");
        assertEquals(8, error.size(), "HTTP error payload must have eight fields");
        for (String field : List.of("domain", "code", "severity", "retryable",
                "retryAfterMs", "userAction", "messageKey", "diagnosticId")) {
            assertTrue(error.has(field), "HTTP error payload lacks a required field");
        }
        assertTrue(expectedCode.equals(error.path("code").textValue()),
                "HTTP error code must be stable");
        assertTrue(error.path("domain").isTextual(), "HTTP error domain must be text");
        assertTrue("FATAL".equals(error.path("severity").textValue()),
                "HTTP error severity must be stable");
        assertTrue(error.path("retryable").isBoolean(),
                "HTTP error retryable must be boolean");
        assertFalse(error.path("retryable").booleanValue(),
                "these HTTP request errors must not be retryable");
        assertTrue(error.path("retryAfterMs").isNull(),
                "non-retryable HTTP error must have null retryAfterMs");
        assertTrue(error.path("userAction").isTextual(),
                "HTTP error userAction must be text");
        assertTrue(error.path("messageKey").isTextual(),
                "HTTP error messageKey must be text");
        assertTrue(error.path("diagnosticId").isTextual(),
                "HTTP error diagnosticId must be text");
    }

    private static String createdMeetingId(DataSource source, String key) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meeting_id FROM meeting_create_fact WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String meetingId = rows.getString(1);
                assertFalse(rows.next());
                return meetingId;
            }
        }
    }

    private static CreateMeetingFactStore.Record createFact(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var result = new JdbcCreateMeetingFactStore(connection)
                    .findByMeetingId(meetingId).orElseThrow();
            connection.rollback();
            return result;
        }
    }

    private static FinalizationExecutionFact executionFact(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var result = new JdbcFinalizationExecutionFactStore(connection)
                    .find(meetingId).orElseThrow();
            connection.rollback();
            return result;
        }
    }

    private static String exchange(int port, String method, String path, String body)
            throws Exception {
        return exchange(port, method, path, body, "");
    }

    private static String exchange(
            int port, String method, String path, String body, String extraHeaders)
            throws Exception {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String head = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + extraHeaders + "Content-Length: " + content.length
                + "\r\nConnection: close\r\n\r\n";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(content);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(received);
            return received.toString(StandardCharsets.UTF_8);
        }
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(0, response.indexOf("\r\n"))
                .split(" ")[1]);
    }

    private static void docker(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "docker";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("isolated PostgreSQL container operation timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("isolated PostgreSQL container operation failed");
        }
    }

    private static String dockerInspect(String containerId) throws Exception {
        return dockerOutput("container", "inspect", "--format",
                "{{.Id}}|{{index .Config.Labels \"com.czur.ai-meeting.t15-health.run-id\"}}",
                containerId);
    }

    private static String dockerOutput(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "docker";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new AssertionError("isolated PostgreSQL container ownership check failed");
        }
        return output;
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
