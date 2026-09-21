package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.OutboxStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-04-03 PostgreSQL 16 Outbox 类型领取、续租和完整栅栏总门禁。
 *
 * <p>本类的三个测试方法只能由 {@code tools/test-t14-outbox-fence.sh} 依次放在
 * 三个全新 Maven/JVM 中运行。第一阶段写入真实 PostgreSQL 并留下 worker-a 的
 * lease；第二阶段由 worker-b 在旧 lease 过期后重新领取，再验证 owner、token、
 * version、expiresAt 四元栅栏、续租句柄旋转、并发单赢家及关闭式故障分类；第三
 * 阶段从数据库核对最终持久状态。这样门禁不把“同一测试进程内两个对象”误当成
 * 跨进程恢复能力。</p>
 *
 * <p>跨 JVM 所需的旧 lease token 只写入脚本创建的 {@code 0600} 临时状态文件。
 * 测试严格拒绝符号链接、过宽权限、额外键和重复键；第二阶段使用完 token 后会
 * 覆盖状态文件，脚本退出时再删除整个 {@code 0700} 目录。任何日志、断言消息和
 * 测试名称都不得包含 token、payload、数据库密码或 JDBC URL。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_OUTBOX_FENCE_STATE_FILE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_OUTBOX_FENCE_FIXTURE_PREFIX", matches = ".+")
class T14OutboxFenceIntegrationTest {
    private static final String STATE_FILE_ENV = "T14_OUTBOX_FENCE_STATE_FILE";
    private static final String FIXTURE_PREFIX_ENV = "T14_OUTBOX_FENCE_FIXTURE_PREFIX";
    private static final String PHASE_PREPARED = "PREPARED";
    private static final String PHASE_EXERCISED = "EXERCISED";
    private static final Set<String> PREPARED_KEYS = Set.of(
            "phase", "pidA", "startA", "reclaimOwner", "reclaimToken",
            "reclaimExpiresAt", "reclaimClaimedAt", "reclaimVersion");
    private static final Set<String> EXERCISED_KEYS = Set.of(
            "phase", "pidA", "startA", "pidB", "startB");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private static final Instant BASE = Instant.parse("2026-09-21T08:00:00Z");
    private static final Duration INITIAL_LEASE = Duration.ofSeconds(10);
    private static final String REGISTERED_TYPE = "T14RegisteredEvent";
    private static final String UNKNOWN_TYPE = "T14UnknownEvent";
    private static final String WORKER_A = "t14-worker-a";
    private static final String WORKER_B = "t14-worker-b";

    /**
     * JVM-A 建立类型过滤样本，并保存一个会在 JVM-B 逻辑时间中过期的旧句柄。
     */
    @Test
    void prepareLeasedFixturesInFirstJvm() throws Exception {
        DataSource dataSource = dataSource();
        assertExactMigrations(dataSource);
        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);

        append(outbox, "00-unknown", UNKNOWN_TYPE, BASE.minusSeconds(2));
        append(outbox, "01-reclaim", REGISTERED_TYPE, BASE);

        assertTrue(outbox.claim(new OutboxStore.ClaimRequest(
                Set.of(), WORKER_A, BASE, INITIAL_LEASE, 1)).isEmpty());
        assertTrue(outbox.claim(new OutboxStore.ClaimRequest(
                Set.of("NotRegistered"), WORKER_A, BASE, INITIAL_LEASE, 1)).isEmpty());

        List<OutboxStore.ClaimedEvent> claimed = outbox.claim(new OutboxStore.ClaimRequest(
                Set.of(REGISTERED_TYPE), WORKER_A, BASE, INITIAL_LEASE, 1));
        assertEquals(1, claimed.size());
        OutboxStore.LeaseToken lease = claimed.get(0).lease();
        assertEquals(eventId("01-reclaim"), lease.eventId());
        assertEquals(WORKER_A, lease.owner());
        assertEquals(BASE, lease.claimedAt());
        assertEquals(BASE.plus(INITIAL_LEASE), lease.expiresAt());
        assertTrue(lease.leaseVersion() > 0);
        assertUnknownUntouched(dataSource);

