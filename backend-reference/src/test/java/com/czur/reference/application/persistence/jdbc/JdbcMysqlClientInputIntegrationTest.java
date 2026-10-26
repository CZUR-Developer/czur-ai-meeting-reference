package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.command.CompleteMeetingReserveFacts;
import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL5.7 正常客户端输入链实跑；每例正式初始化自己的随机库，结束仅删除该库。
 * 身份/权益使用明确测试策略、模型仅提供能力、存储为本机文件替身；不签真实 CZUR。
 * 本片不装配未完成的 MySQL HTTP/结果收口，最终快照不能写入 READY 或用量 COMMIT。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlClientInputIntegrationTest {
    private static final String SCOPE = "m2-client-部署";
    private static final String SUBJECT = "m2-client-用户😀";
    private static final Instant NOW = Instant.parse("2026-10-10T02:03:04.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Set<String> CAPABILITIES = Set.of(CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.TRANSLATION_REALTIME, CapabilityIds.SUMMARY_STRUCTURED);
    private static final ProductProfile PROFILE = new ProductProfile("m2-client", 1,
            Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
            Set.of(CapabilityIds.TRANSLATION_REALTIME, CapabilityIds.SUMMARY_STRUCTURED),
            Map.of(), Map.of(), Map.of());
    private static final CapabilityDescriptor MODEL = new CapabilityDescriptor(CAPABILITIES);
    private String databaseName;
    private JdbcDatabaseContext database;
    private int defaultIsolation;
    @TempDir Path artifactRoot;

    @BeforeEach
    void initializeOwnDatabase() throws Exception {
        databaseName = "ai_trans_m2_b_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        Path script = List.of(Path.of("deploy/migrate-mysql.sh"),
                Path.of("../deploy/migrate-mysql.sh")).stream().filter(Files::isRegularFile)
                .findFirst().orElseThrow().toAbsolutePath();
        var builder = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        builder.environment().put("DATABASE_URL", scopedUrl());
        builder.environment().put("DATABASE_USER", required("REFERENCE_TEST_MYSQL_DATABASE_USER"));
        builder.environment().put("DATABASE_PASSWORD", required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
        builder.environment().remove("MIGRATION_DIR");
        var process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "隔离库初始化超时");
        assertEquals(0, process.exitValue(), "正式 MySQL 初始化必须成功");
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(scopedUrl(),
                required("REFERENCE_TEST_MYSQL_DATABASE_USER"),
                required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"),
                Duration.ofSeconds(2), Duration.ofSeconds(1), 3, 0));
        try (var connection = database.dataSource().getConnection()) {
            defaultIsolation = connection.getTransactionIsolation();
        }
    }

    @AfterEach
    void closeAndDropOnlyOwnDatabase() throws Exception {
        if (database != null) database.close();
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_m2_b_[0-9a-f]{32}"));
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void chineseFinalRevisionsTranslationsAndNonzeroUsageShareOneReadOnlySnapshot() throws Exception {
        String meetingId = createMeeting("revisions", false);
        var input = input();
        var language = new JdbcModelSessionLanguageLookup(database);
        assertEquals("zh-CN", language.load(context(), meetingId).sourceLanguage());
        assertEquals(List.of("en-US"), language.load(context(), meetingId).targetLanguages());
        assertTrue(language.load(context(), meetingId).translationEnabled());
        assertFalse(language.summaryEligibility(context(), meetingId).clientStarted());
        assertStarted(input, meetingId);
        assertStarted(input, meetingId);
        assertInstanceOf(RealtimeStartFactsLookup.Started.class,
                new JdbcRealtimeStartFactsLookup(database).find(SCOPE, meetingId));
        assertEquals(1, count("ai_trans_finalization_execution_fact"));
        assertEquals(1, count("ai_trans_meeting_client_input"));
        assertTrue(language.summaryEligibility(context(), meetingId).clientStarted());
        assertTrue(language.summaryEligibility(context(), meetingId).granted());

        append(input, meetingId, transcript("partial", 1, "source", 1, "初稿", false));
        append(input, meetingId, transcript("first-final", 2, "source", 2, "第一版", true));
        var finalRevision = transcript("last-final", 3, "source", 3, "最终中文😀\n保留空白  ", true);
        append(input, meetingId, finalRevision);
        assertFalse(assertInstanceOf(ClientMeetingInputPort.Appended.class,
                input.append(context(), meetingId, finalRevision)).created());
        append(input, meetingId, new ClientMeetingInputPort.ClientTranslation("translation-partial", 4,
                new CanonicalTranslationEvent("translation", "source", 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, "zh-CN", "en-US",
                        "Draft", false, List.of())));
        append(input, meetingId, new ClientMeetingInputPort.ClientTranslation("translation-final", 5,
                new CanonicalTranslationEvent("translation", "source", 2,
                        CanonicalTranscriptEvent.Operation.REPLACE, "zh-CN", "en-US",
                        "Final translation 😀", true, List.of())));
        append(input, meetingId, transcript("unfinished", 6, "unfinished", 1, "不进入最终结果", false));
        assertEquals(new ClientMeetingInputPort.UsageSubmitted(2500, true),
                input.submitUsage(context(), meetingId, 2500));
        assertEquals(new ClientMeetingInputPort.UsageSubmitted(2500, false),
                input.submitUsage(context(), meetingId, 2500));
        var snapshot = snapshot(meetingId);
        assertEquals(1, snapshot.transcripts().size());
        assertEquals(3, snapshot.transcripts().getFirst().getRevision());
        assertEquals("最终中文😀\n保留空白  ", snapshot.transcripts().getFirst().getText());
        assertEquals("Final translation 😀", snapshot.translations().getFirst().getText());
        assertEquals(2500, snapshot.frozenAudioDurationMs());
        assertEquals("zh-CN", snapshot.language());
        assertEquals("本场中文标题😀", snapshot.title());
        assertTrue(snapshot.retainAudioRequested());
        assertEquals(6, new JdbcMeetingEventLogStore(database).readPage(meetingId, 0, 20).items().size());
        assertPoolRestoredAfterReadOnlyLookups();
        assertUnfinalized();
    }

    @Test
    void measuredZeroUsageIsNotUnknownAndSnapshotDoesNotPublishOrSettle() throws Exception {
        String meetingId = createMeeting("zero", false);
        var input = input();
        assertStarted(input, meetingId);
        assertEquals(new JdbcClientMeetingResultFinalizer.Rejected(ClientMeetingInputPort.Reason.INVALID_STATE),
                JdbcClientMeetingResultFinalizer.readFinalSnapshot(database, context(), meetingId));
        assertEquals(new ClientMeetingInputPort.UsageSubmitted(0, true),
                input.submitUsage(context(), meetingId, 0));
        var snapshot = snapshot(meetingId);
        assertEquals(0, snapshot.frozenAudioDurationMs());
        assertEquals(List.of(), snapshot.transcripts());
        assertEquals(List.of(), snapshot.translations());
        assertEquals("und", snapshot.language());
        assertEquals(snapshot, snapshot(meetingId));
        assertUnfinalized();
    }

    @Test
    void optionalSummaryAndOrderedCaptureUseSameClientInputAndArtifactMetadata() throws Exception {
        String meetingId = createMeeting("optional", true);
        var input = input();
        assertStarted(input, meetingId);
        var summary = new ClientMeetingInputPort.StructuredSummary("zh-CN", "中文纪要😀",
                "正常本场概述", List.of("保留本场事实"), List.of("读取最终快照"));
        assertEquals(new ClientMeetingInputPort.SummarySubmitted(true),
                input.submitSummary(context(), meetingId, summary));
        assertEquals(new ClientMeetingInputPort.SummarySubmitted(false),
                input.submitSummary(context(), meetingId, summary));
        for (int sequence = 1; sequence <= 2; sequence++) {
            byte[] content = ("{\"schemaVersion\":\"presentation-capture/v1\",\"chunkSequence\":"
                    + sequence + ",\"frames\":[{\"offsetMs\":0,\"jpegBase64\":\"/9j/2Q==\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            var chunk = new ClientMeetingInputPort.PresentationChunk(sequence, 1, content);
            assertTrue(assertInstanceOf(ClientMeetingInputPort.CaptureAppended.class,
                    input.appendPresentation(context(), meetingId, chunk)).created());
            assertFalse(assertInstanceOf(ClientMeetingInputPort.CaptureAppended.class,
                    input.appendPresentation(context(), meetingId, chunk)).created());
            try (var connection = database.dataSource().getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                var stored = new JdbcArtifactReadyMetadataStore(connection, database.engine())
                        .findByMeetingId(meetingId).stream()
                        .filter(item -> item.artifact().intent().spec().revision() == chunk.chunkSequence())
                        .findFirst().orElseThrow();
                assertEquals("presentation.capture", stored.artifact().intent().spec().artifactType());
                try (var read = artifacts().openRead(stored.artifact().intent().binding())) {
                    assertArrayEquals(content, read.content().readAllBytes());
                }
                connection.rollback();
            }
        }
        assertEquals(1, count("ai_trans_meeting_client_summary"));
        assertEquals(2, count("ai_trans_artifact"));
        assertInstanceOf(ClientMeetingInputPort.UsageSubmitted.class,
                input.submitUsage(context(), meetingId, 1500));
        var snapshot = snapshot(meetingId);
        assertEquals(1500, snapshot.frozenAudioDurationMs());
        assertEquals("中文纪要😀", snapshot.summary().path("title").asText());
        assertEquals("正常本场概述", snapshot.summary().path("overview").asText());
        assertEquals("保留本场事实", snapshot.summary().path("keyPoints").get(0).asText());
        assertEquals("读取最终快照", snapshot.summary().path("actionItems").get(0).asText());
        assertUnfinalized();
    }

    @Test
    void concurrentSameClientEventReplaysOneFactAndCanonicalWhitespaceRemainsExact() throws Exception {
        String meetingId = createMeeting("concurrent", false);
        var input = input();
        assertStarted(input, meetingId);
        var request = transcript("same-event", 1, "source", 1, "并发中文😀", true);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return input.append(context(), meetingId, request); });
            var second = executor.submit(() -> { gate.await(); return input.append(context(), meetingId, request); });
            gate.countDown();
            var one = assertInstanceOf(ClientMeetingInputPort.Appended.class, first.get(10, TimeUnit.SECONDS));
            var two = assertInstanceOf(ClientMeetingInputPort.Appended.class, second.get(10, TimeUnit.SECONDS));
            assertNotEquals(one.created(), two.created());
            assertEquals(one.event(), two.event());
            var original = one.event();
            var eventStore = new JdbcMeetingEventLogStore(database);
            assertEquals(EventLogStore.AppendStatus.REPLAYED, eventStore.append(original).status());
            var padded = new EventLog(original.specVersion(), original.meetingId(), original.streamId(),
                    original.eventId(), original.eventSequence(), original.emittedAt(), original.eventType(),
                    original.canonicalPayloadJson() + " ");
            assertEquals(EventLogStore.AppendStatus.CONFLICT, eventStore.append(padded).status());
            assertEquals(original, eventStore.readPage(meetingId, 0, 10).items().getFirst());
        }
        assertEquals(1, count("ai_trans_meeting_client_event"));
        assertEquals(1, count("ai_trans_meeting_event"));
    }

    private String createMeeting(String suffix, boolean captures) {
        String meetingId = "mtg_m2_" + suffix;
        String session = "client_" + suffix;
        var snapshot = new CreateMeetingRequestSnapshot(session,
                new CreateMeetingRequestSnapshot.ProfileRef(PROFILE.id(), PROFILE.revision()),
                new CreateMeetingRequestSnapshot.ClientDescriptor("test-client", "1", "v1", "test-device"),
                "本场中文标题😀", "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of("en-US")),
                AudioProfile.pcm16kMono(100), CAPABILITIES,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(true, captures), "m2-client-create-key-" + suffix);
        var request = new CreateMeetingRequestFact(snapshot, MeetingRequestCanonicalizer.digest(snapshot), 1);
        var policy = new ReferenceDevEntitlementPolicy(new ReferenceDevEntitlementPolicyConfig(
                SCOPE, PROFILE.id(), PROFILE.revision(), CAPABILITIES, 3_600_000,
                Duration.ofSeconds(600), Duration.ofSeconds(300)), CLOCK, new EntitlementIdGenerator() {
            public String newGrantId() { return "grant_" + suffix; }
            public String newReservationId() { return "reservation_" + suffix; }
            public String newLeaseToken() { return "test_lease_" + suffix; }
        });
        var provider = new JdbcReferenceDevEntitlementProvider(database, policy, CLOCK);
        var estimate = new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);
        var grant = provider.authorizeStart(new AuthorizeStartRequest(SCOPE, new Subject(SUBJECT),
                PROFILE.id(), PROFILE.revision(), CAPABILITIES, estimate, session)).valueOrThrow();
        var reserve = provider.reserveUsage(new ReserveUsageRequest(SCOPE, SUBJECT,
                UsageOperationScopes.RESERVE, CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant, estimate, meetingId)).valueOrThrow();
        var plan = new CapabilityPlanner().plan(MODEL, PROFILE, CAPABILITIES,
                grant.grantedCapabilities(), DeploymentLimits.NONE, 1);
        var response = new CreateMeetingResponseFact(meetingId, MeetingState.CREATED, snapshot.profile(),
                grant.grantedCapabilities(), plan.decisions(), snapshot.audioProfile(),
                new CreateMeetingResponseFact.RealtimeSession(URI.create("wss://model.example.invalid"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, "local-test-session-token",
                        NOW.plusSeconds(300)), new CreateMeetingResponseFact.Limits(3_600_000, 32_000, 100, null, 120_000));
        assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class,
                new JdbcCompleteMeetingCreationUnitOfWork(database, CLOCK).create(
                        new CompleteMeetingCreationUnitOfWork.CreateRequest(SCOPE, SUBJECT, request, response, reserve)));
        return meetingId;
    }

    private JdbcClientMeetingInputPort input() throws ArtifactStoreException {
        var coordinator = new RealtimeMeetingStartCoordinator(SCOPE,
                new JdbcRealtimeStartFactsLookup(database), (id, revision) -> Optional.of(PROFILE),
                MODEL, DeploymentLimits.NONE, CLOCK,
                new JdbcMeetingStartExecutionUnitOfWork(database, new CapabilityPlanner()));
        return new JdbcClientMeetingInputPort(database, coordinator, CLOCK, artifacts());
    }

    private PersistentFileSystemMockArtifactStore artifacts() throws ArtifactStoreException {
        return new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(SCOPE, new VerifiedIdentity(new Subject(SUBJECT), Set.of("USER"),
                Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP), NOW.minusSeconds(60),
                NOW.plusSeconds(600), null));
    }

    private static ClientMeetingInputPort.ClientTranscript transcript(String clientId, long sequence,
            String segment, int revision, String text, boolean complete) {
        return new ClientMeetingInputPort.ClientTranscript(clientId, sequence,
                new CanonicalTranscriptEvent(segment, revision, CanonicalTranscriptEvent.Operation.UPSERT,
                        0, 2000, "zh-CN", text, complete, null, 0, List.of(), List.of()));
    }

    private static void append(JdbcClientMeetingInputPort input, String id,
            ClientMeetingInputPort.ClientEvent event) {
        assertTrue(assertInstanceOf(ClientMeetingInputPort.Appended.class,
                input.append(context(), id, event)).created());
    }

    private static void assertStarted(JdbcClientMeetingInputPort input, String meetingId) {
        var result = input.start(context(), meetingId);
        assertInstanceOf(ClientMeetingInputPort.Started.class, result, result::toString);
    }

    private JdbcClientMeetingResultFinalizer.Ready snapshot(String meetingId) {
        return assertInstanceOf(JdbcClientMeetingResultFinalizer.Ready.class,
                JdbcClientMeetingResultFinalizer.readFinalSnapshot(database, context(), meetingId));
    }

    private void assertUnfinalized() throws Exception {
        assertEquals(1, count("ai_trans_usage_operation"), "只存在 reserve，最终读取不结算");
        assertEquals(0, count("ai_trans_meeting_client_result"));
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT state FROM ai_trans_meeting")) {
            assertTrue(rows.next());
            assertEquals("STARTING", JdbcValues.text(rows, "state"));
        }
    }

    /** 同时借满本例的小池，验证只读快照/分页不会污染下一次客户端写入连接。 */
    private void assertPoolRestoredAfterReadOnlyLookups() throws Exception {
        try (var first = database.dataSource().getConnection();
             var second = database.dataSource().getConnection();
             var third = database.dataSource().getConnection()) {
            for (var connection : List.of(first, second, third)) {
                assertTrue(connection.getAutoCommit());
                assertFalse(connection.isReadOnly());
                assertEquals(defaultIsolation, connection.getTransactionIsolation());
            }
        }
    }

    private int count(String fixedTable) throws Exception {
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + fixedTable)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"),
                required("REFERENCE_TEST_MYSQL_DATABASE_USER"), required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
    }

    private String scopedUrl() {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        return address.substring(0, address.lastIndexOf('/') + 1) + databaseName
                + (query < 0 ? "" : url.substring(query));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
