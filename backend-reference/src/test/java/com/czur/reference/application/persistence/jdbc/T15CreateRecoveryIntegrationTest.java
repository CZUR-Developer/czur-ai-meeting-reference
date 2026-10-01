package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T15-12-06 门禁：独占 PostgreSQL 16 上的双 JVM 竞争、commit 结果不明与真重启。
 *
 * <p>仅在专用脚本设置随机 loopback 数据库和 T15_CREATE_GATE_RUN_ID 时执行。
 * 竞争测试使用数据库 session advisory lock 使两个子 JVM 同时阻塞在生产工作单元
 * 的同一锁上；父进程从 pg_locks 观察到两个真实 waiter 后才释放锁。重启方法
 * 必须由脚本在 PostgreSQL stop/start 前后的不同 Maven/JVM 中分别调用。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T15_CREATE_GATE_RUN_ID", matches = ".+")
class T15CreateRecoveryIntegrationTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);
    private static final String RESTART_SUFFIX = "restart";
    private static final String RESTART_KEY = "create-key-t15-restart";

    @Test
    void twoJvmsSameDigestReplayWinnerAndDifferentDigestConflicts() throws Exception {
        DataSource source = T15CreateRecoveryFixtures.dataSource();
        T15CreateRecoveryFixtures.insertReserve(source, "same_a");
        T15CreateRecoveryFixtures.insertReserve(source, "same_b");
        List<String> same = compete(source, "create-key-t15-same", "same_session",
                new Candidate("same_a", "same title", "candidate-token-a"),
                new Candidate("same_b", "same title", "candidate-token-b"));
        assertEquals(1, same.stream().filter(item -> item.contains("RESULT=CREATED")).count(), same.toString());
        assertEquals(1, same.stream().filter(item -> item.contains("RESULT=REPLAYED")).count(), same.toString());
        String winner = same.stream().filter(item -> item.contains("RESULT=CREATED"))
                .findFirst().orElseThrow().split("MEETING=")[1].split(" ")[0];
        assertTrue(same.stream().allMatch(item -> item.contains("MEETING=" + winner)), same.toString());
        assertEquals(new Counts(1, 1, 1, 1),
                counts(source, "create-key-t15-same", "same_a", "same_b"));
        assertEquals(winner, responseMeetingId(source, "create-key-t15-same"));
        String responseHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(responseBytes(source, "create-key-t15-same")));
        assertTrue(same.stream().allMatch(item -> item.contains("RESPONSE_SHA256=" + responseHash)),
                "both JVMs must return the winner's original canonical response");
        String winnerToken = winner.equals(T15CreateRecoveryFixtures.meetingId("same_a"))
                ? "candidate-token-a" : "candidate-token-b";
        assertEquals(winnerToken, CreateMeetingResponseFactJson.decode(
                responseBytes(source, "create-key-t15-same")).realtime().sessionToken());
        String loser = winner.equals(T15CreateRecoveryFixtures.meetingId("same_a"))
                ? "same_b" : "same_a";
        String winnerSuffix = loser.equals("same_a") ? "same_b" : "same_a";
        assertEquals(CandidateFacts.bound(T15CreateRecoveryFixtures.meetingId(winnerSuffix)),
                candidateFacts(source, winnerSuffix));
        assertEquals(CandidateFacts.unbound(), candidateFacts(source, loser));

        T15CreateRecoveryFixtures.insertReserve(source, "diff_a");
        T15CreateRecoveryFixtures.insertReserve(source, "diff_b");
        List<String> different = compete(source, "create-key-t15-different", "diff_session",
                new Candidate("diff_a", "title A", "candidate-token-a"),
                new Candidate("diff_b", "title B", "candidate-token-b"));
        assertEquals(1, different.stream().filter(item -> item.contains("RESULT=CREATED")).count(),
                different.toString());
        assertEquals(1, different.stream().filter(item ->
                item.contains("RESULT=IDEMPOTENCY_CONFLICT")).count(), different.toString());
        assertEquals(new Counts(1, 1, 1, 1),
                counts(source, "create-key-t15-different", "diff_a", "diff_b"));
        String differentWinner = different.stream().filter(item -> item.contains("RESULT=CREATED"))
                .findFirst().orElseThrow().split("MEETING=")[1].split(" ")[0];
        String differentWinnerSuffix = differentWinner.equals(
                T15CreateRecoveryFixtures.meetingId("diff_a")) ? "diff_a" : "diff_b";
        String differentLoserSuffix = differentWinnerSuffix.equals("diff_a") ? "diff_b" : "diff_a";
        assertEquals(CandidateFacts.bound(differentWinner),
                candidateFacts(source, differentWinnerSuffix));
        assertEquals(CandidateFacts.unbound(), candidateFacts(source, differentLoserSuffix));
    }

    @Test
    void commitBeforeAndAfterConfirmationLossRemainUnknownAndRecoverSafely() throws Exception {
        DataSource source = T15CreateRecoveryFixtures.dataSource();
        for (CommitFault fault : CommitFault.values()) {
            String suffix = fault == CommitFault.BEFORE ? "unknown_before" : "unknown_after";
            String key = "create-key-t15-" + suffix;
            T15CreateRecoveryFixtures.insertReserve(source, suffix);
            var original = T15CreateRecoveryFixtures.request(suffix, key, null, "original-token");
            var uncertain = T15CreateRecoveryFixtures.unitOfWork(
                    injectCommitFailure(source, fault)).create(original);
            assertTrue(uncertain instanceof CompleteMeetingCreationUnitOfWork.Failure);
            assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                    ((CompleteMeetingCreationUnitOfWork.Failure) uncertain).code());

            // 断开原连接后，只从全新的主库连接读取 V013，不能从失败连接猜测结果。
            assertEquals(fault == CommitFault.BEFORE
                            ? new Counts(0, 0, 0, 0) : new Counts(1, 1, 1, 1),
                    counts(source, key, suffix));
            assertEquals(fault == CommitFault.BEFORE
                            ? CandidateFacts.unbound()
                            : CandidateFacts.bound(T15CreateRecoveryFixtures.meetingId(suffix)),
                    candidateFacts(source, suffix));
            var recovered = T15CreateRecoveryFixtures.unitOfWork(source).create(
                    T15CreateRecoveryFixtures.request(
                            suffix, key, null, "fresh-candidate-token"));
            assertTrue(recovered instanceof CompleteMeetingCreationUnitOfWork.Success);
            var success = (CompleteMeetingCreationUnitOfWork.Success) recovered;
            assertEquals(fault == CommitFault.BEFORE
                            ? CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED
                            : CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED,
                    success.code());
            assertEquals(fault == CommitFault.BEFORE
                            ? "fresh-candidate-token" : "original-token",
                    success.response().realtime().sessionToken());
            assertEquals(new Counts(1, 1, 1, 1), counts(source, key, suffix));
            var replay = T15CreateRecoveryFixtures.unitOfWork(source).create(original);
            assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED,
                    ((CompleteMeetingCreationUnitOfWork.Success) replay).code());
            assertEquals(new Counts(1, 1, 1, 1), counts(source, key, suffix));
        }
    }

    /** 第一 JVM 保存严格 UTF-8 原字节和四类事实计数，不保存数据库凭据。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "T15_CREATE_RESTART_STATE_FILE", matches = ".+")
    void prepareBeforeRestart() throws Exception {
        DataSource source = T15CreateRecoveryFixtures.dataSource();
        T15CreateRecoveryFixtures.insertReserve(source, RESTART_SUFFIX);
        var result = T15CreateRecoveryFixtures.unitOfWork(source).create(
                T15CreateRecoveryFixtures.request(
                        RESTART_SUFFIX, RESTART_KEY, "重启后仍需保留原文", "original-restart-token"));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED,
                ((CompleteMeetingCreationUnitOfWork.Success) result).code());
        byte[] response = responseBytes(source, RESTART_KEY);
        assertArrayEquals(response, CreateMeetingResponseFactJson.encode(
                ((CompleteMeetingCreationUnitOfWork.Success) result).response())
                .getBytes(StandardCharsets.UTF_8));
        Path state = Path.of(System.getenv("T15_CREATE_RESTART_STATE_FILE"));
        Files.createFile(state, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(state, StandardOpenOption.WRITE))) {
            out.writeLong(postmasterStartEpochMicros(source));
            out.writeInt(response.length);
            out.write(response);
            Counts facts = counts(source, RESTART_KEY, RESTART_SUFFIX);
            out.writeLong(facts.meeting());
            out.writeLong(facts.fact());
            out.writeLong(facts.bound());
            out.writeLong(facts.outbox());
        }
        assertEquals(new Counts(1, 1, 1, 1), counts(source, RESTART_KEY, RESTART_SUFFIX));
    }

    /** 第二 JVM 重新连接同一持久库，逐字节核对存储、重放编码和事实计数。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "T15_CREATE_RESTART_STATE_FILE", matches = ".+")
    void verifyAfterRestart() throws Exception {
        DataSource source = T15CreateRecoveryFixtures.dataSource();
        byte[] expected;
        long beforeStart;
        Counts beforeCounts;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(
                Path.of(System.getenv("T15_CREATE_RESTART_STATE_FILE"))))) {
            beforeStart = in.readLong();
            int length = in.readInt();
            assertTrue(length > 0 && length <= 1_048_576);
            expected = in.readNBytes(length);
            assertEquals(length, expected.length);
            beforeCounts = new Counts(in.readLong(), in.readLong(), in.readLong(), in.readLong());
            assertEquals(-1, in.read());
        }
        assertTrue(postmasterStartEpochMicros(source) > beforeStart,
                "PostgreSQL postmaster must truly restart");
        assertArrayEquals(expected, responseBytes(source, RESTART_KEY));
        assertEquals(beforeCounts, counts(source, RESTART_KEY, RESTART_SUFFIX));
        var replay = T15CreateRecoveryFixtures.unitOfWork(source).create(
                T15CreateRecoveryFixtures.request(RESTART_SUFFIX, RESTART_KEY,
                        "重启后仍需保留原文", "new-token-must-not-win"));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED,
                ((CompleteMeetingCreationUnitOfWork.Success) replay).code());
        byte[] encoded = CreateMeetingResponseFactJson.encode(
                ((CompleteMeetingCreationUnitOfWork.Success) replay).response())
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, encoded);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                MessageDigest.getInstance("SHA-256").digest(encoded));
        assertArrayEquals(expected, responseBytes(source, RESTART_KEY));
        assertEquals(beforeCounts, counts(source, RESTART_KEY, RESTART_SUFFIX));
    }

    private static List<String> compete(DataSource source, String key, String session,
                                        Candidate first, Candidate second) throws Exception {
        String scope = IdempotencyScopeCodec.meetingCreateV2(
                T15CreateRecoveryFixtures.DEPLOYMENT, T15CreateRecoveryFixtures.SUBJECT);
        String lockName = scope.length() + ":" + scope + key.length() + ":" + key;
        Process a = null;
        Process b = null;
        try (Connection lock = source.getConnection();
             PreparedStatement hold = lock.prepareStatement(
                     "SELECT pg_advisory_lock(hashtextextended(?, 0))");
             PreparedStatement release = lock.prepareStatement(
                     "SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            hold.setString(1, lockName);
            hold.execute();
            try {
                a = startProbe(first, session, key);
                b = startProbe(second, session, key);
                long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
                while (advisoryWaiters(source) < 2 && System.nanoTime() < deadline) {
                    if (!a.isAlive() || !b.isAlive()) {
                        throw new AssertionError("probe exited before lock overlap");
                    }
                    Thread.sleep(25);
                }
                assertTrue(advisoryWaiters(source) >= 2,
                        "both real JVMs must overlap on the database advisory lock");
            } finally {
                release.setString(1, lockName);
                release.execute();
            }
            return List.of(awaitProbe(a), awaitProbe(b));
        } finally {
            destroyIfAlive(a);
            destroyIfAlive(b);
        }
    }

    private static Process startProbe(Candidate candidate, String session, String key)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath,
                T15CreateRecoveryProcessProbe.class.getName(), candidate.suffix(), session,
                key, candidate.title(), candidate.token())
                .redirectErrorStream(true).start();
    }

    private static String awaitProbe(Process process) throws Exception {
        assertTrue(process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                "child JVM timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertFalse(output.contains("PROBE_FAILED"), output);
        assertEquals(1, output.lines().filter(line -> line.startsWith("RESULT=")).count(), output);
        return output.lines().filter(line -> line.startsWith("RESULT=")).findFirst().orElseThrow();
    }

    private static void destroyIfAlive(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static long advisoryWaiters(DataSource source) throws Exception {
        return count(source, "SELECT count(*) FROM pg_locks WHERE locktype='advisory' "
                + "AND NOT granted AND database=(SELECT oid FROM pg_database "
                + "WHERE datname=current_database())");
    }

    /** 分别按候选 ID 读取四个权威表；不能 JOIN V013，否则孤儿事实会被过滤掉。 */
    private static Counts counts(DataSource source, String key, String... suffixes) throws Exception {
        String scope = IdempotencyScopeCodec.meetingCreateV2(
                T15CreateRecoveryFixtures.DEPLOYMENT, T15CreateRecoveryFixtures.SUBJECT);
        long fact;
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_create_fact WHERE scope=? AND idempotency_key=?")) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                fact = rows.getLong(1);
            }
        }
        long meetings = 0;
        long bound = 0;
        long outbox = 0;
        for (String suffix : suffixes) {
            CandidateFacts candidate = candidateFacts(source, suffix);
            meetings += candidate.meeting();
            bound += candidate.boundMeetingId() == null ? 0 : 1;
            outbox += candidate.outbox();
        }
        return new Counts(meetings, fact, bound, outbox);
    }

    /** 逐候选核验未关联 V013 的 Meeting、Reservation 和 MeetingCreated Outbox。 */
    private static CandidateFacts candidateFacts(DataSource source, String suffix) throws Exception {
        String meetingId = T15CreateRecoveryFixtures.meetingId(suffix);
        try (Connection connection = source.getConnection();
             PreparedStatement direct = connection.prepareStatement("""
                     SELECT (SELECT count(*) FROM meeting WHERE meeting_id=?),
                            (SELECT count(*) FROM outbox_event
                              WHERE aggregate_type='Meeting' AND aggregate_id=?
                                AND event_type='MeetingCreated')
                     """);
             PreparedStatement reservation = connection.prepareStatement("""
                     SELECT status, version, bound_meeting_id, bound_at
                       FROM usage_reservation WHERE reservation_id=?
                     """)) {
            direct.setString(1, meetingId);
            direct.setString(2, meetingId);
            reservation.setString(1, "reservation-" + suffix);
            long meeting;
            long outbox;
            try (ResultSet rows = direct.executeQuery()) {
                assertTrue(rows.next());
                meeting = rows.getLong(1);
                outbox = rows.getLong(2);
            }
            try (ResultSet rows = reservation.executeQuery()) {
                assertTrue(rows.next(), "initial Reservation must remain present");
                CandidateFacts facts = new CandidateFacts(meeting, outbox,
                        rows.getString("status"), rows.getLong("version"),
                        rows.getString("bound_meeting_id"),
                        rows.getTimestamp("bound_at") != null);
                assertFalse(rows.next());
                return facts;
            }
        }
    }

    private static String responseMeetingId(DataSource source, String key) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meeting_id FROM meeting_create_fact WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static byte[] responseBytes(DataSource source, String key) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT response_json, response_sha256 FROM meeting_create_fact WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                byte[] bytes = rows.getString(1).getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(rows.getBytes(2), MessageDigest.getInstance("SHA-256").digest(bytes));
                assertFalse(rows.next());
                return bytes;
            }
        }
    }

    private static long postmasterStartEpochMicros(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT (extract(epoch FROM pg_postmaster_start_time())*1000000)::bigint")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static long count(DataSource source, String sql) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** 只在连接的 commit() 边界注入确认丢失，真实 PostgreSQL 提交语义不模拟。 */
    private static DataSource injectCommitFailure(DataSource delegate, CommitFault fault) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(delegate, method, args);
                    }
                    Connection connection = (Connection) invoke(delegate, method, args);
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, connectionMethod, connectionArgs) -> {
                                if (connectionMethod.getName().equals("commit")) {
                                    if (fault == CommitFault.AFTER) {
                                        invoke(connection, connectionMethod, connectionArgs);
                                    }
                                    throw new SQLException("injected confirmation loss", "08006");
                                }
                                return invoke(connection, connectionMethod, connectionArgs);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private enum CommitFault { BEFORE, AFTER }

    private record Candidate(String suffix, String title, String token) {
    }

    private record Counts(long meeting, long fact, long bound, long outbox) {
    }

    private record CandidateFacts(long meeting, long outbox, String status, long version,
                                  String boundMeetingId, boolean boundAtPresent) {
        static CandidateFacts unbound() {
            return new CandidateFacts(0, 0, "RESERVED", 0, null, false);
        }

        static CandidateFacts bound(String meetingId) {
            return new CandidateFacts(1, 1, "RESERVED", 1, meetingId, true);
        }
    }
}
