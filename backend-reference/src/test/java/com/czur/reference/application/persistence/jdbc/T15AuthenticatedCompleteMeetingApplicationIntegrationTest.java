package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingCreateResult;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.CompleteMeetingReserveFacts;
import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.command.TrustedCreateRuntimeLimits;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.http.CreateMeetingHttpRequestDecoder;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.HttpRequest;
import com.czur.reference.application.http.MeetingHttpAdapter;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.port.CompleteMeetingCreateReplayLookup;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.CreateRealtimeSessionAllocator;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整认证 Create 的真实 PostgreSQL 编排门禁。
 *
 * <p>每例在调用者提供的一次性 PostgreSQL 16 上创建随机 schema，独立连接运行
 * V003/V013。并发例让双方确实先观察 V013 和 V003 未命中，再同时预占；它只
 * 验证同进程两个独立应用服务，跨 JVM 数据库工作单元竞争由 T15-12-06 覆盖。
 * 未显式提供随机测试数据库时本类不执行，绝不连接固定共享地址。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class T15AuthenticatedCompleteMeetingApplicationIntegrationTest {
    private static final String DEPLOYMENT = "complete-app-test";
    private static final String SUBJECT = "complete-app-subject";
    private static final String REALTIME = CapabilityIds.TRANSCRIPT_REALTIME;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "complete_app_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement =
                     connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : List.of("V001__initial_schema.sql",
                "V002__outbox_lease_fencing.sql", "V003__identity_entitlement.sql",
                "V004__meeting_event_envelope.sql",
                "V005__meeting_finalization_fact.sql",
                "V006__meeting_read_model.sql",
                "V007__meeting_manifest_revision.sql",
                "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql",
                "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql",
                "V013__meeting_create_fact.sql")) {
            apply(migration);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection(); Statement statement =
                         connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void refusesKnownSharedPortBeforeDataSourceCreation() {
        assertThrows(IllegalStateException.class, () -> isolatedDatabaseUrl(
                "jdbc:postgresql://127.0.0.1:55432/shared"));
        assertThrows(IllegalStateException.class, () -> isolatedDatabaseUrl(
                "jdbc:postgresql://localhost:55432/shared"));
        assertEquals("jdbc:postgresql://127.0.0.1:33073/disposable",
                isolatedDatabaseUrl("jdbc:postgresql://127.0.0.1:33073/disposable"));
    }

    @Test
    void enabledHttpAdapterCreatesReplaysAndConflictsAgainstV013() throws Exception {
        String key = "http-create-key-12345";
        var service = service("http", "http-first-token",
                new JdbcUsageReserveLookup(dataSource),
                new JdbcCompleteMeetingCreateReplayLookup(dataSource), actualUnitOfWork());
        MeetingQueryPort query = new MeetingQueryPort() {
            @Override public Result get(GetRequest request) { throw new AssertionError("no query"); }
            @Override public ListResult list(ListRequest request) { throw new AssertionError("no list"); }
        };
        MeetingDeletionPort deletion = request -> { throw new AssertionError("no delete"); };
        MeetingHttpAdapter.RequestDecoder oldDecoder = (request, context) -> {
            throw new AssertionError("no old decoder");
        };
        var stopService = new AuthenticatedMeetingStopApplicationService(
                DEPLOYMENT, request -> { throw new AssertionError("no stop identity"); },
                request -> { throw new AssertionError("no stop UOW"); }, query);
        var headers = Map.of("Authorization", "Bearer integration-token",
                "Idempotency-Key", key, "Content-Type", "application/json");
        String body = """
                {"clientSessionId":"client-session-%s","profile":{"id":"standard","revision":1},
                 "client":{"appId":"app","appVersion":"1","contractVersion":"v1","deviceId":"device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":false}}
                """.formatted(key);
        try (var runtime = new MeetingHttpRuntime(query, deletion, oldDecoder,
                new HttpErrorMapper(() -> "http-integration-diag"), stopService, service,
                new CreateMeetingHttpRequestDecoder("meeting-api", "android-app"), true)) {
            var request = new HttpRequest("POST", "/v1/meetings", headers, body);
            var first = runtime.handle(request, null);
            var replay = runtime.handle(request, null);
            assertEquals(201, first.status());
            assertEquals(201, replay.status());
            assertEquals(first.body(), replay.body());
            assertTrue(first.body().contains("http-first-token"));
            assertFalse(first.body().contains("schemaVersion"));
            assertEquals(409, runtime.handle(new HttpRequest("POST", "/v1/meetings", headers,
                    body.replace("client-session-", "different-session-")), null).status());
            assertEquals(1, count("SELECT count(*) FROM meeting_create_fact"));
            assertEquals(1, count("SELECT count(*) FROM meeting"));
            assertEquals(1, count("SELECT count(*) FROM usage_reservation"));
            assertEquals(1, count("SELECT count(*) FROM outbox_event"));
        }
    }

    @Test
    void twoObservedFirstMissesConvergeOnOnePersistentWinner() throws Exception {
        var fact = requestFact("create-concurrent-key-12345");
        var bothMissed = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var firstCreateMisses = new AtomicInteger();
        var firstReserveMisses = new AtomicInteger();
        var serviceA = service("a", "candidate-token-a",
                observedFirstReserveMiss(bothMissed, release, firstReserveMisses),
                observedFirstCreateMiss(firstCreateMisses), actualUnitOfWork());
        var serviceB = service("b", "candidate-token-b",
                observedFirstReserveMiss(bothMissed, release, firstReserveMisses),
                observedFirstCreateMiss(firstCreateMisses), actualUnitOfWork());
        var executor = Executors.newFixedThreadPool(2);
        SecretCredential credentialA = secret();
        SecretCredential credentialB = secret();
        try {
            var first = executor.submit(() -> serviceA.create(
                    verification(credentialA), fact));
            var second = executor.submit(() -> serviceB.create(
                    verification(credentialB), fact));
            assertTrue(bothMissed.await(10, TimeUnit.SECONDS),
                    "双方都必须先见 V003 miss，不能把顺序重放当并发");
            assertEquals(2, firstCreateMisses.get());
            assertEquals(2, firstReserveMisses.get());
            release.countDown();
            var resultA = assertInstanceOf(
                    AuthenticatedCompleteMeetingCreateResult.Success.class,
                    first.get(15, TimeUnit.SECONDS));
            var resultB = assertInstanceOf(
                    AuthenticatedCompleteMeetingCreateResult.Success.class,
                    second.get(15, TimeUnit.SECONDS));
            assertEquals(Set.of(AuthenticatedCompleteMeetingCreateResult.SuccessCode.CREATED,
                    AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED),
                    Set.of(resultA.code(), resultB.code()));
            assertEquals(resultA.response().meetingId(), resultB.response().meetingId());
            assertEquals(resultA.response().realtime().sessionToken(),
                    resultB.response().realtime().sessionToken());
            assertTrue(Set.of("candidate-token-a", "candidate-token-b")
                    .contains(resultA.response().realtime().sessionToken()));
            String losingToken = resultA.code()
                    == AuthenticatedCompleteMeetingCreateResult.SuccessCode.CREATED
                    ? "candidate-token-b" : "candidate-token-a";
            assertNotEquals(losingToken, resultB.response().realtime().sessionToken(),
                    "败者只能收到 V013 提交赢家的 Token，未必是 V003 会议 ID 生成方的 Token");
            assertFalse(resultA.toString().contains("candidate-token"));
            assertFalse(resultB.toString().contains("candidate-token"));
            assertTrue(credentialA.isDestroyed());
            assertTrue(credentialB.isDestroyed());
            assertEquals(1, count("SELECT count(*) FROM usage_operation"));
            assertEquals(1, count("SELECT count(*) FROM usage_reservation"));
            assertEquals(1, count("SELECT count(*) FROM meeting"));
            assertEquals(1, count("SELECT count(*) FROM meeting_create_fact"));
            assertEquals(1, count("SELECT count(*) FROM outbox_event"));
            assertEquals(1, count("SELECT count(*) FROM usage_reservation "
                    + "WHERE bound_meeting_id IS NOT NULL AND version = 1"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unknownAfterRealCommitReplaysV013AndUnknownBeforeCommitStaysUnknown()
            throws Exception {
        var committedFact = requestFact("create-unknown-committed-12345");
        var actual = actualUnitOfWork();
        CompleteMeetingCreationUnitOfWork lostConfirmation = request -> {
            var result = actual.create(request);
            assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class, result);
            return unknown();
        };
        var committed = service("commit", "candidate-token-commit",
                new JdbcUsageReserveLookup(dataSource),
                new JdbcCompleteMeetingCreateReplayLookup(dataSource), lostConfirmation)
                .create(verification(secret()), committedFact);
        var replayed = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Success.class, committed);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED,
                replayed.code());
        assertEquals("candidate-token-commit", replayed.response().realtime().sessionToken());
        assertEquals(1, count("SELECT count(*) FROM meeting_create_fact"));

        var uncommittedFact = requestFact("create-unknown-uncommitted-12345");
        var uncommitted = service("missing", "candidate-token-missing",
                new JdbcUsageReserveLookup(dataSource),
                new JdbcCompleteMeetingCreateReplayLookup(dataSource), request -> unknown())
                .create(verification(secret()), uncommittedFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.CreationFailure.class, uncommitted);
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure.code());
        assertFalse(failure.toString().contains("candidate-token-missing"));
        assertEquals(1, count("SELECT count(*) FROM meeting_create_fact"));
        assertEquals(2, count("SELECT count(*) FROM usage_reservation"));
        assertEquals(1, count("SELECT count(*) FROM usage_reservation "
                + "WHERE bound_meeting_id IS NULL AND version = 0 AND status = 'RESERVED'"));
        assertEquals(1, count("SELECT count(*) FROM outbox_event"));
    }

    @Test
    void completeCreateStartUsesFrozenRevisionOnePlan() throws Exception {
        var created = createForStart("create-start-consistent-12345", "start-ok");
        var start = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        created.response().meetingId(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, start.code());
        assertEquals(MeetingState.STARTING, start.meeting().state());
        assertEquals(1L, start.executionFact().capabilityPlan().planRevision());
        assertEquals(created.response().capabilityDecisions(),
                start.executionFact().capabilityPlan().decisions());
        assertEquals(1, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void realtimeStartUsesV013FirstAndV011FrozenReplayWithoutCurrentCatalog()
            throws Exception {
        var created = createForStart("realtime-start-first-12345", "realtime-start-first");
        String meetingId = created.response().meetingId();
        var first = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                realtimeStarter(NOW.plusSeconds(11), true).start(meetingId));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, first.code());
        assertEquals(NOW.plusSeconds(11), first.executionFact().startedAt());

        // V011 已有完整 Profile；旧 V013 损坏、目录失去旧版本和时钟变化均
        // 不得改写历史。首次路径的坏 V013 另由缺事实用例关闭式验证。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE meeting_create_fact SET request_digest = repeat('a', 64) "
                             + "WHERE meeting_id = ?")) {
            statement.setString(1, meetingId);
            statement.executeUpdate();
        }
        var replayResult = realtimeStarter(NOW.plusSeconds(25), false).start(meetingId);
        var replay = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                replayResult, replayResult.toString());
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.executionFact(), replay.executionFact());
        assertEquals(1, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void concurrentRealtimeFirstStartsKeepOneV011WinnerAndReplayItsTime()
            throws Exception {
        var created = createForStart("realtime-start-race-12345", "realtime-start-race");
        String meetingId = created.response().meetingId();
        CountDownLatch begin = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<MeetingStartExecutionUnitOfWork.Result> earlier = pool.submit(() -> {
                begin.await();
                return realtimeStarter(NOW.plusSeconds(11), true).start(meetingId);
            });
            Future<MeetingStartExecutionUnitOfWork.Result> later = pool.submit(() -> {
                begin.await();
                return realtimeStarter(NOW.plusSeconds(12), true).start(meetingId);
            });
            begin.countDown();
            var leftResult = earlier.get(20, TimeUnit.SECONDS);
            var rightResult = later.get(20, TimeUnit.SECONDS);
            var left = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                    leftResult, leftResult.toString());
            var right = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                    rightResult, rightResult.toString());
            assertNotEquals(left.code(), right.code());
            assertEquals(left.executionFact(), right.executionFact());
            assertEquals(1, count("SELECT count(*) FROM finalization_execution_fact"));
        }
    }

    @Test
    void realtimeFirstStartRejectsMissingV013WithoutCreatingExecutionFact()
            throws Exception {
        var created = createForStart("realtime-start-no-v013-12345", "realtime-no-v013");
        String meetingId = created.response().meetingId();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM meeting_create_fact WHERE meeting_id = ?")) {
            statement.setString(1, meetingId);
            statement.executeUpdate();
        }
        var failure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                realtimeStarter(NOW.plusSeconds(11), true).start(meetingId));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT,
                failure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void completeCreateStartRejectsChangedModelAndDeploymentWithoutWritingV011()
            throws Exception {
        var modelMeeting = createForStart("create-start-model-change-12345", "model-change");
        var modelFailure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        modelMeeting.response().meetingId(),
                        new CapabilityDescriptor(Set.of()), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED,
                modelFailure.code());

        var deploymentMeeting = createForStart(
                "create-start-deployment-change-12345", "deployment-change");
        var deploymentFailure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        deploymentMeeting.response().meetingId(), providerAll(),
                        new DeploymentLimits(Set.of(REALTIME)))));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED,
                deploymentFailure.code());
        var profileMeeting = createForStart(
                "create-start-profile-change-12345", "profile-change");
        var changedProfile = new ProductProfile("standard", 1,
                Set.of(REALTIME), Set.of(CapabilityIds.SUMMARY_STRUCTURED),
                Map.of(), Map.of(), Map.of());
        var profileFailure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(
                        new MeetingStartExecutionUnitOfWork.StartRequest(
                                DEPLOYMENT, SUBJECT, profileMeeting.response().meetingId(),
                                NOW.plusSeconds(11), null, changedProfile,
                                Set.of(REALTIME), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED,
                profileFailure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
        assertEquals(3, count("SELECT count(*) FROM meeting WHERE state='CREATED'"));
    }

    @Test
    void completeCreateStartRequiresV013ButExistingV011ReplaysBeforeReadingIt()
            throws Exception {
        var missing = createForStart("create-start-missing-fact-12345", "missing-fact");
        try (Connection connection = dataSource.getConnection(); Statement statement =
                     connection.createStatement()) {
            statement.execute("DELETE FROM meeting_create_fact WHERE meeting_id='"
                    + missing.response().meetingId() + "'");
        }
        var noFact = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        missing.response().meetingId(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT,
                noFact.code());

        var created = createForStart("create-start-replay-before-fact-12345", "replay-first");
        var first = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        created.response().meetingId(), providerAll(), DeploymentLimits.NONE)));
        try (Connection connection = dataSource.getConnection(); Statement statement =
                     connection.createStatement()) {
            statement.execute("UPDATE meeting_create_fact SET request_digest=repeat('a',64) "
                    + "WHERE meeting_id='" + created.response().meetingId() + "'");
        }
        var replay = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        created.response().meetingId(), new CapabilityDescriptor(Set.of()),
                        new DeploymentLimits(Set.of(REALTIME)))));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.executionFact(), replay.executionFact());
        assertEquals(1, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void completeCreateStartRejectsStoredScopeMismatch() throws Exception {
        var created = createForStart("create-start-scope-mismatch-12345", "scope-mismatch");
        try (Connection connection = dataSource.getConnection(); Statement statement =
                     connection.createStatement()) {
            statement.execute("UPDATE meeting_create_fact SET scope='wrong-scope'");
        }
        var failure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        created.response().meetingId(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT,
                failure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void completeCreateStartRejectsNonFirstPlanRevisionInValidV013Response()
            throws Exception {
        var created = createForStart("create-start-plan-revision-12345", "revision");
        var original = created.response();
        var changed = original.capabilityDecisions().stream()
                .map(decision -> new CapabilityDecision(decision.capability(),
                        decision.availability(), decision.requiredByProfile(),
                        decision.action(), decision.fallbackCapability(),
                        decision.affectedWorkflows(), decision.producedArtifacts(),
                        decision.warningCode(), 2L))
                .toList();
        var response = new CreateMeetingResponseFact(original.meetingId(),
                original.state(), original.acceptedProfile(),
                original.grantedCapabilities(), changed,
                original.acceptedAudioProfile(), original.realtime(), original.limits());
        overwriteCreateResponse(response);
        var failure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        original.meetingId(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT,
                failure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    @Test
    void completeCreateStartRejectsV013WithoutEnabledRealtimeDecision()
            throws Exception {
        var created = createForStart("create-start-no-realtime-12345", "no-realtime");
        var original = created.response();
        overwriteCreateResponse(new CreateMeetingResponseFact(original.meetingId(),
                original.state(), original.acceptedProfile(),
                original.grantedCapabilities(), List.of(),
                original.acceptedAudioProfile(), original.realtime(), original.limits()));
        var failure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest(
                        original.meetingId(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT,
                failure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
    }

    /** 模拟已保存响应被改写，但仍保持 V013 的规范 JSON 与 SHA-256 约束有效。 */
    private void overwriteCreateResponse(CreateMeetingResponseFact response)
            throws Exception {
        String json = CreateMeetingResponseFactJson.encode(response);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                     connection.prepareStatement("""
                             UPDATE meeting_create_fact
                                SET response_json=?,
                                    response_sha256=sha256(convert_to(?, 'UTF8'))
                              WHERE meeting_id=?
                             """)) {
            statement.setString(1, json);
            statement.setString(2, json);
            statement.setString(3, response.meetingId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void completeCreateStartRejectsOptionalDisabledDecisionDriftWithSameEnabledSet()
            throws Exception {
        String optional = CapabilityIds.SUMMARY_STRUCTURED;
        String meetingId = "mtg_optional_decision_drift";
        var requestFact = requestFact("create-start-optional-drift-12345");
        var profile = new ProductProfile("standard", 1, Set.of(REALTIME),
                Set.of(optional), Map.of(), Map.of(), Map.of());
        var granted = Set.of(REALTIME, optional);
        var grant = new MeetingGrant("grant-optional-drift", DEPLOYMENT, SUBJECT,
                "standard", 1, Set.of(REALTIME), granted, 3_600_000,
                NOW.minusSeconds(10), NOW.plusSeconds(300));
        var reservation = UsageReservation.reserved("reservation-optional-drift", grant,
                meetingId, ESTIMATE, NOW, NOW.plusSeconds(200));
        var reserve = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(requestFact),
                requestFact.canonicalRequestDigest(), grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant), reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(connection, reserve, NOW));
            connection.commit();
        }
        var provider = new CapabilityDescriptor(granted);
        var planner = new CapabilityPlanner();
        var frozen = planner.plan(provider, profile, Set.of(REALTIME), granted,
                DeploymentLimits.NONE, 1L);
        var restricted = new DeploymentLimits(Set.of(optional));
        var now = planner.plan(provider, profile, Set.of(REALTIME), granted,
                restricted, 1L);
        assertEquals(frozen.enabledCapabilities(), now.enabledCapabilities());
        assertEquals(CapabilityDecision.Action.DISABLE,
                frozen.decisionFor(optional).action());
        assertEquals(CapabilityDecision.Availability.AVAILABLE,
                frozen.decisionFor(optional).availability());
        assertEquals(CapabilityDecision.Availability.UNAVAILABLE,
                now.decisionFor(optional).availability());
        assertFalse(now.failed());

        var response = new CreateMeetingResponseFact(meetingId, MeetingState.CREATED,
                requestFact.requestSnapshot().profile(), granted, frozen.decisions(),
                requestFact.requestSnapshot().audioProfile(),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/v1/realtime/optional"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "optional-token", NOW.plusSeconds(70)),
                new CreateMeetingResponseFact.Limits(120_000, 3_200, 128, null));
        assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class,
                actualUnitOfWork().create(new CompleteMeetingCreationUnitOfWork.CreateRequest(
                        DEPLOYMENT, SUBJECT, requestFact, response, reserve)));
        var startRequest = new MeetingStartExecutionUnitOfWork.StartRequest(
                DEPLOYMENT, SUBJECT, meetingId, NOW.plusSeconds(11), null,
                profile, Set.of(REALTIME), provider, restricted);
        var failure = assertInstanceOf(MeetingStartExecutionUnitOfWork.Failure.class,
                startUnitOfWork().startCompleteCreate(startRequest));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED,
                failure.code());
        assertEquals(0, count("SELECT count(*) FROM finalization_execution_fact"));
        assertEquals(1, count("SELECT count(*) FROM meeting WHERE state='CREATED'"));
    }

    private AuthenticatedCompleteMeetingCreateResult.Success createForStart(
            String key, String suffix) {
        return assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class,
                service(suffix, "start-token-" + suffix,
                        new JdbcUsageReserveLookup(dataSource),
                        new JdbcCompleteMeetingCreateReplayLookup(dataSource), actualUnitOfWork())
                        .create(verification(secret()), requestFact(key)));
    }

    private JdbcMeetingStartExecutionUnitOfWork startUnitOfWork() {
        return new JdbcMeetingStartExecutionUnitOfWork(dataSource, new CapabilityPlanner());
    }

    private RealtimeMeetingStartCoordinator realtimeStarter(
            Instant instant, boolean profileAvailable) {
        return new RealtimeMeetingStartCoordinator(
                DEPLOYMENT, new JdbcRealtimeStartFactsLookup(dataSource),
                (id, revision) -> profileAvailable && id.equals("standard") && revision == 1
                        ? java.util.Optional.of(new ProductProfile("standard", 1,
                                Set.of(REALTIME), Set.of(), Map.of(), Map.of(), Map.of()))
                        : java.util.Optional.empty(),
                providerAll(), DeploymentLimits.NONE,
                Clock.fixed(instant, ZoneOffset.UTC), startUnitOfWork());
    }

    private static MeetingStartExecutionUnitOfWork.StartRequest startRequest(
            String meetingId, CapabilityDescriptor provider, DeploymentLimits limits) {
        return new MeetingStartExecutionUnitOfWork.StartRequest(
                DEPLOYMENT, SUBJECT, meetingId, NOW.plusSeconds(11), null,
                new ProductProfile("standard", 1, Set.of(REALTIME), Set.of(),
                        Map.of(), Map.of(), Map.of()),
                Set.of(REALTIME), provider, limits);
    }

    private static CapabilityDescriptor providerAll() {
        return new CapabilityDescriptor(Set.of(REALTIME));
    }

    private AuthenticatedCompleteMeetingApplicationService service(String suffix, String token,
            UsageReserveLookup reserveLookup,
            CompleteMeetingCreateReplayLookup createLookup,
            CompleteMeetingCreationUnitOfWork unitOfWork) {
        var clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);
        var policy = new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(DEPLOYMENT, "standard", 1,
                        Set.of(REALTIME), 3_600_000, Duration.ofMinutes(10),
                        Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC),
                new RandomEntitlementIds());
        var entitlement = new JdbcReferenceDevEntitlementProvider(dataSource, policy, clock);
        CreateRealtimeSessionAllocator allocator = request ->
                new com.czur.reference.application.command.CreateMeetingResponseFact
                        .RealtimeSession(
                        URI.create("wss://example.test/v1/realtime/" + request.meetingId()),
                        com.czur.reference.application.command.CreateMeetingResponseFact
                                .REALTIME_SUBPROTOCOL, token, NOW.plusSeconds(70));
        return new AuthenticatedCompleteMeetingApplicationService(DEPLOYMENT,
                request -> IdentityProviderResult.success(new VerifiedIdentity(
                        new Subject(SUBJECT), Set.of(), Set.of(MeetingPermissions.CREATE),
                        NOW.minusSeconds(10), NOW.plusSeconds(300), null)),
                createLookup, reserveLookup, entitlement,
                () -> "mtg_candidate_" + suffix,
                new CompleteMeetingResponseAssembler(new CapabilityPlanner(), allocator,
                        new TrustedCreateRuntimeLimits(120_000, 3_200, 128, null)),
                unitOfWork, ESTIMATE,
                (id, revision) -> id.equals("standard") && revision == 1
                        ? java.util.Optional.of(new ProductProfile("standard", 1,
                                Set.of(REALTIME), Set.of(), Map.of(), Map.of(), Map.of()))
                        : java.util.Optional.empty(),
                new CapabilityDescriptor(Set.of(REALTIME)), DeploymentLimits.NONE);
    }

    private UsageReserveLookup observedFirstReserveMiss(CountDownLatch bothMissed,
            CountDownLatch release, AtomicInteger firstMisses) {
        var actual = new JdbcUsageReserveLookup(dataSource);
        var first = new AtomicBoolean(true);
        return request -> {
            var result = actual.lookup(request);
            if (first.compareAndSet(true, false)) {
                assertInstanceOf(UsageReserveLookup.NotFound.class, result);
                firstMisses.incrementAndGet();
                bothMissed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent reserve release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("concurrent reserve interrupted");
                }
            }
            return result;
        };
    }

    private CompleteMeetingCreateReplayLookup observedFirstCreateMiss(AtomicInteger firstMisses) {
        var actual = new JdbcCompleteMeetingCreateReplayLookup(dataSource);
        var first = new AtomicBoolean(true);
        return request -> {
            var result = actual.lookup(request);
            if (first.compareAndSet(true, false)) {
                assertInstanceOf(CompleteMeetingCreateReplayLookup.NotFound.class, result);
                firstMisses.incrementAndGet();
            }
            return result;
        };
    }

    private CompleteMeetingCreationUnitOfWork actualUnitOfWork() {
        return new JdbcCompleteMeetingCreationUnitOfWork(dataSource,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
    }

    private static CompleteMeetingCreationUnitOfWork.Failure unknown() {
        return new CompleteMeetingCreationUnitOfWork.Failure(
                CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
    }

    private static SecretCredential secret() {
        return SecretCredential.copyOf(new byte[]{1, 2, 3});
    }

    private static IdentityVerificationRequest verification(SecretCredential credential) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential, "meeting-api", "android-app");
    }

    private static CreateMeetingRequestFact requestFact(String key) {
        var snapshot = new CreateMeetingRequestSnapshot("client-session-" + key,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "app", "1", "v1", "device"), null, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), Set.of(REALTIME),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), key);
        return new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
    }

    private int count(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement =
                     connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private void apply(String filename) throws Exception {
        if (!filename.equals("V001__initial_schema.sql")) return;
        Path migration = Path.of("deploy", "migrations", filename);
        if (!Files.isRegularFile(migration)) {
            migration = Path.of("..", "deploy", "migrations", filename);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement =
                     connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        String url = isolatedDatabaseUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        var postgres = new PGSimpleDataSource();
        postgres.setUrl(url);
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

    /** 与既有 T15 JDBC 门禁一致，绝不在测试中连接固定共享端口。 */
    private static String isolatedDatabaseUrl(String url) {
        if (url == null || url.isBlank()
                || url.contains("127.0.0.1:55432")
                || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared local database is forbidden");
        }
        return url;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class RandomEntitlementIds implements EntitlementIdGenerator {
        @Override public String newGrantId() {
            return "grant-" + UUID.randomUUID();
        }

        @Override public String newReservationId() {
            return "reservation-" + UUID.randomUUID();
        }

        @Override public String newLeaseToken() {
            return "lease-" + UUID.randomUUID();
        }
    }
}
