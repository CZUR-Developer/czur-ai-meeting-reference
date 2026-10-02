package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.protocol.json.RealtimeTokenGrantJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 03C-2 独立 PostgreSQL 快照核验门禁；不连接共享测试库。
 *
 * <p>每例建立随机 schema 并安装真实 V001～V014，再经已有完整 Create 工作单元
 * 保存 V013 赢家。续期事实只作为已提交数据库输入插入；本测试不实现 03C-3 的
 * 续期签发，也不建立 WSS/HTTP 连接。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcRealtimeCredentialVerifierIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql",
            "V014__realtime_token_grant.sql");
    private static final String SUFFIX = "credential_a";
    private static final String MEETING = T15CreateRecoveryFixtures.meetingId(SUFFIX);
    private static final String FIRST_TOKEN = token((byte) 1);
    private static final String KEY = "credential-create-key-0001";
    private static final Instant NOW = T15CreateRecoveryFixtures.CREATED_AT.plusSeconds(20);

    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        schema = "credential_verify_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        for (String migration : MIGRATIONS) {
            execute(readMigration(migration));
            try (Connection connection = source(schema).getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO schema_migrations(version) VALUES (?)")) {
                statement.setString(1, migration.substring(0, migration.length() - 4));
                statement.executeUpdate();
            }
        }
        T15CreateRecoveryFixtures.insertReserve(source(schema), SUFFIX);
        assertInstanceOf(com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork
                        .Success.class,
                T15CreateRecoveryFixtures.unitOfWork(source(schema)).create(
                        T15CreateRecoveryFixtures.request(SUFFIX, KEY, null, FIRST_TOKEN)));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema != null) {
            try (Connection connection = source(null).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void firstAndMultipleRenewedTokensSurviveNewConnectionsAndRespectStateMatrix()
            throws Exception {
        String second = token((byte) 2);
        String third = token((byte) 3);
        insertGrant("renew-key-0000000002", second, NOW.plusSeconds(60));
        insertGrant("renew-key-0000000003", third, NOW.plusSeconds(70));
        assertAccess(FIRST_TOKEN, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        assertAccess(second, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        assertAccess(third, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        setState("AUTHORIZED", "INPUT_OPEN");
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, second, MEETING, NOW));
        setState("STARTING", "INPUT_OPEN");
        assertAccess(second, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        setState("RUNNING", "INPUT_OPEN");
        assertAccess(third, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        setState("STOPPING", "INPUT_COMPLETE_PENDING");
        assertAccess(second, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        setState("FINALIZING", "INPUT_ACCEPTED");
        assertAccess(second, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.READ_ONLY);
        setState("DELETION_PENDING", "INPUT_ACCEPTED");
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, second, MEETING, NOW));
    }

    @Test
    void wrongScopeStreamTokenExpiryAndLegacyMeetingAreDenied() throws Exception {
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify("other-deployment", FIRST_TOKEN, MEETING, NOW));
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, "wrong", MEETING, NOW));
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verifyWithStream(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN,
                        MEETING, "mtg_other_stream", NOW));
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN,
                        "mtg_not_present", NOW));
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING,
                        T15CreateRecoveryFixtures.CREATED_AT.plusSeconds(120)));
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at)
                     VALUES ('mtg_legacy_verify', ?, 'legacy-session', 'LEGACY_UNOWNED',
                         'standard', 1, 'CREATED', 0, ?, ?)
                     """)) {
            statement.setString(1, T15CreateRecoveryFixtures.DEPLOYMENT);
            statement.setTimestamp(2, Timestamp.from(NOW));
            statement.setTimestamp(3, Timestamp.from(NOW));
            statement.executeUpdate();
        }
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN,
                        "mtg_legacy_verify", NOW));
    }

    @Test
    void corruptedActiveGrantOrImpossibleStateFailsClosed() throws Exception {
        String renewed = token((byte) 4);
        insertGrant("renew-key-0000000004", renewed, NOW.plusSeconds(60));
        setState("RUNNING", "INPUT_ACCEPTED");
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, renewed, MEETING, NOW));
        setState("CREATED", "INPUT_OPEN");
        execute("ALTER TABLE meeting_realtime_token_grant "
                + "DROP CONSTRAINT meeting_realtime_token_grant_digest_ck");
        execute("UPDATE meeting_realtime_token_grant SET token_sha256 = "
                + "decode(repeat('00', 32), 'hex')");
        // 即便当前凭据命中未损坏的 V013，也不能跳过活动 V014 的坏事实。
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void wrongDeploymentDoesNotRevealDamagedOwnedFact() throws Exception {
        execute("ALTER TABLE meeting_create_fact "
                + "DROP CONSTRAINT meeting_create_fact_response_ck");
        execute("UPDATE meeting_create_fact SET response_sha256 = "
                + "decode(repeat('00', 32), 'hex')");
        assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                verify("other-deployment", FIRST_TOKEN, MEETING, NOW));
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void databaseFailureIsUnavailable() {
        PGSimpleDataSource unavailable = source(null);
        unavailable.setUrl("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1");
        var verifier = new JdbcRealtimeCredentialVerifier(unavailable,
                T15CreateRecoveryFixtures.DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC));
        try (SecretCredential credential = SecretCredential.copyOf(
                FIRST_TOKEN.getBytes(StandardCharsets.UTF_8))) {
            assertInstanceOf(RealtimeCredentialVerifier.Unavailable.class,
                    verifier.verify(new RealtimeCredentialVerifier.Request(MEETING, MEETING,
                            credential)));
        }
    }

    @Test
    void secondJvmReadsPersistedFirstAndRenewedCredentials() throws Exception {
        String renewed = token((byte) 2);
        insertGrant("renew-cross-jvm-0002", renewed, NOW.plusSeconds(60));
        assertAccess(FIRST_TOKEN, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
        assertAccess(renewed, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);

        // 复用仓库现有的 surefire classpath + ProcessBuilder 方式；子进程只接收
        // 随机测试 schema 名，不把 Token、数据库密码或 Grant JSON 放进命令行。
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", classpath,
                JdbcRealtimeCredentialVerifierIntegrationTest.class.getName(),
                "probe", schema).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("credential readback child JVM timed out");
            }
            String output = new String(child.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(),
                    "child JVM did not verify persisted credentials");
            assertEquals(1, output.lines().filter("RESULT=FIRST_AND_RENEWED_WRITE"::equals)
                    .count(), "child JVM did not return the expected fixed result");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void verifierLogsNeverContainTokensGrantOrSubject() throws Exception {
        String renewed = token((byte) 5);
        Instant expiresAt = NOW.plusSeconds(60);
        String grantJson = RealtimeTokenGrantJson.encode(
                new RenewRealtimeTokenUnitOfWork.Grant(MEETING, MEETING, renewed, expiresAt));
        insertGrant("renew-log-gate-0005", renewed, expiresAt);
        Logger logger = Logger.getLogger(JdbcRealtimeCredentialVerifier.class.getName());
        Level previousLevel = logger.getLevel();
        StringBuilder captured = new StringBuilder();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                captured.append(record.getMessage()).append('\n');
                if (record.getParameters() != null) {
                    captured.append(Arrays.toString(record.getParameters())).append('\n');
                }
                if (record.getThrown() != null) {
                    captured.append(record.getThrown()).append('\n');
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            assertAccess(FIRST_TOKEN, MEETING, NOW, RealtimeCredentialVerifier.AccessMode.WRITE);
            assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                    verify(T15CreateRecoveryFixtures.DEPLOYMENT, "wrong-token", MEETING, NOW));
            execute("ALTER TABLE meeting_realtime_token_grant "
                    + "DROP CONSTRAINT meeting_realtime_token_grant_digest_ck");
            execute("UPDATE meeting_realtime_token_grant SET token_sha256 = "
                    + "decode(repeat('00', 32), 'hex')");
            assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                    verify(T15CreateRecoveryFixtures.DEPLOYMENT, renewed, MEETING, NOW));
            PGSimpleDataSource unavailable = source(null);
            unavailable.setUrl("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1");
            try (SecretCredential credential = SecretCredential.copyOf(
                    FIRST_TOKEN.getBytes(StandardCharsets.UTF_8))) {
                assertInstanceOf(RealtimeCredentialVerifier.Unavailable.class,
                        new JdbcRealtimeCredentialVerifier(unavailable,
                                T15CreateRecoveryFixtures.DEPLOYMENT,
                                Clock.fixed(NOW, ZoneOffset.UTC)).verify(
                                new RealtimeCredentialVerifier.Request(MEETING, MEETING,
                                        credential)));
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
        String logs = captured.toString();
        assertFalse(logs.contains(FIRST_TOKEN));
        assertFalse(logs.contains(renewed));
        assertFalse(logs.contains(grantJson));
        assertFalse(logs.contains(T15CreateRecoveryFixtures.SUBJECT));
    }

    /** 真正独立的 Java 进程探针，只输出稳定结果码，凭据在子进程内构造。 */
    public static void main(String[] args) {
        if (args.length != 2 || !"probe".equals(args[0])) {
            System.err.println("PROBE_INVALID_ARGUMENTS");
            System.exit(2);
        }
        try {
            var verifier = new JdbcRealtimeCredentialVerifier(source(args[1]),
                    T15CreateRecoveryFixtures.DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC));
            if (probeAccess(verifier, FIRST_TOKEN) != RealtimeCredentialVerifier.AccessMode.WRITE
                    || probeAccess(verifier, token((byte) 2))
                            != RealtimeCredentialVerifier.AccessMode.WRITE) {
                throw new IllegalStateException("credential readback did not grant write");
            }
            System.out.println("RESULT=FIRST_AND_RENEWED_WRITE");
        } catch (Exception failure) {
            System.err.println("PROBE_FAILED");
            System.exit(3);
        }
    }

    private static RealtimeCredentialVerifier.AccessMode probeAccess(
            JdbcRealtimeCredentialVerifier verifier, String token) {
        try (SecretCredential credential = SecretCredential.copyOf(
                token.getBytes(StandardCharsets.UTF_8))) {
            var result = verifier.verify(new RealtimeCredentialVerifier.Request(
                    MEETING, MEETING, credential));
            return result instanceof RealtimeCredentialVerifier.Granted granted
                    ? granted.access() : null;
        }
    }

    @Test
    void expiryCannotBeExtendedByClockRollbackOrAnotherGrant() throws Exception {
        Instant expiresAt = T15CreateRecoveryFixtures.CREATED_AT.plusSeconds(120);
        AtomicInteger calls = new AtomicInteger();
        Clock rollbackClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() {
                return calls.getAndIncrement() == 0 ? expiresAt : NOW;
            }
        };
        var verifier = new JdbcRealtimeCredentialVerifier(source(schema),
                T15CreateRecoveryFixtures.DEPLOYMENT, rollbackClock);
        try (SecretCredential credential = SecretCredential.copyOf(
                FIRST_TOKEN.getBytes(StandardCharsets.UTF_8))) {
            assertInstanceOf(RealtimeCredentialVerifier.Denied.class,
                    verifier.verify(new RealtimeCredentialVerifier.Request(MEETING, MEETING,
                            credential)));
        }
        // V014 唯一约束只覆盖本表，不覆盖 V013；同一 Token 双重声明不可选择
        // 较远期限来放行。每个测试都有独立 schema，因此不会污染其它场景。
        insertGrant("renew-key-duplicate-0001", FIRST_TOKEN, NOW.plusSeconds(70));
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void createScopeAndTimestampMustMatchOwnedMeeting() throws Exception {
        updateCreateScope("wrong-create-scope");
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
        // 只改回 scope，不重新写 Create 正文；后续单独变更会议创建时间。
        updateCreateScope(com.czur.reference.application.port.IdempotencyScopeCodec.meetingCreateV2(
                T15CreateRecoveryFixtures.DEPLOYMENT,
                T15CreateRecoveryFixtures.SUBJECT));
        execute("UPDATE meeting SET created_at = created_at + interval '1 second', "
                + "updated_at = updated_at + interval '1 second' "
                + "WHERE meeting_id = '" + MEETING + "'");
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void persistedCreateRequestAndResponseAudioMustAgree() throws Exception {
        // 重算数据库摘要，证明失败来自两个各自合法的 V013 正文互相矛盾，
        // 而不是单个 JSON 或 SHA-256 损坏。
        mutateResponseJson("\"frameDurationMs\":100", "\"frameDurationMs\":120");
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void persistedAudioLimitMustFitNominalFrame() throws Exception {
        mutateResponseJson("\"maxAudioFrameBytes\":64000",
                "\"maxAudioFrameBytes\":3199");
        assertInstanceOf(RealtimeCredentialVerifier.FactInconsistent.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, FIRST_TOKEN, MEETING, NOW));
    }

    @Test
    void firstAndRenewedTokensUseThePersistedCreateAudioLimit() throws Exception {
        mutateResponseJson("\"maxAudioFrameBytes\":64000",
                "\"maxAudioFrameBytes\":4000");
        String renewed = token((byte) 6);
        insertGrant("renew-audio-limit-0006", renewed, NOW.plusSeconds(60));
        for (String credential : List.of(FIRST_TOKEN, renewed)) {
            var granted = assertInstanceOf(RealtimeCredentialVerifier.Granted.class,
                    verify(T15CreateRecoveryFixtures.DEPLOYMENT, credential, MEETING, NOW));
            assertEquals(AudioProfile.pcm16kMono(100), granted.audioContract().audioProfile());
            assertEquals(4_000, granted.audioContract().maxAudioFrameBytes());
        }
        setState("FINALIZING", "INPUT_ACCEPTED");
        var readOnly = assertInstanceOf(RealtimeCredentialVerifier.Granted.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, renewed, MEETING, NOW));
        assertEquals(RealtimeCredentialVerifier.AccessMode.READ_ONLY, readOnly.access());
        assertEquals(4_000, readOnly.audioContract().maxAudioFrameBytes());
    }

    private void assertAccess(String token, String meetingId, Instant now,
            RealtimeCredentialVerifier.AccessMode expected) {
        var granted = assertInstanceOf(RealtimeCredentialVerifier.Granted.class,
                verify(T15CreateRecoveryFixtures.DEPLOYMENT, token, meetingId, now));
        assertEquals(expected, granted.access());
        assertEquals(meetingId, granted.readOwnership().meetingId());
        assertEquals(T15CreateRecoveryFixtures.DEPLOYMENT,
                granted.readOwnership().deploymentScope());
        assertEquals(T15CreateRecoveryFixtures.SUBJECT,
                granted.readOwnership().subjectScope());
        assertEquals(AudioProfile.pcm16kMono(100), granted.audioContract().audioProfile());
        assertEquals(64_000, granted.audioContract().maxAudioFrameBytes());
    }

    private RealtimeCredentialVerifier.Result verify(
            String deployment, String token, String meetingId, Instant now) {
        return verifyWithStream(deployment, token, meetingId, meetingId, now);
    }

    private RealtimeCredentialVerifier.Result verifyWithStream(
            String deployment, String token, String meetingId, String streamId, Instant now) {
        var verifier = new JdbcRealtimeCredentialVerifier(source(schema), deployment,
                Clock.fixed(now, ZoneOffset.UTC));
        try (SecretCredential credential = SecretCredential.copyOf(
                token.getBytes(StandardCharsets.UTF_8))) {
            return verifier.verify(new RealtimeCredentialVerifier.Request(meetingId,
                    streamId, credential));
        }
    }

    private void insertGrant(String key, String token, Instant expiresAt) throws Exception {
        String json = RealtimeTokenGrantJson.encode(new RenewRealtimeTokenUnitOfWork.Grant(
                MEETING, MEETING, token, expiresAt));
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_realtime_token_grant (
                         meeting_id, idempotency_key, grant_json, grant_sha256,
                         token_sha256, issued_at, expires_at)
                     VALUES (?, ?, ?, sha256(convert_to(?, 'UTF8')),
                         sha256(convert_to(?, 'UTF8')), ?, ?)
                     """)) {
            statement.setString(1, MEETING);
            statement.setString(2, key);
            statement.setString(3, json);
            statement.setString(4, json);
            statement.setString(5, token);
            statement.setTimestamp(6, Timestamp.from(NOW));
            statement.setTimestamp(7, Timestamp.from(expiresAt));
            statement.executeUpdate();
        }
    }

    private void setState(String state, String inputState) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET state = ?, input_state = ?,
                         finalization_receipt_id = ? WHERE meeting_id = ?
                     """)) {
            statement.setString(1, state);
            statement.setString(2, inputState);
            statement.setString(3, "INPUT_ACCEPTED".equals(inputState)
                    ? "receipt-verifier-test" : null);
            statement.setString(4, MEETING);
            statement.executeUpdate();
        }
    }

    private void updateCreateScope(String scope) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE meeting_create_fact SET scope = ? WHERE meeting_id = ?")) {
            statement.setString(1, scope);
            statement.setString(2, MEETING);
            statement.executeUpdate();
        }
    }

    private void mutateResponseJson(String original, String replacement) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_create_fact
                        SET response_json = replace(response_json, ?, ?),
                            response_sha256 = sha256(convert_to(
                                    replace(response_json, ?, ?), 'UTF8'))
                      WHERE meeting_id = ?
                     """)) {
            statement.setString(1, original);
            statement.setString(2, replacement);
            statement.setString(3, original);
            statement.setString(4, replacement);
            statement.setString(5, MEETING);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String token(byte marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, marker);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PGSimpleDataSource source(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredUrl());
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String name) throws Exception {
        for (Path path : new Path[]{Path.of("deploy", "migrations", name),
                Path.of("..", "deploy", "migrations", name)}) {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found");
    }

    private static String requiredUrl() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        return url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
