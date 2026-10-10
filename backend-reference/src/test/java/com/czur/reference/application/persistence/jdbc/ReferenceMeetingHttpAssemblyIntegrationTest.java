package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingJdkHttpService;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.model.BailianSummarySessionConfig;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.HttpRequest;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedMeetingStopResult;
import com.czur.reference.application.command.StopMeetingIntent;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.identity.referencedev.ReferenceDevLoginConfig;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        private MutableClock(Instant now, ZoneId zone) {
            this(new AtomicReference<>(now), zone);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advanceTo(Instant instant) {
            now.updateAndGet(current -> {
                if (instant.isBefore(current)) {
                    throw new IllegalArgumentException("test clock cannot move backwards");
                }
                return instant;
            });
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }

    private String schema;
    private PGSimpleDataSource fixtureDataSource;

    @Test
    void preconfiguredLoginTokenCanCreateAndStartClientMeetingOverHttp(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> settings = enabledConfig();
        settings.put(ReferenceDevLoginConfig.USERNAME, "meeting-user");
        settings.put(ReferenceDevLoginConfig.PASSWORD, "local-password");
        try (var assembly = ReferenceMeetingHttpAssembly.start(settings, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(
                     new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String login = exchange(port, "POST", ReferenceDevSessionLoginEndpoint.PATH,
                    "{\"username\":\"meeting-user\",\"password\":\"local-password\"}",
                    "Content-Type: application/json\r\n");
            assertEquals(200, status(login));
            JsonNode session = ERROR_JSON.readTree(responseBody(login));
            String issuedToken = session.path("accessToken").asText();
            assertEquals(TOKEN, issuedToken);
            assertEquals("2027-01-01T00:00:00Z", session.path("expiresAt").asText());

            String key = "login-create-key-123456789";
            String create = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, "login-session").body(),
                    networkHeaders(issuedToken, key, true));
            assertEquals(201, status(create));
            String meetingId = ERROR_JSON.readTree(responseBody(create))
                    .path("meetingId").asText();
            String start = exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":startClient", "", networkHeaders(issuedToken, null, false));
            assertEquals(200, status(start));
            assertEquals("STARTING", ERROR_JSON.readTree(responseBody(start))
                    .path("state").asText());
            assertEquals("STARTING", meetingState(meetingId));
            completeClientTranscriptOverHttp(port, meetingId, issuedToken,
                    "login-stop-key-123456789");
            assertEquals("READY", meetingState(meetingId));
            assertEquals(1, count("meeting_client_result"));
            // 旧客户端没有采音事实时仍可显示结果，不生成实测0的结算。
            assertEquals(1, count("usage_operation"));
            assertEquals(0, countCommittedUsage());
            assertEquals(1000, clientManifest(meetingId).durationMs());
        }
    }

    @Test
    void clientCapturedUsageSettlesOnceAndIgnoresSummaryWaiting(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), clock,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String meetingId = createAndStartClient(port, "captured-usage");
            appendClientTranscriptOverHttp(port, meetingId, TOKEN);
            long originalVersion = reservationVersion(meetingId);
            String usagePath = "/v1/meetings/" + meetingId + "/usage:submit";
            String usage = contractFixture("client-input-usage.valid.json");
            // 正负Fixture都通过正式HTTP解码边界，不另建一套Schema验证器。
            assertEquals(400, status(exchange(port, "POST", usagePath,
                    contractFixture("client-input-usage.negative.invalid.json"),
                    networkHeaders(TOKEN, null, true))));
            String submitted = exchange(port, "POST", usagePath, usage,
                    networkHeaders(TOKEN, null, true));
            assertEquals(201, status(submitted));
            assertEquals(1000, ERROR_JSON.readTree(responseBody(submitted))
                    .path("audioDurationMs").asLong());
            assertEquals(200, status(exchange(port, "POST", usagePath, usage,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(409, status(exchange(port, "POST", usagePath,
                    "{\"audioDurationMs\":1001}", networkHeaders(TOKEN, null, true))));
            // 模型收口／总结即使等待两分钟，实际采音用量仍为1000毫秒。
            clock.advanceTo(NOW.plusSeconds(120));
            String stopPath = "/v1/meetings/" + meetingId + ":stop";
            String stopKey = "captured-usage-stop-key-123456789";
            String stopped = exchange(port, "POST", stopPath, "",
                    networkHeaders(TOKEN, stopKey, false));
            assertEquals(200, status(stopped));
            assertEquals("READY", meetingState(meetingId));
            assertEquals(1000, clientManifest(meetingId).durationMs());
            assertCommittedUsage(meetingId, 1000, originalVersion);
            String replay = exchange(port, "POST", stopPath, "",
                    networkHeaders(TOKEN, stopKey, false));
            assertEquals(200, status(replay));
            assertEquals(responseBody(stopped), responseBody(replay));
            assertEquals(200, status(exchange(port, "POST", usagePath, usage,
                    networkHeaders(TOKEN, null, true))));
            assertCommittedUsage(meetingId, 1000, originalVersion);
            assertEquals(1, countCommittedUsage());
            assertEquals(2, count("usage_operation")); // 首次reserve＋唯一commit。
            assertEquals(1, count("meeting_client_result"));
        }
    }

    @Test
    void measuredZeroClientUsageSettlesAndCreatesEmptyResult(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), clock,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String meetingId = createAndStartClient(port, "measured-zero");
            long originalVersion = reservationVersion(meetingId);
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/usage:submit", "{\"audioDurationMs\":0}",
                    networkHeaders(TOKEN, null, true))));
            var context = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                    assembly.contextResolver().forClientWrite(request("POST",
                            "/v1/meetings/" + meetingId + ":startClient"))).context();
            var beforeStop = assertInstanceOf(JdbcClientMeetingResultFinalizer.Ready.class,
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(
                            assembly.databaseRuntime().dataSource(), context, meetingId));
            assertEquals(List.of(), beforeStop.transcripts());
            assertEquals(List.of(), beforeStop.translations());
            assertEquals("und", beforeStop.language());
            assertEquals(0, beforeStop.frozenAudioDurationMs());
            assertFalse(beforeStop.retainAudioRequested());
            assertEquals("zh-CN", beforeStop.sourceLanguage());
            assertEquals(null, beforeStop.title());
            assertEquals("STARTING", meetingState(meetingId));
            assertEquals(0, countCommittedUsage());
            assertEquals(0, count("artifact"));
            assertEquals(0, count("meeting_client_result"));
            clock.advanceTo(NOW.plusSeconds(45));
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":stop", "{}", networkHeaders(TOKEN,
                    "measured-zero-stop-key-123456789", true))));
            assertEquals("READY", meetingState(meetingId));
            assertCommittedUsage(meetingId, 0, originalVersion);
            assertEquals(1, countCommittedUsage());
            assertEquals(0, clientManifest(meetingId).durationMs());
            assertEquals(List.of(), clientManifest(meetingId).artifacts());
            assertEquals(beforeStop, JdbcClientMeetingResultFinalizer.readFinalSnapshot(
                    assembly.databaseRuntime().dataSource(), context, meetingId));
        }
    }

    @Test
    void finalSnapshotRequiresClientSourceAndFrozenUsageAndRejectsOtherOwner(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String key = "snapshot-gate-create-key-123456789";
            String created = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, "snapshot-gate").body(), networkHeaders(TOKEN, key, true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created)).path("meetingId").asText();
            var context = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                    assembly.contextResolver().forClientWrite(request("POST",
                            "/v1/meetings/" + meetingId + ":startClient"))).context();
            DataSource source = assembly.databaseRuntime().dataSource();
            assertEquals(new JdbcClientMeetingResultFinalizer.Rejected(
                    ClientMeetingInputPort.Reason.NOT_FOUND),
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(source, context, "mtg_missing"));
            assertEquals(new JdbcClientMeetingResultFinalizer.Rejected(
                    ClientMeetingInputPort.Reason.INPUT_SOURCE_CONFLICT),
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(source, context, meetingId));
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":startClient", "", networkHeaders(TOKEN, null, false))));
            // 未冻结用量不是实测 0；只有真实 submitUsage 后才能取得 Ready。
            assertEquals(new JdbcClientMeetingResultFinalizer.Rejected(
                    ClientMeetingInputPort.Reason.INVALID_STATE),
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(source, context, meetingId));
            VerifiedIdentity owner = context.identity();
            var other = new MeetingCommandContext(context.deploymentScope(),
                    new VerifiedIdentity(new Subject("another-snapshot-owner"), owner.roles(),
                            owner.permissions(), owner.issuedAt(), owner.expiresAt(), null));
            assertEquals(new JdbcClientMeetingResultFinalizer.Rejected(
                    ClientMeetingInputPort.Reason.ACCESS_DENIED),
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(source, other, meetingId));
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/events:append", """
                    {"clientEventId":"snapshot-partial","clientSequence":1,
                     "transcriptUpsert":{"segmentId":"unfinished","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":0,"endMs":500,
                     "language":"zh-CN","text":"尚未完成","final":false}}
                    """, networkHeaders(TOKEN, null, true))));
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/usage:submit", "{\"audioDurationMs\":500}",
                    networkHeaders(TOKEN, null, true))));
            var snapshot = assertInstanceOf(JdbcClientMeetingResultFinalizer.Ready.class,
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(source, context, meetingId));
            assertEquals(List.of(), snapshot.transcripts());
            assertEquals(List.of(), snapshot.translations());
            assertEquals("und", snapshot.language());
            assertEquals(500, snapshot.frozenAudioDurationMs());
            assertEquals("STARTING", meetingState(meetingId));
            assertEquals(1, count("meeting_client_event"));
            assertEquals(1, count("usage_operation"));
            assertEquals(0, countCommittedUsage());
            assertEquals(0, count("artifact"));
            assertEquals(0, count("meeting_client_result"));
        }
    }

    @Test
    void clientResultWaitsForSettlementAndRetriesTheSameFrozenRequest(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabledConfig(), CLOCK, artifacts);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String meetingId = createAndStartClient(port, "settlement-wait");
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/usage:submit", "{\"audioDurationMs\":1000}",
                    networkHeaders(TOKEN, null, true))));
            var requests = new ArrayList<CommitUsageRequest>();
            EntitlementProvider delegate = assembly.entitlementProvider();
            EntitlementProvider onceUnavailable = new EntitlementProvider() {
                @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                        AuthorizeStartRequest request) { return delegate.authorizeStart(request); }
                @Override public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                        ReserveUsageRequest request) { return delegate.reserveUsage(request); }
                @Override public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                        CancelReservationRequest request) {
                    return delegate.cancelReservation(request);
                }
                @Override public EntitlementProviderResult<UsageOperationResult> commitUsage(
                        CommitUsageRequest request) {
                    requests.add(request);
                    return requests.size() == 1 ? EntitlementProviderResult.failure(
                            new EntitlementProviderError(
                                    EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true))
                            : delegate.commitUsage(request);
                }
            };
            var finalizer = new JdbcClientMeetingResultFinalizer(fixtureDataSource,
                    () -> "receipt_" + UUID.randomUUID(), CLOCK, artifacts, onceUnavailable);
            assertTrue(finalizer.finalizeIfClient(meetingId));
            assertEquals(0, requests.size(), "尚未Stop时不得结算");
            // 同一个认证Stop服务每次均提交／重放原停止意图，再触发同一Finalizer。
            var stopService = new AuthenticatedMeetingStopApplicationService(SCOPE,
                    new ReferenceDevIdentityProvider(
                            ReferenceDevIdentityConfig.fromEnvironment(enabledConfig()), CLOCK),
                    new JdbcOwnedMeetingStopUnitOfWork(fixtureDataSource, CLOCK),
                    new JdbcMeetingQueryPort(fixtureDataSource,
                            "assembly-page-secret-0123456789-abcdefgh"
                                    .getBytes(StandardCharsets.UTF_8)),
                    null, finalizer);
            var stop = new StopMeetingIntent(meetingId,
                    "settlement-wait-stop-key-123456789");
            var first = assertInstanceOf(AuthenticatedMeetingStopResult.Success.class,
                    stopService.stop(clientStopVerification(), stop));
            assertEquals(MeetingState.STOPPING, first.meeting().state());
            assertEquals(202, assembly.errorMapper().stop(first).status());
            assertEquals("STOPPING", meetingState(meetingId));
            assertEquals(0, count("meeting_client_result"));
            assertEquals(0, countCommittedUsage());
            var replay = assertInstanceOf(AuthenticatedMeetingStopResult.Success.class,
                    stopService.stop(clientStopVerification(), stop));
            assertEquals(MeetingState.READY, replay.meeting().state());
            assertEquals(200, assembly.errorMapper().stop(replay).status());
            assertEquals("READY", meetingState(meetingId));
            assertEquals(2, requests.size());
            assertEquals(requests.get(0), requests.get(1));
            assertEquals(1, countCommittedUsage());
            assertCommittedUsage(meetingId, 1000, requests.get(0).expectedVersion());
        }
    }

    @Test
    void entitlementDecoratorIsSharedByCreateStopAndWorkerAccessor(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(enabledConfig());
        var identity = new ReferenceDevIdentityProvider(config.identity(), CLOCK);
        var policy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK,
                new EntitlementIdGenerator() {
                    @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                    @Override public String newReservationId() { return "reserve_" + UUID.randomUUID(); }
                    @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
                });
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger authorizeCalls = new AtomicInteger();
        AtomicInteger reserveCalls = new AtomicInteger();
        var commitRequests = new ArrayList<CommitUsageRequest>();
        AtomicReference<EntitlementProvider> shared = new AtomicReference<>();
        UnaryOperator<EntitlementProvider> factory = delegate -> {
            factoryCalls.incrementAndGet();
            assertInstanceOf(JdbcEntitlementProvider.class, delegate);
            EntitlementProvider decorated = new EntitlementProvider() {
                @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                        AuthorizeStartRequest request) {
                    authorizeCalls.incrementAndGet();
                    return delegate.authorizeStart(request);
                }
                @Override public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                        ReserveUsageRequest request) {
                    reserveCalls.incrementAndGet();
                    return delegate.reserveUsage(request);
                }
                @Override public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                        CancelReservationRequest request) {
                    return delegate.cancelReservation(request);
                }
                @Override public EntitlementProviderResult<UsageOperationResult> commitUsage(
                        CommitUsageRequest request) {
                    commitRequests.add(request);
                    var persisted = delegate.commitUsage(request);
                    assertTrue(persisted.isSuccess());
                    // 模拟PG已提交但部署方外部结算尚未确认，不能提前发布READY。
                    return commitRequests.size() == 1 ? EntitlementProviderResult.failure(
                            new EntitlementProviderError(
                                    EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true))
                            : persisted;
                }
            };
            shared.set(decorated);
            return decorated;
        };
        try (var assembly = ReferenceMeetingHttpAssembly.start(config, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024),
                identity, policy, null, factory);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            assertEquals(1, factoryCalls.get());
            assertSame(shared.get(), assembly.entitlementProvider());
            network.start();
            int port = network.address().getPort();
            String key = "decorator-create-key-123456789";
            String body = createRequest(key, "decorator-session").body();
            String created = exchange(port, "POST", "/v1/meetings", body,
                    networkHeaders(TOKEN, key, true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            String replay = exchange(port, "POST", "/v1/meetings", body,
                    networkHeaders(TOKEN, key, true));
            assertEquals(responseBody(created), responseBody(replay));
            assertEquals(1, authorizeCalls.get());
            assertEquals(1, reserveCalls.get());
            assertEquals(1, count("usage_reservation"));
            assertEquals(1, count("usage_operation"));
            long version = reservationVersion(meetingId);
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":startClient", "", networkHeaders(TOKEN, null, false))));
            appendClientTranscriptOverHttp(port, meetingId, TOKEN);
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/usage:submit", "{\"audioDurationMs\":1000}",
                    networkHeaders(TOKEN, null, true))));
            String stopPath = "/v1/meetings/" + meetingId + ":stop";
            String stopKey = "decorator-stop-key-123456789";
            assertEquals(202, status(exchange(port, "POST", stopPath, "",
                    networkHeaders(TOKEN, stopKey, false))));
            assertEquals("STOPPING", meetingState(meetingId));
            assertEquals(0, count("meeting_client_result"));
            assertEquals(1, commitRequests.size());
            assertCommittedUsage(meetingId, 1000, version);
            assertEquals(200, status(exchange(port, "POST", stopPath, "",
                    networkHeaders(TOKEN, stopKey, false))));
            assertEquals("READY", meetingState(meetingId));
            assertEquals(2, commitRequests.size());
            assertEquals(commitRequests.get(0), commitRequests.get(1));
            assertEquals(1, countCommittedUsage());
            assertEquals(2, count("usage_operation"));
            assertEquals(1, count("meeting_client_result"));
            // Worker接线只能取得同一装饰器；PG重放也继续经过部署方确认。
            assertTrue(assembly.entitlementProvider().commitUsage(
                    commitRequests.get(0)).isSuccess());
            assertEquals(3, commitRequests.size());
            assertEquals(1, factoryCalls.get());
            assertEquals(1, countCommittedUsage());
        }
    }

    @Test
    void entitlementDecoratorFailureClosesPoolAndRejectsNullResult() throws Exception {
        migrate(1);
        String applicationName = "hook_failure_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> settings = enabledConfig();
        settings.compute(DatabaseRuntimeConfig.DATABASE_URL,
                (key, value) -> value + "&ApplicationName=" + applicationName);
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(settings);
        var identity = new ReferenceDevIdentityProvider(config.identity(), CLOCK);
        EntitlementPolicy unusedPolicy = new EntitlementPolicy() {
            @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                    AuthorizeStartRequest request) { throw new AssertionError("不得调用资格策略"); }
            @Override public EntitlementProviderResult<UsageReservation> prepareReservation(
                    ReserveUsageRequest request) { throw new AssertionError("不得预占用量"); }
        };
        List<UnaryOperator<EntitlementProvider>> factories = List.of(
                delegate -> { throw new IllegalStateException("test decorator unavailable"); },
                delegate -> null);
        for (UnaryOperator<EntitlementProvider> factory : factories) {
            assertThrows(RuntimeException.class, () -> ReferenceMeetingHttpAssembly.start(
                    config, CLOCK, null, identity, unusedPolicy, null, factory));
            try (var connection = fixtureDataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT count(*) FROM pg_stat_activity WHERE application_name=?")) {
                statement.setString(1, applicationName);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "装饰工厂失败后不得遗留PG池连接");
                }
            }
            assertEquals(0, count("usage_operation"));
            assertEquals(0, count("meeting"));
        }
    }

    @Test
    void nullEntitlementDecoratorIsRejectedBeforeOpeningDatabase() {
        Map<String, String> settings = enabledConfig();
        settings.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://127.0.0.1:1/unreachable");
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(settings);
        var identity = new ReferenceDevIdentityProvider(config.identity(), CLOCK);
        EntitlementPolicy unusedPolicy = new EntitlementPolicy() {
            @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                    AuthorizeStartRequest request) { throw new AssertionError("不得调用资格策略"); }
            @Override public EntitlementProviderResult<UsageReservation> prepareReservation(
                    ReserveUsageRequest request) { throw new AssertionError("不得预占用量"); }
        };
        var failure = assertThrows(NullPointerException.class,
                () -> ReferenceMeetingHttpAssembly.start(
                        config, CLOCK, null, identity, unusedPolicy, null, null));
        assertEquals("entitlementProviderDecorator", failure.getMessage());
    }

    @Test
    void externalIdentityAndPolicySharePgCreateReplayAndClientStop(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> settings = enabledConfig();
        settings.keySet().removeIf(key -> key.startsWith("REFERENCE_DEV_"));
        var config = ReferenceMeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(
                settings, "external-meeting", "external-android");
        AtomicInteger identityCalls = new AtomicInteger();
        AtomicInteger authorizeCalls = new AtomicInteger();
        AtomicInteger prepareCalls = new AtomicInteger();
        String subject = "external-subject";
        // 仅测试部署注入边界；此替身不代替CZUR的真实登录／会员实现。
        IdentityProvider identity = request -> {
            identityCalls.incrementAndGet();
            assertEquals("external-meeting", request.audience());
            assertEquals("external-android", request.clientId());
            boolean matches = request.credential().useBytes(bytes ->
                    MessageDigest.isEqual(bytes, TOKEN.getBytes(StandardCharsets.US_ASCII)));
            if (!matches) {
                return IdentityProviderResult.failure(new IdentityProviderError(
                        IdentityProviderError.Code.INVALID_CREDENTIAL, false));
            }
            return IdentityProviderResult.success(new VerifiedIdentity(
                    new Subject(subject, null), Set.of("USER"),
                    Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP),
                    NOW.minusSeconds(60), NOW.plusSeconds(600), null));
        };
        EntitlementIdGenerator ids = new EntitlementIdGenerator() {
            @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
            @Override public String newReservationId() {
                return "reservation_" + UUID.randomUUID();
            }
            @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
        };
        var defaultPolicy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK, ids);
        EntitlementPolicy policy = new EntitlementPolicy() {
            @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                    AuthorizeStartRequest request) {
                authorizeCalls.incrementAndGet();
                assertEquals(subject, request.subject().subjectScope());
                return defaultPolicy.authorizeStart(request);
            }
            @Override public EntitlementProviderResult<UsageReservation> prepareReservation(
                    ReserveUsageRequest request) {
                prepareCalls.incrementAndGet();
                assertEquals(subject, request.subjectScope());
                return defaultPolicy.prepareReservation(request);
            }
        };
        try (var assembly = ReferenceMeetingHttpAssembly.start(config, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024),
                identity, policy);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            assertInstanceOf(JdbcEntitlementProvider.class,
                    assembly.entitlementProvider());
            String key = "external-create-key-123456789";
            String body = createRequest(key, "external-session").body();
            String created = exchange(port, "POST", "/v1/meetings", body,
                    networkHeaders(TOKEN, key, true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            String replay = exchange(port, "POST", "/v1/meetings", body,
                    networkHeaders(TOKEN, key, true));
            assertEquals(responseBody(created), responseBody(replay));
            assertEquals(1, authorizeCalls.get());
            assertEquals(1, prepareCalls.get());
            assertEquals(1, count("meeting"));
            assertEquals(1, count("usage_reservation"));
            assertEquals(1, count("usage_operation"));
            try (var connection = fixtureDataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT subject_scope FROM meeting WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(subject, rows.getString(1));
                }
            }
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":startClient", "", networkHeaders(TOKEN, null, false))));
            completeClientTranscriptOverHttp(port, meetingId, TOKEN,
                    "external-stop-key-123456789");
            assertEquals("READY", meetingState(meetingId));
            assertEquals(1, count("meeting_client_result"));
            assertEquals(200, status(exchange(port, "GET", "/v1/meetings/" + meetingId,
                    "", networkHeaders(TOKEN, null, false))));
            assertTrue(identityCalls.get() >= 6);
        }
    }

    @Test
    void externalModelIssuerReceivesOwnedMeetingAndFrozenLanguageDecisions() throws Exception {
        migrate(1);
        Map<String, String> settings = enabledConfig();
        settings.keySet().removeIf(key -> key.startsWith("REFERENCE_DEV_"));
        settings.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                "translation.realtime");
        settings.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime,translation.realtime");
        settings.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime,translation.realtime");
        var config = ReferenceMeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(
                settings, "external-model-meeting", "external-model-android");
        assertTrue(config.tingwuModelSession().isEmpty());
        assertTrue(config.sampleJsonWsEndpoint().isEmpty());
        IdentityProvider identity = request -> {
            assertEquals("external-model-meeting", request.audience());
            assertEquals("external-model-android", request.clientId());
            boolean matches = request.credential().useBytes(bytes ->
                    MessageDigest.isEqual(bytes, TOKEN.getBytes(StandardCharsets.US_ASCII)));
            assertTrue(matches);
            return IdentityProviderResult.success(new VerifiedIdentity(
                    new Subject("external-model-subject", null), Set.of("USER"),
                    Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP),
                    NOW.minusSeconds(60), NOW.plusSeconds(600), null));
        };
        EntitlementIdGenerator ids = new EntitlementIdGenerator() {
            @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
            @Override public String newReservationId() {
                return "reservation_" + UUID.randomUUID();
            }
            @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
        };
        var policy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK, ids);
        AtomicReference<ModelSessionIssuer.Request> issued = new AtomicReference<>();
        AtomicInteger issueCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        // 仅验证部署扩展与真实PG事实接线，不访问厂商或代签CZUR模型发行。
        class ExternalIssuer implements ModelSessionIssuer, AutoCloseable {
            @Override public Session issue(Request request) {
                issued.set(request);
                issueCalls.incrementAndGet();
                return new Session("sample-json-ws", "ws://model.example.invalid/external",
                        NOW.plusSeconds(300), Map.of("taskId", "external-test-task"));
            }
            @Override public void close() { closeCalls.incrementAndGet(); }
        }
        var issuer = new ExternalIssuer();
        try (var assembly = ReferenceMeetingHttpAssembly.start(
                config, CLOCK, null, identity, policy, issuer);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            for (boolean translation : List.of(false, true)) {
                String source = translation ? "zh-CN" : "en-US";
                String target = translation ? "en-US" : "zh-CN";
                String key = "external-model-create-" + translation + "-123456789";
                String body = createRequest(key, "external-model-" + translation).body()
                        .replace("\"source\":\"zh-CN\"", "\"source\":\"" + source + "\"")
                        .replace("\"targets\":[]", "\"targets\":[\"" + target + "\"]");
                if (translation) {
                    body = body.replace("[\"transcript.realtime\"]",
                            "[\"transcript.realtime\",\"translation.realtime\"]");
                }
                String created = exchange(port, "POST", "/v1/meetings", body,
                        networkHeaders(TOKEN, key, true));
                assertEquals(201, status(created));
                String meetingId = ERROR_JSON.readTree(responseBody(created))
                        .path("meetingId").asText();
                String path = "/v1/meetings/" + meetingId + ":modelSession";
                String response = exchange(port, "POST", path, "",
                        networkHeaders(TOKEN, null, false));
                assertEquals(200, status(response));
                assertEquals(new ModelSessionIssuer.Request(source, List.of(target),
                        translation, meetingId), issued.get());
                JsonNode materials = ERROR_JSON.readTree(responseBody(response));
                assertEquals(meetingId, materials.path("meetingId").asText());
                assertEquals("sample-json-ws", materials.path("adapterId").asText());
                assertEquals("external-test-task",
                        materials.path("initParams").path("taskId").asText());
                var firstRequest = issued.get();
                assertEquals(200, status(exchange(port, "POST", path, "",
                        networkHeaders(TOKEN, null, false))));
                assertEquals(firstRequest, issued.get());
            }
            assertEquals(2, count("meeting"));
            assertEquals(4, issueCalls.get());
        }
        assertEquals(0, closeCalls.get(), "外部发行器的生命周期仍由部署方持有");
        issuer.close();
        assertEquals(1, closeCalls.get());
    }

    /** 两种身份装配共用标准字幕→Stop→同键Stop重放，不调用外部模型。 */
    private void completeClientTranscriptOverHttp(int port, String meetingId, String token,
            String stopKey) throws Exception {
        appendClientTranscriptOverHttp(port, meetingId, token);
        String stopPath = "/v1/meetings/" + meetingId + ":stop";
        String stopped = exchange(port, "POST", stopPath, "",
                networkHeaders(token, stopKey, false));
        assertEquals(200, status(stopped));
        String replay = exchange(port, "POST", stopPath, "",
                networkHeaders(token, stopKey, false));
        assertEquals(200, status(replay));
        assertEquals(responseBody(stopped), responseBody(replay));
    }

    private void appendClientTranscriptOverHttp(int port, String meetingId, String token)
            throws Exception {
        String transcript = """
                {"clientEventId":"client-transcript-1","clientSequence":1,
                 "transcriptUpsert":{"segmentId":"source-1","revision":1,
                 "operation":"UPSERT_OPERATION_UPSERT","startMs":0,"endMs":1000,
                 "language":"zh-CN","text":"测试字幕","final":true}}
                """;
        assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                + "/events:append", transcript, networkHeaders(token, null, true))));
    }

    private String createAndStartClient(int port, String name) throws Exception {
        String key = name + "-create-key-123456789";
        String created = exchange(port, "POST", "/v1/meetings",
                createRequest(key, name).body(), networkHeaders(TOKEN, key, true));
        assertEquals(201, status(created));
        String meetingId = ERROR_JSON.readTree(responseBody(created)).path("meetingId").asText();
        assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                + ":startClient", "", networkHeaders(TOKEN, null, false))));
        return meetingId;
    }

    private static String contractFixture(String name) throws IOException {
        Path directory = Files.isDirectory(Path.of("contracts/fixtures"))
                ? Path.of("contracts/fixtures") : Path.of("../contracts/fixtures");
        return Files.readString(directory.resolve(name), StandardCharsets.UTF_8);
    }

    private static IdentityVerificationRequest clientStopVerification() {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                SecretCredential.copyOf(TOKEN.getBytes(StandardCharsets.US_ASCII)),
                "assembly-audience", "assembly-client");
    }

    private long reservationVersion(String meetingId) throws SQLException {
        try (var connection = fixtureDataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT r.version FROM meeting m JOIN usage_reservation r
                       ON r.reservation_id=m.usage_reservation_id WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private int countCommittedUsage() throws SQLException {
        try (var connection = fixtureDataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT count(*) FROM usage_operation WHERE operation_scope='usage.commit.v1'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void assertCommittedUsage(String meetingId, long actual, long expectedVersion)
            throws SQLException {
        try (var connection = fixtureDataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT r.status, r.actual_amount, r.version,
                            i.audio_duration_ms, i.usage_expected_version, i.usage_submitted_at
                     FROM meeting m JOIN meeting_client_input i USING (meeting_id)
                     JOIN usage_reservation r ON r.reservation_id=m.usage_reservation_id
                     WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("COMMITTED", rows.getString("status"));
                assertEquals(actual, rows.getLong("actual_amount"));
                assertEquals(actual, rows.getLong("audio_duration_ms"));
                assertEquals(expectedVersion, rows.getLong("usage_expected_version"));
                assertTrue(rows.getLong("version") > expectedVersion);
                assertTrue(rows.getTimestamp("usage_submitted_at") != null);
            }
        }
    }

    private MeetingManifest clientManifest(String meetingId) throws Exception {
        try (var connection = fixtureDataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT manifest_json FROM meeting_client_result WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return MeetingManifestJson.decode(rows.getString(1));
            }
        }
    }

    @Test
    void summarySessionUsesStartedClientInputWhileMeetingRemainsStarting() throws Exception {
        migrate(1);
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/api/v1/tokens", exchange -> {
            tokenRequests.incrementAndGet();
            byte[] body = ("{\"token\":\"st-local-only\",\"expires_at\":"
                    + NOW.plusSeconds(300).getEpochSecond() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        tokenServer.start();
        try {
            String base = "http://127.0.0.1:" + tokenServer.getAddress().getPort();
            Map<String, String> settings = enabledConfig();
            settings.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                    "summary.structured");
            settings.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                    "transcript.realtime,summary.structured");
            settings.put(ReferenceMeetingDeploymentConfig.MODEL_CAPABILITIES,
                    "transcript.realtime,summary.structured");
            settings.put(BailianSummarySessionConfig.ENABLED, "true");
            settings.put(BailianSummarySessionConfig.APP_ID, "app-local");
            settings.put(BailianSummarySessionConfig.API_KEY, "permanent-local-only");
            settings.put(BailianSummarySessionConfig.TOKEN_URL, base + "/api/v1/tokens");
            settings.put(BailianSummarySessionConfig.COMPLETION_BASE_URL,
                    base + "/api/v1/apps");
            try (var assembly = ReferenceMeetingHttpAssembly.start(settings, CLOCK);
                 var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
                network.start();
                int port = network.address().getPort();
                String discovered = exchange(port, "GET", "/v1/capabilities", "");
                assertEquals(200, status(discovered));
                assertTrue(responseBody(discovered).contains("summary.structured"));
                String key = "summary-session-starting-key-123456789";
                String create = createRequest(key, "summary-session-starting").body()
                        .replace("[\"transcript.realtime\"]",
                                "[\"transcript.realtime\",\"summary.structured\"]");
                String created = exchange(port, "POST", "/v1/meetings", create,
                        networkHeaders(TOKEN, key, true));
                assertEquals(201, status(created));
                String meetingId = ERROR_JSON.readTree(responseBody(created))
                        .path("meetingId").asText();
                String sessionPath = "/v1/meetings/" + meetingId + ":summarySession";
                assertEquals(409, status(exchange(port, "POST", sessionPath, "",
                        networkHeaders(TOKEN, null, false))));
                String started = exchange(port, "POST", "/v1/meetings/" + meetingId
                        + ":startClient", "", networkHeaders(TOKEN, null, false));
                assertEquals(200, status(started));
                assertEquals("STARTING", meetingState(meetingId));
                String grant = exchange(port, "POST", sessionPath, "",
                        networkHeaders(TOKEN, null, false));
                assertEquals(200, status(grant));
                assertEquals("st-local-only", ERROR_JSON.readTree(responseBody(grant))
                        .path("token").asText());
                assertEquals(1, tokenRequests.get());
            }
        } finally {
            tokenServer.stop(0);
        }
    }

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
    void clientEventsStopPublishesFinalTranscriptThroughUnifiedResultApi(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        createOwned("client_result");
        startOwned("client_result");
        String meetingId = "mtg_client_result";
        try (Connection connection = fixtureDataSource.getConnection();
             PreparedStatement marker = connection.prepareStatement("""
                     INSERT INTO meeting_client_input(meeting_id, started_at) VALUES (?, ?)
                     """)) {
            marker.setString(1, meetingId);
            marker.setTimestamp(2, java.sql.Timestamp.from(NOW.minusSeconds(60)));
            marker.executeUpdate();
        }
        var events = new JdbcMeetingEventLogStore(fixtureDataSource);
        // 超过旧 Mock 的 4096 字节读取上限，证明客户端结果确实走独立正文额度。
        String finalText = "最终文字".repeat(600);
        for (int revision = 1; revision <= 2; revision++) {
            var event = RealtimeTranscriptEventMapper.event(meetingId,
                    "evt_client_result_" + revision, revision, NOW.minusSeconds(30),
                    new CanonicalTranscriptEvent("segment-1", revision,
                            CanonicalTranscriptEvent.Operation.UPSERT, 0, 1200, "zh-CN",
                            revision == 1 ? "尚未完成的初稿" : finalText,
                            revision == 2, null, 0, List.of(), List.of()));
            events.append(event);
            try (Connection connection = fixtureDataSource.getConnection();
                 PreparedStatement identity = connection.prepareStatement("""
                         INSERT INTO meeting_client_event
                             (meeting_id, client_event_id, client_sequence, event_id)
                         VALUES (?, ?, ?, ?)
                         """)) {
                identity.setString(1, meetingId);
                identity.setString(2, "client-event-" + revision);
                identity.setInt(3, revision);
                identity.setString(4, event.eventId());
                identity.executeUpdate();
            }
        }
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK, artifacts)) {
            var stopped = assembly.runtime().handle(new HttpRequest("POST",
                    "/v1/meetings/" + meetingId + ":stop",
                    headers("client-stop-key-123456789"), ""), null);
            assertEquals(200, stopped.status());
            assertEquals("READY", meetingState(meetingId));
            assertEquals(1, count("meeting_client_result"));
            assertEquals(1, count("artifact"));
            assertEquals(0, count("meeting_manifest_revision"));

            Map<String, String> query = Map.of("genTime", Long.toString(NOW.toEpochMilli()));
            var manifestResponse = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/meetings/" + meetingId + "/manifest",
                    Map.of(), query, ""), null);
            assertEquals(200, manifestResponse.status());
            MeetingManifest manifest = MeetingManifestJson.decode(manifestResponse.body());
            assertEquals(NOW.minusSeconds(60), manifest.startedAt());
            assertEquals(60_000, manifest.durationMs());
            assertEquals("transcript.final", manifest.artifacts().getFirst().type());
            String artifactId = manifest.artifacts().getFirst().artifactId();
            var artifactResponse = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/artifacts/" + artifactId, Map.of(), query, ""), null);
            assertEquals(200, artifactResponse.status());
            assertEquals(finalText, ERROR_JSON.readTree(artifactResponse.body())
                    .path("text").asText());
            JsonNode segments = ERROR_JSON.readTree(artifactResponse.body()).path("segments");
            assertEquals(1, segments.size());
            assertFalse(segments.get(0).has("sessionSpeakerId"));
        }
    }

    @Test
    void mixedClientLanguageAndFinalTranslationsPublishTwoReadableArtifacts(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        String meetingId = clientMeeting("mixed_translation");
        List<EventLog> revisions = List.of(
                RealtimeTranscriptEventMapper.event(meetingId, "evt_mixed_1", 1, NOW,
                        new CanonicalTranscriptEvent("zh-segment", 1,
                                CanonicalTranscriptEvent.Operation.UPSERT, 0, 1000,
                                "zh-CN", "初稿", false, null, 0, List.of(), List.of())),
                RealtimeTranscriptEventMapper.event(meetingId, "evt_mixed_2", 2, NOW,
                        new CanonicalTranscriptEvent("zh-segment", 2,
                                CanonicalTranscriptEvent.Operation.REPLACE, 0, 1000,
                                "zh-CN", "你好", true, null, 0, List.of(), List.of())),
                RealtimeTranscriptEventMapper.event(meetingId, "evt_mixed_3", 3, NOW,
                        new CanonicalTranscriptEvent("en-segment", 1,
                                CanonicalTranscriptEvent.Operation.UPSERT, 1000, 2000,
                                "en-US", "Good morning", true, null, 0, List.of(), List.of())),
                RealtimeTranslationEventMapper.event(meetingId, "evt_mixed_4", 4, NOW,
                        new CanonicalTranslationEvent("zh-translation", "zh-segment", 1,
                                CanonicalTranscriptEvent.Operation.UPSERT, "zh-CN", "en-US",
                                "Draft", false, List.of())),
                RealtimeTranslationEventMapper.event(meetingId, "evt_mixed_5", 5, NOW,
                        new CanonicalTranslationEvent("zh-translation", "zh-segment", 2,
                                CanonicalTranscriptEvent.Operation.REPLACE, "zh-CN", "en-US",
                                "Hello", true, List.of())),
                RealtimeTranslationEventMapper.event(meetingId, "evt_mixed_6", 6, NOW,
                        new CanonicalTranslationEvent("en-translation", "en-segment", 1,
                                CanonicalTranscriptEvent.Operation.UPSERT, "en-US", "zh-CN",
                                "早上好", true, List.of())));
        for (int index = 0; index < revisions.size(); index++) {
            EventLog event = revisions.get(index);
            new JdbcMeetingEventLogStore(fixtureDataSource).append(event);
            try (Connection connection = fixtureDataSource.getConnection();
                 PreparedStatement identity = connection.prepareStatement("""
                         INSERT INTO meeting_client_event
                             (meeting_id, client_event_id, client_sequence, event_id)
                         VALUES (?, ?, ?, ?)
                         """)) {
                identity.setString(1, meetingId);
                identity.setString(2, "mixed-client-" + (index + 1));
                identity.setInt(3, index + 1);
                identity.setString(4, event.eventId());
                identity.executeUpdate();
            }
        }
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024))) {
            assertEquals(200, assembly.runtime().handle(new HttpRequest("POST",
                    "/v1/meetings/" + meetingId + ":stop",
                    headers("client-mixed-stop-key-123456789"), ""), null).status());
            assertEquals("READY", meetingState(meetingId));
            assertEquals(2, count("artifact"));
            Map<String, String> query = Map.of("genTime", Long.toString(NOW.toEpochMilli()));
            var response = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/meetings/" + meetingId + "/manifest", Map.of(), query, ""), null);
            assertEquals(200, response.status());
            MeetingManifest manifest = MeetingManifestJson.decode(response.body());
            assertEquals("mul", manifest.languages().source());
            assertEquals(List.of("en-US", "zh-CN"), manifest.languages().targets());
            assertEquals(List.of("transcript.final", "translation.final"),
                    manifest.artifacts().stream().map(MeetingManifest.ArtifactRef::type).toList());
            var transcript = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/artifacts/" + manifest.artifacts().get(0).artifactId(),
                    Map.of(), query, ""), null);
            var translation = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/artifacts/" + manifest.artifacts().get(1).artifactId(),
                    Map.of(), query, ""), null);
            assertEquals(200, transcript.status());
            assertEquals(200, translation.status());
            JsonNode source = ERROR_JSON.readTree(transcript.body());
            assertEquals("你好\nGood morning", source.path("text").asText());
            assertEquals("zh-CN", source.path("segments").get(0).path("language").asText());
            assertEquals("en-US", source.path("segments").get(1).path("language").asText());
            JsonNode translated = ERROR_JSON.readTree(translation.body());
            assertEquals(2, translated.path("translations").size());
            assertEquals("Hello", translated.path("translations").get(0).path("text").asText());
            assertEquals("早上好", translated.path("translations").get(1).path("text").asText());
        }
    }

    @Test
    void translationAppendUsesFrozenGrantSharedSequenceAndAuthenticatedEventPage(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> enabled = enabledConfig();
        enabled.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                "translation.realtime");
        enabled.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime,translation.realtime");
        enabled.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime,translation.realtime");
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            HttpRequest base = createRequest("translation-create-key-123456789", "translation-client");
            String body = base.body().replace("\"targets\":[]", "\"targets\":[\"en-US\"]")
                    .replace("[\"transcript.realtime\"]",
                            "[\"transcript.realtime\",\"translation.realtime\"]")
                    .replace("\"retainAudio\":false", "\"retainAudio\":true")
                    .replace("\"timezone\":", "\"title\":\"本场快照标题\",\"timezone\":");
            String created = exchange(port, "POST", "/v1/meetings", body,
                    networkHeaders(TOKEN, "translation-create-key-123456789", true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + meetingId + ":startClient", "",
                    networkHeaders(TOKEN, null, false))));
            String path = "/v1/meetings/" + meetingId + "/events:append";
            String transcript = """
                    {"clientEventId":"client-transcript-1","clientSequence":1,
                     "transcriptUpsert":{"segmentId":"source-1","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":0,"endMs":1000,
                     "language":"zh-CN","text":"你好","final":true}}
                    """;
            String translation = """
                    {"clientEventId":"client-translation-2","clientSequence":2,
                     "translationUpsert":{"translationId":"translation-1",
                     "sourceSegmentId":"source-1","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","sourceLanguage":"zh-CN",
                     "targetLanguage":"en-US","text":"Hello","final":true,
                     "supersedesTranslationIds":[]}}
                    """;
            assertEquals(201, status(exchange(port, "POST", path, transcript,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(201, status(exchange(port, "POST", path, translation,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(200, status(exchange(port, "POST", path, translation,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(409, status(exchange(port, "POST", path,
                    translation.replace("\"clientSequence\":2", "\"clientSequence\":3"),
                    networkHeaders(TOKEN, null, true))));
            assertEquals(2, count("meeting_client_event"));
            String page = exchange(port, "GET", "/v1/meetings/" + meetingId
                    + "/events?afterSequence=0&limit=10", "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(page));
            JsonNode events = ERROR_JSON.readTree(responseBody(page)).path("items");
            assertEquals(2, events.size());
            assertEquals("Hello", events.get(1).path("translationUpsert").path("text").asText());

            // 沿真实 append HTTP 保存不同片段和后续修订；快照与 Stop 共用原投影。
            List<String> tail = List.of("""
                    {"clientEventId":"snapshot-source-2","clientSequence":3,
                     "transcriptUpsert":{"segmentId":"source-2","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":1000,"endMs":2000,
                     "language":"zh-CN","text":"再见","final":true}}
                    """, """
                    {"clientEventId":"snapshot-source-1-r2","clientSequence":4,
                     "transcriptUpsert":{"segmentId":"source-1","revision":2,
                     "operation":"UPSERT_OPERATION_REPLACE","startMs":0,"endMs":1000,
                     "language":"zh-CN","text":"你好，最终修订","final":true}}
                    """, """
                    {"clientEventId":"snapshot-translation-1-r2","clientSequence":5,
                     "translationUpsert":{"translationId":"translation-1",
                     "sourceSegmentId":"source-1","revision":2,
                     "operation":"UPSERT_OPERATION_REPLACE","sourceLanguage":"zh-CN",
                     "targetLanguage":"en-US","text":"Hello, final revision","final":true,
                     "supersedesTranslationIds":[]}}
                    """, """
                    {"clientEventId":"snapshot-unfinished","clientSequence":6,
                     "transcriptUpsert":{"segmentId":"unfinished","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":2000,"endMs":2100,
                     "language":"zh-CN","text":"尚未完成","final":false}}
                    """, """
                    {"clientEventId":"snapshot-translation-2","clientSequence":7,
                     "translationUpsert":{"translationId":"translation-2",
                     "sourceSegmentId":"source-2","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","sourceLanguage":"zh-CN",
                     "targetLanguage":"en-US","text":"Goodbye","final":true,
                     "supersedesTranslationIds":[]}}
                    """);
            for (String event : tail) {
                assertEquals(201, status(exchange(port, "POST", path, event,
                        networkHeaders(TOKEN, null, true))));
            }
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/usage:submit", "{\"audioDurationMs\":2000}",
                    networkHeaders(TOKEN, null, true))));
            var context = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                    assembly.contextResolver().forClientWrite(request("POST", path))).context();
            var snapshot = assertInstanceOf(JdbcClientMeetingResultFinalizer.Ready.class,
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(
                            assembly.databaseRuntime().dataSource(), context, meetingId));
            assertEquals(List.of("source-1", "source-2"), snapshot.transcripts().stream()
                    .map(item -> item.getSegmentId()).toList());
            assertEquals(List.of("你好，最终修订", "再见"), snapshot.transcripts().stream()
                    .map(item -> item.getText()).toList());
            assertEquals(List.of(2, 1), snapshot.transcripts().stream()
                    .map(item -> item.getRevision()).toList());
            assertEquals(List.of("Hello, final revision", "Goodbye"),
                    snapshot.translations().stream().map(item -> item.getText()).toList());
            assertEquals("zh-CN", snapshot.language());
            assertEquals("zh-CN", snapshot.sourceLanguage());
            assertEquals("本场快照标题", snapshot.title());
            assertTrue(snapshot.retainAudioRequested());
            assertEquals(2000, snapshot.frozenAudioDurationMs());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.transcripts().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.translations().clear());
            assertEquals("STARTING", meetingState(meetingId));
            assertEquals(7, count("meeting_client_event"));
            assertEquals(1, count("usage_operation"));
            assertEquals(0, countCommittedUsage());
            assertEquals(0, count("artifact"));
            assertEquals(0, count("meeting_client_result"));

            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":stop", "", networkHeaders(TOKEN,
                    "snapshot-translation-stop-key-123456789", false))));
            assertEquals(snapshot, JdbcClientMeetingResultFinalizer.readFinalSnapshot(
                    assembly.databaseRuntime().dataSource(), context, meetingId));
            MeetingManifest manifest = clientManifest(meetingId);
            assertEquals(2000, manifest.durationMs());
            assertEquals(snapshot.language(), manifest.languages().source());
            String query = "?genTime=" + NOW.toEpochMilli();
            JsonNode finalTranscript = ERROR_JSON.readTree(responseBody(exchange(port, "GET",
                    "/v1/artifacts/" + manifest.artifacts().get(0).artifactId() + query, "")));
            JsonNode finalTranslation = ERROR_JSON.readTree(responseBody(exchange(port, "GET",
                    "/v1/artifacts/" + manifest.artifacts().get(1).artifactId() + query, "")));
            for (int index = 0; index < snapshot.transcripts().size(); index++) {
                var segment = snapshot.transcripts().get(index);
                JsonNode published = finalTranscript.path("segments").get(index);
                assertEquals(segment.getSegmentId(), published.path("segmentId").asText());
                assertEquals(segment.getText(), published.path("text").asText());
                assertEquals(segment.getStartMs(), published.path("startMs").asLong());
                assertEquals(segment.getEndMs(), published.path("endMs").asLong());
            }
            for (int index = 0; index < snapshot.translations().size(); index++) {
                var translationItem = snapshot.translations().get(index);
                JsonNode published = finalTranslation.path("translations").get(index);
                assertEquals(translationItem.getTranslationId(),
                        published.path("translationId").asText());
                assertEquals(translationItem.getText(), published.path("text").asText());
            }
            assertEquals(1, countCommittedUsage());
            assertEquals(2, count("artifact"));
            assertEquals("READY", meetingState(meetingId));
        }
    }

    @Test
    void translationAppendIsDeniedWithoutFrozenCapability() throws Exception {
        migrate(1);
        String meetingId = clientMeeting("translation_denied");
        try (var assembly = ReferenceMeetingHttpAssembly.start(config(), CLOCK);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            String denied = exchange(network.address().getPort(), "POST",
                    "/v1/meetings/" + meetingId + "/events:append", """
                    {"clientEventId":"denied-translation-1","clientSequence":1,
                     "translationUpsert":{"translationId":"translation-1",
                     "sourceSegmentId":"source-1","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","sourceLanguage":"zh-CN",
                     "targetLanguage":"en-US","text":"Hello","final":true,
                     "supersedesTranslationIds":[]}}
                    """, networkHeaders(TOKEN, null, true));
            assertNetworkError(denied, 422, "TRANSLATION_NOT_GRANTED");
            assertEquals(0, count("meeting_client_event"));
        }
    }

    @Test
    void sessionSpeakerIsGrantedPerMeetingAndReadableAfterStop(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> enabled = enabledConfig();
        enabled.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime,speaker.diarization.session");
        enabled.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime,speaker.diarization.session");
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            HttpRequest base = createRequest("speaker-create-key-123456789", "speaker-client");
            String create = base.body().replace("[\"transcript.realtime\"]",
                    "[\"transcript.realtime\",\"speaker.diarization.session\"]");
            String created = exchange(port, "POST", "/v1/meetings", create,
                    networkHeaders(TOKEN, "speaker-create-key-123456789", true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + meetingId + ":startClient", "",
                    networkHeaders(TOKEN, null, false))));
            String path = "/v1/meetings/" + meetingId + "/events:append";
            String first = """
                    {"clientEventId":"speaker-1-r1","clientSequence":1,
                     "transcriptUpsert":{"segmentId":"speaker-1","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":0,"endMs":1000,
                     "language":"zh-CN","text":"你好","final":true,
                     "speaker":{"sessionSpeakerId":"speaker-2"}}}
                    """;
            String second = """
                    {"clientEventId":"speaker-2-r1","clientSequence":2,
                     "transcriptUpsert":{"segmentId":"speaker-2","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":1000,"endMs":2000,
                     "language":"zh-CN","text":"再见","final":true}}
                    """;
            assertEquals(201, status(exchange(port, "POST", path, first,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(201, status(exchange(port, "POST", path, second,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + meetingId + ":stop", "",
                    networkHeaders(TOKEN, "speaker-stop-key-123456789", false))));
            String manifestResponse = exchange(port, "GET",
                    "/v1/meetings/" + meetingId + "/manifest?genTime=" + NOW.toEpochMilli(),
                    "", networkHeaders(TOKEN, null, false));
            assertEquals(200, status(manifestResponse));
            JsonNode manifest = ERROR_JSON.readTree(responseBody(manifestResponse));
            String artifactId = manifest.path("artifacts").get(0).path("artifactId").asText();
            String artifact = exchange(port, "GET",
                    "/v1/artifacts/" + artifactId + "?genTime=" + NOW.toEpochMilli(),
                    "", networkHeaders(TOKEN, null, false));
            assertEquals(200, status(artifact));
            JsonNode segments = ERROR_JSON.readTree(responseBody(artifact)).path("segments");
            assertEquals("speaker-2", segments.get(0).path("sessionSpeakerId").asText());
            assertFalse(segments.get(1).has("sessionSpeakerId"));

            HttpRequest withoutSpeaker = createRequest(
                    "speaker-denied-create-key-123456789", "speaker-denied-client");
            String deniedCreate = exchange(port, "POST", "/v1/meetings", withoutSpeaker.body(),
                    networkHeaders(TOKEN, "speaker-denied-create-key-123456789", true));
            assertEquals(201, status(deniedCreate));
            String deniedId = ERROR_JSON.readTree(responseBody(deniedCreate))
                    .path("meetingId").asText();
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + deniedId + ":startClient", "",
                    networkHeaders(TOKEN, null, false))));
            assertNetworkError(exchange(port, "POST",
                    "/v1/meetings/" + deniedId + "/events:append", first,
                    networkHeaders(TOKEN, null, true)), 422, "SPEAKER_NOT_GRANTED");
        }
    }

    @Test
    void clientSummaryIsSubmittedBeforeStopAndPublishedAsReadyArtifact(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> enabled = enabledConfig();
        enabled.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                "summary.structured");
        enabled.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime,summary.structured");
        enabled.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime,summary.structured");
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String key = "summary-create-key-123456789";
            String create = createRequest(key, "summary-client-session").body()
                    .replace("[\"transcript.realtime\"]",
                            "[\"transcript.realtime\",\"summary.structured\"]");
            String created = exchange(port, "POST", "/v1/meetings", create,
                    networkHeaders(TOKEN, key, true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":startClient", "", networkHeaders(TOKEN, null, false))));
            String transcript = """
                    {"clientEventId":"summary-final-1","clientSequence":1,
                     "transcriptUpsert":{"segmentId":"summary-segment-1","revision":1,
                     "operation":"UPSERT_OPERATION_UPSERT","startMs":0,"endMs":1000,
                     "language":"zh-CN","text":"今天确认了计划","final":true}}
                    """;
            assertEquals(201, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + "/events:append", transcript, networkHeaders(TOKEN, null, true))));
            String path = "/v1/meetings/" + meetingId + "/summary:submit";
            String summary = """
                    {"language":"zh-CN","title":"项目会议","overview":"确认了计划",
                     "keyPoints":["先交付 Mock 闭环"],"actionItems":["检查结果页面"]}
                    """;
            assertNetworkError(exchange(port, "POST", path,
                    summary.replace("\"title\"", "\"providerSecret\":\"x\",\"title\""),
                    networkHeaders(TOKEN, null, true)), 400, "INVALID_REQUEST");
            assertEquals(201, status(exchange(port, "POST", path, summary,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(200, status(exchange(port, "POST", path, summary,
                    networkHeaders(TOKEN, null, true))));
            assertNetworkError(exchange(port, "POST", path,
                    summary.replace("项目会议", "另一标题"),
                    networkHeaders(TOKEN, null, true)), 409, "EVENT_CONFLICT");
            assertEquals(1, count("meeting_client_summary"));
            assertEquals(200, status(exchange(port, "POST", "/v1/meetings/" + meetingId
                    + ":stop", "", networkHeaders(TOKEN,
                            "summary-stop-key-123456789", false))));
            String manifestResponse = exchange(port, "GET", "/v1/meetings/" + meetingId
                    + "/manifest?genTime=" + NOW.toEpochMilli(), "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(manifestResponse));
            JsonNode manifest = ERROR_JSON.readTree(responseBody(manifestResponse));
            assertEquals("项目会议", manifest.path("title").asText());
            assertEquals(2, manifest.path("artifacts").size());
            JsonNode ref = manifest.path("artifacts").get(1);
            assertEquals("summary.structured", ref.path("type").asText());
            assertEquals("READY", ref.path("status").asText());
            String artifact = exchange(port, "GET", "/v1/artifacts/"
                    + ref.path("artifactId").asText() + "?genTime=" + NOW.toEpochMilli(),
                    "", networkHeaders(TOKEN, null, false));
            assertEquals(200, status(artifact));
            JsonNode body = ERROR_JSON.readTree(responseBody(artifact));
            assertEquals("client-summary/v1", body.path("schemaVersion").asText());
            assertEquals("检查结果页面", body.path("actionItems").get(0).asText());
        }
    }

    @Test
    void clientStopWithoutRecognizedSubtitleReturnsEmptyReadyResult(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        String meetingId = clientMeeting("no_subtitles");
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK, artifacts)) {
            HttpRequest stop = new HttpRequest("POST", "/v1/meetings/" + meetingId + ":stop",
                    headers("client-empty-stop-key-123456789"), "");
            assertEquals(200, assembly.runtime().handle(stop, null).status());
            assertEquals("READY", meetingState(meetingId));
            assertEquals(200, assembly.runtime().handle(stop, null).status());
            assertEquals(1, count("meeting_client_result WHERE artifact_id IS NULL"));
            assertEquals(0, count("meeting_client_event"));
            assertEquals(0, count("artifact"));
            Map<String, String> query = Map.of("genTime", Long.toString(NOW.toEpochMilli()));
            var result = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/meetings/" + meetingId + "/manifest", Map.of(), query, ""), null);
            assertEquals(200, result.status());
            MeetingManifest manifest = MeetingManifestJson.decode(result.body());
            assertEquals(MeetingState.READY.name(), manifest.status().name());
            assertTrue(manifest.artifacts().isEmpty());
            assertEquals("und", manifest.languages().source());
        }
    }

    @Test
    void presentationChunksSurviveStopInOrderAndReadThroughArtifactStore(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        Map<String, String> enabled = enabledConfig();
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                    new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024));
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String created = exchange(port, "POST", "/v1/meetings",
                    createRequest("presentation-create-123456789", "presentation-session")
                            .body().replace("\"presentationCapture\":false",
                                    "\"presentationCapture\":true"),
                    networkHeaders(TOKEN, "presentation-create-123456789", true));
            assertEquals(201, status(created));
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").asText();
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + meetingId + ":startClient", "",
                    networkHeaders(TOKEN, null, false))));
            String path = "/v1/meetings/" + meetingId + "/presentation-captures:append";
            String first = """
                    {"chunkSequence":1,"frames":[{"offsetMs":0,"jpegBase64":"/9j/2Q=="}]}
                    """;
            String second = """
                    {"chunkSequence":2,"frames":[
                     {"offsetMs":10000,"jpegBase64":"/9j/2Q=="},
                     {"offsetMs":20000,"jpegBase64":"/9j/2Q=="}]}
                    """;
            assertEquals(409, status(exchange(port, "POST", path, second,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(201, status(exchange(port, "POST", path, first,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(200, status(exchange(port, "POST", path, first,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(409, status(exchange(port, "POST", path,
                    first.replace("\"offsetMs\":0", "\"offsetMs\":1"),
                    networkHeaders(TOKEN, null, true))));
            assertEquals(201, status(exchange(port, "POST", path, second,
                    networkHeaders(TOKEN, null, true))));
            assertEquals(2, count("artifact"));
            assertEquals(200, status(exchange(port, "POST",
                    "/v1/meetings/" + meetingId + ":stop", "",
                    networkHeaders(TOKEN, "presentation-stop-123456789", false))));
            String manifestResponse = exchange(port, "GET", "/v1/meetings/" + meetingId
                    + "/manifest?genTime=" + NOW.toEpochMilli(), "", "");
            assertEquals(200, status(manifestResponse));
            MeetingManifest manifest = MeetingManifestJson.decode(responseBody(manifestResponse));
            assertEquals(2, manifest.artifacts().size());
            for (int sequence = 1; sequence <= 2; sequence++) {
                var ref = manifest.artifacts().get(sequence - 1);
                assertEquals("presentation.capture", ref.type());
                assertEquals(sequence, ref.revision());
                String content = exchange(port, "GET", "/v1/artifacts/" + ref.artifactId()
                        + "?genTime=" + NOW.toEpochMilli(), "", "");
                assertEquals(200, status(content));
                JsonNode payload = ERROR_JSON.readTree(responseBody(content));
                assertEquals("presentation-capture/v1", payload.path("schemaVersion").asText());
                assertEquals(sequence, payload.path("chunkSequence").asInt());
                assertEquals(sequence, payload.path("frames").size());
            }
        }
    }

    @Test
    void clientStopWithOnlyPartialSubtitleDoesNotWaitForever(
            @TempDir Path artifactRoot) throws Exception {
        migrate(1);
        String meetingId = clientMeeting("partial_only");
        var event = RealtimeTranscriptEventMapper.event(meetingId,
                "evt_client_partial_only", 1, NOW.minusSeconds(30),
                new CanonicalTranscriptEvent("segment-1", 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 1200, "zh-CN",
                        "未确认初稿", false, null, 0, List.of(), List.of()));
        new JdbcMeetingEventLogStore(fixtureDataSource).append(event);
        try (Connection connection = fixtureDataSource.getConnection();
             PreparedStatement identity = connection.prepareStatement("""
                     INSERT INTO meeting_client_event
                         (meeting_id, client_event_id, client_sequence, event_id)
                     VALUES (?, ?, ?, ?)
                     """)) {
            identity.setString(1, meetingId);
            identity.setString(2, "client-partial-1");
            identity.setInt(3, 1);
            identity.setString(4, event.eventId());
            identity.executeUpdate();
        }
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024))) {
            var stopped = assembly.runtime().handle(new HttpRequest("POST",
                    "/v1/meetings/" + meetingId + ":stop",
                    headers("client-partial-stop-key-123456789"), ""), null);
            assertEquals(200, stopped.status());
            assertEquals("READY", meetingState(meetingId));
            assertEquals(1, count("meeting_client_event"));
            assertEquals(0, count("artifact"));
            Map<String, String> query = Map.of("genTime", Long.toString(NOW.toEpochMilli()));
            var result = assembly.runtime().handle(new HttpRequest("GET",
                    "/v1/meetings/" + meetingId + "/manifest", Map.of(), query, ""), null);
            assertEquals(200, result.status());
            assertTrue(MeetingManifestJson.decode(result.body()).artifacts().isEmpty());
        }
    }

    private String clientMeeting(String suffix) throws Exception {
        createOwned(suffix);
        startOwned(suffix);
        String meetingId = "mtg_" + suffix;
        try (Connection connection = fixtureDataSource.getConnection();
             PreparedStatement marker = connection.prepareStatement("""
                     INSERT INTO meeting_client_input(meeting_id, started_at) VALUES (?, ?)
                     """)) {
            marker.setString(1, meetingId);
            marker.setTimestamp(2, java.sql.Timestamp.from(NOW.minusSeconds(60)));
            marker.executeUpdate();
        }
        return meetingId;
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
    void realHttpRealtimeRenewalIsOptInAndReplaysPersistedGrantBytes() throws Exception {
        migrate(1);
        String route = "/v1/meetings/mtg_closed:renewRealtimeToken";
        String keyOne = "network-renew-key-0001";
        String keyTwo = "network-renew-key-0002";
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.REALTIME_RENEW_ENABLED, "true");

        // 续期关闭时仍可使用其它路由；关闭响应固定为 501。
        try (var closed = ReferenceMeetingHttpAssembly.start(config(), CLOCK);
             var network = closed.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            assertNetworkError(exchange(network.address().getPort(), "POST", route, "{}", ""),
                    501, "ROUTE_NOT_IMPLEMENTED");
            assertEquals(200, status(exchange(network.address().getPort(), "GET",
                    MeetingJdkHttpServer.READINESS_PATH, "")));
        }

        enabled.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        MutableClock renewalClock = new MutableClock(NOW, ZoneOffset.UTC);
        try (var open = ReferenceMeetingHttpAssembly.start(enabled, renewalClock);
             var network = open.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            assertEquals(200, status(exchange(port, "GET",
                    MeetingJdkHttpServer.READINESS_PATH, "")));
            String createKey = "network-renew-create-key-0001";
            String created = exchange(port, "POST", "/v1/meetings",
                    createRequest(createKey, "network-renew-session").body(),
                    networkHeaders(TOKEN, createKey, true));
            assertEquals(201, status(created));
            assertTrue(responseBody(created).equals(responseBody(exchange(port, "POST",
                    "/v1/meetings", createRequest(createKey, "network-renew-session").body(),
                    networkHeaders(TOKEN, createKey, true)))),
                    "Create replay must preserve original bytes without logging its Token");
            String meetingId = ERROR_JSON.readTree(responseBody(created))
                    .path("meetingId").textValue();
            String initialSessionToken = ERROR_JSON.readTree(responseBody(created))
                    .path("realtime").path("sessionToken").textValue();
            String path = "/v1/meetings/" + meetingId + ":renewRealtimeToken";
            assertNetworkError(exchange(port, "POST", path, "",
                    "Idempotency-Key: " + keyOne + "\r\n"),
                    401, "AUTHENTICATION_REQUIRED");
            assertNetworkError(exchange(port, "POST", path, "",
                    networkHeaders(initialSessionToken, keyOne, false)),
                    401, "AUTHENTICATION_FAILED");
            assertNetworkError(exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, "short", false)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST", path, "{}",
                    networkHeaders(TOKEN, keyOne, true)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST", path + "?x=1", "",
                    networkHeaders(TOKEN, keyOne, false)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST",
                    "/v1/meetings/mtg_" + "x".repeat(1021) + ":renewRealtimeToken", "",
                    networkHeaders(TOKEN, keyOne, false)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST",
                    "/v1/meetings/mtg_missing:renewRealtimeToken", "",
                    networkHeaders(TOKEN, keyOne, false)), 404, "MEETING_NOT_FOUND");
            assertEquals(0, count("meeting_realtime_token_grant"));

            String first = exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, keyOne, false));
            assertEquals(200, status(first));
            String firstJson = responseBody(first);
            assertTrue(storedRealtimeGrantJson(meetingId, keyOne).equals(firstJson),
                    "first Grant must equal persisted JSON without logging its Token");
            assertTrue(first.toLowerCase(java.util.Locale.ROOT)
                    .contains("cache-control: no-store"));
            assertEquals(meetingId, ERROR_JSON.readTree(firstJson).path("streamId").textValue());
            assertFalse(initialSessionToken.equals(
                    ERROR_JSON.readTree(firstJson).path("sessionToken").textValue()),
                    "renewed Token must differ from the first session Token");

            // 首次 Grant 到期后同键仍重放原文，新键从新的服务端时间签发。
            renewalClock.advanceTo(NOW.plusSeconds(301));
            assertTrue(firstJson.equals(responseBody(exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, keyOne, false)))),
                    "expired same-key Grant must replay original bytes");

            try (Connection connection = fixtureDataSource.getConnection();
                 PreparedStatement change = connection.prepareStatement("""
                         UPDATE meeting SET state='STOPPING',
                             input_state='INPUT_COMPLETE_PENDING'
                          WHERE meeting_id=?
                         """)) {
                change.setString(1, meetingId);
                assertEquals(1, change.executeUpdate());
            }
            String second = exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, keyTwo, false));
            assertEquals(200, status(second));
            assertFalse(firstJson.equals(responseBody(second)),
                    "new key must receive a distinct Grant");
            assertTrue(storedRealtimeGrantJson(meetingId, keyTwo).equals(responseBody(second)),
                    "second Grant must equal persisted JSON without logging its Token");
            assertTrue(Instant.parse(ERROR_JSON.readTree(responseBody(second))
                    .path("expiresAt").textValue()).isAfter(Instant.parse(
                    ERROR_JSON.readTree(firstJson).path("expiresAt").textValue())));

            try (Connection connection = fixtureDataSource.getConnection();
                 PreparedStatement change = connection.prepareStatement("""
                         UPDATE meeting SET state='FINALIZING', input_state='INPUT_ACCEPTED',
                             finalization_receipt_id='receipt-network-renew'
                          WHERE meeting_id=?
                         """)) {
                change.setString(1, meetingId);
                assertEquals(1, change.executeUpdate());
            }
            assertTrue(firstJson.equals(responseBody(exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, keyOne, false)))),
                    "terminal-state same-key replay must preserve original bytes");
            assertNetworkError(exchange(port, "POST", path, "",
                    networkHeaders(TOKEN, "network-renew-key-0003", false)),
                    409, "INVALID_MEETING_STATE");
            assertEquals(2, count("meeting_realtime_token_grant"));
            assertEquals(200, status(exchange(port, "GET", "/v1/meetings/" + meetingId, "",
                    networkHeaders(TOKEN, null, false))));
        }

        Map<String, String> foreign = new HashMap<>(enabled);
        foreign.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, "another-renew-subject");
        try (var other = ReferenceMeetingHttpAssembly.start(foreign, CLOCK);
             var network = other.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            String meetingId = createdMeetingId(other.databaseRuntime().dataSource(),
                    "network-renew-create-key-0001");
            assertNetworkError(exchange(network.address().getPort(), "POST",
                    "/v1/meetings/" + meetingId + ":renewRealtimeToken", "",
                    networkHeaders(TOKEN, keyOne, false)), 403, "ACCESS_DENIED");
        }
    }

    private String storedRealtimeGrantJson(String meetingId, String key) throws Exception {
        try (Connection connection = fixtureDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT grant_json FROM meeting_realtime_token_grant
                      WHERE meeting_id=? AND idempotency_key=?
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String json = rows.getString(1);
                assertFalse(rows.next());
                return json;
            }
        }
    }

    @Test
    void missingBaselineAndInvalidPhysicalFactRejectStartup() throws Exception {
        var missing = assertThrows(DatabaseStartupException.class,
                () -> ReferenceMeetingHttpAssembly.start(config(), CLOCK));
        assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                missing.readiness().status());
        migrate(1);
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

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void realHttpStopAdmissionAndMockWorkerPublishAsrOnlyResult() throws Exception {
        runHttpFinalizationFlow(false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void realHttpAnonymousManifestUsesFrozenJdbcFactsAndRevokesOnDelete() throws Exception {
        runHttpFinalizationFlow(true);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HEALTH_GATE_CONTAINER_ID", matches = "[0-9a-f]{64}")
    void realHttpOpenToClosedRollbackPreservesV1AndV2Facts() throws Exception {
        migrate(13);
        var v1Request = createOwned("rollback_v1");
        String key = v1Request.idempotencyKey();
        String v1MeetingId = v1Request.meetingId();
        String v1Scope = IdempotencyScopeCodec.meetingCreate(SCOPE, SUBJECT);
        String v2Scope = IdempotencyScopeCodec.meetingCreateV2(SCOPE, SUBJECT);
        assertNotEquals(v1Scope, v2Scope);
        var v1Store = new JdbcMeetingCreateOutcomeStore(fixtureDataSource, CLOCK);
        var firstV1 = v1Store.find(v1Scope, key).orElseThrow();
        assertEquals(1, count("idempotency_record"));
        assertEquals(0, count("meeting_create_fact"));

        String v2MeetingId;
        Map<String, String> openConfig = config();
        openConfig.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        try (var open = ReferenceMeetingHttpAssembly.start(openConfig, CLOCK);
             var network = open.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String first = exchange(port, "POST", "/v1/meetings",
                    createRequest(key, "rollback-v2-session").body(),
                    networkHeaders(TOKEN, key, true));
            assertEquals(201, status(first));
            v2MeetingId = createdMeetingId(open.databaseRuntime().dataSource(), key);
            assertNotEquals(v1MeetingId, v2MeetingId);
            assertTrue(responseBody(first).contains("\"meetingId\":\"" + v2MeetingId + "\""));
            assertEquals(v2Scope, createFact(open.databaseRuntime().dataSource(),
                    v2MeetingId).scope());
            assertEquals(key, createFact(open.databaseRuntime().dataSource(),
                    v2MeetingId).key());
            assertEquals(firstV1, v1Store.find(v1Scope, key).orElseThrow());
            assertEquals(1, count("idempotency_record"));
            assertEquals(1, count("meeting_create_fact"));
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
        }

        // 同一 schema、同一 V013 历史事实；仅重新装配默认关态，不回滚迁移。
        List<Integer> beforeClosedRequests = creationFactCounts();
        assertEquals(List.of(2, 1, 1, 2, 2, 2), beforeClosedRequests);
        try (var closed = ReferenceMeetingHttpAssembly.start(config(), CLOCK);
             var network = closed.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            assertEquals(200, status(exchange(port, "GET", "/v1/meetings/" + v1MeetingId,
                    "", networkHeaders(TOKEN, null, false))));
            assertEquals(200, status(exchange(port, "GET", "/v1/meetings/" + v2MeetingId,
                    "", networkHeaders(TOKEN, null, false))));
            String list = exchange(port, "GET", "/v1/meetings", "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(list));
            assertTrue(responseBody(list).contains(v1MeetingId));
            assertTrue(responseBody(list).contains(v2MeetingId));

            assertNetworkError(exchange(port, "POST", "/v1/meetings",
                    createRequest(key, "rollback-v2-session").body(),
                    networkHeaders(TOKEN, key, true)), 501, "ROUTE_NOT_IMPLEMENTED");
            assertNetworkError(exchange(port, "POST", "/v1/meetings", "not JSON", ""),
                    501, "ROUTE_NOT_IMPLEMENTED");
            var v1Replay = assertInstanceOf(OwnedMeetingCreationUnitOfWork.Success.class,
                    new JdbcOwnedMeetingCreationUnitOfWork(fixtureDataSource, CLOCK)
                            .create(v1Request));
            assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED,
                    v1Replay.code());
            assertEquals(v1MeetingId, v1Replay.result().meeting().meetingId());
            assertEquals(firstV1, v1Store.find(v1Scope, key).orElseThrow());
            assertEquals(v2Scope, createFact(closed.databaseRuntime().dataSource(),
                    v2MeetingId).scope());
            assertEquals(beforeClosedRequests, creationFactCounts());
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
        }
    }

    private void runHttpFinalizationFlow(boolean manifestEnabled) throws Exception {
        migrate(13);
        if (manifestEnabled) {
            // 旧配置即使没有 TTL 也可启动；新路由在任何 genTime 解析或 JDBC 查询前 501。
            try (var defaultAssembly = ReferenceMeetingHttpAssembly.start(config(), CLOCK);
                 var defaultNetwork = defaultAssembly.newJdkHttpServer(
                         new InetSocketAddress("127.0.0.1", 0))) {
                defaultNetwork.start();
                assertNetworkError(exchange(defaultNetwork.address().getPort(), "POST",
                        "/v1/meetings", "not JSON", ""),
                        501, "ROUTE_NOT_IMPLEMENTED");
                assertNetworkError(exchange(defaultNetwork.address().getPort(), "GET",
                        "/v1/meetings/mtg_missing/manifest?genTime=invalid", "", ""),
                        501, "ROUTE_NOT_IMPLEMENTED");
                assertNetworkError(exchange(defaultNetwork.address().getPort(), "GET",
                        "/v1/artifacts/art_missing?genTime=invalid", "", ""),
                        501, "ROUTE_NOT_IMPLEMENTED");
                assertTrue(defaultNetwork.shutdown(Duration.ofSeconds(3)).clean());
            }
        }
        Map<String, String> enabled = config();
        enabled.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        if (manifestEnabled) {
            enabled.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        }
        var trusted = ReferenceMeetingDeploymentConfig.fromEnvironment(enabled);
        MutableClock httpClock = new MutableClock(NOW, ZoneOffset.UTC);
        Path artifactRoot = Path.of(System.getenv("T15_HEALTH_GATE_ARTIFACT_ROOT"));
        assertTrue(Files.isDirectory(artifactRoot), "gate must own a private Artifact directory");
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot, 4096);
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
        try (var assembly = ReferenceMeetingHttpAssembly.start(enabled, httpClock, artifacts);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            String invalidManifestPath = "/v1/meetings/mtg_missing/manifest";
            if (manifestEnabled) {
                assertNetworkError(exchange(port, "GET", invalidManifestPath
                        + "?genTime=-1", "", ""), 404, "MANIFEST_NOT_FOUND");
                String missingArtifact = "/v1/artifacts/art_missing";
                assertNetworkError(exchange(port, "GET", missingArtifact
                        + "?genTime=-1", "", ""), 404, "ARTIFACT_NOT_FOUND");
                for (String query : List.of("", "?genTime=", "?genTime=-2",
                        "?genTime=01", "?genTime=9223372036854775808",
                        "?genTime=-1&extra=1")) {
                    assertNetworkError(exchange(port, "GET", missingArtifact + query,
                            "", ""), 400, "INVALID_REQUEST");
                }
                for (String query : List.of("", "?genTime=", "?genTime=-2",
                        "?genTime=01", "?genTime=9223372036854775808",
                        "?genTime=-1&extra=1", "?genTime=-1&genTime=0")) {
                    assertNetworkError(exchange(port, "GET", invalidManifestPath + query,
                            "", ""), 400, "INVALID_REQUEST");
                }
                assertNetworkError(exchange(port, "GET", invalidManifestPath
                        + "?genTime=-1", "{}", ""), 400, "INVALID_REQUEST");
                assertNetworkError(exchange(port, "GET", invalidManifestPath
                        + "/extra?genTime=-1", "", ""), 404, "ROUTE_NOT_FOUND");
                assertNetworkError(exchange(port, "POST", invalidManifestPath
                        + "?genTime=-1", "", ""), 404, "ROUTE_NOT_FOUND");
            } else {
                assertNetworkError(exchange(port, "GET", invalidManifestPath
                        + "?genTime=invalid", "", ""), 501, "ROUTE_NOT_IMPLEMENTED");
            }
            DataSource source = assembly.databaseRuntime().dataSource();
            String createKey = "finalization-create-key-123456789";
            String clientSession = "finalization-client-session-sentinel";
            String created = exchange(port, "POST", "/v1/meetings",
                    createRequest(createKey, clientSession).body(),
                    networkHeaders(TOKEN, createKey, true));
            assertEquals(201, status(created));
            assertTrue(responseBody(created).contains("\"state\":\"CREATED\""));
            String meetingId = createdMeetingId(source, createKey);
            var createFact = createFact(source, meetingId);
            assertEquals(Set.of("transcript.realtime"),
                    createFact.response().grantedCapabilities());
            assertEquals(CapabilityDecision.Action.ENABLE,
                    createFact.response().capabilityDecisions().getFirst().action());
            assertEquals(List.of("mock.transcript"),
                    createFact.response().capabilityDecisions().getFirst().affectedWorkflows());
            assertEquals(List.of("transcript.final"),
                    createFact.response().capabilityDecisions().getFirst().producedArtifacts());

            var started = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                    new JdbcMeetingStartExecutionUnitOfWork(source, new CapabilityPlanner())
                            .startCompleteCreate(new MeetingStartExecutionUnitOfWork.StartRequest(
                                    SCOPE, SUBJECT, meetingId, NOW, null, trusted.profile(),
                                    Set.of("transcript.realtime"), trusted.mockCapabilities(),
                                    trusted.capabilityLimits())));
            assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, started.code());
            FinalizationExecutionFact execution = executionFact(source, meetingId);
            assertEquals(createFact.response().capabilityDecisions(),
                    execution.capabilityPlan().decisions());

            String path = "/v1/meetings/" + meetingId;
            String stopKey = "finalization-stop-key-123456789";
            String wrongStopToken = "wrong-stop-token-0123456789-abcdefgh";
            String noAuthStopKey = "no-auth-stop-key-123456789";
            String wrongAuthStopKey = "wrong-auth-stop-key-123456789";
            String shortStopKey = "short";
            String malformedStopKey = "malformed-stop-key-123456789";
            String objectStopKey = "object-stop-key-123456789";
            assertNetworkError(exchange(port, "POST", path + ":stop", "",
                    "Idempotency-Key: " + noAuthStopKey + "\r\n"),
                    401, "AUTHENTICATION_REQUIRED");
            assertNetworkError(exchange(port, "POST", path + ":stop", "",
                    networkHeaders(wrongStopToken, wrongAuthStopKey, false)),
                    401, "AUTHENTICATION_FAILED");
            assertNetworkError(exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, null, false)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, shortStopKey, false)), 400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST", path + ":stop", "{bad",
                    networkHeaders(TOKEN, malformedStopKey, true)),
                    400, "INVALID_REQUEST");
            assertNetworkError(exchange(port, "POST", path + ":stop", "{\"unexpected\":1}",
                    networkHeaders(TOKEN, objectStopKey, true)),
                    400, "INVALID_REQUEST");
            assertEquals("STARTING", meetingState(meetingId));
            assertEquals(0, count("workflow_step"));
            String firstStop = exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, stopKey, false));
            assertEquals(202, status(firstStop));
            assertTrue(responseBody(firstStop).contains("\"state\":\"STOPPING\""));
            String replayStop = exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, stopKey, false));
            assertEquals(202, status(replayStop));
            assertTrue(MessageDigest.isEqual(
                    responseBody(firstStop).getBytes(StandardCharsets.UTF_8),
                    responseBody(replayStop).getBytes(StandardCharsets.UTF_8)),
                    "pending Stop replay must preserve public response bytes");
            assertEquals(1, count("usage_operation"));
            assertEquals(0, count("durable_input_fact"));
            assertEquals(0, count("workflow_step"));

            // 仅测试可信侧执行 Admission：V008/V009、receipt 和 Workflow 由原工作单元原子提交。
            FinalizationInputFact input = new FinalizationInputFact(
                    meetingId, "mock-input-binding-" + meetingId, 3, 2_000,
                    "zh-CN", List.of(), NOW.plusSeconds(1),
                    sha256Bytes("mock-input".getBytes(StandardCharsets.UTF_8)));
            var request = new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                    SCOPE, SUBJECT, meetingId, input);
            var admission = new JdbcFinalizationAdmissionUnitOfWork(source,
                    () -> "receipt-" + UUID.randomUUID(),
                    new DeterministicMockFinalizationPlanner());
            var admitted = assertInstanceOf(FinalizationAdmissionUnitOfWork.Success.class,
                    admission.admit(request));
            assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED, admitted.code());
            var replayed = assertInstanceOf(FinalizationAdmissionUnitOfWork.Success.class,
                    admission.admit(request));
            assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED, replayed.code());
            assertEquals(admitted.finalization().finalizationReceipt(),
                    replayed.finalization().finalizationReceipt());
            assertEquals(admitted.workflowKey(), replayed.workflowKey());
            WorkflowStep step = new JdbcWorkflowStepStore(source)
                    .find(admitted.workflowKey()).orElseThrow();
            assertEquals(FinalizationCheckpoint.STEP_TYPE, step.key().stepType());
            assertEquals(FinalizationCheckpoint.CHECKPOINT_TYPE, step.checkpointType());
            FinalizationCheckpoint checkpoint = FinalizationCheckpointJson.decode(
                    step.canonicalPayload());
            assertEquals(FinalizationExecutionFactJson.sha256(execution),
                    checkpoint.executionFactDigest());
            assertEquals(FinalizationCheckpoint.inputFactDigest(input),
                    checkpoint.inputFactDigest());
            assertEquals(1, checkpoint.artifacts().size());
            assertEquals("transcript.final",
                    checkpoint.artifacts().getFirst().spec().artifactType());
            assertEquals(1, count("durable_input_fact"));
            assertEquals(1, count("final_usage_fact"));
            assertEquals(1, count("workflow_step"));
            assertEquals(0, count("artifact"));
            String pending = exchange(port, "GET", path, "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(pending));
            assertTrue(responseBody(pending).contains("\"manifestState\":\"GENERATING\""));
            String manifestPath = path + "/manifest";
            if (manifestEnabled) {
                String pendingManifest = exchange(port, "GET", manifestPath + "?genTime=-1",
                        "", "");
                assertEquals(202, status(pendingManifest));
                JsonNode pendingJson = ERROR_JSON.readTree(responseBody(pendingManifest));
                assertEquals(meetingId, pendingJson.path("meetingId").asText());
                assertEquals("GENERATING", pendingJson.path("manifestState").asText());
                assertFalse(pendingJson.has("artifacts"));
                assertEquals(202, status(exchange(port, "GET", manifestPath + "?genTime=-1",
                        "", networkHeaders(wrongStopToken, null, false))),
                        "invalid Bearer must not turn anonymous Manifest GET into login");
            }
            String prematureDeleteKey = "premature-delete-key-123456789";
            assertNetworkError(exchange(port, "DELETE", path, "",
                    networkHeaders(TOKEN, prematureDeleteKey, false)),
                    409, "DELETE_INVALID_MEETING_STATE");
            assertEquals(0, count("meeting_deletion_plan"));

            Clock workerClock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
            EntitlementIdGenerator ids = new EntitlementIdGenerator() {
                @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                @Override public String newReservationId() {
                    return "reservation_" + UUID.randomUUID();
                }
                @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
            };
            var entitlement = new JdbcReferenceDevEntitlementProvider(source,
                    new ReferenceDevEntitlementPolicy(trusted.entitlement(), workerClock, ids),
                    workerClock);
            var worker = new MockFinalizationWorker(
                    new JdbcWorkflowStepStore(source), artifacts, entitlement,
                    new JdbcFinalizationCompletionUnitOfWork(source),
                    new MockFinalizationWorker.Config("t15-finalization-worker", workerClock,
                            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, 1, 3));
            var completed = worker.runOnce();
            assertEquals(1, completed.claimedCount());
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    completed.items().getFirst().status());
            assertTrue(worker.runOnce().idle(), "terminal Worker replay must be idle");
            assertEquals(WorkflowStep.Status.COMPLETED,
                    new JdbcWorkflowStepStore(source).find(admitted.workflowKey())
                            .orElseThrow().status());

            MeetingManifest manifest = manifest(source, meetingId);
            assertEquals(ManifestStatus.READY, manifest.status());
            assertEquals(1, manifest.manifestRevision());
            assertEquals(1, manifest.artifacts().size());
            MeetingManifest.ArtifactRef reference = manifest.artifacts().getFirst();
            assertEquals("transcript.final", reference.type());
            assertEquals(MeetingManifest.ArtifactStatus.READY, reference.status());
            var material = checkpoint.artifacts().getFirst();
            assertEquals(material.spec().artifactId(), reference.artifactId());
            var readyMetadata = artifactMetadata(source, meetingId);
            assertEquals(1, readyMetadata.size());
            var persistedArtifact = readyMetadata.getFirst().artifact();
            assertEquals(material.spec(), persistedArtifact.intent().spec());
            assertEquals(material.claim(), persistedArtifact.content());
            assertEquals(reference.artifactId(), persistedArtifact.intent().spec().artifactId());
            assertEquals(reference.size(), persistedArtifact.content().size());
            assertEquals(reference.checksum(), persistedArtifact.content().checksum());
            byte[] physical;
            try (ArtifactStore.ReadHandle read = artifacts.openRead(
                    persistedArtifact.intent().binding())) {
                physical = read.content().readAllBytes();
                assertEquals(persistedArtifact, read.artifact());
            }
            assertTrue(MessageDigest.isEqual(material.canonicalContentBytes(), physical),
                    "physical Artifact must match the frozen checkpoint bytes");
            assertTrue(physical.length > 0, "published Artifact must contain physical bytes");
            assertEquals(reference.size(), physical.length);
            assertEquals(reference.checksum(), sha256Bytes(physical));
            String artifactPath = "/v1/artifacts/" + reference.artifactId();
            if (manifestEnabled) {
                String content = exchange(port, "GET", artifactPath + "?genTime=-1", "", "");
                assertEquals(200, status(content));
                assertEquals(new String(physical, StandardCharsets.UTF_8),
                        responseBody(content));
                assertTrue(responseBody(content).contains("【模拟最终转写】"));
                assertNetworkError(exchange(port, "GET", artifactPath + "?genTime="
                        + NOW.minusSeconds(61).toEpochMilli(), "", ""),
                        403, "ACCESS_DENIED");
            }
            assertEquals(1, count("artifact"));
            assertEquals(1, count("meeting_manifest_revision"));
            assertEquals(2, count("usage_operation"));
            assertEquals("READY", meetingState(meetingId));
            var manifestQuery = new JdbcMeetingManifestQueryPort(source,
                    new ManifestResultAccessPolicy(CLOCK, Duration.ofHours(1)));
            var found = assertInstanceOf(MeetingManifestPort.Found.class,
                    manifestQuery.get(new MeetingManifestPort.Request(meetingId, -1)));
            assertEquals(manifest, found.manifest());
            if (manifestEnabled) {
                String completeManifest = exchange(port, "GET", manifestPath
                        + "?genTime=" + NOW.toEpochMilli(), "", "");
                assertEquals(200, status(completeManifest));
                assertEquals(MeetingManifestJson.encode(manifest), responseBody(completeManifest));
                assertEquals(200, status(exchange(port, "GET", manifestPath
                        + "?genTime=" + NOW.toEpochMilli(), "",
                        networkHeaders(wrongStopToken, null, false))),
                        "invalid Bearer must not turn anonymous Manifest GET into login");
                assertEquals(200, status(exchange(port, "GET", manifestPath + "?genTime="
                        + NOW.minusSeconds(60).plusMillis(1).toEpochMilli(), "", "")),
                        "link remains valid 1 ms before the TTL boundary");
                assertEquals(200, status(exchange(port, "GET", manifestPath + "?genTime=-1",
                        "", "")), "-1 is a documented permanent URL, not an auth proof");
                assertNetworkError(exchange(port, "GET", manifestPath + "?genTime="
                        + NOW.minusSeconds(60).toEpochMilli(), "", ""),
                        403, "ACCESS_DENIED");
            }

            String ready = exchange(port, "GET", path, "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(ready));
            assertTrue(responseBody(ready).contains("\"manifestState\":\"READY\""));
            assertTrue(responseBody(ready).contains(
                    admitted.finalization().finalizationReceipt()));
            String list = exchange(port, "GET", "/v1/meetings", "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(list));
            assertTrue(responseBody(list).contains(meetingId));
            String acceptedStop = exchange(port, "POST", path + ":stop", "",
                    networkHeaders(TOKEN, stopKey, false));
            assertEquals(200, status(acceptedStop));
            assertTrue(responseBody(acceptedStop).contains(
                    admitted.finalization().finalizationReceipt()));
            assertEquals(2, count("usage_operation"));

            Map<String, String> other = new HashMap<>(config());
            String otherToken = "other-finalization-token-0123456789-abcdefgh";
            String foreignDeleteKey = "foreign-delete-key-123456789";
            other.put(ReferenceDevIdentityConfig.BEARER_TOKEN, otherToken);
            other.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, "other-finalization-subject");
            try (var foreign = ReferenceMeetingHttpAssembly.start(other, CLOCK);
                 var foreignNetwork = foreign.newJdkHttpServer(
                         new InetSocketAddress("127.0.0.1", 0))) {
                foreignNetwork.start();
                assertNetworkError(exchange(foreignNetwork.address().getPort(), "GET", path, "",
                        networkHeaders(otherToken, null, false)), 403, "ACCESS_DENIED");
                String foreignList = exchange(foreignNetwork.address().getPort(), "GET",
                        "/v1/meetings", "", networkHeaders(otherToken, null, false));
                assertEquals(200, status(foreignList));
                assertFalse(responseBody(foreignList).contains(meetingId));
                assertNetworkError(exchange(foreignNetwork.address().getPort(),
                        "DELETE", path, "",
                        networkHeaders(otherToken, foreignDeleteKey, false)),
                        403, "ACCESS_DENIED");
            }
            Map<String, String> noDelete = new HashMap<>(config());
            String readOnlyToken = "read-only-finalization-token-0123456789-abcdefgh";
            String deniedDeleteKey = "denied-delete-key-123456789";
            noDelete.put(ReferenceDevIdentityConfig.BEARER_TOKEN, readOnlyToken);
            noDelete.put(ReferenceDevIdentityConfig.PERMISSIONS,
                    "meeting:create," + MeetingPermissions.STOP);
            try (var readOnly = ReferenceMeetingHttpAssembly.start(noDelete, CLOCK);
                 var readOnlyNetwork = readOnly.newJdkHttpServer(
                         new InetSocketAddress("127.0.0.1", 0))) {
                readOnlyNetwork.start();
                assertNetworkError(exchange(readOnlyNetwork.address().getPort(),
                        "DELETE", path, "",
                        networkHeaders(readOnlyToken, deniedDeleteKey, false)),
                        403, "PERMISSION_DENIED");
            }
            assertEquals(0, count("meeting_deletion_plan"));
            String deleteKey = "finalization-delete-key-123456789";
            // Worker 已用 NOW+2 更新会议；真实 HTTP 删除时间必须随生命周期向前。
            httpClock.advanceTo(NOW.plusSeconds(3));
            String deleted = exchange(port, "DELETE", path, "",
                    networkHeaders(TOKEN, deleteKey, false));
            assertEquals(202, status(deleted));
            assertEquals(202, status(exchange(port, "DELETE", path, "",
                    networkHeaders(TOKEN, deleteKey, false))));
            assertEquals("DELETION_PENDING", meetingState(meetingId));
            String pendingDeletion = exchange(port, "GET", path, "",
                    networkHeaders(TOKEN, null, false));
            assertEquals(200, status(pendingDeletion));
            assertTrue(responseBody(pendingDeletion).contains(
                    "\"manifestState\":\"DELETION_PENDING\""));
            assertEquals(1, count("meeting_deletion_plan"));
            assertEquals(1, count("meeting_deletion_plan WHERE target_count=1"));
            assertEquals(2, count("meeting_manifest_revision"));
            assertEquals(1, count("artifact"));
            assertEquals(2, count("usage_operation"));
            assertTrue(worker.runOnce().idle(), "deletion must not requeue finalization");
            assertInstanceOf(MeetingManifestPort.AccessDenied.class,
                    manifestQuery.get(new MeetingManifestPort.Request(meetingId, -1)));
            if (manifestEnabled) {
                assertNetworkError(exchange(port, "GET", manifestPath + "?genTime=-1",
                        "", ""), 403, "ACCESS_DENIED");
                assertNetworkError(exchange(port, "GET", artifactPath + "?genTime=-1",
                        "", ""), 403, "ACCESS_DENIED");
                assertNetworkError(exchange(port, "GET", manifestPath + "?genTime="
                        + NOW.toEpochMilli(), "", ""), 403, "ACCESS_DENIED");
            }
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
            synchronized (logs) {
                String text = logs.toString();
                for (String secret : List.of(TOKEN, wrongStopToken, otherToken, readOnlyToken,
                        createKey, clientSession, stopKey, noAuthStopKey, wrongAuthStopKey,
                        shortStopKey, malformedStopKey, objectStopKey, prematureDeleteKey,
                        foreignDeleteKey, deniedDeleteKey, deleteKey, input.inputBinding(),
                        admitted.finalization().finalizationReceipt(), password(), url())) {
                    assertFalse(text.contains(secret),
                            "HTTP and Worker logs must not contain request secrets");
                }
            }
        } finally {
            root.removeHandler(capture);
        }
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

    private static MeetingManifest manifest(DataSource source, String meetingId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var result = new JdbcManifestFactStore(connection)
                    .findLatest(meetingId).orElseThrow();
            connection.rollback();
            return result;
        }
    }

    private static List<ArtifactReadyMetadataStore.ReadyMetadata> artifactMetadata(
            DataSource source, String meetingId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var result = new JdbcArtifactReadyMetadataStore(connection)
                    .findByMeetingId(meetingId);
            connection.rollback();
            return result;
        }
    }

    private static String sha256Bytes(byte[] content) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
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

    private OwnedMeetingCreationUnitOfWork.CreateRequest createOwned(String suffix)
            throws Exception {
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
        var request = new OwnedMeetingCreationUnitOfWork.CreateRequest(
                SCOPE, SUBJECT, meetingId, "session-" + suffix,
                "create-key-" + suffix, "create-digest-" + suffix, reserve);
        var createdResult = new JdbcOwnedMeetingCreationUnitOfWork(
                fixtureDataSource, Clock.fixed(created, ZoneOffset.UTC)).create(request);
        assertInstanceOf(OwnedMeetingCreationUnitOfWork.Success.class, createdResult);
        assertEquals("CREATED", meetingState(meetingId));
        return request;
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
            if (version != 1) continue;
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

    private List<Integer> creationFactCounts() throws Exception {
        return List.of(count("meeting"), count("meeting_create_fact"),
                count("idempotency_record"), count("usage_reservation"),
                count("usage_operation"), count("outbox_event"));
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
