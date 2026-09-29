package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.manifest.ManifestDeletionRevisionFactory;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 删除 Workflow 的最终收口事务。
 *
 * <p>该类不启动后台线程，也不访问 ArtifactStore。调用方必须先逐项确认删除；
 * 本工作单元只在同一 PostgreSQL 事务中按 Meeting → deletion Workflow → item/
 * metadata → Manifest 的顺序加锁，并一次性写入会议、计划、Workflow 和 Outbox
 * 终态。任何 item 未确认都回滚，会议保持 DELETION_PENDING。</p>
 */
public final class JdbcArtifactDeletionTerminalUnitOfWork
        implements ArtifactDeletionTerminalUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcArtifactDeletionTerminalUnitOfWork.class.getName());

    private final DataSource dataSource;

    public JdbcArtifactDeletionTerminalUnitOfWork(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Result complete(CompleteRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean commitStarted = false;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);

            // 锁序第一段：Meeting。锁住后再读取 Workflow，阻止删除受理或发布事务插队。
            JdbcMeetingRepository.VersionedMeeting meeting =
                    JdbcMeetingRepository.findVersionedForUpdate(
                            connection, request.lease().key().meetingId()).orElse(null);
            if (meeting == null) {
                rollback(connection);
                return failure(ErrorCode.NOT_FOUND);
            }

            JdbcWorkflowStepStore.LeaseFenceResult fence =
                    JdbcWorkflowStepStore.lockActiveLease(
                            connection, request.lease(), request.completedAt());
            if (fence.status() != JdbcWorkflowStepStore.LeaseFenceStatus.ACTIVE) {
                // 已经提交的终态只允许作为只读重放返回；其它 fencing 结果不能写任何事实。
                if (fence.status() == JdbcWorkflowStepStore.LeaseFenceStatus.ALREADY_COMPLETED
                        && meeting.snapshot().state()
                        == com.czur.reference.domain.meeting.MeetingState.DELETED) {
                    rollback(connection);
                    return new Success(SuccessCode.REPLAYED, null);
                }
                rollback(connection);
                return fenceFailure(fence.status());
            }

            // 锁序第二段：删除计划（其 item 在下一段按 ordinal 锁定）。
            MeetingDeletionPlan persisted = new JdbcArtifactDeletionPlanStore(connection)
                    .findForUpdate(request.lease().key().meetingId()).orElse(null);
            if (persisted == null) {
                rollback(connection);
                return failure(ErrorCode.PLAN_NOT_FOUND);
            }
            ArtifactDeletionPlan plan = persisted.plan();
            WorkflowStep expected = ArtifactDeletionPlanJson.workflowStep(plan);
            if (!definitionMatches(expected, fence.step())) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            if (persisted.status() != MeetingDeletionPlan.PlanStatus.PENDING
                    || meeting.snapshot().state()
                    != com.czur.reference.domain.meeting.MeetingState.DELETION_PENDING) {
                rollback(connection);
                return failure(ErrorCode.PLAN_NOT_PENDING);
            }

            // 锁序第三段：全部 item，再锁仍存在的 metadata 行。严格空计划自然通过。
            if (!allItemsDeleted(connection, plan)) {
                rollback(connection);
                return failure(ErrorCode.ITEM_NOT_CONFIRMED);
            }
            if (!metadataAbsent(connection, plan)) {
                rollback(connection);
                return failure(ErrorCode.ITEM_NOT_CONFIRMED);
            }

            // 锁序第四段：Manifest。没有源 revision 时严格不制造 tombstone。
            Optional<MeetingManifest> latest = new JdbcManifestFactStore(connection)
                    .findLatestForLockedMeeting(plan.meetingId());
            Integer deletedRevision = null;
            if (latest.isPresent()) {
                MeetingManifest source = latest.orElseThrow();
                if (source.status() != ManifestStatus.DELETION_PENDING
                        || !source.meetingId().equals(plan.meetingId())) {
                    rollback(connection);
                    return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
                }
                MeetingManifest deleted = ManifestDeletionRevisionFactory.deletedAfter(source);
                ManifestFactStore.AppendResult appended = new JdbcManifestFactStore(connection)
                        .append(new ManifestFactStore.AppendRequest(
                                deleted,
                                new ManifestFactStore.ExpectedPreviousRevision.Present(
                                        source.manifestRevision())));
                if (appended.status() != ManifestFactStore.AppendStatus.APPENDED
                        && appended.status() != ManifestFactStore.AppendStatus.REPLAYED) {
                    rollback(connection);
                    return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
                }
                deletedRevision = deleted.manifestRevision();
            }

            if (!JdbcMeetingRepository.markDeleted(
                    connection, meeting, request.completedAt())) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            if (!JdbcArtifactDeletionPlanStore.complete(
                    connection, plan.meetingId(), request.completedAt())) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            WorkflowStepStore.CompleteResult workflow = JdbcWorkflowStepStore.complete(
                    connection, request.lease(), request.completedAt());
            if (workflow.status() != WorkflowStepStore.CompleteStatus.COMPLETED) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            OutboxStore.AppendResult event = JdbcOutboxStore.append(connection,
                    MeetingCommandEventFactory.meetingDeleted(
                            meeting.snapshot().deploymentScope(), plan.meetingId(),
                            request.completedAt()));
            if (event.status() == OutboxStore.AppendStatus.CONFLICT) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            commitStarted = true;
            connection.commit();
            LOGGER.log(Level.FINE, "删除终态事务已提交 result=COMPLETED");
            return new Success(SuccessCode.COMPLETED, deletedRevision);
        } catch (SQLException | RuntimeException failure) {
            if (commitStarted) {
                return failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            rollback(connection);
            return failure(ErrorCode.STORAGE_FAILURE);
        } finally {
            close(connection);
        }
    }

    @Override
    public Result recover(CompleteRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            String meetingId = request.lease().key().meetingId();
            boolean deleted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT state, manifest_state, deleted_at FROM meeting WHERE meeting_id = ?
                    """)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return failure(ErrorCode.NOT_FOUND);
                    }
                    deleted = "DELETED".equals(rows.getString("state"))
                            && "DELETED".equals(rows.getString("manifest_state"))
                            && rows.getTimestamp("deleted_at") != null;
                }
            }
            MeetingDeletionPlan persisted = new JdbcArtifactDeletionPlanStore(connection)
                    .find(meetingId).orElse(null);
            WorkflowStep workflow = JdbcWorkflowStepStore.find(connection, request.lease().key())
                    .orElse(null);
            boolean eventPresent = JdbcOutboxStore.find(connection,
                    MeetingCommandEventFactory.meetingDeleted(
                            "recovery", meetingId, request.completedAt()).eventId()).isPresent();
            Optional<MeetingManifest> latest = new JdbcManifestFactStore(connection)
                    .findLatestForLockedMeeting(meetingId);
            boolean manifestDone = latest.isEmpty()
                    || latest.orElseThrow().status() == ManifestStatus.DELETED;
            if (!deleted || persisted == null
                    || persisted.status() != MeetingDeletionPlan.PlanStatus.COMPLETED
                    || workflow == null || workflow.status() != WorkflowStep.Status.COMPLETED
                    || !eventPresent || !manifestDone) {
                connection.rollback();
                return failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            Integer revision = latest.map(MeetingManifest::manifestRevision).orElse(null);
            connection.rollback();
            return new Success(SuccessCode.REPLAYED, revision);
        } catch (SQLException | RuntimeException failure) {
            return failure(ErrorCode.OUTCOME_UNKNOWN);
        }
    }

    private static boolean definitionMatches(WorkflowStep expected, WorkflowStep actual) {
        return expected.key().equals(actual.key())
                && expected.checkpointType().equals(actual.checkpointType())
                && expected.canonicalPayload().equals(actual.canonicalPayload())
                && expected.contentDigest().equals(actual.contentDigest());
    }

    private static boolean allItemsDeleted(Connection connection, ArtifactDeletionPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ordinal, artifact_id, artifact_type, revision, status
                  FROM artifact_deletion_item
                 WHERE meeting_id = ?
                 ORDER BY ordinal
                 FOR UPDATE
                """)) {
            statement.setString(1, plan.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (count >= plan.targets().size()) {
                        return false;
                    }
                    int ordinal = rows.getInt("ordinal");
                    ArtifactDeletionTarget target = plan.targets().get(count);
                    if (ordinal != count
                            || !target.artifactId().equals(rows.getString("artifact_id"))
                            || !target.spec().artifactType().equals(rows.getString("artifact_type"))
                            || target.spec().revision() != rows.getInt("revision")
                            || !"DELETED".equals(rows.getString("status"))) {
                        return false;
                    }
                    count++;
                }
                return count == plan.targets().size();
            }
        }
    }

    private static boolean metadataAbsent(Connection connection, ArtifactDeletionPlan plan)
            throws SQLException {
        for (ArtifactDeletionTarget target : plan.targets()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT artifact_id FROM artifact WHERE artifact_id = ? FOR UPDATE
                    """)) {
                statement.setString(1, target.artifactId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Result fenceFailure(JdbcWorkflowStepStore.LeaseFenceStatus status) {
        return switch (status) {
            case NOT_FOUND -> failure(ErrorCode.NOT_FOUND);
            case NOT_OWNER -> failure(ErrorCode.NOT_OWNER);
            case LEASE_EXPIRED -> failure(ErrorCode.LEASE_EXPIRED);
            case ALREADY_COMPLETED -> failure(ErrorCode.ALREADY_COMPLETED);
            case ALREADY_DEAD -> failure(ErrorCode.ALREADY_DEAD);
            case ACTIVE -> throw new IllegalStateException("active fence is not a failure");
        };
    }

    private static Failure failure(ErrorCode code) {
        return new Failure(code, code.retryable());
    }

    private static void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 下次只读恢复会重新裁决已提交事实。
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 不向日志输出连接信息。
            }
        }
    }
}
