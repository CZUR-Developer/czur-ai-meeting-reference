package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2-T14-09-10 双 JVM 门禁共享的权威场景构造器。
 *
 * <p>只有父测试进程调用 {@link #prepare(DataSource, Path, String)} 建立会议、
 * Reservation、execution fact、durable input、final usage、receipt 和 Workflow。
 * 子进程不调用本方法，也不能从环境变量接收 checkpoint 或模拟正文；恢复进程只能
 * 从 PostgreSQL 的 Workflow payload 和同一持久文件根读取这些材料。</p>
 *
 * <p>本类位于测试源码，不是参考后端公共 API。固定时间和固定业务范围仅用于让
 * 五个真实进程退出窗口产生可逐字比较的事实，不能进入生产装配。</p>
 */
final class T14WorkerCrossJvmTestSupport {
    static final String DEPLOYMENT = "cross-jvm-deployment";
    static final String SUBJECT = "cross-jvm-subject";
    static final Instant RESERVED_AT = Instant.parse("2026-09-29T04:00:00Z");
    static final Instant CREATED_AT = Instant.parse("2026-09-29T04:01:00Z");
    static final Instant STARTED_AT = Instant.parse("2026-09-29T04:01:05.123456Z");
    static final Instant STOPPED_AT = Instant.parse("2026-09-29T04:09:59.123456Z");
    static final Instant ACCEPTED_AT = Instant.parse("2026-09-29T04:10:00.123456Z");
    static final Instant FIRST_CLAIM_AT = ACCEPTED_AT.plusSeconds(1);
    static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    static final Instant RECOVERY_CLAIM_AT = FIRST_CLAIM_AT.plus(LEASE_DURATION);
    static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    static final long ARTIFACT_LIMIT = 1024 * 1024;

    private T14WorkerCrossJvmTestSupport() {
    }

    /**
     * 通过正式本地工作单元建立一场可被 Worker 领取的会议。
     *
     * <p>方法不手工插入 Workflow、receipt、输入或用量事实。测试只手工建立
     * reference-dev 权益的初始 Reservation，随后依次走 owned 建会、开始会议、
     * Stop 输入请求和 Finalization Admission，避免跨 JVM 门禁把伪造数据库行当成
     * 正式恢复链路。</p>
     */
    static Prepared prepare(DataSource dataSource, Path artifactRoot, String suffix)
            throws Exception {
        String normalized = suffix.replace('-', '_').toLowerCase();
        String meetingId = "mtg_cross_jvm_" + normalized;
        String receipt = "receipt-cross-jvm-" + normalized;
        ProductProfile profile = profile();
        Set<String> requested = Set.of("transcript.final", "summary.structured");
        MeetingGrant grant = new MeetingGrant(
                "grant-cross-jvm-" + normalized,
                DEPLOYMENT,
                SUBJECT,
                profile.id(),
                profile.revision(),
                requested,
                new HashSet<>(requested),
                3_600_000,
                RESERVED_AT,
                RESERVED_AT.plusSeconds(3_600));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-cross-jvm-" + normalized,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVED_AT.plusSeconds(1),
                RESERVED_AT.plusSeconds(3_000));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-cross-jvm-" + normalized,
                "reserve-digest-cross-jvm-" + normalized,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            require(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, RESERVED_AT.plusSeconds(1)),
                    "initial reserve operation was not inserted");
            connection.commit();
        }

        OwnedMeetingCreationUnitOfWork.Result created =
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                "session-cross-jvm-" + normalized,
                                "create-key-cross-jvm-" + normalized,
                                "create-digest-cross-jvm-" + normalized,
                                reserve));
        require(created instanceof OwnedMeetingCreationUnitOfWork.Success,
                "owned meeting creation failed");

        MeetingStartExecutionUnitOfWork.Result started =
                new JdbcMeetingStartExecutionUnitOfWork(
                        dataSource, new CapabilityPlanner())
                        .start(new MeetingStartExecutionUnitOfWork.StartRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                STARTED_AT,
                                "Cross JVM gate",
                                profile,
                                requested,
                                new CapabilityDescriptor(
                                        "cross-jvm-provider",
                                        Set.of("transcript.final", "summary.structured")),
                                DeploymentLimits.NONE));
        require(started instanceof MeetingStartExecutionUnitOfWork.Success,
                "meeting start execution fact was not frozen");
        FinalizationExecutionFact executionFact =
                ((MeetingStartExecutionUnitOfWork.Success) started).executionFact();

        // MeetingStartExecutionUnitOfWork 已把 AUTHORIZED 原子推进到 STARTING 并冻结
        // execution fact。停止必须继续走正式 owned Stop 工作单元，不能在测试中用
        // Repository 手工改状态或直接调用 requestInputCompletion 绕过鉴权、幂等、
        // Outbox 和同事务 pending fact。
        VerifiedIdentity identity = new VerifiedIdentity(
                new Subject(SUBJECT),
                Set.of("ROLE_USER"),
                Set.of(MeetingPermissions.STOP),
                CREATED_AT.minusSeconds(60),
                STOPPED_AT.plusSeconds(600),
                "cross-jvm-credential-reference");
        OwnedMeetingStopUnitOfWork.Result stopped =
                new JdbcOwnedMeetingStopUnitOfWork(
                        dataSource, Clock.fixed(STOPPED_AT, ZoneOffset.UTC))
                        .stop(new OwnedMeetingStopUnitOfWork.StopRequest(
                                new MeetingCommandContext(DEPLOYMENT, identity),
                                meetingId,
                                "stop-key-cross-jvm-" + normalized,
                                "stop-digest-cross-jvm-" + normalized));
        require(stopped instanceof OwnedMeetingStopUnitOfWork.Success,
                "owned meeting stop was not accepted");

        FinalizationInputFact input = new FinalizationInputFact(
                meetingId,
                "input-binding-" + meetingId,
                42,
                60_000,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                "sha256:input-digest-" + meetingId);
        FinalizationAdmissionUnitOfWork.Result admitted =
                new JdbcFinalizationAdmissionUnitOfWork(
                        dataSource,
                        () -> receipt,
                        new DeterministicMockFinalizationPlanner())
                        .admit(new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                                DEPLOYMENT, SUBJECT, meetingId, input));
        require(admitted instanceof FinalizationAdmissionUnitOfWork.Success,
                "finalization admission failed");
        FinalizationAdmissionUnitOfWork.Success success =
                (FinalizationAdmissionUnitOfWork.Success) admitted;
        WorkflowStep stored = new JdbcWorkflowStepStore(dataSource)
                .find(success.workflowKey()).orElseThrow();
        FinalizationCheckpoint checkpoint = FinalizationCheckpointJson.decode(
                stored.canonicalPayload());
        require(checkpoint.artifacts().size() == 2,
                "cross JVM gate requires exactly two deterministic artifacts");
        return new Prepared(
                meetingId,
                receipt,
                input,
                executionFact,
                checkpoint,
                artifactRoot.toAbsolutePath().normalize(),
                reservation.bind(meetingId, CREATED_AT, reservation.version()),
                reserve);
    }

    /** 构造真实 JDBC reference-dev Provider；commit 路径仍由数据库保存幂等事实。 */
    static JdbcReferenceDevEntitlementProvider entitlementProvider(
            DataSource dataSource,
            Clock clock) {
        return new JdbcReferenceDevEntitlementProvider(
                dataSource, policy(clock), clock);
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile",
                1,
                Set.of("transcript.final"),
                Set.of("summary.structured"),
                Map.of("summary.structured", Set.of("transcript.final")),
                Map.of(),
                Map.of(),
                Map.of(
                        "transcript.final", List.of("transcript"),
                        "summary.structured", List.of("summary")),
                Map.of(
                        "transcript.final", List.of("transcript.final"),
                        "summary.structured", List.of("summary.structured")));
    }

    private static ReferenceDevEntitlementPolicy policy(Clock clock) {
        return new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT,
                        "profile",
                        1,
                        Set.of("transcript.final", "summary.structured"),
                        3_600_000,
                        Duration.ofHours(1),
                        Duration.ofMinutes(30)),
                clock,
                new FixedIds());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 父进程保留的断言材料；其内容不得写入子进程日志。 */
    record Prepared(
            String meetingId,
            String receipt,
            FinalizationInputFact input,
            FinalizationExecutionFact executionFact,
            FinalizationCheckpoint checkpoint,
            Path artifactRoot,
            UsageReservation boundReservation,
            UsageOperationResult reserveOperation) {
    }

    private static final class FixedIds implements EntitlementIdGenerator {
        @Override
        public String newGrantId() {
            return "unused-cross-jvm-grant";
        }

        @Override
        public String newReservationId() {
            return "unused-cross-jvm-reservation";
        }

        @Override
        public String newLeaseToken() {
            return "unused-cross-jvm-lease";
        }
    }
}
