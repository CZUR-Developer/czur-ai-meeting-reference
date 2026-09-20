package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingSummary;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.IOException;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T13 查询、事件、最终收口和删除事实跨 PostgreSQL 进程重启的长期门禁。
 *
 * <p>该类故意提供两个可单独选择的测试方法。工具脚本先在第一个 Maven/JVM 中
 * 调用 {@link #prepareBeforeRestart()}，关闭运行时并重启同一 PostgreSQL 容器；
 * 然后在第二个 Maven/JVM 中调用 {@link #verifyAfterRestart()}。两个阶段只通过
 * PostgreSQL 和权限受限的状态文件交接，绝不依赖测试类静态字段或连接池内存。</p>
 *
 * <p>测试不会创建、停止或删除容器，也不会执行迁移。数据库生命周期、V001～
 * V006 迁移和精确资源清理由 {@code tools/test-t13-postgres-restart.sh} 负责；本类
 * 只使用 {@link JdbcDatabaseRuntime} 和仓库现有 JDBC 业务端口建立、读取事实。
 * 普通 PostgreSQL 集成测试只设置数据库环境变量时，本门禁保持禁用；只有脚本
 * 同时提供权限受限的 {@code T13_RESTART_STATE_FILE} 才允许执行。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T13_RESTART_STATE_FILE", matches = ".+")
class T13PostgresRestartIntegrationTest {
    private static final String STATE_FILE_ENV = "T13_RESTART_STATE_FILE";
    private static final String DEPLOYMENT = "t13-restart-deployment";
    private static final String SUBJECT = "t13-restart-subject";
    private static final String STOP_MEETING = "mtg_t13_restart_stop";
    private static final String DELETE_MEETING = "mtg_t13_restart_delete";
    private static final String EVENT_MEETING = "mtg_t13_restart_events";
    private static final String AUX_MEETING = "mtg_t13_restart_aux";
    private static final String STOP_RECEIPT = "receipt-t13-restart-stop";
    private static final String DELETE_RECEIPT = "receipt-t13-restart-delete";
    private static final String DELETE_KEY = "delete-key-t13-restart";
    private static final String DELETE_DIGEST = "delete-digest-t13-restart";
    private static final Instant BASE = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant STOP_AT = BASE.plusSeconds(100);
    private static final Instant STOP_ACCEPTED_AT = BASE.plusSeconds(101);
    private static final Instant DELETE_STOP_AT = BASE.plusSeconds(110);
    private static final Instant DELETE_ACCEPTED_AT = BASE.plusSeconds(111);
    private static final Instant DELETE_READY_AT = BASE.plusSeconds(112);
    private static final Instant DELETE_AT = BASE.plusSeconds(113);
    private static final byte[] PAGE_TOKEN_SIGNING_KEY = pageTokenSecret();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<String> STATE_KEYS = Set.of(
            "postmasterStart", "pageToken", "deletionVersion");

    /**
     * 在重启前建立全部持久事实，并保存客户端原本可持有的分页 token 与固定预期。
     *
     * <p>四场会议全部通过 owned 创建工作单元建立。停止会议走真实 Stop 与
     * input-accepted 端口；删除会议先按领域状态机推进到 READY，再走删除受理
     * 事务；事件会议写入 meeting-global 的 A1/B2/A3。状态文件不包含数据库
     * 密码、连接地址、身份凭据、事件正文或 HMAC Secret。</p>
     */
    @Test
    void prepareBeforeRestart() throws Exception {
        Path stateFile = newStateFile();
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(), "V001-V006 readiness must pass");
            DataSource dataSource = runtime.dataSource();
            Instant postmasterStart = postmasterStart(dataSource);

            createOwned(dataSource, STOP_MEETING, BASE);
            createOwned(dataSource, DELETE_MEETING, BASE.plusSeconds(10));
            createOwned(dataSource, EVENT_MEETING, BASE.plusSeconds(20));
            createOwned(dataSource, AUX_MEETING, BASE.plusSeconds(30));
            assertEquals(4L, ownedMeetingCount(dataSource));

            advanceToRunning(dataSource, STOP_MEETING, BASE);
            stopAndAccept(
                    dataSource, STOP_MEETING, STOP_AT, STOP_ACCEPTED_AT, STOP_RECEIPT,
                    "stop-key-t13-restart", "stop-digest-t13-restart");

            advanceToRunning(dataSource, DELETE_MEETING, BASE.plusSeconds(10));
            stopAndAccept(
                    dataSource,
                    DELETE_MEETING,
                    DELETE_STOP_AT,
                    DELETE_ACCEPTED_AT,
                    DELETE_RECEIPT,
                    "stop-key-t13-restart-delete",
                    "stop-digest-t13-restart-delete");
            advanceOwned(dataSource, DELETE_MEETING, MeetingEvent.READY, DELETE_READY_AT);
            DeleteResult.Accepted deletion = assertInstanceOf(
                    DeleteResult.Accepted.class,
                    new JdbcMeetingDeletionPort(
                            dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC))
                            .delete(deleteRequest()));
            assertDeletionSnapshot(deletion.meeting());
            long deletionVersion = aggregateVersion(dataSource, DELETE_MEETING);

            JdbcMeetingEventLogStore events = new JdbcMeetingEventLogStore(dataSource);
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    events.append(event(1, "stream-a", "A1")).status());
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    events.append(event(2, "stream-b", "B2")).status());
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    events.append(event(3, "stream-a", "A3")).status());

            MeetingQueryPort.ListPage firstPage = assertInstanceOf(
                    MeetingQueryPort.ListPage.class,
                    queryPort(dataSource).list(new MeetingQueryPort.ListRequest(
                            context(), null, 2)));
            assertEquals(List.of(AUX_MEETING, EVENT_MEETING), meetingIds(firstPage));
            assertNotNull(firstPage.nextPageToken(), "first page must expose a restart token");

            writeState(stateFile, new RestartState(
                    postmasterStart, firstPage.nextPageToken(), deletionVersion));
        }
    }

    /**
     * 在 PostgreSQL stop/start 后的新 Maven/JVM 和新数据库运行时中验证全部事实。
     *
     * <p>删除重放使用拒绝读取的 Clock；只要实现错误地越过幂等记录进入首次路径，
     * 测试会立即失败。重放前后还会以只读 SQL 对比聚合版本，并确认删除幂等记录
     * 与 {@code MeetingDeletionRequested} Outbox 各自仍然只有一条。</p>
     */
    @Test
    void verifyAfterRestart() throws Exception {
        RestartState expected = readState(existingStateFile());
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(), "V001-V006 readiness must survive restart");
            DataSource dataSource = runtime.dataSource();
            assertTrue(postmasterStart(dataSource).isAfter(expected.postmasterStart()),
                    "PostgreSQL postmaster start time must advance after docker restart");
            assertEquals(4L, ownedMeetingCount(dataSource));

            MeetingQueryPort.Found stop = assertInstanceOf(
                    MeetingQueryPort.Found.class,
                    queryPort(dataSource).get(new MeetingQueryPort.GetRequest(
                            context(), STOP_MEETING)));
            assertEquals(MeetingState.FINALIZING, stop.meeting().state());
            assertEquals(InputState.INPUT_ACCEPTED, stop.meeting().inputState());
            assertEquals(STOP_RECEIPT, stop.meeting().finalizationReceipt());
            // T13 的 acceptInput 只提交输入接管事实，不代表产物 Worker 已经开始。
            // GENERATING 必须由后续 T14 Worker 在实际开始生成 Manifest 时推进；
            // 重启不能擅自把当前权威 NOT_STARTED 猜成更后的处理状态。
            assertEquals(ManifestStatus.NOT_STARTED, stop.meeting().manifestState());
            assertEquals(STOP_ACCEPTED_AT, stop.meeting().updatedAt());
            MeetingFinalizationStore.Found finalization = assertInstanceOf(
                    MeetingFinalizationStore.Found.class,
                    new JdbcMeetingFinalizationStore(
                            dataSource,
                            () -> {
                                throw new AssertionError(
                                        "restart verification must not generate a receipt");
                            }).find(STOP_MEETING));
            assertEquals(FinalizationFact.accepted(STOP_RECEIPT), finalization.fact());

            MeetingQueryPort.ListPage continued = assertInstanceOf(
                    MeetingQueryPort.ListPage.class,
                    queryPort(dataSource).list(new MeetingQueryPort.ListRequest(
                            context(), expected.pageToken(), 2)));
            assertEquals(List.of(DELETE_MEETING, STOP_MEETING), meetingIds(continued));
            assertNull(continued.nextPageToken(), "old token must resume at the exact final page");

            JdbcMeetingEventLogPort eventPort = new JdbcMeetingEventLogPort(dataSource);
            MeetingEventLogPort.Page firstEvents = assertInstanceOf(
                    MeetingEventLogPort.Page.class,
                    eventPort.read(new MeetingEventLogPort.Request(
                            context(), EVENT_MEETING, 0, 2)));
            assertEquals(List.of(1L, 2L), firstEvents.page().items().stream()
                    .map(EventLog::eventSequence).toList());
            assertEquals(List.of("stream-a", "stream-b"), firstEvents.page().items().stream()
                    .map(EventLog::streamId).toList());
            assertEquals(List.of("{\"label\":\"A1\"}", "{\"label\":\"B2\"}"),
                    firstEvents.page().items().stream()
                            .map(EventLog::canonicalPayloadJson).toList());
            assertTrue(firstEvents.page().hasMore());
            MeetingEventLogPort.Page remainingEvents = assertInstanceOf(
                    MeetingEventLogPort.Page.class,
                    eventPort.read(new MeetingEventLogPort.Request(
                            context(), EVENT_MEETING, firstEvents.page().lastSequence(), 2)));
            assertEquals(List.of(3L), remainingEvents.page().items().stream()
                    .map(EventLog::eventSequence).toList());
            assertEquals(List.of("stream-a"), remainingEvents.page().items().stream()
                    .map(EventLog::streamId).toList());
            assertEquals(List.of("{\"label\":\"A3\"}"),
                    remainingEvents.page().items().stream()
                            .map(EventLog::canonicalPayloadJson).toList());
            assertFalse(remainingEvents.page().hasMore());
            assertEquals(3L, remainingEvents.page().lastSequence());

            long versionBeforeReplay = aggregateVersion(dataSource, DELETE_MEETING);
            assertEquals(expected.deletionVersion(), versionBeforeReplay);
            DeleteResult.Replayed replay = assertInstanceOf(
                    DeleteResult.Replayed.class,
                    new JdbcMeetingDeletionPort(dataSource, new RejectingClock())
                            .delete(deleteRequest()));
            assertDeletionSnapshot(replay.meeting());
            assertEquals(versionBeforeReplay, aggregateVersion(dataSource, DELETE_MEETING),
                    "stable replay must not advance aggregate_version");
            assertEquals(1L, deletionIdempotencyCount(dataSource));
            assertEquals(1L, deletionOutboxCount(dataSource));
        }
    }

    private static JdbcDatabaseRuntime startRuntime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                requireEnvironment("REFERENCE_TEST_DATABASE_URL"),
                requireEnvironment("REFERENCE_TEST_DATABASE_USER"),
                requireEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                4,
                1));
    }

    /** 建立稳定的首次 reserve 事实，再由 owned 创建工作单元原子 bind 并建会。 */
    private static void createOwned(
            DataSource dataSource, String meetingId, Instant createdAt) throws SQLException {
        Instant expiresAt = BASE.plusSeconds(86_400);
        MeetingGrant grant = new MeetingGrant(
                "grant-" + meetingId,
                DEPLOYMENT,
                SUBJECT,
                "profile-t13-restart",
                1,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                createdAt.minusSeconds(30),
                expiresAt.plusSeconds(60));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + meetingId,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                createdAt.minusSeconds(10),
                expiresAt);
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-" + meetingId,
                "reserve-digest-" + meetingId,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcUsageReservationStore.insertReservation(connection, reservation);
                assertTrue(JdbcUsageReservationStore.insertOperation(
                        connection, reserve, createdAt.minusSeconds(10)));
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }

        OwnedMeetingCreationUnitOfWork.Success created = assertInstanceOf(
                OwnedMeetingCreationUnitOfWork.Success.class,
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(createdAt, ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                "session-" + meetingId,
                                "create-key-" + meetingId,
                                "create-digest-" + meetingId,
                                reserve)));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, created.code());
    }

    /** 严格按领域状态机的 AUTHORIZE、START、START 顺序推进到 RUNNING。 */
    private static void advanceToRunning(
            DataSource dataSource, String meetingId, Instant createdAt) throws SQLException {
        advanceOwned(dataSource, meetingId, MeetingEvent.AUTHORIZE, createdAt.plusSeconds(1));
        advanceOwned(dataSource, meetingId, MeetingEvent.START, createdAt.plusSeconds(2));
        advanceOwned(dataSource, meetingId, MeetingEvent.START, createdAt.plusSeconds(3));
    }

    /**
     * 使用 Repository 的行锁、领域转换和聚合版本 CAS 执行一次 owned 状态推进。
     * 该辅助方法不直接 UPDATE 状态列，也不绕过 {@link MeetingAggregate}。
     */
    private static void advanceOwned(
            DataSource dataSource,
            String meetingId,
            MeetingEvent event,
            Instant eventAt) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting before =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                                .orElseThrow();
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(before.snapshot());
                aggregate.apply(event, eventAt);
                JdbcMeetingRepository.updateOwnedExisting(
                        connection, before, aggregate.snapshot());
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /** 通过生产 Stop 工作单元和最终收口端口建立可跨重启查询的 receipt 事实。 */
    private static void stopAndAccept(
            DataSource dataSource,
            String meetingId,
            Instant stopAt,
            Instant acceptedAt,
            String receipt,
            String stopKey,
            String stopDigest) {
        OwnedMeetingStopUnitOfWork.Success stopped = assertInstanceOf(
                OwnedMeetingStopUnitOfWork.Success.class,
                new JdbcOwnedMeetingStopUnitOfWork(
                        dataSource, Clock.fixed(stopAt, ZoneOffset.UTC))
                        .stop(new OwnedMeetingStopUnitOfWork.StopRequest(
                                context(), meetingId, stopKey, stopDigest)));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, stopped.code());
        assertEquals(MeetingState.STOPPING, stopped.result().meeting().state());

        MeetingFinalizationStore.Applied accepted = assertInstanceOf(
                MeetingFinalizationStore.Applied.class,
                new JdbcMeetingFinalizationStore(dataSource, () -> receipt)
                        .acceptInput(meetingId, acceptedAt));
        assertEquals(FinalizationFact.accepted(receipt), accepted.fact());
    }

    private static JdbcMeetingQueryPort queryPort(DataSource dataSource) {
        return new JdbcMeetingQueryPort(
                dataSource,
                Arrays.copyOf(PAGE_TOKEN_SIGNING_KEY, PAGE_TOKEN_SIGNING_KEY.length));
    }

    private static List<String> meetingIds(MeetingQueryPort.ListPage page) {
        return page.items().stream().map(MeetingSummary::meetingId).toList();
    }

    private static DeleteRequest deleteRequest() {
        return new DeleteRequest(context(), DELETE_MEETING, DELETE_KEY, DELETE_DIGEST);
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(
                DEPLOYMENT,
                new VerifiedIdentity(
                        new Subject(SUBJECT),
                        Set.of("ROLE_USER"),
                        Set.of(
                                "meeting:read",
                                MeetingPermissions.STOP,
                                MeetingPermissions.DELETE),
                        BASE.minusSeconds(60),
                        BASE.plusSeconds(86_400),
                        "credential-t13-restart"));
    }

    private static EventLog event(long sequence, String streamId, String label) {
        return new EventLog(
                "0.1",
                EVENT_MEETING,
                streamId,
                "event-t13-restart-" + sequence,
                sequence,
                BASE.plusSeconds(40 + sequence),
                "transcriptUpsert",
                "{\"label\":\"" + label + "\"}");
    }

    private static void assertDeletionSnapshot(MeetingStatus status) {
        assertEquals(DELETE_MEETING, status.meetingId());
        assertEquals(MeetingState.DELETION_PENDING, status.state());
        assertEquals(InputState.INPUT_ACCEPTED, status.inputState());
        assertEquals(DELETE_RECEIPT, status.finalizationReceipt());
        assertEquals(ManifestStatus.DELETION_PENDING, status.manifestState());
        assertEquals(DELETE_AT, status.updatedAt());
    }

    private static Instant postmasterStart(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pg_postmaster_start_time()");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            Timestamp value = rows.getTimestamp(1);
            assertNotNull(value);
            return value.toInstant();
        }
    }

    private static long ownedMeetingCount(DataSource dataSource) throws SQLException {
        return queryLong(dataSource, """
                SELECT count(*) FROM meeting
                 WHERE deployment_scope=? AND subject_scope=?
                """, DEPLOYMENT, SUBJECT);
    }

    private static long aggregateVersion(DataSource dataSource, String meetingId)
            throws SQLException {
        return queryLong(dataSource,
                "SELECT aggregate_version FROM meeting WHERE meeting_id=?", meetingId);
    }

    private static long deletionIdempotencyCount(DataSource dataSource) throws SQLException {
        return queryLong(dataSource, """
                SELECT count(*) FROM idempotency_record
                 WHERE subject_scope=? AND idempotency_key=?
                """,
                IdempotencyScopeCodec.meetingDelete(DEPLOYMENT, SUBJECT),
                DELETE_KEY);
    }

    private static long deletionOutboxCount(DataSource dataSource) throws SQLException {
        return queryLong(dataSource, """
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id=? AND event_type='MeetingDeletionRequested'
                """, DELETE_MEETING);
    }

    /** 只执行带文本参数的只读标量查询，避免把动态值拼进 SQL。 */
    private static long queryLong(
            DataSource dataSource, String sql, String... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setReadOnly(true);
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static Path newStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertFalse(Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS),
                "prepare state file must not already exist");
        return stateFile;
    }

    private static Path existingStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertTrue(Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS),
                "restart state file must be a regular file");
        assertFalse(Files.isSymbolicLink(stateFile), "restart state file must not be a symlink");
        assertEquals(PRIVATE_FILE_PERMISSIONS, Files.getPosixFilePermissions(stateFile));
        return stateFile;
    }

    private static Path stateFilePath() {
        return Path.of(requireEnvironment(STATE_FILE_ENV)).toAbsolutePath().normalize();
    }

    private static void assertPrivateParent(Path stateFile) throws IOException {
        Path parent = stateFile.getParent();
        assertNotNull(parent, "restart state file must have a parent directory");
        assertTrue(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(parent), "restart state directory must not be a symlink");
        assertEquals(PRIVATE_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(parent));
    }

    private static void writeState(Path stateFile, RestartState state) throws IOException {
        Files.createFile(
                stateFile,
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
        List<String> lines = List.of(
                "postmasterStart=" + state.postmasterStart(),
                "pageToken=" + singleLine("pageToken", state.pageToken()),
                "deletionVersion=" + state.deletionVersion());
        OpenOption[] options = {
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        };
        Files.write(stateFile, lines, StandardCharsets.UTF_8, options);
        assertEquals(PRIVATE_FILE_PERMISSIONS, Files.getPosixFilePermissions(stateFile));
    }

    private static RestartState readState(Path stateFile) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            assertTrue(separator > 0, "restart state line must use key=value");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            assertFalse(value.isBlank(), "restart state value must not be blank");
            assertNull(values.put(key, value), "restart state key must be unique");
        }
        assertEquals(STATE_KEYS, values.keySet(), "restart state keys must be exact");
        return new RestartState(
                Instant.parse(values.get("postmasterStart")),
                singleLine("pageToken", values.get("pageToken")),
                Long.parseLong(values.get("deletionVersion")));
    }

    private static String singleLine(String name, String value) {
        assertNotNull(value, name + " must not be null");
        assertFalse(value.isBlank(), name + " must not be blank");
        assertFalse(value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0,
                name + " must stay on one line");
        return value;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the restart gate");
        }
        return value;
    }

    private static byte[] pageTokenSecret() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x54);
        return secret;
    }

    private record RestartState(
            Instant postmasterStart, String pageToken, long deletionVersion) {
        private RestartState {
            assertNotNull(postmasterStart);
            singleLine("pageToken", pageToken);
            assertTrue(deletionVersion >= 0, "deletionVersion must not be negative");
        }
    }

    /** 删除稳定重放不得读取的新时钟；调用即表示实现越过了幂等边界。 */
    private static final class RejectingClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new AssertionError("deletion replay must not read Clock");
        }
    }
}
