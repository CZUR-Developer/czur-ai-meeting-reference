package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.protocol.json.RealtimeTokenGrantJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V014 续期写事务的随机 PostgreSQL 16 门禁；不启动 HTTP/WSS，不访问共享库。
 * 每例使用真实完整 Create 工作单元保存 V013，然后只通过新 UoW 写续期事实。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcRenewRealtimeTokenUnitOfWorkIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql",
            "V014__realtime_token_grant.sql");
    private static final String SUFFIX = "renew_a";
    private static final String MEETING = T15CreateRecoveryFixtures.meetingId(SUFFIX);
    private static final String INITIAL_TOKEN = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);
    private static final String KEY_ONE = "renew-idempotency-key-0001";
    private static final String KEY_TWO = "renew-idempotency-key-0002";
    private static final Instant NOW = T15CreateRecoveryFixtures.CREATED_AT.plusSeconds(20);

    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        schema = "renew_token_" + UUID.randomUUID().toString().replace("-", "");
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
                        T15CreateRecoveryFixtures.request(SUFFIX,
                                "renew-create-key-0000", null, INITIAL_TOKEN)));
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
    void sameKeyReplaysOriginalAfterStateChangeAndNewKeyGetsDifferentToken()
            throws Exception {
        String initialResponseJson = readCreateResponseJson();
        var first = success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.CREATED, first.code());
        var replay = success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.persistedGrantJson(), replay.persistedGrantJson());
        assertEquals(1, countGrants());

        setState("STOPPING", "INPUT_COMPLETE_PENDING");
        var second = success(unitOfWork(source(schema)).renew(request(KEY_TWO)));
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.CREATED, second.code());
        assertNotEquals(first.persistedGrantJson(), second.persistedGrantJson());
        var firstGrant = RealtimeTokenGrantJson.decode(first.persistedGrantJson());
        var secondGrant = RealtimeTokenGrantJson.decode(second.persistedGrantJson());
        assertNotEquals(firstGrant.sessionToken(), secondGrant.sessionToken());
        assertEquals(MEETING, secondGrant.streamId());
        assertEquals(2, countGrants());

        setState("FINALIZING", "INPUT_ACCEPTED");
        assertEquals(first.persistedGrantJson(),
                success(unitOfWork(source(schema)).renew(request(KEY_ONE)))
                        .persistedGrantJson());
        assertFailure(unitOfWork(source(schema)).renew(request(
                "renew-idempotency-key-0003")),
                RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT);
        // 即使历史 Grant 已经过期，同键也只能重放原字节；不得按新时钟刷新。
        assertEquals(first.persistedGrantJson(),
                success(unitOfWorkAt(source(schema), NOW.plusSeconds(120))
                        .renew(request(KEY_ONE))).persistedGrantJson());
        setState("DELETION_PENDING", "INPUT_ACCEPTED");
        assertEquals(second.persistedGrantJson(),
                success(unitOfWork(source(schema)).renew(request(KEY_TWO)))
                        .persistedGrantJson());
        assertEquals(initialResponseJson, readCreateResponseJson(),
                "renewal must never rewrite the V013 first Create response");
    }

    @Test
    void wrongOwnerOrDeploymentCannotProbeExistingKey() throws Exception {
        success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
        var wrongOwner = request(context(T15CreateRecoveryFixtures.DEPLOYMENT,
                "another-subject"), KEY_ONE);
        assertFailure(unitOfWork(source(schema)).renew(wrongOwner),
                RenewRealtimeTokenUnitOfWork.ErrorCode.ACCESS_DENIED);
        var wrongDeployment = request(context("another-deployment",
                T15CreateRecoveryFixtures.SUBJECT), KEY_ONE);
        assertFailure(unitOfWork(source(schema)).renew(wrongDeployment),
                RenewRealtimeTokenUnitOfWork.ErrorCode.ACCESS_DENIED);
        assertEquals(1, countGrants());
    }

    @Test
    void commitAfterPersistenceButBeforeAcknowledgementReturnsStoredWinner()
            throws Exception {
        DataSource uncertain = commitFault(source(schema), true);
        var recovered = success(unitOfWork(uncertain).renew(request(KEY_ONE)));
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.REPLAYED,
                recovered.code());
        assertEquals(1, countGrants());
        assertEquals(recovered.persistedGrantJson(),
                success(unitOfWork(source(schema)).renew(request(KEY_ONE)))
                        .persistedGrantJson());
    }

    @Test
    void commitBeforePersistenceAndLostAcknowledgementNeverReturnsCandidate()
            throws Exception {
        DataSource uncertain = commitFault(source(schema), false);
        assertFailure(unitOfWork(uncertain).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.OUTCOME_UNKNOWN);
        assertEquals(0, countGrants());
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.CREATED,
                success(unitOfWork(source(schema)).renew(request(KEY_ONE))).code());
    }

    @Test
    void committedGrantWithUnavailableRecoveryStillReturnsUnknownNotCandidate()
            throws Exception {
        DataSource uncertain = commitFault(source(schema), true, true);
        assertFailure(unitOfWork(uncertain).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.OUTCOME_UNKNOWN);
        assertEquals(1, countGrants(), "the database may have committed despite unknown result");
        assertEquals(RenewRealtimeTokenUnitOfWork.SuccessCode.REPLAYED,
                success(unitOfWork(source(schema)).renew(request(KEY_ONE))).code());
    }

    @Test
    void committedGrantWithCorruptRecoveryFactNeverReturnsCandidate() throws Exception {
        execute("ALTER TABLE meeting_realtime_token_grant "
                + "DROP CONSTRAINT meeting_realtime_token_grant_digest_ck");
        DataSource uncertain = commitFault(source(schema), true, false, () -> {
            try {
                execute("UPDATE meeting_realtime_token_grant SET grant_sha256 = "
                        + "decode(repeat('00', 32), 'hex')");
            } catch (Exception failure) {
                throw new IllegalStateException("cannot corrupt committed test row", failure);
            }
        });
        assertFailure(unitOfWork(uncertain).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.OUTCOME_UNKNOWN);
        assertEquals(1, countGrants());
        assertFailure(unitOfWork(source(schema)).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.FACT_INCONSISTENT);
    }

    @Test
    void renewalLogsNeverContainTokenOrRequestIdentity() throws Exception {
        Logger logger = Logger.getLogger(JdbcRenewRealtimeTokenUnitOfWork.class.getName());
        Level originalLevel = logger.getLevel();
        StringBuilder logged = new StringBuilder();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                logged.append(record.getMessage())
                        .append(Arrays.toString(record.getParameters())).append('\n');
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try {
            var created = success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
            success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
            byte[][] partiallyFilled = new byte[1][];
            assertFailure(unitOfWork(source(schema), new SecureRandom() {
                @Override public void nextBytes(byte[] bytes) {
                    Arrays.fill(bytes, 0, 5, (byte) 0x5a);
                    partiallyFilled[0] = bytes;
                    throw new IllegalArgumentException("sensitive=" + INITIAL_TOKEN);
                }
            }).renew(request(KEY_TWO)), RenewRealtimeTokenUnitOfWork.ErrorCode.INTERNAL);
            assertTrue(partiallyFilled[0] != null
                    && Arrays.equals(new byte[32], partiallyFilled[0]),
                    "partially filled candidate bytes must be cleared after RNG failure");
            for (String sensitive : List.of(INITIAL_TOKEN, KEY_ONE, KEY_TWO, MEETING,
                    T15CreateRecoveryFixtures.SUBJECT,
                    T15CreateRecoveryFixtures.DEPLOYMENT,
                    created.persistedGrantJson(),
                    RealtimeTokenGrantJson.decode(created.persistedGrantJson()).sessionToken())) {
                assertFalse(logged.toString().contains(sensitive),
                        "renewal logs must not contain token, identity or grant JSON");
            }
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void secondJvmReplaysOriginalGrantAndIssuesDistinctNewKey() throws Exception {
        String firstJson = success(unitOfWork(source(schema)).renew(request(KEY_ONE)))
                .persistedGrantJson();
        // 独立 JVM 只接收随机测试 schema；Token、Grant JSON 和数据库密码均不
        // 出现在 argv。子进程从环境取得隔离数据库配置，只输出固定结果码。
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", classpath,
                JdbcRenewRealtimeTokenUnitOfWorkIntegrationTest.class.getName(),
                "probe", schema).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("renewal child JVM timed out");
            }
            String output = new String(child.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), "renewal child JVM failed");
            assertEquals(1, output.lines().filter("RESULT=REPLAYED_AND_DISTINCT"::equals)
                    .count(), "child JVM did not confirm both persistent outcomes");
            assertFalse(output.contains(firstJson));
            assertFalse(output.contains(INITIAL_TOKEN));
            assertFalse(output.contains(KEY_ONE));
            assertFalse(output.contains(KEY_TWO));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(2, countGrants());
        assertEquals(firstJson, success(unitOfWork(source(schema)).renew(request(KEY_ONE)))
                .persistedGrantJson(), "a second JVM must not alter first grant bytes");
        String secondJson = success(unitOfWork(source(schema)).renew(request(KEY_TWO)))
                .persistedGrantJson();
        assertNotEquals(firstJson, secondJson);
    }

    /** 独立 JVM 的小探针只输出固定状态码，任何异常都不打印动态正文。 */
    public static void main(String[] args) {
        if (args.length != 2 || !"probe".equals(args[0])) {
            System.err.println("PROBE_INVALID_ARGUMENTS");
            System.exit(2);
        }
        try {
            var unit = new JdbcRenewRealtimeTokenUnitOfWork(source(args[1]),
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60),
                    new SecureRandom());
            var replay = unit.renew(request(KEY_ONE));
            if (!(replay instanceof RenewRealtimeTokenUnitOfWork.Success first)
                    || first.code() != RenewRealtimeTokenUnitOfWork.SuccessCode.REPLAYED
                    || !first.persistedGrantJson().equals(storedGrantJson(args[1], KEY_ONE))) {
                throw new IllegalStateException("replay differs from persisted original");
            }
            var fresh = unit.renew(request(KEY_TWO));
            if (!(fresh instanceof RenewRealtimeTokenUnitOfWork.Success second)
                    || second.code() != RenewRealtimeTokenUnitOfWork.SuccessCode.CREATED
                    || second.persistedGrantJson().equals(first.persistedGrantJson())
                    || !second.persistedGrantJson().equals(storedGrantJson(args[1], KEY_TWO))) {
                throw new IllegalStateException("new key did not persist a distinct grant");
            }
            System.out.println("RESULT=REPLAYED_AND_DISTINCT");
        } catch (Exception failure) {
            System.err.println("PROBE_FAILED");
            System.exit(3);
        }
    }

    private static String storedGrantJson(String schema, String key) throws SQLException {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT grant_json FROM meeting_realtime_token_grant
                      WHERE meeting_id = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, MEETING);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("grant row absent");
                String json = rows.getString(1);
                if (rows.next()) throw new IllegalStateException("duplicate grant rows");
                return json;
            }
        }
    }

    @Test
    void renewalReadsStateCommittedByStopAfterRealMeetingRowLockWait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String applicationName = "renew_wait_" + schema;
        try (Connection blocker = source(schema).getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT meeting_id FROM meeting WHERE meeting_id = ? FOR UPDATE")) {
                lock.setString(1, MEETING);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            }
            CountDownLatch started = new CountDownLatch(1);
            Future<RenewRealtimeTokenUnitOfWork.Result> pending = executor.submit(() -> {
                started.countDown();
                return unitOfWork(source(schema, applicationName)).renew(request(KEY_ONE));
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            awaitRenewalRowLockWait(applicationName, pending);
            // 模拟 Stop/Admission 在同一会议行上提交状态：续期不能从锁前
            // CREATED+OPEN 的旧状态签发，而必须读取解锁后的新状态。
            updateState(blocker, "FINALIZING", "INPUT_ACCEPTED");
            blocker.commit();
            assertFailure(pending.get(10, TimeUnit.SECONDS),
                    RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, countGrants());
    }

    /**
     * 等到 PostgreSQL 亲自报告续期连接在等待 Lock，而非仅等 Java 线程启动。
     * 真实 Stop/Admission 与续期都更新相同 meeting 行，因此此门禁证明其串行点。
     */
    private void awaitRenewalRowLockWait(String applicationName,
            Future<RenewRealtimeTokenUnitOfWork.Result> pending) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            assertFalse(pending.isDone(), "renewal finished before waiting for meeting row lock");
            try (Connection observer = source(schema).getConnection();
                 PreparedStatement statement = observer.prepareStatement("""
                         SELECT wait_event_type, query
                           FROM pg_stat_activity
                          WHERE application_name = ?
                         """)) {
                statement.setString(1, applicationName);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if ("Lock".equals(rows.getString(1))
                                && rows.getString(2).contains("FOR UPDATE")) return;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("renewal did not wait for meeting row lock within 3 seconds");
    }

    @Test
    void damagedExistingGrantFailsClosedBeforeIssuingAnotherToken() throws Exception {
        success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
        execute("ALTER TABLE meeting_realtime_token_grant "
                + "DROP CONSTRAINT meeting_realtime_token_grant_digest_ck");
        execute("UPDATE meeting_realtime_token_grant SET grant_sha256 = "
                + "decode(repeat('00', 32), 'hex')");
        assertFailure(unitOfWork(source(schema)).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.FACT_INCONSISTENT);
        assertEquals(1, countGrants());
    }

    @Test
    void initialAndRenewedTokenCollisionsNeverReturnDuplicateGrant() throws Exception {
        assertFailure(unitOfWork(source(schema), fixedRandom(new byte[32]))
                .renew(request(KEY_ONE)), RenewRealtimeTokenUnitOfWork.ErrorCode.INTERNAL);
        assertEquals(0, countGrants());
        var first = success(unitOfWork(source(schema)).renew(request(KEY_ONE)));
        byte[] firstBytes = Base64.getUrlDecoder().decode(
                RealtimeTokenGrantJson.decode(first.persistedGrantJson()).sessionToken());
        try {
            assertFailure(unitOfWork(source(schema), fixedRandom(firstBytes))
                    .renew(request(KEY_TWO)), RenewRealtimeTokenUnitOfWork.ErrorCode.INTERNAL);
        } finally {
            Arrays.fill(firstBytes, (byte) 0);
        }
        assertEquals(1, countGrants());
    }

    @Test
    void corruptedFirstFactAndNonWritableStatesFailClosed() throws Exception {
        setState("AUTHORIZED", "INPUT_OPEN");
        assertFailure(unitOfWork(source(schema)).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT);
        setState("CANCELLED", "INPUT_CANCELLED");
        assertFailure(unitOfWork(source(schema)).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT);
        setState("CREATED", "INPUT_OPEN");
        execute("UPDATE meeting_create_fact SET scope = 'wrong-create-scope'");
        assertFailure(unitOfWork(source(schema)).renew(request(KEY_ONE)),
                RenewRealtimeTokenUnitOfWork.ErrorCode.FACT_INCONSISTENT);
        assertEquals(0, countGrants());
    }

    private JdbcRenewRealtimeTokenUnitOfWork unitOfWork(DataSource source) {
        return unitOfWork(source, new SecureRandom());
    }

    private JdbcRenewRealtimeTokenUnitOfWork unitOfWork(
            DataSource source, SecureRandom random) {
        return new JdbcRenewRealtimeTokenUnitOfWork(source,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60), random);
    }

    private JdbcRenewRealtimeTokenUnitOfWork unitOfWorkAt(DataSource source, Instant at) {
        return new JdbcRenewRealtimeTokenUnitOfWork(source,
                Clock.fixed(at, ZoneOffset.UTC), Duration.ofSeconds(60), new SecureRandom());
    }

    private static SecureRandom fixedRandom(byte[] value) {
        byte[] fixed = value.clone();
        return new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) {
                System.arraycopy(fixed, 0, bytes, 0, bytes.length);
            }
        };
    }

    private static RenewRealtimeTokenUnitOfWork.Request request(String key) {
        return request(context(T15CreateRecoveryFixtures.DEPLOYMENT,
                T15CreateRecoveryFixtures.SUBJECT), key);
    }

    private static RenewRealtimeTokenUnitOfWork.Request request(
            MeetingCommandContext context, String key) {
        return new RenewRealtimeTokenUnitOfWork.Request(context, MEETING, key);
    }

    private static MeetingCommandContext context(String deployment, String subject) {
        return new MeetingCommandContext(deployment,
                new VerifiedIdentity(new Subject(subject), Set.of(), Set.of(),
                        NOW.minusSeconds(5), NOW.plusSeconds(300), null));
    }

    private static RenewRealtimeTokenUnitOfWork.Success success(
            RenewRealtimeTokenUnitOfWork.Result result) {
        return assertInstanceOf(RenewRealtimeTokenUnitOfWork.Success.class, result);
    }

    private static void assertFailure(RenewRealtimeTokenUnitOfWork.Result result,
            RenewRealtimeTokenUnitOfWork.ErrorCode expected) {
        assertEquals(expected,
                assertInstanceOf(RenewRealtimeTokenUnitOfWork.Failure.class, result).code());
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
                    ? "receipt-renew-test" : null);
            statement.setString(4, MEETING);
            statement.executeUpdate();
        }
    }

    private static void updateState(Connection connection, String state, String inputState)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE meeting SET state = ?, input_state = ?,
                    finalization_receipt_id = ? WHERE meeting_id = ?
                """)) {
            statement.setString(1, state);
            statement.setString(2, inputState);
            statement.setString(3, "INPUT_ACCEPTED".equals(inputState)
                    ? "receipt-renew-test" : null);
            statement.setString(4, MEETING);
            statement.executeUpdate();
        }
    }

    private String readCreateResponseJson() throws Exception {
        try (Connection connection = source(schema).getConnection()) {
            connection.setAutoCommit(false);
            CreateMeetingFactStore.Record record = new JdbcCreateMeetingFactStore(connection)
                    .findByMeetingId(MEETING).orElseThrow();
            connection.rollback();
            return com.czur.reference.protocol.json.CreateMeetingResponseFactJson
                    .encode(record.response());
        }
    }

    private long countGrants() throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_realtime_token_grant WHERE meeting_id = ?")) {
            statement.setString(1, MEETING);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 首连接模拟 commit 前断联或 commit 实际成功但确认丢失；回查连接真实。 */
    private static DataSource commitFault(PGSimpleDataSource source, boolean afterCommit) {
        return commitFault(source, afterCommit, false);
    }

    private static DataSource commitFault(PGSimpleDataSource source, boolean afterCommit,
            boolean failRecoveryConnection) {
        return commitFault(source, afterCommit, failRecoveryConnection, () -> { });
    }

    private static DataSource commitFault(PGSimpleDataSource source, boolean afterCommit,
            boolean failRecoveryConnection, Runnable afterCommitAction) {
        AtomicInteger calls = new AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    try {
                        if ("getConnection".equals(method.getName())
                                && calls.getAndIncrement() > 0 && failRecoveryConnection) {
                            throw new SQLException("recovery connection unavailable", "08006");
                        }
                        Object value = method.invoke(source, args);
                        if (!"getConnection".equals(method.getName())
                                || calls.get() != 1) return value;
                        Connection physical = (Connection) value;
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (connectionProxy,
                                        connectionMethod, connectionArgs) -> {
                                    if ("commit".equals(connectionMethod.getName())) {
                                        if (afterCommit) {
                                            physical.commit();
                                            afterCommitAction.run();
                                        }
                                        throw new SQLException("commit acknowledgement lost", "08006");
                                    }
                                    try {
                                        return connectionMethod.invoke(physical, connectionArgs);
                                    } catch (InvocationTargetException reflected) {
                                        throw reflected.getCause();
                                    }
                                });
                    } catch (InvocationTargetException reflected) {
                        throw reflected.getCause();
                    }
                });
    }

    private static PGSimpleDataSource source(String schema) {
        return source(schema, null);
    }

    private static PGSimpleDataSource source(String schema, String applicationName) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        String url = requiredUrl();
        source.setUrl(applicationName == null ? url : url
                + (url.contains("?") ? "&" : "?") + "ApplicationName=" + applicationName);
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String name) throws Exception {
        if (!"V001__initial_schema.sql".equals(name)) {
            return "SELECT 1";
        }
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