        Path stateFile = newStateFile();
        writeExactState(stateFile, List.of(
                "phase=" + PHASE_PREPARED,
                "pidA=" + ProcessHandle.current().pid(),
                "startA=" + ManagementFactory.getRuntimeMXBean().getStartTime(),
                "reclaimOwner=" + lease.owner(),
                "reclaimToken=" + lease.token(),
                "reclaimExpiresAt=" + lease.expiresAt(),
                "reclaimClaimedAt=" + lease.claimedAt(),
                "reclaimVersion=" + lease.leaseVersion()));
    }

    /**
     * JVM-B 执行双 Worker 领取、过期回收、续租旋转、完整栅栏和并发竞争。
     */
    @Test
    void exerciseFencedLeasesInSecondJvm() throws Exception {
        Path stateFile = existingStateFile();
        Map<String, String> prepared = readExactState(stateFile, PREPARED_KEYS);
        assertEquals(PHASE_PREPARED, prepared.get("phase"));
        long pidB = ProcessHandle.current().pid();
        long startB = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertDifferentJvm(prepared.get("pidA"), prepared.get("startA"), pidB, startB);

        DataSource dataSource = dataSource();
        assertExactMigrations(dataSource);
        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
        OutboxStore.LeaseToken workerALease = new OutboxStore.LeaseToken(
                eventId("01-reclaim"),
                prepared.get("reclaimOwner"),
                prepared.get("reclaimToken"),
                Instant.parse(prepared.get("reclaimExpiresAt")),
                Instant.parse(prepared.get("reclaimClaimedAt")),
                Long.parseLong(prepared.get("reclaimVersion")));

        // worker-b 只能在旧 lease 过期以后重领；成功后旧 worker 的三个写操作均失效。
        OutboxStore.ClaimedEvent workerBClaim = outbox.claim(new OutboxStore.ClaimRequest(
                Set.of(REGISTERED_TYPE), WORKER_B, BASE.plusSeconds(11),
                INITIAL_LEASE, 1)).stream().findFirst().orElseThrow();
        assertEquals(eventId("01-reclaim"), workerBClaim.event().eventId());
        assertEquals(workerALease.leaseVersion() + 1, workerBClaim.lease().leaseVersion());
        assertTrue(!workerALease.token().equals(workerBClaim.lease().token()),
                "expired reclaim must rotate the opaque token");
        assertAllLeaseOperationsNotOwner(outbox, workerALease, BASE.plusSeconds(12));

        // 每一个栅栏字段都必须独立参与条件更新，不能只检查 owner 或 token 的子集。
        for (OutboxStore.LeaseToken altered : alteredFences(workerBClaim.lease())) {
            assertAllLeaseOperationsNotOwner(outbox, altered, BASE.plusSeconds(12));
        }

        OutboxStore.RenewResult renewed = outbox.renew(
                workerBClaim.lease(), BASE.plusSeconds(12), Duration.ofSeconds(20));
        assertEquals(OutboxStore.RenewStatus.RENEWED, renewed.status());
        assertEquals(workerBClaim.lease().leaseVersion() + 1, renewed.lease().leaseVersion());
        assertTrue(!workerBClaim.lease().token().equals(renewed.lease().token()),
                "renew must rotate the opaque token");
        assertEquals(BASE.plusSeconds(32), renewed.lease().expiresAt());
        assertAllLeaseOperationsNotOwner(outbox, workerBClaim.lease(), BASE.plusSeconds(13));

        // 两个独立 JDBC Store/连接同时使用同一有效句柄续租，数据库只能允许一个赢家。
        OutboxStore.RenewResult concurrentWinner = concurrentRenewSingleWinner(
                dataSource, renewed.lease());
        assertAllLeaseOperationsNotOwner(outbox, renewed.lease(), BASE.plusSeconds(15));
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                outbox.markPublished(concurrentWinner.lease(), BASE.plusSeconds(15)).status());

        exerciseConcurrentClaimSingleWinner(dataSource);
        exerciseClosedFailureClassifications(outbox, concurrentWinner.lease());
        assertUnknownUntouched(dataSource);

        // 覆盖状态文件以尽快移除跨 JVM token；C 阶段只需要证明是第三个 JVM。
        writeExactState(stateFile, List.of(
                "phase=" + PHASE_EXERCISED,
                "pidA=" + prepared.get("pidA"),
                "startA=" + prepared.get("startA"),
                "pidB=" + pidB,
                "startB=" + startB));
    }

    /**
     * JVM-C 只读核对第二阶段的最终持久状态，避免门禁只验证瞬时返回值。
     */
    @Test
    void verifyDurableFenceStateInThirdJvm() throws Exception {
        Map<String, String> exercised = readExactState(existingStateFile(), EXERCISED_KEYS);
        assertEquals(PHASE_EXERCISED, exercised.get("phase"));
        long pidC = ProcessHandle.current().pid();
        long startC = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertDifferentJvm(exercised.get("pidA"), exercised.get("startA"), pidC, startC);
        assertDifferentJvm(exercised.get("pidB"), exercised.get("startB"), pidC, startC);

        DataSource dataSource = dataSource();
        assertExactMigrations(dataSource);
        assertUnknownUntouched(dataSource);
        assertEquals(OutboxStore.EventStatus.PUBLISHED,
                rawFence(dataSource, eventId("01-reclaim")).status());
        assertEquals(OutboxStore.EventStatus.PUBLISHED,
                rawFence(dataSource, eventId("02-concurrent-claim")).status());
        assertEquals(OutboxStore.EventStatus.LEASED,
                rawFence(dataSource, eventId("03-expired")).status());
        RawFence dead = rawFence(dataSource, eventId("04-dead"));
        assertEquals(OutboxStore.EventStatus.DEAD, dead.status());
        assertEquals(1, dead.attemptCount());
    }

    private static OutboxStore.RenewResult concurrentRenewSingleWinner(
            DataSource dataSource, OutboxStore.LeaseToken lease) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxStore.RenewResult> first = executor.submit(() -> {
                start.await();
                return new JdbcOutboxStore(dataSource).renew(
                        lease, BASE.plusSeconds(14), Duration.ofSeconds(30));
            });
            Future<OutboxStore.RenewResult> second = executor.submit(() -> {
                start.await();
                return new JdbcOutboxStore(dataSource).renew(
                        lease, BASE.plusSeconds(14), Duration.ofSeconds(30));
            });
            start.countDown();
            List<OutboxStore.RenewResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream()
                    .filter(result -> result.status() == OutboxStore.RenewStatus.RENEWED).count());
            assertEquals(1, results.stream()
                    .filter(result -> result.status() == OutboxStore.RenewStatus.NOT_OWNER).count());
            return results.stream()
                    .filter(result -> result.status() == OutboxStore.RenewStatus.RENEWED)
                    .findFirst().orElseThrow();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void exerciseConcurrentClaimSingleWinner(DataSource dataSource) throws Exception {
        JdbcOutboxStore setup = new JdbcOutboxStore(dataSource);
        append(setup, "02-concurrent-claim", "T14ConcurrentClaim", BASE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<OutboxStore.ClaimedEvent>> first = executor.submit(() -> {
                start.await();
                return new JdbcOutboxStore(dataSource).claim(new OutboxStore.ClaimRequest(
                        Set.of("T14ConcurrentClaim"), "t14-claim-worker-a",
                        BASE.plusSeconds(20), Duration.ofSeconds(30), 1));
            });
            Future<List<OutboxStore.ClaimedEvent>> second = executor.submit(() -> {
                start.await();
                return new JdbcOutboxStore(dataSource).claim(new OutboxStore.ClaimRequest(
                        Set.of("T14ConcurrentClaim"), "t14-claim-worker-b",
                        BASE.plusSeconds(20), Duration.ofSeconds(30), 1));
            });
            start.countDown();
            List<OutboxStore.ClaimedEvent> winners = new ArrayList<>();
            winners.addAll(first.get());
            winners.addAll(second.get());
            assertEquals(1, winners.size());
            assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                    setup.markPublished(winners.get(0).lease(), BASE.plusSeconds(21)).status());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void exerciseClosedFailureClassifications(
            JdbcOutboxStore outbox, OutboxStore.LeaseToken publishedLease) {
        OutboxStore.LeaseToken missing = new OutboxStore.LeaseToken(
                eventId("missing"), "missing-worker", "missing-token",
                BASE.plusSeconds(10), BASE, 1);
        assertEquals(OutboxStore.RenewStatus.NOT_FOUND,
                outbox.renew(missing, BASE.plusSeconds(1), Duration.ofSeconds(10)).status());
        assertEquals(OutboxStore.PublishStatus.NOT_FOUND,
                outbox.markPublished(missing, BASE.plusSeconds(1)).status());
        assertEquals(OutboxStore.FailureStatus.NOT_FOUND,
                outbox.markFailed(missing, BASE.plusSeconds(1), BASE.plusSeconds(2),
                        "missing fixture", 3).status());

        append(outbox, "03-expired", "T14Expired", BASE);
        OutboxStore.LeaseToken expired = outbox.claim(new OutboxStore.ClaimRequest(
                Set.of("T14Expired"), "t14-expired-worker", BASE,
                INITIAL_LEASE, 1)).get(0).lease();
        assertEquals(OutboxStore.RenewStatus.LEASE_EXPIRED,
                outbox.renew(expired, BASE.plusSeconds(10), Duration.ofSeconds(10)).status());
        assertEquals(OutboxStore.PublishStatus.LEASE_EXPIRED,
                outbox.markPublished(expired, BASE.plusSeconds(10)).status());
        assertEquals(OutboxStore.FailureStatus.LEASE_EXPIRED,
                outbox.markFailed(expired, BASE.plusSeconds(10), BASE.plusSeconds(11),
                        "expired fixture", 3).status());

        assertEquals(OutboxStore.RenewStatus.ALREADY_PUBLISHED,
                outbox.renew(publishedLease, BASE.plusSeconds(16),
                        Duration.ofSeconds(40)).status());
        assertEquals(OutboxStore.PublishStatus.ALREADY_PUBLISHED,
                outbox.markPublished(publishedLease, BASE.plusSeconds(16)).status());
        assertEquals(OutboxStore.FailureStatus.ALREADY_PUBLISHED,
                outbox.markFailed(publishedLease, BASE.plusSeconds(16), BASE.plusSeconds(17),
                        "published fixture", 3).status());

        append(outbox, "04-dead", "T14Dead", BASE);
        OutboxStore.LeaseToken dead = outbox.claim(new OutboxStore.ClaimRequest(
                Set.of("T14Dead"), "t14-dead-worker", BASE,
                INITIAL_LEASE, 1)).get(0).lease();
        assertEquals(OutboxStore.FailureStatus.DEAD,
                outbox.markFailed(dead, BASE.plusSeconds(1), BASE.plusSeconds(2),
                        "terminal fixture", 1).status());
        assertEquals(OutboxStore.RenewStatus.ALREADY_DEAD,
                outbox.renew(dead, BASE.plusSeconds(2), Duration.ofSeconds(20)).status());
        assertEquals(OutboxStore.PublishStatus.ALREADY_DEAD,
                outbox.markPublished(dead, BASE.plusSeconds(2)).status());
        assertEquals(OutboxStore.FailureStatus.ALREADY_DEAD,
                outbox.markFailed(dead, BASE.plusSeconds(2), BASE.plusSeconds(3),
                        "dead fixture", 3).status());
    }

    private static List<OutboxStore.LeaseToken> alteredFences(OutboxStore.LeaseToken lease) {
        return List.of(
                new OutboxStore.LeaseToken(
                        lease.eventId(), lease.owner() + "-other", lease.token(),
                        lease.expiresAt(), lease.claimedAt(), lease.leaseVersion()),
                new OutboxStore.LeaseToken(
                        lease.eventId(), lease.owner(), lease.token() + "-other",
                        lease.expiresAt(), lease.claimedAt(), lease.leaseVersion()),
                new OutboxStore.LeaseToken(
                        lease.eventId(), lease.owner(), lease.token(),
                        lease.expiresAt(), lease.claimedAt(), lease.leaseVersion() + 1),
                new OutboxStore.LeaseToken(
                        lease.eventId(), lease.owner(), lease.token(),
                        lease.expiresAt().plusNanos(1_000), lease.claimedAt(),
                        lease.leaseVersion()));
    }

    private static void assertAllLeaseOperationsNotOwner(
            JdbcOutboxStore outbox, OutboxStore.LeaseToken lease, Instant operationAt) {
        assertEquals(OutboxStore.RenewStatus.NOT_OWNER,
                outbox.renew(lease, operationAt, Duration.ofSeconds(30)).status());
        assertEquals(OutboxStore.PublishStatus.NOT_OWNER,
                outbox.markPublished(lease, operationAt).status());
        assertEquals(OutboxStore.FailureStatus.NOT_OWNER,
                outbox.markFailed(lease, operationAt, operationAt.plusSeconds(1),
                        "fence mismatch", 3).status());
    }

    private static void append(
            JdbcOutboxStore outbox, String suffix, String eventType, Instant availableAt) {
        OutboxStore.AppendResult result = outbox.append(OutboxStore.OutboxEvent.pending(
                eventId(suffix), "T14Fence", fixturePrefix(), eventType,
                "{}", availableAt, availableAt));
        assertEquals(OutboxStore.AppendStatus.APPENDED, result.status());
    }

    private static void assertUnknownUntouched(DataSource dataSource) throws SQLException {
        RawFence unknown = rawFence(dataSource, eventId("00-unknown"));
        assertEquals(OutboxStore.EventStatus.PENDING, unknown.status());
        assertEquals(0, unknown.attemptCount());
        assertNull(unknown.owner());
        assertNull(unknown.leaseUntil());
        assertNull(unknown.token());
        assertEquals(0L, unknown.version());
    }

    private static RawFence rawFence(DataSource dataSource, String eventId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, attempt_count, lease_owner, lease_until,
                            lease_token, lease_version
                       FROM outbox_event
                      WHERE event_id = ?
                     """)) {
            connection.setReadOnly(true);
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "expected fixture row is missing");
                RawFence result = new RawFence(
                        OutboxStore.EventStatus.valueOf(rows.getString("status")),
                        rows.getInt("attempt_count"),
                        rows.getString("lease_owner"),
                        toInstant(rows.getTimestamp("lease_until")),
                        rows.getString("lease_token"),
                        rows.getLong("lease_version"));
                assertFalse(rows.next(), "event_id must identify exactly one row");
                return result;
            }
        }
    }

    private static void assertExactMigrations(DataSource dataSource) throws SQLException {
        String expected = "V001__initial_schema,V002__outbox_lease_fencing," +
                "V003__identity_entitlement,V004__meeting_event_envelope," +
                "V005__meeting_finalization_fact,V006__meeting_read_model," +
                "V007__meeting_manifest_revision";
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT string_agg(version, ',' ORDER BY version) FROM schema_migrations");
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expected, rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(requireEnvironment("REFERENCE_TEST_DATABASE_URL"));
        postgres.setUser(requireEnvironment("REFERENCE_TEST_DATABASE_USER"));
        postgres.setPassword(requireEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        return postgres;
    }

    private static String eventId(String suffix) {
        return fixturePrefix() + "-" + suffix;
    }

    private static String fixturePrefix() {
        String prefix = requireEnvironment(FIXTURE_PREFIX_ENV);
        if (!prefix.matches("[a-z0-9-]{12,120}")) {
            throw new IllegalArgumentException("T14 Outbox fixture prefix format is invalid");
        }
        return prefix;
    }

    private static void assertDifferentJvm(
            String previousPid, String previousStart, long currentPid, long currentStart) {
        boolean sameProcessIdentity = Long.parseLong(previousPid) == currentPid
                && Long.parseLong(previousStart) == currentStart;
        assertFalse(sameProcessIdentity, "gate phases must run in distinct Maven/JVM processes");
    }

    private static Path newStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertFalse(Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS),
                "phase A state file must not already exist");
        Files.createFile(stateFile,
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
        return stateFile;
    }

    private static Path existingStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertTrue(Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS),
                "state file must be a regular file");
        assertFalse(Files.isSymbolicLink(stateFile), "state file must not be a symlink");
        assertEquals(PRIVATE_FILE_PERMISSIONS, Files.getPosixFilePermissions(stateFile));
        return stateFile;
    }

    private static Path stateFilePath() {
        return Path.of(requireEnvironment(STATE_FILE_ENV)).toAbsolutePath().normalize();
    }

    private static void assertPrivateParent(Path stateFile) throws IOException {
        Path parent = stateFile.getParent();
        assertNotNull(parent, "state file must have a parent directory");
        assertTrue(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(parent), "state directory must not be a symlink");
        assertEquals(PRIVATE_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(parent));
    }

    private static void writeExactState(Path stateFile, List<String> lines) throws IOException {
        OpenOption[] options = {
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        };
        Files.write(stateFile, lines, StandardCharsets.UTF_8, options);
        assertEquals(PRIVATE_FILE_PERMISSIONS, Files.getPosixFilePermissions(stateFile));
    }

    private static Map<String, String> readExactState(Path stateFile, Set<String> expectedKeys)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            assertTrue(separator > 0 && separator < line.length() - 1,
                    "state line must contain one non-empty key/value pair");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            assertTrue(expectedKeys.contains(key), "state file contains an unexpected key");
            assertNull(values.putIfAbsent(key, value), "state file contains a duplicate key");
        }
        assertEquals(expectedKeys, values.keySet());
        return Map.copyOf(values);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record RawFence(
            OutboxStore.EventStatus status,
            int attemptCount,
            String owner,
            Instant leaseUntil,
            String token,
            long version) {
    }
}
