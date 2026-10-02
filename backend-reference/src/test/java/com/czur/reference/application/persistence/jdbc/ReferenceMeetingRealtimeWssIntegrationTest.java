package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.AudioEncoding;
import com.czur.aimeeting.contract.realtime.v1.AudioProfile;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
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
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 专属 PostgreSQL 16 随机 schema：真实 HTTP Create 到正式 WSS 持久 ACK 的最短链。 */
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
    void httpCreateUrlAndTokenReachRealWssStartStoredFrameAndDurableAck() throws Exception {
        migrate(1, 13);
        TestTls tls = TestTls.create(temporaryDirectory);
        int wssPort = freeLoopbackPort();
        Map<String, String> config = config(wssPort);
        try (var assembly = ReferenceMeetingHttpAssembly.start(config, CLOCK)) {
            var missing = assertThrows(DatabaseStartupException.class,
                    () -> assembly.newRealtimeWssServer(
                            new InetSocketAddress("127.0.0.1", wssPort), tls.server()));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    missing.readiness().status(), "WSS requires V014 before binding");
        }
        migrate(14, 14);
        try (var assembly = ReferenceMeetingHttpAssembly.start(config, CLOCK)) {
            var missing = assertThrows(DatabaseStartupException.class,
                    () -> assembly.newRealtimeWssServer(
                            new InetSocketAddress("127.0.0.1", wssPort), tls.server()));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    missing.readiness().status(), "WSS requires V015 before binding");
        }
        migrate(15, 15);

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
            String meetingId = response.path("meetingId").textValue();
            String sessionToken = response.path("realtime").path("sessionToken").textValue();
            String subprotocol = response.path("realtime").path("subprotocol").textValue();
            URI wssUrl = URI.create(response.path("realtime").path("url").textValue());
            assertTrue(meetingId.startsWith("mtg_"));
            assertNotNull(sessionToken);
            assertEquals(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, subprotocol);
            assertEquals(wssPort, wssUrl.getPort());
            assertEquals("/v1/realtime/" + meetingId, wssUrl.getPath());

            AckListener listener = new AckListener();
            java.net.http.WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + sessionToken)
                    .subprotocols(subprotocol)
                    .buildAsync(wssUrl, listener).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(subprotocol, socket.getSubprotocol());
            socket.sendBinary(ByteBuffer.wrap(start(meetingId).toByteArray()), true).join();
            byte[] pcm = new byte[3_200];
            Arrays.fill(pcm, (byte) 0x5a);
            socket.sendBinary(ByteBuffer.wrap(frame(meetingId, pcm).toByteArray()), true).join();

            ServerEnvelope ack = listener.ack.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(meetingId, ack.getMeetingId());
            assertEquals(meetingId, ack.getStreamId());
            assertEquals(0, ack.getEventSequence());
            assertEquals("", ack.getEventId());
            assertTrue(ack.getAudioAck().getDurable());
            assertEquals(1, ack.getAudioAck().getAcceptedStreamSequence());
            assertEquals(100, ack.getAudioAck().getAcceptedAudioPositionMs());
            assertEquals(1, listener.ackCount.get());

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
            }
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

    private static final class AckListener implements java.net.http.WebSocket.Listener {
        private final CompletableFuture<ServerEnvelope> ack = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final AtomicInteger ackCount = new AtomicInteger();

        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }

        @Override public CompletionStage<?> onBinary(java.net.http.WebSocket socket,
                ByteBuffer bytes, boolean last) {
            if (last) {
                byte[] wire = new byte[bytes.remaining()];
                bytes.get(wire);
                ackCount.incrementAndGet();
                ack.complete(CODEC.decodeServer(wire));
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
            ack.completeExceptionally(error);
        }
    }

    private record TestTls(SSLContext server, SSLContext trusted) {
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
                return new TestTls(server, client);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }
}
