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
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.SpeakerReference;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨会议人物产物“已发布但数据库登记失败”的重试门禁。
 *
 * <p>每个用例新建随机 MySQL 5.7 库并执行正式 V001。第一次收尾在人物正文发布后
 * 注入一次结果 INSERT 失败；重试必须复用第一次冻结的正文、只调用一次识别
 * Provider、最终进入 READY，且对象存储里同一产物只保留一份内容。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlSpeakerIdentityRetryIntegrationTest {
    private static final String SCOPE = "m2-speaker-部署";
    private static final String SUBJECT = "m2-speaker-用户😀";
    private static final Instant NOW = Instant.parse("2026-10-10T02:03:04.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final Set<String> CAPABILITIES = Set.of(CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.SPEAKER_DIARIZATION_SESSION,
            CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
    private static final ProductProfile PROFILE = new ProductProfile("m2-speaker", 1,
            Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
            Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION,
                    CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING),
            Map.of(), Map.of(), Map.of());
    private static final CapabilityDescriptor MODEL = new CapabilityDescriptor(CAPABILITIES);
    private static final ObjectMapper JSON = new ObjectMapper();

    private String databaseName;
    private JdbcDatabaseContext database;
    private ArtifactStore store;
    @TempDir Path artifactRoot;

    @BeforeEach
    void initializeOwnDatabase() throws Exception {
        databaseName = "ai_trans_m2_speaker_" + UUID.randomUUID().toString().replace("-", "");
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
        assertEquals(0, process.exitValue(), "正式 MySQL V001 初始化必须成功");
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(scopedUrl(),
                required("REFERENCE_TEST_MYSQL_DATABASE_USER"),
                required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(20), 8, 0));
        store = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
    }

    @AfterEach
    void closeAndDropOnlyOwnDatabase() throws Exception {
        if (database != null) database.close();
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_m2_speaker_[0-9a-f]{32}"));
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void retryAfterPublishedButUnregisteredSpeakerArtifactReusesTheFrozenBody() throws Exception {
        String meetingId = createMeeting("retry");
        var input = input();
        assertInstanceOf(ClientMeetingInputPort.Started.class,
                input.start(context(), meetingId));
        append(input, meetingId, new ClientMeetingInputPort.ClientTranscript(
                "client-transcript-1", 1, new CanonicalTranscriptEvent("segment-1", 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 2000, "zh-CN",
                        "人物发言😀", true,
                        new SpeakerReference("speaker-1", null, null), 0.9,
                        List.of(), List.of())));
        byte[] audio = "synthetic-speaker-audio".getBytes(StandardCharsets.UTF_8);
        assertInstanceOf(ClientMeetingInputPort.AudioAppended.class, input.appendAudio(
                context(), meetingId, new ClientMeetingInputPort.AudioArtifact("audio/wav",
                        audio.length, "sha256:" + JdbcClientResultArtifacts.digest(audio),
                        new ByteArrayInputStream(audio))));
        assertInstanceOf(OwnedMeetingStopUnitOfWork.Success.class,
                new JdbcOwnedMeetingStopUnitOfWork(database, CLOCK).stop(
                        new OwnedMeetingStopUnitOfWork.StopRequest(context(), meetingId,
                                "m2-speaker-stop-key-123456789", "m2-speaker-stop-digest")));

        AtomicInteger identifies = new AtomicInteger();
        SpeakerIdentityProvider provider = speakerProvider(identifies);

        // 第一次收尾：正文已经发布，结果 INSERT 注入一次失败，事务整体回滚。
        var first = new JdbcClientMeetingResultFinalizer(
                failFirstResultRegistration(database.dataSource()), MYSQL,
                () -> "receipt-speaker-first", CLOCK, store, null, provider);
        assertThrows(IllegalStateException.class, () -> first.finalizeIfClient(meetingId));

        assertEquals("STOPPING", meetingState(meetingId));
        assertEquals(0, count("ai_trans_meeting_client_result"));
        assertEquals(1, count("ai_trans_artifact"), "首次登记的产物元数据必须随事务回滚");
        assertEquals(1, count("ai_trans_meeting_speaker_identity"));
        assertEquals(1, identifies.get());
        String frozenBody = frozenBody(meetingId);
        assertTrue(frozenBody.contains("\"personId\":\"person_call_1\""), frozenBody);
        assertEquals(3, blobs(), "录音、字幕与人物正文各只发布一份");

        // 第二次收尾：不再重新识别，发布同一份冻结正文并完成 READY 登记。
        var retry = new JdbcClientMeetingResultFinalizer(database,
                () -> "receipt-speaker-retry", CLOCK, store, null, provider);
        assertTrue(retry.finalizeIfClient(meetingId));

        assertEquals(1, identifies.get(), "重试必须复用首次识别结果，不再调用 Provider");
        assertEquals("READY", meetingState(meetingId));
        assertEquals(1, count("ai_trans_meeting_client_result"));
        assertEquals(3, count("ai_trans_artifact"));
        assertEquals(3, blobs(), "同一产物重试后仍只有一份正文");
        String checksum = "sha256:" + JdbcClientResultArtifacts.digest(
                frozenBody.getBytes(StandardCharsets.UTF_8));

        var manifest = resultManifest(meetingId);
        JsonNode ref = null;
        for (JsonNode item : manifest.path("artifacts")) {
            if ("speaker.identity".equals(item.path("type").asText())) ref = item;
        }
        assertNotNull(ref, "结果清单必须包含人物产物");
        assertEquals(checksum, ref.path("checksum").asText());
        assertEquals(frozenBody.getBytes(StandardCharsets.UTF_8).length, ref.path("size").asLong());
        assertEquals(frozenBody, artifactContent(ref.path("artifactId").asText()));

        // 已 READY 的再次 Stop 重放直接复用结果，不再识别。
        assertTrue(retry.finalizeIfClient(meetingId));
        assertEquals(1, identifies.get());
    }

    /**
     * “已发布但登记失败”的固定窗口：只让结果 INSERT 失败一次，其余语句原样透传。
     */
    private static DataSource failFirstResultRegistration(DataSource delegate) {
        AtomicBoolean failed = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(
                JdbcMysqlSpeakerIdentityRetryIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (method.getName().equals("getConnection")
                            && result instanceof Connection connection) {
                        return connectionFailingResultInsert(connection, failed);
                    }
                    return result;
                });
    }

    private static Connection connectionFailingResultInsert(Connection delegate,
            AtomicBoolean failed) {
        return (Connection) Proxy.newProxyInstance(
                JdbcMysqlSpeakerIdentityRetryIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (method.getName().startsWith("prepareStatement") && args != null
                            && args.length > 0 && args[0] instanceof String sql
                            && sql.contains("INSERT INTO ai_trans_meeting_client_result")
                            && result instanceof PreparedStatement statement) {
                        return statementFailingOnce(statement, failed);
                    }
                    return result;
                });
    }

    private static PreparedStatement statementFailingOnce(PreparedStatement delegate,
            AtomicBoolean failed) {
        return (PreparedStatement) Proxy.newProxyInstance(
                JdbcMysqlSpeakerIdentityRetryIntegrationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeUpdate")
                            && failed.compareAndSet(false, true)) {
                        throw new SQLException("injected client result registration failure");
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static SpeakerIdentityProvider speakerProvider(AtomicInteger identifies) {
        return new SpeakerIdentityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor("test-speaker",
                        Set.of(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING));
            }

            @Override public Identification identify(MeetingSpeakers request) {
                int call = identifies.incrementAndGet();
                return new Identification(Map.of(
                        request.speakers().getFirst().sessionSpeakerId(),
                        new Person("person_call_" + call, "人物" + call)));
            }

            @Override public Person enroll(Enrollment request) {
                throw new UnsupportedOperationException("本例不注册人物");
            }
        };
    }

    private String createMeeting(String suffix) {
        String meetingId = "mtg_m2_speaker_" + suffix;
        String session = "client_" + suffix;
        var snapshot = new CreateMeetingRequestSnapshot(session,
                new CreateMeetingRequestSnapshot.ProfileRef(PROFILE.id(), PROFILE.revision()),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "test-client", "1", "v1", "test-device"),
                "人物识别标题😀", "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), CAPABILITIES,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(true, false),
                "m2-speaker-create-key-" + suffix);
        var request = new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
        var policy = new ReferenceDevEntitlementPolicy(new ReferenceDevEntitlementPolicyConfig(
                SCOPE, PROFILE.id(), PROFILE.revision(), CAPABILITIES, 3_600_000,
                Duration.ofSeconds(600), Duration.ofSeconds(300)), CLOCK,
                new EntitlementIdGenerator() {
                    @Override public String newGrantId() { return "grant_" + suffix; }
                    @Override public String newReservationId() { return "reservation_" + suffix; }
                    @Override public String newLeaseToken() { return "test_lease_" + suffix; }
                });
        var provider = new JdbcReferenceDevEntitlementProvider(database, policy, CLOCK);
        var estimate = new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);
        var grant = provider.authorizeStart(new AuthorizeStartRequest(SCOPE,
                new Subject(SUBJECT), PROFILE.id(), PROFILE.revision(), CAPABILITIES,
                estimate, session)).valueOrThrow();
        var reserve = provider.reserveUsage(new ReserveUsageRequest(SCOPE, SUBJECT,
                UsageOperationScopes.RESERVE, CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant, estimate, meetingId)).valueOrThrow();
        var plan = new CapabilityPlanner().plan(MODEL, PROFILE, CAPABILITIES,
                grant.grantedCapabilities(), DeploymentLimits.NONE, 1);
        var response = new CreateMeetingResponseFact(meetingId, MeetingState.CREATED,
                snapshot.profile(), grant.grantedCapabilities(), plan.decisions(),
                snapshot.audioProfile(), new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://model.example.invalid"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "local-test-session-token", NOW.plusSeconds(300)),
                new CreateMeetingResponseFact.Limits(3_600_000, 32_000, 100, null, 120_000));
        assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class,
                new JdbcCompleteMeetingCreationUnitOfWork(database, CLOCK).create(
                        new CompleteMeetingCreationUnitOfWork.CreateRequest(
                                SCOPE, SUBJECT, request, response, reserve)));
        return meetingId;
    }

    private JdbcClientMeetingInputPort input() {
        var coordinator = new RealtimeMeetingStartCoordinator(SCOPE,
                new JdbcRealtimeStartFactsLookup(database), (id, revision) -> Optional.of(PROFILE),
                MODEL, DeploymentLimits.NONE, CLOCK,
                new JdbcMeetingStartExecutionUnitOfWork(database, new CapabilityPlanner()));
        return new JdbcClientMeetingInputPort(database, coordinator, CLOCK, store);
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(SCOPE, new VerifiedIdentity(new Subject(SUBJECT),
                Set.of("USER"), Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP),
                NOW.minusSeconds(60), NOW.plusSeconds(600), null));
    }

    private static void append(JdbcClientMeetingInputPort input, String meetingId,
            ClientMeetingInputPort.ClientEvent event) {
        assertTrue(assertInstanceOf(ClientMeetingInputPort.Appended.class,
                input.append(context(), meetingId, event)).created());
    }

    private String frozenBody(String meetingId) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "SELECT body_json FROM ai_trans_meeting_speaker_identity WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString("body_json");
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode resultManifest(String meetingId) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "SELECT manifest_json FROM ai_trans_meeting_client_result WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return JSON.readTree(rows.getString("manifest_json"));
            }
        }
    }

    private String artifactContent(String artifactId) throws Exception {
        try (var connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                var metadata = new JdbcArtifactReadyMetadataStore(connection, MYSQL)
                        .findByArtifactId(artifactId).orElseThrow();
                try (var read = store.openRead(metadata.artifact().intent().binding())) {
                    return new String(read.content().readAllBytes(), StandardCharsets.UTF_8);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private String meetingState(String meetingId) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "SELECT state FROM ai_trans_meeting WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return JdbcValues.text(rows, "state");
            }
        }
    }

    private int count(String fixedTable) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + fixedTable)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private long blobs() throws Exception {
        try (var files = Files.list(artifactRoot.resolve("objects"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".blob")).count();
        }
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"),
                required("REFERENCE_TEST_MYSQL_DATABASE_USER"),
                required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
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
