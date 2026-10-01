package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** 04-04 专属 A/B 测试输入；只保存跨进程可比较的非敏感状态。 */
final class T15HttpRecoveryFixtures {
    static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    static final String SCOPE = "t15-http-recovery";
    static final String SUBJECT = "recovery-subject";
    static final String CREATE_KEY = "t15-http-recovery-create-key-123456";
    static final String STOP_KEY = "t15-http-recovery-stop-key-123456";
    static final String CLIENT_SESSION = "t15-http-recovery-session";
    private static final String TOKEN = "t15-recovery-token-0123456789-abcdefgh";
    private static final int STATE_VERSION = 1;
    private static final int STATE_MAGIC = 0x54313552; // T15R

    private T15HttpRecoveryFixtures() { }

    static Map<String, String> config() {
        Map<String, String> values = new HashMap<>();
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database endpoint is forbidden");
        }
        values.put(DatabaseRuntimeConfig.DATABASE_URL, url);
        values.put(DatabaseRuntimeConfig.DATABASE_USER, required("REFERENCE_TEST_DATABASE_USER"));
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD,
                required("REFERENCE_TEST_DATABASE_PASSWORD"));
        values.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "1000");
        values.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "1000");
        values.put(ReferenceDevIdentityConfig.ENABLED, "true");
        values.put(ReferenceDevIdentityConfig.BEARER_TOKEN, TOKEN);
        values.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, SUBJECT);
        values.put(ReferenceDevIdentityConfig.ROLES, "USER");
        values.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create," + MeetingPermissions.STOP + "," + MeetingPermissions.DELETE);
        values.put(ReferenceDevIdentityConfig.AUDIENCE, "t15-recovery-audience");
        values.put(ReferenceDevIdentityConfig.CLIENT_ID, "t15-recovery-client");
        values.put(ReferenceDevIdentityConfig.ISSUED_AT, "2026-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2027-01-01T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.CREDENTIAL_ID, "t15-recovery-credential");
        values.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, SCOPE);
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_ID, "t15-recovery-profile");
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
                "t15-recovery-page-secret-0123456789-abcdefgh");
        values.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN,
                "wss://meeting.example.invalid");
        values.put(ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        return values;
    }

    static String createBody() {
        return """
                {"clientSessionId":"%s","profile":{"id":"t15-recovery-profile","revision":1},
                 "client":{"appId":"app","appVersion":"1","contractVersion":"v1","deviceId":"device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":100},
                 "requestedCapabilities":["transcript.realtime"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":false}}
                """.formatted(CLIENT_SESSION);
    }

    static String authorization(String key, boolean json) {
        StringBuilder headers = new StringBuilder("Authorization: Bearer ").append(TOKEN)
                .append("\r\n");
        if (key != null) headers.append("Idempotency-Key: ").append(key).append("\r\n");
        if (json) headers.append("Content-Type: application/json\r\n");
        return headers.toString();
    }

    static Reply exchange(int port, String method, String path, String body, String headers)
            throws Exception {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String head = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + headers + "Content-Length: " + content.length
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
            String raw = received.toString(StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) throw new AssertionError("HTTP response has no body boundary");
            int line = raw.indexOf("\r\n");
            int status = Integer.parseInt(raw.substring(0, line).split(" ")[1]);
            return new Reply(status, raw.substring(split + 4));
        }
    }

    static String createdMeetingId(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meeting_id FROM meeting_create_fact WHERE idempotency_key=?")) {
            statement.setString(1, CREATE_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("V013 Create fact missing");
                String id = rows.getString(1);
                if (rows.next()) throw new AssertionError("V013 Create fact duplicated");
                return id;
            }
        }
    }

    static Instant postmasterStart(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_postmaster_start_time()")) {
            if (!rows.next()) throw new AssertionError("PostgreSQL start time missing");
            Instant start = rows.getTimestamp(1).toInstant();
            if (rows.next()) throw new AssertionError("PostgreSQL start time duplicated");
            return start;
        }
    }

    static long count(DataSource source, String table) throws Exception {
        if (!java.util.Set.of("meeting", "meeting_create_fact", "usage_reservation",
                "usage_operation", "durable_input_fact", "final_usage_fact",
                "workflow_step", "artifact", "meeting_manifest_revision",
                "meeting_deletion_plan").contains(table)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            if (!rows.next()) throw new AssertionError("count result missing");
            return rows.getLong(1);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String sha256(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static Path artifactRoot() {
        Path root = Path.of(required("T15_HTTP_RECOVERY_ARTIFACT_ROOT"))
                .toAbsolutePath().normalize();
        Path state = stateFile();
        if (!root.equals(state.getParent().resolve("artifacts"))
                || Files.isSymbolicLink(root) || Files.isSymbolicLink(root.getParent())) {
            throw new IllegalStateException("Artifact root is not this private state directory");
        }
        return root;
    }

    static Path stateFile() {
        Path state = Path.of(required("T15_HTTP_RECOVERY_STATE_FILE"))
                .toAbsolutePath().normalize();
        Path parent = state.getParent();
        if (!state.getFileName().toString().equals("recovery-state.bin")
                || parent == null || !parent.getParent().equals(Path.of("/tmp"))
                || !parent.getFileName().toString()
                        .matches("czur-t15-http-recovery-state\\.[A-Za-z0-9]{6}")) {
            throw new IllegalStateException("state file is not this private test directory");
        }
        return state;
    }

    static void writeState(State state) throws Exception {
        Path path = stateFile();
        Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(
                path, StandardOpenOption.WRITE))) {
            out.writeInt(STATE_MAGIC);
            out.writeInt(STATE_VERSION);
            out.writeUTF(state.meetingId());
            out.writeInt(state.createLength());
            out.writeUTF(state.createSha256());
            out.writeUTF(state.artifactId());
            out.writeLong(state.artifactSize());
            out.writeUTF(state.artifactSha256());
            out.writeUTF(state.receiptSha256());
            out.writeUTF(state.postmasterStart().toString());
            out.writeLong(state.phasePid());
        }
    }

    static State readState() throws Exception {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(stateFile()))) {
            if (in.readInt() != STATE_MAGIC || in.readInt() != STATE_VERSION) {
                throw new AssertionError("recovery state version mismatch");
            }
            State state = new State(in.readUTF(), in.readInt(), in.readUTF(), in.readUTF(),
                    in.readLong(), in.readUTF(), in.readUTF(), Instant.parse(in.readUTF()),
                    in.readLong());
            if (in.read() != -1) throw new AssertionError("recovery state has trailing bytes");
            return state;
        }
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing test environment: " + key);
        }
        return value;
    }

    record Reply(int status, String body) {
        byte[] bodyBytes() { return body.getBytes(StandardCharsets.UTF_8); }
    }

    record State(String meetingId, int createLength, String createSha256,
                 String artifactId, long artifactSize, String artifactSha256,
                 String receiptSha256, Instant postmasterStart, long phasePid) {
        State {
            if (meetingId == null || !meetingId.matches("mtg_[A-Za-z0-9_-]+")
                    || artifactId == null || !artifactId.matches("art_[A-Za-z0-9_-]+")
                    || createLength <= 0 || artifactSize <= 0 || phasePid <= 0) {
                throw new IllegalArgumentException("recovery state identity or size is invalid");
            }
            for (String digest : java.util.List.of(createSha256, artifactSha256,
                    receiptSha256)) {
                if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("recovery state digest is invalid");
                }
            }
            Objects.requireNonNull(postmasterStart, "postmasterStart");
        }
    }
}
