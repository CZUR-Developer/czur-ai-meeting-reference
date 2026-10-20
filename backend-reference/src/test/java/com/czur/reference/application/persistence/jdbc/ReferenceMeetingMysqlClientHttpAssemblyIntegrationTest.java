package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.speaker.JdbcReferenceSpeakerPersons;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C 正常链作者门禁：MySQL5.7、正式 V001、真实 loopback HTTP 和本机产物替身。
 * 每例新建自己的库，先放旧业务哨兵并记录 DDL／数据，结束只删除本例随机库。
 * 身份、资格和模型材料均显式注入测试端口，不请求厂商，也不代表 Cloud 真会或部署验收。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class ReferenceMeetingMysqlClientHttpAssemblyIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-10T02:03:04.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SCOPE = "m2-c-部署😀";
    private static final String SUBJECT = "m2-c-用户😀 ";
    private static final String TOKEN = "synthetic-m2-c-bearer-not-a-real-credential";
    private static final String SENTINEL = "旧业务哨兵😀  ";
    private String databaseName;
    private String sentinelDdl;
    @TempDir Path artifactRoot;

    @BeforeEach
    void initializeOnlyOwnDatabaseWithOldBusinessSentinel() throws Exception {
        databaseName = "ai_trans_m2_c_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        try (var connection = fixture(); var statement = connection.createStatement()) {
            statement.execute("SET NAMES utf8mb4");
            statement.execute("CREATE TABLE legacy_business_sentinel "
                    + "(id BIGINT PRIMARY KEY, body VARCHAR(80)) ENGINE=InnoDB");
            try (var insert = connection.prepareStatement(
                    "INSERT INTO legacy_business_sentinel VALUES (1, ?)")) {
                insert.setString(1, SENTINEL);
                insert.executeUpdate();
            }
            try (var rows = statement.executeQuery("SHOW CREATE TABLE legacy_business_sentinel")) {
                assertTrue(rows.next());
                sentinelDdl = rows.getString(2);
            }
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
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "本例隔离库初始化超时");
        assertEquals(0, process.exitValue(), "正式 MySQL V001 初始化必须成功");
        assertSentinelUnchanged();
    }

    @AfterEach
    void preserveOldTableAndDropOnlyOwnRandomDatabase() throws Exception {
        if (databaseName == null) return;
        try {
            if (sentinelDdl != null) assertSentinelUnchanged();
        } finally {
            assertTrue(databaseName.matches("ai_trans_m2_c_[0-9a-f]{32}"));
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void actualHttpNormalClientMeetingReplaysAndPublishesReadyResults() throws Exception {
        // 本例自己创建的随机库移除可选空表，证明旧默认装配不被人物插件表阻断。
        try (var connection = fixture(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE ai_trans_speaker_identity_person");
        }
        var config = config();
        IdentityProvider identity = request -> {
            assertEquals("m2-c-audience", request.audience());
            assertEquals("m2-c-client", request.clientId());
            boolean matches = request.credential().useBytes(bytes -> MessageDigest.isEqual(
                    bytes, TOKEN.getBytes(StandardCharsets.US_ASCII)));
            assertTrue(matches);
            return IdentityProviderResult.success(verifiedIdentity());
        };
        var policy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK,
                new EntitlementIdGenerator() {
                    @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                    @Override public String newReservationId() { return "reservation_" + UUID.randomUUID(); }
                    @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
                });
        AtomicReference<ModelSessionIssuer.Request> modelRequest = new AtomicReference<>();
        AtomicInteger modelIssues = new AtomicInteger();
        ModelSessionIssuer issuer = request -> {
            modelRequest.set(request);
            return new ModelSessionIssuer.Session("sample-json-ws",
                    "ws://model.example.invalid/m2-c", NOW.plusSeconds(300),
                    Map.of("taskId", "synthetic-m2-c-task-" + modelIssues.incrementAndGet()));
        };
        AtomicInteger decoratorCalls = new AtomicInteger();
        AtomicReference<TrackingEntitlementProvider> decorated = new AtomicReference<>();
        var store = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
        try (var assembly = ReferenceMeetingHttpAssembly.startClientModel(config, CLOCK,
                store, identity, policy, issuer, delegate -> {
                    decoratorCalls.incrementAndGet();
                    var wrapper = new TrackingEntitlementProvider(delegate);
                    decorated.set(wrapper);
                    return wrapper;
                });
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                     .version(HttpClient.Version.HTTP_1_1).build()) {
            network.start();
            int port = network.address().getPort();
            assertEquals(JdbcDatabaseEngine.MYSQL, assembly.databaseContext().engine());
            try (var connection = assembly.databaseContext().dataSource().getConnection()) {
                assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("5.7."),
                        "本门禁必须实际连接 MySQL5.7");
            }
            assertSame(decorated.get(), assembly.entitlementProvider());
            assertEquals(1, decoratorCalls.get());
            assertEquals(200, exchange(client, port, "GET", MeetingJdkHttpServer.READINESS_PATH,
                    "", null, false).statusCode());
            assertThrows(UnsupportedOperationException.class, assembly::databaseRuntime);
            assertThrows(UnsupportedOperationException.class,
                    () -> assembly.newInputReconciliationScan(10));
            assertThrows(UnsupportedOperationException.class,
                    () -> assembly.newRealtimeWssServer(new InetSocketAddress("127.0.0.1", 0), null));

            String createKey = "m2-c-create-key-123456789";
            var created = exchange(client, port, "POST", "/v1/meetings", createBody(), createKey, true);
            assertEquals(201, created.statusCode());
            String meetingId = json(created).path("meetingId").asText();
            assertFalse(meetingId.isBlank());
            var replay = exchange(client, port, "POST", "/v1/meetings", createBody(), createKey, true);
            assertEquals(201, replay.statusCode(), "沿用公开 Create 首次／重放的 201 协议");
            assertEquals(created.body(), replay.body(), "重放须使用持久的原 Create 材料");
            assertEquals(1, decorated.get().reserveCalls);
            assertEquals(1, count("ai_trans_meeting"));
            assertEquals(1, count("ai_trans_meeting_create_fact"));
            assertEquals(1, count("ai_trans_outbox_event"));
            assertEquals(1, count("ai_trans_usage_reservation"));
            assertEquals(1, count("ai_trans_usage_operation"));

            String meetingPath = "/v1/meetings/" + meetingId;
            var materials = exchange(client, port, "POST", meetingPath + ":modelSession", "", null, true);
            assertEquals(200, materials.statusCode());
            assertEquals(new ModelSessionIssuer.Request("zh-CN", List.of("en-US"), true, meetingId),
                    modelRequest.get());
            assertEquals("sample-json-ws", json(materials).path("adapterId").asText());
            assertEquals("synthetic-m2-c-task-1", json(materials).path("initParams").path("taskId").asText());
            for (int call = 0; call < 2; call++) {
                var started = exchange(client, port, "POST", meetingPath + ":startClient", "", null, true);
                assertEquals(200, started.statusCode());
                assertEquals("STARTING", json(started).path("state").asText());
            }
            assertEquals(1, count("ai_trans_meeting_client_input"));
            assertEquals(1, count("ai_trans_finalization_execution_fact"));

            // 本段通过真实 HTTP 和同一个 JDBC 装配读取 Start 后的状态，再请求新模型材料；
            // 只续本场，不伪改 RUNNING、不再 Create，也不改变首次冻结的语言／翻译能力。
            var running = exchange(client, port, "GET", meetingPath, "", null, true);
            assertEquals(200, running.statusCode());
            assertEquals("STARTING", json(running).path("state").asText());
            var renewed = exchange(client, port, "POST", meetingPath + ":modelSession", "", null, true);
            assertEquals(200, renewed.statusCode());
            assertEquals(meetingId, json(renewed).path("meetingId").asText());
            assertEquals("synthetic-m2-c-task-2", json(renewed).path("initParams").path("taskId").asText());
            assertEquals(new ModelSessionIssuer.Request("zh-CN", List.of("en-US"), true, meetingId),
                    modelRequest.get());
            assertEquals(2, modelIssues.get());
            assertEquals(1, count("ai_trans_meeting"));

            String appendPath = meetingPath + "/events:append";
            append(client, port, appendPath, transcript("partial", 1, "source", 1, "初稿", false));
            append(client, port, appendPath, transcript("first-final", 2, "source", 2, "第一版", true));
            String finalTranscript = transcript("last-final", 3, "source", 3, "最终中文😀  ", true);
            var finalReply = append(client, port, appendPath, finalTranscript);
            var eventReplay = exchange(client, port, "POST", appendPath, finalTranscript, null, true);
            assertEquals(200, eventReplay.statusCode());
            assertEquals(finalReply.body(), eventReplay.body());
            append(client, port, appendPath, translation("translation-partial", 4, 1, "Draft", false));
            append(client, port, appendPath, translation("translation-final", 5, 2, "Final translation 😀", true));
            append(client, port, appendPath, transcript("unfinished", 6, "unfinished", 1, "不进入最终结果", false));
            assertEquals(6, count("ai_trans_meeting_event"));
            assertEquals(6, count("ai_trans_meeting_client_event"));

            String summary = """
                    {"language":"zh-CN","title":"正常会议😀","overview":"确认本场事实",
                     "keyPoints":["保留最后修订"],"actionItems":["读取最终产物"]}
                    """;
            assertEquals(201, exchange(client, port, "POST", meetingPath + "/summary:submit",
                    summary, null, true).statusCode());
            assertEquals(200, exchange(client, port, "POST", meetingPath + "/summary:submit",
                    summary, null, true).statusCode());
            String capture = "{\"chunkSequence\":1,\"frames\":[{\"offsetMs\":0,\"jpegBase64\":\"/9j/2Q==\"}]}";
            var captured = exchange(client, port, "POST", meetingPath + "/presentation-captures:append",
                    capture, null, true);
            assertEquals(201, captured.statusCode());
            var captureReplay = exchange(client, port, "POST", meetingPath + "/presentation-captures:append",
                    capture, null, true);
            assertEquals(200, captureReplay.statusCode());
            assertEquals(captured.body(), captureReplay.body());
            long expectedVersion = reservationVersion();
            assertEquals(201, exchange(client, port, "POST", meetingPath + "/usage:submit",
                    "{\"audioDurationMs\":2500}", null, true).statusCode());
            assertEquals(200, exchange(client, port, "POST", meetingPath + "/usage:submit",
                    "{\"audioDurationMs\":2500}", null, true).statusCode());

            var before = assertInstanceOf(JdbcClientMeetingResultFinalizer.Ready.class,
                    JdbcClientMeetingResultFinalizer.readFinalSnapshot(assembly.databaseContext(),
                            new MeetingCommandContext(SCOPE, verifiedIdentity()), meetingId));
            assertEquals(2500, before.frozenAudioDurationMs());
            assertEquals("最终中文😀  ", before.transcripts().getFirst().getText());
            assertEquals(3, before.transcripts().getFirst().getRevision());
            assertEquals(1, before.transcripts().size());
            assertEquals("Final translation 😀", before.translations().getFirst().getText());
            assertEquals(2, before.translations().getFirst().getRevision());
            assertEquals(0, count("ai_trans_meeting_client_result"));
            assertEquals(1, count("ai_trans_artifact"), "只读最终快照不额外发布产物");
            assertEquals(1, count("ai_trans_usage_operation"), "只读最终快照不结算");

            String stopKey = "m2-c-stop-key-123456789";
            var stopped = exchange(client, port, "POST", meetingPath + ":stop", "", stopKey, true);
            assertEquals(200, stopped.statusCode());
            assertEquals("READY", json(stopped).path("state").asText());
            assertEquals("INPUT_ACCEPTED", json(stopped).path("inputState").asText());
            assertFalse(json(stopped).path("finalizationReceipt").asText().isBlank());
            var stopReplay = exchange(client, port, "POST", meetingPath + ":stop", "", stopKey, true);
            assertEquals(200, stopReplay.statusCode());
            assertEquals(stopped.body(), stopReplay.body());
            assertEquals(1, count("ai_trans_meeting_client_result"));
            assertEquals(4, count("ai_trans_artifact"));
            assertEquals(2, count("ai_trans_usage_operation"));
            assertEquals(2, count("ai_trans_outbox_event"));
            assertCommittedUsage(expectedVersion);
            assertEquals(2, decorated.get().commits.size());
            assertEquals(decorated.get().commits.getFirst(), decorated.get().commits.getLast());
            assertEquals(expectedVersion, decorated.get().commits.getFirst().expectedVersion());

            var detail = exchange(client, port, "GET", meetingPath, "", null, true);
            assertEquals(200, detail.statusCode());
            assertEquals("READY", json(detail).path("state").asText());
            var listed = exchange(client, port, "GET", "/v1/meetings?pageSize=1", "", null, true);
            assertEquals(200, listed.statusCode());
            assertEquals(meetingId, json(listed).path("items").get(0).path("meetingId").asText());
            assertTrue(json(listed).path("nextPageToken").isNull());
            long cursor = 0;
            for (int page = 0; page < 3; page++) {
                var response = exchange(client, port, "GET", meetingPath
                        + "/events?afterSequence=" + cursor + "&limit=2", "", null, true);
                assertEquals(200, response.statusCode());
                JsonNode eventPage = json(response);
                assertEquals(2, eventPage.path("items").size());
                assertEquals(page < 2, eventPage.path("hasMore").asBoolean());
                cursor = eventPage.path("lastSequence").asLong();
                assertEquals(2L * (page + 1), cursor);
            }
            var emptyPage = exchange(client, port, "GET", meetingPath
                    + "/events?afterSequence=6&limit=2", "", null, true);
            assertEquals(200, emptyPage.statusCode());
            assertEquals(0, json(emptyPage).path("items").size());
            assertEquals(6, json(emptyPage).path("lastSequence").asLong());

            String generation = "?genTime=" + NOW.toEpochMilli();
            var manifestResponse = exchange(client, port, "GET", meetingPath + "/manifest" + generation,
                    "", null, false);
            assertEquals(200, manifestResponse.statusCode());
            var manifest = MeetingManifestJson.decode(manifestResponse.body());
            assertEquals(2500, manifest.durationMs());
            assertEquals("正常会议😀", manifest.title());
            assertEquals(List.of("transcript.final", "translation.final", "summary.structured", "presentation.capture"),
                    manifest.artifacts().stream().map(item -> item.type()).toList());
            for (var reference : manifest.artifacts()) {
                var artifact = exchange(client, port, "GET", "/v1/artifacts/"
                        + reference.artifactId() + generation, "", null, false);
                assertEquals(200, artifact.statusCode());
                JsonNode content = json(artifact);
                switch (reference.type()) {
                    case "transcript.final" -> assertEquals("最终中文😀  ", content.path("text").asText());
                    case "translation.final" -> assertEquals("Final translation 😀",
                            content.path("translations").get(0).path("text").asText());
                    case "summary.structured" -> assertEquals("读取最终产物", content.path("actionItems").get(0).asText());
                    case "presentation.capture" -> assertEquals("/9j/2Q==",
                            content.path("frames").get(0).path("jpegBase64").asText());
                    default -> fail("正常链出现未要求的产物类型");
                }
            }
            assertEquals(501, exchange(client, port, "DELETE", meetingPath, "",
                    "m2-c-delete-key-123456789", true).statusCode());
            assertEquals(501, exchange(client, port, "POST", meetingPath + ":renewRealtimeToken",
                    "", "m2-c-renew-key-123456789", true).statusCode());
            // 借满唯一小池，证明事件 RR 和最终只读快照不污染后续连接状态。
            try (var first = assembly.databaseContext().dataSource().getConnection();
                 var second = assembly.databaseContext().dataSource().getConnection();
                 var third = assembly.databaseContext().dataSource().getConnection()) {
                for (var connection : List.of(first, second, third)) {
                    assertTrue(connection.getAutoCommit());
                    assertFalse(connection.isReadOnly());
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                }
            }
            assertSentinelUnchanged();
        }
    }

    @Test
    void optionalSpeakerProviderPersistsRegistrationAndRecognizesTheNextMeeting() throws Exception {
        var config = config(true);
        IdentityProvider identity = request -> IdentityProviderResult.success(verifiedIdentity());
        var policy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK,
                new EntitlementIdGenerator() {
                    @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                    @Override public String newReservationId() { return "reservation_" + UUID.randomUUID(); }
                    @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
                });
        ModelSessionIssuer issuer = request -> new ModelSessionIssuer.Session("sample-json-ws",
                "ws://model.example.invalid/speaker", NOW.plusSeconds(300), Map.of("taskId", "synthetic-speaker"));
        var store = new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024);
        AtomicInteger identifies = new AtomicInteger();
        AtomicInteger enrollments = new AtomicInteger();
        var failEnrollment = new java.util.concurrent.atomic.AtomicBoolean();
        try (var assembly = ReferenceMeetingHttpAssembly.startClientModel(config, CLOCK, store,
                identity, policy, issuer, java.util.function.UnaryOperator.identity(), null, null,
                (database, artifacts) -> {
                    var persons = new JdbcReferenceSpeakerPersons(database);
                    // 确定性 Provider 只验证标准链和 MySQL 映射；不冒充真实声学识别。
                    // 池仅1连接：Provider若被放进会议连接／行锁事务，本例正常链会超时。
                    return new SpeakerIdentityProvider() {
                        @Override public CapabilityDescriptor descriptor() {
                            return new CapabilityDescriptor("test-speaker", Set.of("speaker.identity.cross_meeting"));
                        }
                        @Override public Identification identify(MeetingSpeakers request) throws Exception {
                            identifies.incrementAndGet();
                            var entries = persons.list(request.deploymentScope(), request.subjectScope(), "test-speaker");
                            return new Identification(entries.isEmpty() ? Map.of()
                                    : Map.of(request.speakers().getFirst().sessionSpeakerId(), entries.getFirst().person()));
                        }
                        @Override public Person enroll(Enrollment request) throws Exception {
                            enrollments.incrementAndGet();
                            if (failEnrollment.get()) throw new SampleUnavailable("synthetic short sample");
                            Person person = new Person("person_reference_test", request.displayName());
                            persons.save(request.meeting().deploymentScope(), request.meeting().subjectScope(),
                                    new JdbcReferenceSpeakerPersons.Entry(person, "test-speaker", "private_group", "private_feature"));
                            return person;
                        }
                    };
                });
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            network.start();
            int port = network.address().getPort();
            assertTrue(json(exchange(client, port, "GET", "/v1/capabilities", "", null, false))
                    .path("capabilities").toString().contains("speaker.identity.cross_meeting"));
            assertFalse(json(exchange(client, port, "GET", "/v1/capabilities", "", null, false))
                    .path("resultSpeakerEditingRequiresLogin").asBoolean(true));
            String first = completeSpeakerMeeting(client, port, "first");
            String generation = "?genTime=" + NOW.toEpochMilli();
            var original = MeetingManifestJson.decode(exchange(client, port, "GET",
                    "/v1/meetings/" + first + "/manifest" + generation, "", null, false).body());
            assertEquals(1, original.manifestRevision());
            var identityRef = original.artifacts().stream().filter(ref -> ref.type().equals("speaker.identity"))
                    .findFirst().orElseThrow();
            JsonNode unknown = json(exchange(client, port, "GET", "/v1/artifacts/"
                    + identityRef.artifactId() + generation, "", null, false));
            assertTrue(unknown.path("speakers").get(0).path("personId").isNull());

            String enrollPath = "/v1/meetings/" + first + "/speakers/speaker-1:enroll";
            String name = "{\"displayName\":\"参考人物😀\"}";
            assertEquals(401, exchange(client, port, "POST", enrollPath, name, null, false).statusCode());
            var enrolled = exchange(client, port, "POST", enrollPath, name, null, true);
            assertEquals(200, enrolled.statusCode());
            assertEquals("person_reference_test", json(enrolled).path("personId").asText());
            assertEquals(2, json(enrolled).path("manifestRevision").asInt());
            assertEquals(enrolled.body(), exchange(client, port, "POST", enrollPath, name, null, true).body());
            assertEquals(1, enrollments.get(), "同名注册重放不重复调用供应商");
            assertEquals(1, count("ai_trans_speaker_identity_person"));
            var revised = MeetingManifestJson.decode(exchange(client, port, "GET",
                    "/v1/meetings/" + first + "/manifest" + generation, "", null, false).body());
            assertEquals(2, revised.manifestRevision());
            assertEquals(original.startedAt(), revised.startedAt());
            assertEquals(original.endedAt(), revised.endedAt());
            assertEquals(original.artifacts().stream().filter(ref -> !ref.type().equals("speaker.identity")).toList(),
                    revised.artifacts().stream().filter(ref -> !ref.type().equals("speaker.identity")).toList());
            assertEquals(0, count("ai_trans_meeting_manifest_revision"), "不混用旧Worker清单链");

            String second = completeSpeakerMeeting(client, port, "second");
            var next = MeetingManifestJson.decode(exchange(client, port, "GET",
                    "/v1/meetings/" + second + "/manifest" + generation, "", null, false).body());
            var nextIdentity = next.artifacts().stream().filter(ref -> ref.type().equals("speaker.identity"))
                    .findFirst().orElseThrow();
            var matched = json(exchange(client, port, "GET", "/v1/artifacts/"
                    + nextIdentity.artifactId() + generation, "", null, false));
            assertEquals("person_reference_test", matched.path("speakers").get(0).path("personId").asText());
            assertEquals("参考人物😀", matched.path("speakers").get(0).path("displayName").asText());
            assertEquals(2, identifies.get());
            String rename = "/v1/meetings/" + first + "/speakers/speaker-1:rename";
            var preflight = client.send(java.net.http.HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + rename + generation))
                    .header("Origin", "https://reference-web.example.invalid")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type")
                    .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode(), "带原genTime的JSON跨源编辑必须能先通过预检");
            assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            String single = """
                    {"displayName":"单段记录","scope":"SINGLE","segmentId":"speaker-segment","remember":true}
                    """;
            assertEquals(401, exchange(client, port, "POST", rename, single, null, false).statusCode());
            String expired = "?genTime=" + (NOW.toEpochMilli() - config.resultLinkTtl().orElseThrow().toMillis());
            assertEquals(403, exchange(client, port, "POST", rename + expired, single, null, false).statusCode());
            assertEquals(400, exchange(client, port, "POST", rename + "?genTime=invalid", single, null, false).statusCode());
            var renamed = exchange(client, port, "POST", rename + generation, single, null, false);
            assertEquals(200, renamed.statusCode());
            assertEquals("REGISTERED", json(renamed).path("registration").asText());
            var text = readTranscript(client, port, first, generation);
            assertEquals("单段记录", text.path("segments").get(0).path("speakerDisplayName").asText());
            assertEquals("参考人物😀", text.path("segments").get(1).path("speakerDisplayName").asText());
            assertFalse(text.path("segments").get(2).has("speakerDisplayName"));
            assertFalse(readTranscript(client, port, second, generation).path("segments").get(0)
                    .has("speakerDisplayName"), "其他已完成会议保持原样");
            int registrations = enrollments.get();
            var all = exchange(client, port, "POST", rename + generation,
                    "{\"displayName\":\"本场全局\",\"scope\":\"ALL\",\"remember\":false}", null, false);
            assertEquals("NOT_REQUESTED", json(all).path("registration").asText());
            assertEquals(2, json(all).path("changedSegments").asInt());
            assertEquals(registrations, enrollments.get());
            assertEquals("本场全局", readTranscript(client, port, first, generation)
                    .path("segments").get(1).path("speakerDisplayName").asText());
            failEnrollment.set(true);
            var partial = exchange(client, port, "POST", rename, single.replace("单段记录", "仍然保存"), null, true);
            assertEquals(200, partial.statusCode());
            assertEquals("SAMPLE_UNAVAILABLE", json(partial).path("registration").asText());
            assertEquals("仍然保存", readTranscript(client, port, first, generation)
                    .path("segments").get(0).path("speakerDisplayName").asText());
            assertSentinelUnchanged();
        }
    }

    private String completeSpeakerMeeting(HttpClient client, int port, String suffix) throws Exception {
        return completeSpeakerMeeting(client, port, suffix, true);
    }

    @Test void manualNamesWorkWithoutSpeakerProviderOrPersonsTable() throws Exception {
        try (var connection = fixture(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE ai_trans_speaker_identity_person");
        }
        var config = config(true);
        var policy = new ReferenceDevEntitlementPolicy(config.entitlement(), CLOCK, new EntitlementIdGenerator() {
            public String newGrantId() { return "grant_" + UUID.randomUUID(); }
            public String newReservationId() { return "reservation_" + UUID.randomUUID(); }
            public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
        });
        try (var assembly = ReferenceMeetingHttpAssembly.startClientModel(config, CLOCK,
                new PersistentFileSystemMockArtifactStore(artifactRoot, 1024 * 1024),
                request -> IdentityProviderResult.success(verifiedIdentity()), policy,
                request -> new ModelSessionIssuer.Session("sample-json-ws", "ws://model.example.invalid/manual",
                        NOW.plusSeconds(300), Map.of()), java.util.function.UnaryOperator.identity());
             var server = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             var client = HttpClient.newHttpClient()) {
            server.start();
            int port = server.address().getPort();
            var capabilities = json(exchange(client, port, "GET", "/v1/capabilities", "", null, false));
            assertTrue(capabilities.path("resultSpeakerEditingEnabled").asBoolean());
            assertFalse(capabilities.path("capabilities").toString().contains("speaker.identity.cross_meeting"));
            String id = completeSpeakerMeeting(client, port, "manual", false);
            String rename = "/v1/meetings/" + id + "/speakers/speaker-1:rename";
            var reply = exchange(client, port, "POST", rename + "?genTime=" + NOW.toEpochMilli(),
                    "{\"displayName\":\"人工名字\",\"scope\":\"ALL\",\"remember\":false}", null, false);
            assertEquals(200, reply.statusCode());
            assertEquals("NOT_REQUESTED", json(reply).path("registration").asText());
            assertEquals("人工名字", readTranscript(client, port, id, "?genTime=" + NOW.toEpochMilli())
                    .path("segments").get(1).path("speakerDisplayName").asText());
        }
    }

    private JsonNode readTranscript(HttpClient client, int port, String id, String generation) throws Exception {
        var manifest = MeetingManifestJson.decode(exchange(client, port, "GET",
                "/v1/meetings/" + id + "/manifest" + generation, "", null, false).body());
        var ref = manifest.artifacts().stream().filter(item -> "transcript.final".equals(item.type()))
                .findFirst().orElseThrow();
        var response = exchange(client, port, "GET", "/v1/artifacts/" + ref.artifactId() + generation, "", null, false);
        assertEquals(200, response.statusCode());
        return json(response);
    }

    private String completeSpeakerMeeting(HttpClient client, int port, String suffix, boolean crossMeeting) throws Exception {
        String body = """
                {"clientSessionId":"speaker-%s","profile":{"id":"m2-c-profile","revision":1},
                 "client":{"appId":"speaker-app","appVersion":"1","contractVersion":"v1","deviceId":"test-device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime","speaker.diarization.session","speaker.identity.cross_meeting"],
                 "artifactPolicy":{"retainAudio":true,"presentationCapture":false}}
                """.formatted(suffix);
        if (!crossMeeting) body = body.replace(",\"speaker.identity.cross_meeting\"", "");
        var created = exchange(client, port, "POST", "/v1/meetings", body,
                "speaker-create-" + suffix + "-123456789", true);
        assertEquals(201, created.statusCode());
        String id = json(created).path("meetingId").asText();
        String path = "/v1/meetings/" + id;
        assertEquals(200, exchange(client, port, "POST", path + ":startClient", "", null, true).statusCode());
        String event = """
                {"clientEventId":"speaker-event","clientSequence":1,"transcriptUpsert":{
                 "segmentId":"speaker-segment","revision":1,"operation":"UPSERT_OPERATION_UPSERT",
                 "startMs":0,"endMs":6000,"language":"zh-CN","text":"同一位参考发言人",
                 "speaker":{"sessionSpeakerId":"speaker-1"},"final":true}}
                """;
        append(client, port, path + "/events:append", event);
        append(client, port, path + "/events:append", event.replace("speaker-event", "speaker-event2")
                .replace("\"clientSequence\":1", "\"clientSequence\":2")
                .replace("speaker-segment", "speaker-segment2").replace("\"startMs\":0", "\"startMs\":2000"));
        append(client, port, path + "/events:append", event.replace("speaker-event", "speaker-event3")
                .replace("\"clientSequence\":1", "\"clientSequence\":3")
                .replace("speaker-segment", "speaker-segment3").replace("speaker-1", "speaker-2")
                .replace("\"startMs\":0", "\"startMs\":4000"));
        byte[] audio = new byte[16000 * 2 * 6];
        var upload = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/audio-original"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/octet-stream")
                .header("X-Content-SHA256", "sha256:" + java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(audio)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio)).build();
        var uploaded = client.send(upload, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode());
        assertEquals(201, exchange(client, port, "POST", path + "/usage:submit",
                "{\"audioDurationMs\":6000}", null, true).statusCode());
        var stopped = exchange(client, port, "POST", path + ":stop", "",
                "speaker-stop-" + suffix + "-123456789", true);
        assertEquals(200, stopped.statusCode());
        assertEquals("READY", json(stopped).path("state").asText());
        return id;
    }

    private ReferenceMeetingDeploymentConfig config() { return config(false); }

    private ReferenceMeetingDeploymentConfig config(boolean speakerIdentity) {
        Map<String, String> values = new HashMap<>();
        values.put(DatabaseRuntimeConfig.DATABASE_URL, scopedUrl());
        values.put(DatabaseRuntimeConfig.DATABASE_USER, required("REFERENCE_TEST_MYSQL_DATABASE_USER"));
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
        values.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "2000");
        values.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "1000");
        values.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "3");
        values.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "0");
        values.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, SCOPE);
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_ID, "m2-c-profile");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REVISION, "1");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES, "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES, "translation.realtime,summary.structured");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime,translation.realtime,summary.structured");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                "transcript.realtime,translation.realtime,summary.structured");
        values.put(ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_MEETING_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "32000");
        values.put(ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW, "100");
        values.put(ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET, "synthetic-m2-c-page-token-secret-0123456789");
        values.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN, "wss://meeting.example.invalid");
        values.put(ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        if (speakerIdentity) {
            values.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "1");
            values.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                    "speaker.diarization.session,speaker.identity.cross_meeting");
            values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                    "transcript.realtime,speaker.diarization.session,speaker.identity.cross_meeting");
            values.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                    "transcript.realtime,speaker.diarization.session");
        }
        return ReferenceMeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(values,
                "m2-c-audience", "m2-c-client");
    }

    private static VerifiedIdentity verifiedIdentity() {
        return new VerifiedIdentity(new Subject(SUBJECT), Set.of("USER"),
                Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP, MeetingPermissions.DELETE),
                NOW.minusSeconds(60), NOW.plusSeconds(600), null);
    }

    private static String createBody() {
        return """
                {"clientSessionId":"m2-c-client-session","profile":{"id":"m2-c-profile","revision":1},
                 "client":{"appId":"m2-c-app","appVersion":"1","contractVersion":"v1","deviceId":"test-device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":["en-US"]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime","translation.realtime","summary.structured"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":true}}
                """;
    }

    private static String transcript(String id, long sequence, String segment, int revision,
            String text, boolean complete) throws Exception {
        return JSON.writeValueAsString(Map.of("clientEventId", id, "clientSequence", sequence,
                "transcriptUpsert", Map.of("segmentId", segment, "revision", revision,
                        "operation", "UPSERT_OPERATION_UPSERT", "startMs", 0, "endMs", 2000,
                        "language", "zh-CN", "text", text, "final", complete)));
    }

    private static String translation(String id, long sequence, int revision, String text,
            boolean complete) throws Exception {
        return JSON.writeValueAsString(Map.of("clientEventId", id, "clientSequence", sequence,
                "translationUpsert", Map.of("translationId", "translation", "sourceSegmentId", "source",
                        "revision", revision, "operation", "UPSERT_OPERATION_UPSERT", "sourceLanguage", "zh-CN",
                        "targetLanguage", "en-US", "text", text, "final", complete,
                        "supersedesTranslationIds", List.of())));
    }

    private static HttpResponse<String> append(HttpClient client, int port, String path,
            String body) throws Exception {
        var response = exchange(client, port, "POST", path, body, null, true);
        assertEquals(201, response.statusCode());
        return response;
    }

    private static HttpResponse<String> exchange(HttpClient client, int port, String method,
            String path, String body, String key, boolean authenticated) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (authenticated) builder.header("Authorization", "Bearer " + TOKEN);
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body,
                StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private long reservationVersion() throws Exception {
        try (var connection = fixture(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version FROM ai_trans_usage_reservation")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void assertCommittedUsage(long expectedVersion) throws Exception {
        try (var connection = fixture(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT status, actual_amount, version FROM ai_trans_usage_reservation")) {
            assertTrue(rows.next());
            assertEquals("COMMITTED", JdbcValues.text(rows, "status"));
            assertEquals(2500, rows.getBigDecimal("actual_amount").longValueExact());
            assertEquals(expectedVersion + 1, rows.getLong("version"));
            assertFalse(rows.next());
        }
        try (var connection = fixture(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ai_trans_usage_operation WHERE operation_scope=?")) {
            JdbcValues.setText(statement, 1, JdbcDatabaseEngine.MYSQL, UsageOperationScopes.COMMIT);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private int count(String fixedTable) throws Exception {
        if (!fixedTable.matches("ai_trans_[a-z_]+")) throw new IllegalArgumentException("fixed test table required");
        try (var connection = fixture(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + fixedTable)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void assertSentinelUnchanged() throws Exception {
        try (var connection = fixture(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SHOW CREATE TABLE legacy_business_sentinel")) {
                assertTrue(rows.next());
                assertEquals(sentinelDdl, rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT id, body FROM legacy_business_sentinel ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
                assertEquals(SENTINEL, rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"),
                required("REFERENCE_TEST_MYSQL_DATABASE_USER"), required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
    }

    private Connection fixture() throws Exception {
        return DriverManager.getConnection(scopedUrl(), required("REFERENCE_TEST_MYSQL_DATABASE_USER"),
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

    private static final class TrackingEntitlementProvider implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private int reserveCalls;
        private final List<CommitUsageRequest> commits = new ArrayList<>();
        private TrackingEntitlementProvider(EntitlementProvider delegate) { this.delegate = delegate; }
        @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request) {
            return delegate.authorizeStart(request);
        }
        @Override public EntitlementProviderResult<UsageOperationResult> reserveUsage(ReserveUsageRequest request) {
            reserveCalls++;
            return delegate.reserveUsage(request);
        }
        @Override public EntitlementProviderResult<UsageOperationResult> commitUsage(CommitUsageRequest request) {
            commits.add(request);
            return delegate.commitUsage(request);
        }
        @Override public EntitlementProviderResult<UsageOperationResult> cancelReservation(CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }
}
