package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestPublicationUnitOfWork;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
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
 * P2-T14-03-05 Manifest 发布、结果查询和删除跨两次 PostgreSQL 真重启的总门禁。
 *
 * <p>三个测试方法由 {@code tools/test-t14-manifest-restart.sh} 分别放在三个全新
 * Maven/JVM 中运行。第一阶段使用正式 owned 建会、领域状态机、停止/输入接管、
 * {@link JdbcManifestPublicationUnitOfWork} 和 {@link JdbcMeetingManifestQueryPort}
 * 建立 READY revision 1；第二阶段在第一次数据库重启后重放发布，并通过
 * {@link JdbcMeetingDeletionPort} 原子产生 DELETION_PENDING revision 2；第三阶段
 * 在第二次数据库重启后重放删除并核对最终持久事实。</p>
 *
 * <p>三个 JVM 只经 PostgreSQL 与权限受限状态文件交接。测试不会创建、停止、
 * 启动或删除 Docker 资源，也不会执行迁移。业务状态绝不通过 SQL UPDATE 伪造；
 * SQL 仅用于读取 postmaster 启动时间、revision 数量、latest 状态、幂等记录和
 * Outbox 数量。状态文件只保存无敏感稳定预期，严格拒绝符号链接、额外键、重复
 * 键、空值以及过宽 POSIX 权限。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_MANIFEST_RESTART_STATE_FILE", matches = ".+")
class T14ManifestRestartIntegrationTest {
    private static final String STATE_FILE_ENV = "T14_MANIFEST_RESTART_STATE_FILE";
    private static final String PHASE_PUBLISHED = "PUBLISHED_REVISION_1";
    private static final String PHASE_DELETION_ACCEPTED = "DELETION_ACCEPTED_REVISION_2";
    private static final Set<String> PUBLISHED_STATE_KEYS = Set.of(
            "phase", "postmasterStartA", "publishedRevision");
    private static final Set<String> DELETION_STATE_KEYS = Set.of(
            "phase", "postmasterStartA", "postmasterStartB",
            "publishedRevision", "deletionRevision");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private static final String DEPLOYMENT = "t14-manifest-restart-deployment";
    private static final String SUBJECT = "t14-manifest-restart-subject";
    private static final String MEETING_ID = "mtg_t14_manifest_restart";
    private static final String SESSION_ID = "session-t14-manifest-restart";
    private static final String PROFILE_ID = "meeting-minutes/v1";
    private static final int PROFILE_REVISION = 3;
    private static final String STOP_RECEIPT = "receipt-t14-manifest-restart-stop";
    private static final String DELETE_KEY = "delete-key-t14-manifest-restart";
    private static final String DELETE_DIGEST = "delete-digest-t14-manifest-restart";
    private static final Instant BASE = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant STOP_AT = BASE.plusSeconds(300);
    private static final Instant INPUT_ACCEPTED_AT = BASE.plusSeconds(301);
    private static final Instant BEGIN_AT = BASE.plusSeconds(302);
    private static final Instant PUBLISH_AT = BASE.plusSeconds(303);
    private static final Instant DELETE_AT = BASE.plusSeconds(304);
    private static final byte[] PAGE_TOKEN_SIGNING_KEY = pageTokenSecret();

    /**
     * 重启前通过正式端口建立并发布 revision 1，再保存第一阶段稳定预期。
     *
     * <p>发布前显式证明会议为 FINALIZING、输入已接管、Manifest 尚未开始且
     * revision 数为零。begin 和 publish 均调用正式工作单元；发布后再调用正式
     * 结果查询，不能以事实表裸写或只读 SQL 代替业务闭环。</p>
     */
    @Test
    void publishBeforeFirstRestart() throws Exception {
        Path stateFile = newStateFile();
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(), "V001-V007 readiness must pass");
            DataSource dataSource = runtime.dataSource();
            Instant postmasterStartA = postmasterStart(dataSource);

            createOwned(dataSource);
            advanceToRunning(dataSource);
            stopAndAccept(dataSource);

            MeetingStatus beforeBegin = ownedMeeting(dataSource);
            assertEquals(MeetingState.FINALIZING, beforeBegin.state());
            assertEquals(InputState.INPUT_ACCEPTED, beforeBegin.inputState());
            assertEquals(STOP_RECEIPT, beforeBegin.finalizationReceipt());
            assertEquals(ManifestStatus.NOT_STARTED, beforeBegin.manifestState());
            assertEquals(0L, manifestRevisionCount(dataSource));

