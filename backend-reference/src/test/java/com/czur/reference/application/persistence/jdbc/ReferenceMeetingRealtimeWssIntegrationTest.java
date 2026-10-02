package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.AudioEncoding;
import com.czur.aimeeting.contract.realtime.v1.AudioProfile;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.InputComplete;
import com.czur.aimeeting.contract.realtime.v1.ResumeRequest;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.bootstrap.ReferenceMeetingProcess;
import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import com.czur.reference.application.wss.MeetingRealtimeWssServer;
import com.czur.reference.protocol.realtime.RealtimeWireCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 专属 PostgreSQL 16 随机 schema：真实 Create、WSS ACK、Mock 字幕和重连去重。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class ReferenceMeetingRealtimeWssIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String USER_TOKEN = "wss-network-user-token-0123456789";
    private static final String SCOPE = "wss-network-deployment";
    private static final String SUBJECT = "wss-network-subject";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RealtimeWireCodec CODEC = new RealtimeWireCodec(
            new RealtimeWireCodec.AudioContract(16_000, 1, 100, 32_000));

    @TempDir Path temporaryDirectory;
    private String schema;
    private PGSimpleDataSource fixtureDataSource;

    @BeforeEach
    void createOwnSchema() throws Exception {
        assertFalse(databaseUrl().contains("127.0.0.1:55432"), "shared test database forbidden");
        schema = "wss_network_" + UUID.randomUUID().toString().replace("-", "");
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
    void singleProcessRunsNetworkAndWorkerThenClosesWithoutSeparateWorkerProcess()
            throws Exception {
        migrate(1, 15);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        int httpPort = freeLoopbackPort();
        Map<String, String> values = config(wssPort);
        values.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create," + MeetingPermissions.STOP);
        values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "86400000");
        values.put(ReferenceMeetingProcess.HTTP_BIND, "127.0.0.1:" + httpPort);
        values.put(ReferenceMeetingProcess.WSS_BIND, "127.0.0.1:" + wssPort);
        values.put(ReferenceMeetingProcess.TLS_KEYSTORE_PATH,
                tls.keystore().toString());
        values.put(ReferenceMeetingProcess.TLS_KEYSTORE_PASSWORD, tls.password());
        values.put(ReferenceMeetingProcess.ARTIFACT_ROOT,
                temporaryDirectory.resolve("process-artifacts").toString());
        Map<String, String> missingBind = new HashMap<>(values);
        missingBind.remove(ReferenceMeetingProcess.HTTP_BIND);
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingProcess.start(missingBind, CLOCK));

        Path processLog = temporaryDirectory.resolve("reference-process.log");
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder command = new ProcessBuilder(javaCommand, "-cp", classpath,
                "com.czur.reference.application.bootstrap.ReferenceMeetingMain")
                .redirectErrorStream(true).redirectOutput(processLog.toFile());
        command.environment().putAll(values);
        Process process = command.start();
        String meetingId;
        try (HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean ready = false;
            while (System.nanoTime() < startupDeadline && process.isAlive()) {
                try {
                    HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + httpPort
                                    + "/internal/health/readiness")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (health.statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (IOException ignored) {
                    // 子进程尚未绑定 HTTP，短暂等待即可。
                }
                Thread.sleep(50);
            }
            assertTrue(ready, "child process failed readiness: "
                    + Files.readString(processLog));
            HttpResponse<String> created = request(client, httpPort, "POST", "/v1/meetings",
                    createBody(), "process-create-key-0001");
            assertEquals(201, created.statusCode(), created.body() + "\n"
                    + Files.readString(processLog));
            JsonNode create = JSON.readTree(created.body());
            meetingId = create.path("meetingId").asText();
            String path = "/v1/meetings/" + meetingId;
            EventListener listener = new EventListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer "
                            + create.path("realtime").path("sessionToken").asText())
                    .subprotocols(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)
                    .buildAsync(URI.create(create.path("realtime").path("url").asText()), listener)
                    .orTimeout(5, TimeUnit.SECONDS).join();
            try {
                byte[] pcm = new byte[3_200];
                Arrays.fill(pcm, (byte) 0x5a);
                socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
                socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
                assertTrue(listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join()
                        .getAudioAck().getDurable());
                assertEquals("模拟实时字幕（修订 1）", listener.transcript
                        .orTimeout(5, TimeUnit.SECONDS).join().getTranscriptUpsert().getText());
                assertEquals(202, request(client, httpPort, "POST", path + ":stop", "",
                        "process-stop-key-0001").statusCode());
                socket.sendBinary(ByteBuffer.wrap(inputComplete(meetingId).toByteArray()), true)
                        .join();
                awaitMeetingCount("SELECT count(*) FROM durable_input_fact WHERE meeting_id=?",
                        meetingId, 1);
                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
                listener.closed.orTimeout(5, TimeUnit.SECONDS).join();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                HttpResponse<String> manifest;
                do {
                    manifest = request(client, httpPort, "GET", path + "/manifest?genTime=-1",
                            "", null);
                    if (manifest.statusCode() == 200) break;
                    Thread.sleep(50);
                } while (System.nanoTime() < deadline);
                assertEquals(200, manifest.statusCode(), "periodic Worker must finish the meeting");
                JsonNode artifact = JSON.readTree(manifest.body()).path("artifacts").get(0);
                assertEquals("transcript.final", artifact.path("type").asText());
                HttpResponse<String> transcript = request(client, httpPort, "GET",
                        "/v1/artifacts/" + artifact.path("artifactId").asText()
                                + "?genTime=-1", "", null);
                assertEquals(200, transcript.statusCode());
                assertTrue(transcript.body().contains("【模拟最终转写】"));
                assertEquals(200, request(client, httpPort, "GET",
                        path + "/events?afterSequence=0&limit=10", "", null).statusCode());
            } finally {
                socket.abort();
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "child process did not exit");
            }
        }
        // Unix SIGTERM 的 JVM 退出码通常为 143；短会必须在进程退出前已持久化。
        assertTrue(process.exitValue() == 0 || process.exitValue() == 143,
                Files.readString(processLog));
        // 同配置重启可读回结果，并由显式 close 验证网络和数据库关闭顺序。
        try (ReferenceMeetingProcess restarted = ReferenceMeetingProcess.start(
                values, Clock.tickMillis(ZoneOffset.UTC));
             HttpClient readClient = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            HttpResponse<String> manifest = request(readClient, httpPort, "GET",
                    "/v1/meetings/" + meetingId + "/manifest?genTime=-1", "", null);
            assertEquals(200, manifest.statusCode());
            assertEquals("READY", JSON.readTree(manifest.body()).path("status").asText());
        }
        // 显式 close 后不能继续接收请求。
        try (var socket = new java.net.Socket()) {
            assertThrows(IOException.class, () -> socket.connect(
                    new InetSocketAddress("127.0.0.1", httpPort), 500));
        }
    }

    @Test
    void httpCreateAndRealWssPersistAckThenReconnectAndResumeTranscriptWithoutDuplication()
            throws Exception {
        migrate(1, 15);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        Map<String, String> config = config(wssPort);

        String meetingId;
        String sessionToken;
        ServerEnvelope transcript;
        byte[] pcm = new byte[3_200];
        Arrays.fill(pcm, (byte) 0x5a);
        try (var assembly = ReferenceMeetingHttpAssembly.start(config, CLOCK);
             var http = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             MeetingRealtimeWssServer wss = assembly.newRealtimeWssServer(
                     new InetSocketAddress("127.0.0.1", wssPort), tls.server());
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            http.start();
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            String key = "wss-network-create-key-0001";
            HttpRequest create = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + http.address().getPort() + "/v1/meetings"))
                    .header("Authorization", "Bearer " + USER_TOKEN)
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createBody(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> created = client.send(create,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(201, created.statusCode());
            JsonNode response = JSON.readTree(created.body());
            meetingId = response.path("meetingId").textValue();
            sessionToken = response.path("realtime").path("sessionToken").textValue();
            String subprotocol = response.path("realtime").path("subprotocol").textValue();
            URI wssUrl = URI.create(response.path("realtime").path("url").textValue());
            assertTrue(meetingId.startsWith("mtg_"));
            assertNotNull(sessionToken);
            assertEquals(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, subprotocol);
            assertEquals(wssPort, wssUrl.getPort());
            assertEquals("/v1/realtime/" + meetingId, wssUrl.getPath());

            EventListener listener = new EventListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + sessionToken)
                    .subprotocols(subprotocol)
                    .buildAsync(wssUrl, listener).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(subprotocol, socket.getSubprotocol());
            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();

            ServerEnvelope ack = listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(meetingId, ack.getMeetingId());
            assertEquals(meetingId, ack.getStreamId());
            assertEquals(0, ack.getEventSequence());
            assertEquals("", ack.getEventId());
            assertTrue(ack.getAudioAck().getDurable());
            assertEquals(1, ack.getAudioAck().getAcceptedStreamSequence());
            assertEquals(100, ack.getAudioAck().getAcceptedAudioPositionMs());
            transcript = listener.transcript.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(meetingId, transcript.getMeetingId());
            assertEquals(meetingId, transcript.getStreamId());
            assertEquals(1, transcript.getEventSequence());
            assertTrue(transcript.getEventId().startsWith("evt_mock_"));
            assertEquals("mock-segment-1", transcript.getTranscriptUpsert().getSegmentId());
            assertEquals(1, transcript.getTranscriptUpsert().getRevision());
            assertEquals("模拟实时字幕（修订 1）", transcript.getTranscriptUpsert().getText());
            assertFalse(transcript.getTranscriptUpsert().getFinal());
            assertEquals(List.of("ACK", "TRANSCRIPT"), listener.order);

            // 同一写者重复 V015 帧只重发持久 ACK；不重复推送/落库事件。
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
            ServerEnvelope duplicateAck = listener.secondAck.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, duplicateAck.getAudioAck().getAcceptedStreamSequence());

            // 新 JDBC 连接独立读回 V011 Start、V015 写者/游标与完整帧；不能只信 ACK。
            try (Connection readback = fixtureDataSource.getConnection()) {
                assertEquals("STARTING", scalar(readback,
                        "SELECT state FROM meeting WHERE meeting_id=?", meetingId));
                assertEquals("1", scalar(readback,
                        "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id=?",
                        meetingId));
                try (PreparedStatement query = readback.prepareStatement("""
                        SELECT writer_id, writer_epoch, accepted_stream_sequence,
                               accepted_audio_position_ms
                          FROM meeting_audio_input WHERE meeting_id=?
                        """)) {
                    query.setString(1, meetingId);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertNotNull(rows.getObject(1));
                        assertEquals(1, rows.getLong(2));
                        assertEquals(1, rows.getLong(3));
                        assertEquals(100, rows.getLong(4));
                        assertFalse(rows.next());
                    }
                }
                try (PreparedStatement query = readback.prepareStatement("""
                        SELECT stream_sequence, start_position_ms, duration_ms, audio,
                               digest_version, content_digest
                          FROM meeting_audio_frame WHERE meeting_id=?
                        """)) {
                    query.setString(1, meetingId);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getLong(1));
                        assertEquals(0, rows.getLong(2));
                        assertEquals(100, rows.getInt(3));
                        assertArrayEquals(pcm, rows.getBytes(4));
                        assertEquals(AudioFrameContentDigest.VERSION, rows.getInt(5));
                        assertArrayEquals(AudioFrameContentDigest.sha256(
                                new MeetingAudioFrame(1, 0, 100, pcm, null, 0, 0, 0,
                                        Set.of())), rows.getBytes(6));
                        assertFalse(rows.next());
                    }
                }
                assertEquals("1", scalar(readback,
                        "SELECT count(*) FROM meeting_event WHERE meeting_id=?", meetingId));
                try (PreparedStatement query = readback.prepareStatement("""
                        SELECT event_sequence, event_id, event_type, canonical_payload,
                               spec_version, stream_id
                          FROM meeting_event WHERE meeting_id=?
                        """)) {
                    query.setString(1, meetingId);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getLong("event_sequence"));
                        assertEquals(transcript.getEventId(), rows.getString("event_id"));
                        assertEquals("transcriptUpsert", rows.getString("event_type"));
                        assertEquals(RealtimeWireCodec.SPEC_VERSION,
                                rows.getString("spec_version"));
                        assertEquals(meetingId, rows.getString("stream_id"));
                        JsonNode stored = JSON.readTree(rows.getString("canonical_payload"));
                        assertEquals("模拟实时字幕（修订 1）",
                                stored.path("transcriptUpsert").path("text").textValue());
                        assertEquals(transcript.getEventId(), stored.path("eventId").textValue());
                        assertFalse(rows.next());
                    }
                }
            }
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(List.of("ACK", "TRANSCRIPT", "ACK"), listener.order);
        }

        // 新装配、新连接从 V015 重建 Mock 状态；已发布赢家不会再次推送或分配序号。
        int reconnectPort = freeLoopbackPort();
        try (var assembly = ReferenceMeetingHttpAssembly.start(config(reconnectPort), CLOCK);
             MeetingRealtimeWssServer wss = assembly.newRealtimeWssServer(
                     new InetSocketAddress("127.0.0.1", reconnectPort), tls.server());
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            EventListener listener = new EventListener();
            URI reconnectUrl = URI.create("wss://127.0.0.1:" + reconnectPort
                    + "/v1/realtime/" + meetingId);
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + sessionToken)
                    .subprotocols(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)
                    .buildAsync(reconnectUrl, listener).orTimeout(5, TimeUnit.SECONDS).join();
            // 新进程的连接尚未 stream_start：同一 V011 事件事实即可按全局游标补拉。
            socket.sendBinary(ByteBuffer.wrap(resume(meetingId, 0).toByteArray()), true).join();
            ServerEnvelope replayed = listener.transcript.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(transcript.getEventId(), replayed.getEventId());
            assertEquals(transcript.getEventSequence(), replayed.getEventSequence());
            assertEquals(transcript.getTranscriptUpsert(), replayed.getTranscriptUpsert());
            ServerEnvelope pageEnd = listener.pageEnd.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, pageEnd.getEventSequence());
            assertEquals("", pageEnd.getEventId());
            assertEquals(1, pageEnd.getResumePageEnd().getLastEventSequence());
            assertFalse(pageEnd.getResumePageEnd().getHasMore());
            assertEquals(List.of("TRANSCRIPT", "PAGE_END"), listener.order);

            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
            ServerEnvelope ack = listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, ack.getAudioAck().getAcceptedStreamSequence());
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(List.of("TRANSCRIPT", "PAGE_END", "ACK"), listener.order);
            try (Connection readback = fixtureDataSource.getConnection()) {
                assertEquals("1", scalar(readback,
                        "SELECT count(*) FROM meeting_event WHERE meeting_id=?", meetingId));
                assertEquals("1", scalar(readback,
                        "SELECT max(event_sequence) FROM meeting_event WHERE meeting_id=?",
                        meetingId));
            }
        }
    }

    @Test
    void realShortMeetingClosesInputRunsWorkerAndRevokesResultsAfterDelete()
            throws Exception {
        migrate(1, 15);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        Map<String, String> values = config(wssPort);
        values.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create," + MeetingPermissions.STOP + "," + MeetingPermissions.DELETE);
        values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        var trusted = ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        var clock = new MutableClock(NOW);
        Path artifactRoot = Files.createDirectory(temporaryDirectory.resolve("short-meeting-artifacts"));
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot, 4096);
        byte[] pcm = new byte[3_200];
        Arrays.fill(pcm, (byte) 0x5a);

        try (var assembly = ReferenceMeetingHttpAssembly.start(values, clock, artifacts);
             var http = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             MeetingRealtimeWssServer wss = assembly.newRealtimeWssServer(
                     new InetSocketAddress("127.0.0.1", wssPort), tls.server());
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            http.start();
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            int port = http.address().getPort();
            HttpResponse<String> created = request(client, port, "POST", "/v1/meetings",
                    createBody(), "short-meeting-create-key-0001");
            assertEquals(201, created.statusCode());
            JsonNode create = JSON.readTree(created.body());
            String meetingId = create.path("meetingId").asText();
            String path = "/v1/meetings/" + meetingId;
            URI wssUrl = URI.create(create.path("realtime").path("url").asText());
            String sessionToken = create.path("realtime").path("sessionToken").asText();
            assertEquals(wssPort, wssUrl.getPort());

            EventListener listener = new EventListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + sessionToken)
                    .subprotocols(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)
                    .buildAsync(wssUrl, listener).orTimeout(5, TimeUnit.SECONDS).join();
            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
            ServerEnvelope ack = listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join();
            ServerEnvelope subtitle = listener.transcript.orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(ack.getAudioAck().getDurable());
            assertEquals(1, ack.getAudioAck().getAcceptedStreamSequence());
            assertEquals(1, subtitle.getEventSequence());
            assertEquals("模拟实时字幕（修订 1）", subtitle.getTranscriptUpsert().getText());

            // Stop 先提交但没有完成声明：不得提前生成 V008、receipt 或 Workflow。
            HttpResponse<String> pendingStop = request(client, port, "POST", path + ":stop",
                    "", "short-meeting-stop-key-0001");
            assertEquals(202, pendingStop.statusCode());
            assertTrue(JSON.readTree(pendingStop.body()).path("finalizationReceipt").isNull());
            assertEquals("0", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));

            socket.sendBinary(ByteBuffer.wrap(inputComplete(meetingId).toByteArray()), true)
                    .join();
            awaitMeetingCount("SELECT count(*) FROM durable_input_fact WHERE meeting_id=?",
                    meetingId, 1);
            assertEquals(1, listener.ackCount.get(), "完成声明不得冒充音频 ACK");
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM final_usage_fact WHERE meeting_id=?", meetingId));
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            String receipt = scalarForMeeting(
                    "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId);
            assertTrue(receipt.startsWith("receipt_"));
            JsonNode accepted = JSON.readTree(request(client, port, "GET", path, "", null).body());
            assertEquals("INPUT_ACCEPTED", accepted.path("inputState").asText());
            assertEquals(receipt, accepted.path("finalizationReceipt").asText());
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();

            Clock workerClock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
            EntitlementIdGenerator ids = new EntitlementIdGenerator() {
                @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                @Override public String newReservationId() {
                    return "reservation_" + UUID.randomUUID();
                }
                @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
            };
            var source = assembly.databaseRuntime().dataSource();
            var entitlement = new JdbcReferenceDevEntitlementProvider(source,
                    new ReferenceDevEntitlementPolicy(trusted.entitlement(), workerClock, ids),
                    workerClock);
            var worker = new MockFinalizationWorker(new JdbcWorkflowStepStore(source),
                    artifacts, entitlement, new JdbcFinalizationCompletionUnitOfWork(source),
                    new MockFinalizationWorker.Config("short-meeting-worker", workerClock,
                            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, 1, 3));
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    worker.runOnce().items().getFirst().status());
            assertTrue(worker.runOnce().idle());

            HttpResponse<String> events = request(client, port, "GET",
                    path + "/events?afterSequence=0&limit=10", "", null);
            assertEquals(200, events.statusCode());
            JsonNode eventPage = JSON.readTree(events.body());
            assertEquals(1, eventPage.path("items").size());
            assertEquals("模拟实时字幕（修订 1）", eventPage.path("items").get(0)
                    .path("transcriptUpsert").path("text").asText());
            HttpResponse<String> manifestResponse = request(client, port, "GET",
                    path + "/manifest?genTime=-1", "", null);
            assertEquals(200, manifestResponse.statusCode());
            JsonNode manifest = JSON.readTree(manifestResponse.body());
            assertEquals("READY", manifest.path("status").asText());
            assertEquals(1, manifest.path("artifacts").size());
            JsonNode artifact = manifest.path("artifacts").get(0);
            assertEquals("transcript.final", artifact.path("type").asText());
            String artifactPath = "/v1/artifacts/" + artifact.path("artifactId").asText()
                    + "?genTime=-1";
            HttpResponse<String> transcript = request(client, port, "GET", artifactPath, "", null);
            assertEquals(200, transcript.statusCode());
            assertTrue(transcript.body().contains("【模拟最终转写】"));
            assertFalse(transcript.body().isBlank());

            HttpResponse<String> replayStop = request(client, port, "POST", path + ":stop",
                    "", "short-meeting-stop-key-0001");
            assertEquals(200, replayStop.statusCode());
            assertEquals(receipt, JSON.readTree(replayStop.body())
                    .path("finalizationReceipt").asText());
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));

            clock.advanceTo(NOW.plusSeconds(2));
            assertEquals(202, request(client, port, "DELETE", path, "",
                    "short-meeting-delete-key-0001").statusCode());
            assertEquals(403, request(client, port, "GET",
                    path + "/events?afterSequence=0&limit=10", "", null).statusCode());
            assertEquals(403, request(client, port, "GET",
                    path + "/manifest?genTime=-1", "", null).statusCode());
            assertEquals(403, request(client, port, "GET", artifactPath, "", null).statusCode());
        }
    }

    @Test
    void restartedAssemblyScansCommittedStopAndCompletedAudioIntoOneReceipt()
            throws Exception {
        migrate(1, 15);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        Map<String, String> values = config(wssPort);
        byte[] pcm = new byte[3_200];
        Arrays.fill(pcm, (byte) 0x5a);
        String meetingId;

        // 先完整提交 V015 音频和完成声明，但此时未 Stop，正常入口不会签发 receipt。
        try (var first = ReferenceMeetingHttpAssembly.start(values, CLOCK);
             var http = first.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             MeetingRealtimeWssServer wss = first.newRealtimeWssServer(
                     new InetSocketAddress("127.0.0.1", wssPort), tls.server());
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            http.start();
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            HttpResponse<String> created = request(client, http.address().getPort(), "POST",
                    "/v1/meetings", createBody(), "restart-scan-create-key-0001");
            assertEquals(201, created.statusCode());
            JsonNode create = JSON.readTree(created.body());
            meetingId = create.path("meetingId").asText();
            EventListener listener = new EventListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer "
                            + create.path("realtime").path("sessionToken").asText())
                    .subprotocols(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)
                    .buildAsync(URI.create(create.path("realtime").path("url").asText()),
                            listener).orTimeout(5, TimeUnit.SECONDS).join();
            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
            assertTrue(listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join()
                    .getAudioAck().getDurable());
            socket.sendBinary(ByteBuffer.wrap(inputComplete(meetingId).toByteArray()), true)
                    .join();
            awaitMeetingCount("SELECT count(*) FROM meeting_audio_input"
                    + " WHERE meeting_id=? AND complete_last_stream_sequence=1", meetingId, 1);
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
        }

        // 仅调用 Stop 的已验收数据库事务，模拟“Stop 提交后、入口触发
        // Reconciler 之前”进程退出；不能直接把会议状态用 SQL 改到 STOPPING。
        var identity = new VerifiedIdentity(new Subject(SUBJECT), Set.of("ROLE_USER"),
                Set.of(MeetingPermissions.STOP), NOW.minusSeconds(60),
                NOW.plusSeconds(600), "restart-scan-credential");
        var stop = new JdbcOwnedMeetingStopUnitOfWork(fixtureDataSource, CLOCK).stop(
                new OwnedMeetingStopUnitOfWork.StopRequest(
                        new MeetingCommandContext(SCOPE, identity), meetingId,
                        "restart-scan-stop-key-0001", "restart-scan-stop-digest"));
        assertTrue(stop instanceof OwnedMeetingStopUnitOfWork.Success);
        assertEquals("0", scalarForMeeting(
                "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
        assertNull(scalarForMeeting(
                "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId));
        assertEquals(List.of(), new JdbcPendingMeetingInputPort(
                fixtureDataSource, "another-deployment").listAfter(null, 1));
        assertEquals(List.of(meetingId), new JdbcPendingMeetingInputPort(
                fixtureDataSource, SCOPE).listAfter(null, 1));

        try (var restarted = ReferenceMeetingHttpAssembly.start(values, CLOCK)) {
            var scan = restarted.newInputReconciliationScan(1);
            assertEquals(1, scan.runOnce().accepted());
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            String receipt = scalarForMeeting(
                    "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId);
            assertTrue(receipt.startsWith("receipt_"));
            assertEquals(0, scan.runOnce().candidates());
            assertEquals(0, scan.runOnce().candidates());
            assertEquals(receipt, scalarForMeeting(
                    "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId));
        }
    }

    @Test
    void inputCompleteBeforeStopWaitsThenProducesOneReceipt() throws Exception {
        migrate(1, 15);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        Map<String, String> values = config(wssPort);
        values.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create," + MeetingPermissions.STOP);
        byte[] pcm = new byte[3_200];
        Arrays.fill(pcm, (byte) 0x5a);

        try (var assembly = ReferenceMeetingHttpAssembly.start(values, CLOCK);
             var http = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0));
             MeetingRealtimeWssServer wss = assembly.newRealtimeWssServer(
                     new InetSocketAddress("127.0.0.1", wssPort), tls.server());
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            http.start();
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            int port = http.address().getPort();
            HttpResponse<String> created = request(client, port, "POST", "/v1/meetings",
                    createBody(), "complete-first-create-key-0001");
            assertEquals(201, created.statusCode());
            JsonNode create = JSON.readTree(created.body());
            String meetingId = create.path("meetingId").asText();
            String path = "/v1/meetings/" + meetingId;
            EventListener listener = new EventListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer "
                            + create.path("realtime").path("sessionToken").asText())
                    .subprotocols(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)
                    .buildAsync(URI.create(create.path("realtime").path("url").asText()), listener)
                    .orTimeout(5, TimeUnit.SECONDS).join();
            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();
            assertTrue(listener.firstAck.orTimeout(5, TimeUnit.SECONDS).join()
                    .getAudioAck().getDurable());
            listener.transcript.orTimeout(5, TimeUnit.SECONDS).join();

            socket.sendBinary(ByteBuffer.wrap(inputComplete(meetingId).toByteArray()), true)
                    .join();
            awaitMeetingCount("SELECT count(*) FROM meeting_audio_input"
                    + " WHERE meeting_id=? AND complete_last_stream_sequence=1", meetingId, 1);
            assertEquals(1, listener.ackCount.get(), "完成声明不能产生第二条音频 ACK");
            assertEquals("0", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals("0", scalarForMeeting(
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            assertNull(scalarForMeeting(
                    "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId));

            HttpResponse<String> stopped = request(client, port, "POST", path + ":stop",
                    "", "complete-first-stop-key-0001");
            assertEquals(200, stopped.statusCode());
            String receipt = JSON.readTree(stopped.body()).path("finalizationReceipt").asText();
            assertTrue(receipt.startsWith("receipt_"));
            assertEquals(receipt, scalarForMeeting(
                    "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?", meetingId));
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            HttpResponse<String> replay = request(client, port, "POST", path + ":stop",
                    "", "complete-first-stop-key-0001");
            assertEquals(200, replay.statusCode());
            assertEquals(receipt, JSON.readTree(replay.body())
                    .path("finalizationReceipt").asText());
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals("1", scalarForMeeting(
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    private static String scalar(Connection connection, String sql, String meetingId)
            throws Exception {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, meetingId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                String value = rows.getString(1);
                assertFalse(rows.next());
                return value;
            }
        }
    }

    private String scalarForMeeting(String sql, String meetingId) throws Exception {
        try (Connection connection = fixtureDataSource.getConnection()) {
            return scalar(connection, sql, meetingId);
        }
    }

    private void awaitMeetingCount(String sql, String meetingId, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Integer.parseInt(scalarForMeeting(sql, meetingId)) < expected
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(Integer.toString(expected), scalarForMeeting(sql, meetingId));
    }

    private static HttpResponse<String> request(HttpClient client, int port, String method,
            String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + USER_TOKEN);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (!body.isEmpty()) builder.header("Content-Type", "application/json");
        HttpRequest request = builder.method(method,
                body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) { now = new AtomicReference<>(initial); }

        private void advanceTo(Instant instant) {
            now.updateAndGet(current -> {
                if (instant.isBefore(current)) {
                    throw new IllegalArgumentException("test clock cannot move backwards");
                }
                return instant;
            });
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static ClientEnvelope start(String meetingId) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setClientMessageId("network-start-1")
                .setStreamStart(StreamStart.newBuilder().setAudioProfile(AudioProfile.newBuilder()
                        .setEncoding(AudioEncoding.AUDIO_ENCODING_PCM_S16LE)
                        .setSampleRateHz(16_000).setChannelCount(1).setFrameDurationMs(100)))
                .build();
    }

    private static ClientEnvelope frame(String meetingId, byte[] pcm) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setClientMessageId("network-frame-1")
                .setAudioFrame(com.czur.aimeeting.contract.realtime.v1.MeetingAudioFrame
                        .newBuilder().setStreamSequence(1).setStartPositionMs(0)
                        .setDurationMs(100).setAudio(ByteString.copyFrom(pcm)))
                .build();
    }

    private static ClientEnvelope inputComplete(String meetingId) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setClientMessageId("network-complete-1")
                .setInputComplete(InputComplete.newBuilder()
                        .setLastStreamSequence(1).setLastAudioPositionMs(100)
                        .setRequestId("network-complete-1"))
                .build();
    }

    private static ClientEnvelope resume(String meetingId, long afterEventSequence) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setClientMessageId("network-resume-" + afterEventSequence)
                .setResumeRequest(ResumeRequest.newBuilder()
                        .setAfterEventSequence(afterEventSequence)).build();
    }

    private static String createBody() {
        return """
                {"clientSessionId":"wss-network-session-1",
                 "profile":{"id":"wss-network-profile","revision":1},
                 "client":{"appId":"app","appVersion":"1","contractVersion":"v1","deviceId":"device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,
                                 "channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":false}}
                """;
    }

    private Map<String, String> config(int wssPort) {
        Map<String, String> values = new HashMap<>();
        values.put(DatabaseRuntimeConfig.DATABASE_URL, scopedUrl());
        values.put(DatabaseRuntimeConfig.DATABASE_USER, user());
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, password());
        values.put(ReferenceDevIdentityConfig.ENABLED, "true");
        values.put(ReferenceDevIdentityConfig.BEARER_TOKEN, USER_TOKEN);
        values.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, SUBJECT);
        values.put(ReferenceDevIdentityConfig.ROLES, "USER");
        values.put(ReferenceDevIdentityConfig.PERMISSIONS, "meeting:create");
        values.put(ReferenceDevIdentityConfig.AUDIENCE, "wss-network-audience");
        values.put(ReferenceDevIdentityConfig.CLIENT_ID, "wss-network-client");
        values.put(ReferenceDevIdentityConfig.ISSUED_AT, "2026-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2027-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.CREDENTIAL_ID, "wss-network-credential");
        values.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, SCOPE);
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_ID, "wss-network-profile");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REVISION, "1");
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES, "transcript.realtime");
        values.put(ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS, "600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_MEETING_DURATION_MS, "3600000");
        values.put(ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "32000");
        values.put(ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW, "100");
        values.put(ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET,
                "wss-network-page-secret-0123456789-abcdefgh");
        values.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN,
                "wss://127.0.0.1:" + wssPort);
        values.put(ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        return values;
    }

    /** V015 的 SQL 自己原子登记版本；不能再仿照旧迁移重复 INSERT。 */
    private void migrate(int first, int last) throws Exception {
        for (int version = first; version <= last; version++) {
            if (version != 1) continue;
            String prefix = String.format("V%03d__", version);
            Path directory = Path.of("deploy/migrations");
            if (!Files.isDirectory(directory)) directory = Path.of("../deploy/migrations");
            Path migration;
            try (var paths = Files.list(directory)) {
                migration = paths.filter(path -> path.getFileName().toString().startsWith(prefix))
                        .findFirst().orElseThrow();
            }
            try (Connection connection = fixtureDataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
                if (version != 15) {
                    statement.execute("INSERT INTO schema_migrations(version) VALUES ('"
                            + migration.getFileName().toString().replace(".sql", "") + "')");
                }
            }
        }
    }

    private static int freeLoopbackPort() throws Exception {
        try (ServerSocket reserved = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return reserved.getLocalPort();
        }
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(databaseUrl(), user(), password());
    }

    private String scopedUrl() {
        return databaseUrl() + (databaseUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
    }

    private static String databaseUrl() {
        return System.getenv("REFERENCE_TEST_DATABASE_URL");
    }

    private static String user() { return System.getenv("REFERENCE_TEST_DATABASE_USER"); }
    private static String password() { return System.getenv("REFERENCE_TEST_DATABASE_PASSWORD"); }

    private static final class EventListener implements java.net.http.WebSocket.Listener {
        private final CompletableFuture<ServerEnvelope> firstAck = new CompletableFuture<>();
        private final CompletableFuture<ServerEnvelope> secondAck = new CompletableFuture<>();
        private final CompletableFuture<ServerEnvelope> transcript = new CompletableFuture<>();
        private final CompletableFuture<ServerEnvelope> pageEnd = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final AtomicInteger ackCount = new AtomicInteger();
        private final List<String> order = new CopyOnWriteArrayList<>();

        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }

        @Override public CompletionStage<?> onBinary(java.net.http.WebSocket socket,
                ByteBuffer bytes, boolean last) {
            if (last) {
                byte[] wire = new byte[bytes.remaining()];
                bytes.get(wire);
                ServerEnvelope envelope = CODEC.decodeServer(wire);
                switch (envelope.getBodyCase()) {
                    case AUDIO_ACK -> {
                        order.add("ACK");
                        if (ackCount.incrementAndGet() == 1) firstAck.complete(envelope);
                        else secondAck.complete(envelope);
                    }
                    case TRANSCRIPT_UPSERT -> {
                        order.add("TRANSCRIPT");
                        transcript.complete(envelope);
                    }
                    case RESUME_PAGE_END -> {
                        order.add("PAGE_END");
                        pageEnd.complete(envelope);
                    }
                    default -> closed.completeExceptionally(
                            new AssertionError("unexpected WSS event body"));
                }
            }
            socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(java.net.http.WebSocket socket,
                int code, String reason) {
            closed.complete(code);
            return null;
        }

        @Override public void onError(java.net.http.WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
            firstAck.completeExceptionally(error);
            secondAck.completeExceptionally(error);
            transcript.completeExceptionally(error);
            pageEnd.completeExceptionally(error);
        }
    }

    private record TestTls(SSLContext server, SSLContext trusted,
                           Path keystore, String password) {
        private static TestTls create(Path directory) throws Exception {
            Path keystore = directory.resolve("wss-loopback-test.p12");
            char[] password = ("temporary-" + UUID.randomUUID()).toCharArray();
            try {
                Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
                Process process = new ProcessBuilder(keytool.toString(), "-genkeypair",
                        "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "1", "-dname", "CN=localhost",
                        "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                        "-storetype", "PKCS12", "-keystore", keystore.toString(),
                        "-storepass", new String(password), "-keypass", new String(password),
                        "-noprompt").redirectErrorStream(true).start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("test certificate generation timed out");
                }
                assertEquals(0, process.exitValue());
                KeyStore keys = KeyStore.getInstance("PKCS12");
                try (var input = Files.newInputStream(keystore)) {
                    keys.load(input, password);
                }
                KeyManagerFactory managers = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                managers.init(keys, password);
                TrustManagerFactory trust = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                trust.init(keys);
                SSLContext server = SSLContext.getInstance("TLS");
                server.init(managers.getKeyManagers(), null, null);
                SSLContext client = SSLContext.getInstance("TLS");
                client.init(null, trust.getTrustManagers(), null);
                return new TestTls(server, client, keystore, new String(password));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }
}