            ManifestPublicationUnitOfWork.ExpectedMeetingFacts expected = expectedFacts();
            ManifestPublicationUnitOfWork.BeginApplied begun = assertInstanceOf(
                    ManifestPublicationUnitOfWork.BeginApplied.class,
                    new JdbcManifestPublicationUnitOfWork(
                            dataSource, Clock.fixed(BEGIN_AT, ZoneOffset.UTC))
                            .begin(new ManifestPublicationUnitOfWork.BeginRequest(
                                    MEETING_ID, expected)));
            assertEquals(0, begun.manifestRevision());
            assertEquals(ManifestStatus.GENERATING, ownedMeeting(dataSource).manifestState());
            assertEquals(0L, manifestRevisionCount(dataSource));

            MeetingManifest manifest = publishedManifest();
            ManifestPublicationUnitOfWork.PublishApplied published = assertInstanceOf(
                    ManifestPublicationUnitOfWork.PublishApplied.class,
                    new JdbcManifestPublicationUnitOfWork(
                            dataSource, Clock.fixed(PUBLISH_AT, ZoneOffset.UTC))
                            .publish(new ManifestPublicationUnitOfWork.PublishRequest(
                                    MEETING_ID, expected, manifest)));
            assertEquals(1, published.manifestRevision());
            assertEquals(MeetingState.READY, published.state());
            assertEquals(ManifestStatus.READY, published.manifestState());
            assertEquals(1L, manifestRevisionCount(dataSource));

            MeetingManifestPort.Found found = assertInstanceOf(
                    MeetingManifestPort.Found.class,
                    manifestQuery(dataSource).get(resultRequest()));
            assertEquals(manifest, found.manifest());

            writePublishedState(stateFile, new PublishedState(
                    postmasterStartA, published.manifestRevision()));
        }
    }

    /**
     * 第一次真重启后的新 JVM 重放发布、受理删除并保存第二阶段稳定预期。
     *
     * <p>发布重放注入拒绝读取的 Clock：若实现错误地越过相同 canonical 正文的
     * 幂等边界，测试会立即失败。删除必须通过正式端口产生 revision 2；只读 SQL
     * 仅证明 latest 的 revision/status，公开结果则必须由正式查询立即撤权。</p>
     */
    @Test
    void replayPublishAndDeleteAfterFirstRestart() throws Exception {
        Path stateFile = existingStateFile();
        PublishedState previous = readPublishedState(stateFile);
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "V001-V007 readiness must survive first restart");
            DataSource dataSource = runtime.dataSource();
            Instant postmasterStartB = postmasterStart(dataSource);
            assertTrue(postmasterStartB.isAfter(previous.postmasterStartA()),
                    "postmaster start time B must be later than A");

            MeetingManifest expectedManifest = publishedManifest();
            MeetingManifestPort.Found found = assertInstanceOf(
                    MeetingManifestPort.Found.class,
                    manifestQuery(dataSource).get(resultRequest()));
            assertEquals(expectedManifest, found.manifest());
            assertEquals(previous.publishedRevision(), found.manifest().manifestRevision());

            ManifestPublicationUnitOfWork.PublishReplayed replayed = assertInstanceOf(
                    ManifestPublicationUnitOfWork.PublishReplayed.class,
                    new JdbcManifestPublicationUnitOfWork(dataSource, new RejectingClock(
                            "publish replay must not read Clock"))
                            .publish(new ManifestPublicationUnitOfWork.PublishRequest(
                                    MEETING_ID, expectedFacts(), expectedManifest)));
            assertEquals(1, replayed.manifestRevision());
            assertEquals(MeetingState.READY, replayed.state());
            assertEquals(ManifestStatus.READY, replayed.manifestState());
            assertEquals(1L, manifestRevisionCount(dataSource),
                    "publish replay must not append a revision");

            DeleteResult.Accepted accepted = assertInstanceOf(
                    DeleteResult.Accepted.class,
                    new JdbcMeetingDeletionPort(
                            dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC))
                            .delete(deleteRequest()));
            assertDeletionSnapshot(accepted.meeting());

            LatestManifestFact latest = latestManifestFact(dataSource);
            assertEquals(2L, manifestRevisionCount(dataSource));
            assertEquals(2, latest.revision());
            assertEquals(ManifestStatus.DELETION_PENDING, latest.status());
            assertInstanceOf(
                    MeetingManifestPort.AccessDenied.class,
                    manifestQuery(dataSource).get(resultRequest()));

            writeDeletionState(stateFile, new DeletionState(
                    previous.postmasterStartA(),
                    postmasterStartB,
                    previous.publishedRevision(),
                    latest.revision()));
        }
    }

    /**
     * 第二次真重启后的新 JVM 重放删除并完成最终只读总回归。
     *
     * <p>删除重放同样注入拒绝读取的 Clock；稳定重放必须直接返回首次保存的
     * pending 快照。最后核对 revision、幂等记录、Outbox、会议状态和结果撤权，
     * 并再次执行 readiness，证明验证过程没有破坏数据库运行时。</p>
     */
    @Test
    void verifyAfterSecondRestart() throws Exception {
        DeletionState expected = readDeletionState(existingStateFile());
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "V001-V007 readiness must survive second restart");
            DataSource dataSource = runtime.dataSource();
            Instant postmasterStartC = postmasterStart(dataSource);
            assertTrue(postmasterStartC.isAfter(expected.postmasterStartB()),
                    "postmaster start time C must be later than B");

            DeleteResult.Replayed replayed = assertInstanceOf(
                    DeleteResult.Replayed.class,
                    new JdbcMeetingDeletionPort(dataSource, new RejectingClock(
                            "deletion replay must not read Clock"))
                            .delete(deleteRequest()));
            assertDeletionSnapshot(replayed.meeting());

            assertInstanceOf(
                    MeetingManifestPort.AccessDenied.class,
                    manifestQuery(dataSource).get(resultRequest()));
            assertEquals(2, expected.deletionRevision());
            assertEquals(2L, manifestRevisionCount(dataSource));
            LatestManifestFact latest = latestManifestFact(dataSource);
            assertEquals(2, latest.revision());
            assertEquals(ManifestStatus.DELETION_PENDING, latest.status());
            assertEquals(1L, deletionIdempotencyCount(dataSource));
            assertEquals(1L, deletionOutboxCount(dataSource));

            MeetingStatus meeting = ownedMeeting(dataSource);
            assertEquals(MeetingState.DELETION_PENDING, meeting.state());
            assertEquals(ManifestStatus.DELETION_PENDING, meeting.manifestState());
            assertEquals(InputState.INPUT_ACCEPTED, meeting.inputState());
            assertEquals(STOP_RECEIPT, meeting.finalizationReceipt());
            assertTrue(runtime.checkReadiness().ready(),
                    "readiness must still pass after all replay assertions");
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

    /** 建立稳定 reserve 事实，再由正式 owned 创建工作单元原子 bind 并建会。 */
    private static void createOwned(DataSource dataSource) throws SQLException {
        Instant expiresAt = BASE.plusSeconds(86_400);
        MeetingGrant grant = new MeetingGrant(
                "grant-t14-manifest-restart",
                DEPLOYMENT,
                SUBJECT,
                PROFILE_ID,
                PROFILE_REVISION,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                BASE.minusSeconds(30),
                expiresAt.plusSeconds(60));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-t14-manifest-restart",
                grant,
                MEETING_ID,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                BASE.minusSeconds(10),
                expiresAt);
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-t14-manifest-restart",
                "reserve-digest-t14-manifest-restart",
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcUsageReservationStore.insertReservation(connection, reservation);
                assertTrue(JdbcUsageReservationStore.insertOperation(
                        connection, reserve, BASE.minusSeconds(10)));
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }

        OwnedMeetingCreationUnitOfWork.Success created = assertInstanceOf(
                OwnedMeetingCreationUnitOfWork.Success.class,
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(BASE, ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                MEETING_ID,
                                SESSION_ID,
                                "create-key-t14-manifest-restart",
                                "create-digest-t14-manifest-restart",
                                reserve)));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, created.code());
    }

    /** 严格按领域状态机 AUTHORIZE、START、START 推进到 RUNNING。 */
    private static void advanceToRunning(DataSource dataSource) throws SQLException {
        advanceOwned(dataSource, MeetingEvent.AUTHORIZE, BASE.plusSeconds(1));
        advanceOwned(dataSource, MeetingEvent.START, BASE.plusSeconds(2));
        advanceOwned(dataSource, MeetingEvent.START, BASE.plusSeconds(3));
    }

    /**
     * 使用 Repository 行锁、领域聚合和聚合版本 CAS 推进状态；不直接更新状态列。
     */
    private static void advanceOwned(
            DataSource dataSource, MeetingEvent event, Instant eventAt) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting before =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, MEETING_ID)
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

    /** 通过正式 Stop 工作单元与输入接管端口建立最终收口事实。 */
    private static void stopAndAccept(DataSource dataSource) {
        OwnedMeetingStopUnitOfWork.Success stopped = assertInstanceOf(
                OwnedMeetingStopUnitOfWork.Success.class,
                new JdbcOwnedMeetingStopUnitOfWork(
                        dataSource, Clock.fixed(STOP_AT, ZoneOffset.UTC))
                        .stop(new OwnedMeetingStopUnitOfWork.StopRequest(
                                context(),
                                MEETING_ID,
                                "stop-key-t14-manifest-restart",
                                "stop-digest-t14-manifest-restart")));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, stopped.code());
        assertEquals(MeetingState.STOPPING, stopped.result().meeting().state());

        MeetingFinalizationStore.Applied accepted = assertInstanceOf(
                MeetingFinalizationStore.Applied.class,
                new JdbcMeetingFinalizationStore(dataSource, () -> STOP_RECEIPT)
                        .acceptInput(MEETING_ID, INPUT_ACCEPTED_AT));
        assertEquals(FinalizationFact.accepted(STOP_RECEIPT), accepted.fact());
    }

    private static MeetingStatus ownedMeeting(DataSource dataSource) {
        MeetingQueryPort.Found found = assertInstanceOf(
                MeetingQueryPort.Found.class,
                new JdbcMeetingQueryPort(
                        dataSource,
                        Arrays.copyOf(PAGE_TOKEN_SIGNING_KEY, PAGE_TOKEN_SIGNING_KEY.length))
                        .get(new MeetingQueryPort.GetRequest(context(), MEETING_ID)));
        return found.meeting();
    }

    private static JdbcMeetingManifestQueryPort manifestQuery(DataSource dataSource) {
        return new JdbcMeetingManifestQueryPort(
                dataSource,
                new ManifestResultAccessPolicy(
                        Clock.fixed(DELETE_AT.plusSeconds(60), ZoneOffset.UTC),
                        Duration.ofDays(1)));
    }

    private static MeetingManifestPort.Request resultRequest() {
        return new MeetingManifestPort.Request(MEETING_ID, -1);
    }

    private static ManifestPublicationUnitOfWork.ExpectedMeetingFacts expectedFacts() {
        return new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                DEPLOYMENT,
                SUBJECT,
                SESSION_ID,
                PROFILE_ID,
                PROFILE_REVISION,
                FinalizationFact.accepted(STOP_RECEIPT));
    }

    private static MeetingManifest publishedManifest() {
        return new MeetingManifest(
                "0.1",
                MEETING_ID,
                1,
                new MeetingManifest.Profile(PROFILE_ID, PROFILE_REVISION),
                "T14 restart manifest",
                ManifestStatus.READY,
                BASE.plusSeconds(3),
                STOP_AT,
                Duration.between(BASE.plusSeconds(3), STOP_AT).toMillis(),
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(),
                List.of());
    }

    private static DeleteRequest deleteRequest() {
        return new DeleteRequest(context(), MEETING_ID, DELETE_KEY, DELETE_DIGEST);
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
                        "credential-t14-manifest-restart"));
    }

    private static void assertDeletionSnapshot(MeetingStatus status) {
        assertEquals(MEETING_ID, status.meetingId());
        assertEquals(MeetingState.DELETION_PENDING, status.state());
        assertEquals(InputState.INPUT_ACCEPTED, status.inputState());
        assertEquals(STOP_RECEIPT, status.finalizationReceipt());
        assertEquals(ManifestStatus.DELETION_PENDING, status.manifestState());
        assertEquals(DELETE_AT, status.updatedAt());
    }

    private static Instant postmasterStart(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_postmaster_start_time()");
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                Timestamp value = rows.getTimestamp(1);
                assertNotNull(value);
                assertFalse(rows.next());
                return value.toInstant();
            }
        }
    }

    private static long manifestRevisionCount(DataSource dataSource) throws SQLException {
        return queryLong(dataSource, """
                SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?
                """, MEETING_ID);
    }

    private static LatestManifestFact latestManifestFact(DataSource dataSource)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT manifest_revision, manifest_json->>'status'
                       FROM meeting_manifest_revision
                      WHERE meeting_id=?
                      ORDER BY manifest_revision DESC
                      LIMIT 1
                     """)) {
            connection.setReadOnly(true);
            statement.setString(1, MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                LatestManifestFact latest = new LatestManifestFact(
                        rows.getInt(1), ManifestStatus.valueOf(rows.getString(2)));
                assertFalse(rows.next());
                return latest;
            }
        }
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
                """, MEETING_ID);
    }

    /** 只执行带文本参数的只读标量查询，禁止把动态值拼进 SQL。 */
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
                long value = rows.getLong(1);
                assertFalse(rows.next());
                return value;
            }
        }
    }

    private static Path newStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertFalse(Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS),
                "phase A state file must not already exist");
        return stateFile;
    }

    private static Path existingStateFile() throws IOException {
        Path stateFile = stateFilePath();
        assertPrivateParent(stateFile);
        assertTrue(Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS),
                "restart state file must be a regular file");
        assertFalse(Files.isSymbolicLink(stateFile),
                "restart state file must not be a symlink");
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
        assertFalse(Files.isSymbolicLink(parent),
                "restart state directory must not be a symlink");
        assertEquals(PRIVATE_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(parent));
    }

    private static void writePublishedState(Path stateFile, PublishedState state)
            throws IOException {
        Files.createFile(
                stateFile,
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
        writeExactState(stateFile, List.of(
                "phase=" + PHASE_PUBLISHED,
                "postmasterStartA=" + state.postmasterStartA(),
                "publishedRevision=" + state.publishedRevision()));
    }

    private static void writeDeletionState(Path stateFile, DeletionState state)
            throws IOException {
        existingStateFile();
        writeExactState(stateFile, List.of(
                "phase=" + PHASE_DELETION_ACCEPTED,
                "postmasterStartA=" + state.postmasterStartA(),
                "postmasterStartB=" + state.postmasterStartB(),
                "publishedRevision=" + state.publishedRevision(),
                "deletionRevision=" + state.deletionRevision()));
    }

    private static void writeExactState(Path stateFile, List<String> lines)
            throws IOException {
        OpenOption[] options = {
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        };
        Files.write(stateFile, lines, StandardCharsets.UTF_8, options);
        assertEquals(PRIVATE_FILE_PERMISSIONS, Files.getPosixFilePermissions(stateFile));
    }

    private static PublishedState readPublishedState(Path stateFile) throws IOException {
        Map<String, String> values = readExactState(stateFile, PUBLISHED_STATE_KEYS);
        assertEquals(PHASE_PUBLISHED, values.get("phase"));
        return new PublishedState(
                Instant.parse(values.get("postmasterStartA")),
                Integer.parseInt(values.get("publishedRevision")));
    }

    private static DeletionState readDeletionState(Path stateFile) throws IOException {
        Map<String, String> values = readExactState(stateFile, DELETION_STATE_KEYS);
        assertEquals(PHASE_DELETION_ACCEPTED, values.get("phase"));
        return new DeletionState(
                Instant.parse(values.get("postmasterStartA")),
                Instant.parse(values.get("postmasterStartB")),
                Integer.parseInt(values.get("publishedRevision")),
                Integer.parseInt(values.get("deletionRevision")));
    }

    private static Map<String, String> readExactState(
            Path stateFile, Set<String> expectedKeys) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            assertTrue(separator > 0, "restart state line must use key=value");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            assertFalse(value.isBlank(), "restart state value must not be blank");
            assertFalse(value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0,
                    "restart state value must stay on one line");
            assertNull(values.put(key, value), "restart state key must be unique");
        }
        assertEquals(expectedKeys, values.keySet(), "restart state keys must be exact");
        return values;
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
        Arrays.fill(secret, (byte) 0x14);
        return secret;
    }

    private record LatestManifestFact(int revision, ManifestStatus status) {
        private LatestManifestFact {
            assertTrue(revision > 0, "latest revision must be positive");
            assertNotNull(status);
        }
    }

    private record PublishedState(Instant postmasterStartA, int publishedRevision) {
        private PublishedState {
            assertNotNull(postmasterStartA);
            assertEquals(1, publishedRevision);
        }
    }

    private record DeletionState(
            Instant postmasterStartA,
            Instant postmasterStartB,
            int publishedRevision,
            int deletionRevision) {
        private DeletionState {
            assertNotNull(postmasterStartA);
            assertNotNull(postmasterStartB);
            assertTrue(postmasterStartB.isAfter(postmasterStartA));
            assertEquals(1, publishedRevision);
            assertEquals(2, deletionRevision);
        }
    }

    /** 稳定重放不得读取新时钟；调用即表示实现越过了持久化幂等边界。 */
    private static final class RejectingClock extends Clock {
        private final String message;

        private RejectingClock(String message) {
            this.message = message;
        }

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
            throw new AssertionError(message);
        }
    }
}
