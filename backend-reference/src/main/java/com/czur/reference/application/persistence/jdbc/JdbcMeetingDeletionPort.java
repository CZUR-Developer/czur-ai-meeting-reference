package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.manifest.ManifestDeletionRevisionFactory;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL 单连接事务实现的会议删除受理端口。
 *
 * <p>调用方负责验证 credential 和 {@code meeting:delete} 权限；本端口负责在
 * {@code meeting FOR UPDATE} 行锁内再次按数据库原始部署/主体校验归属。访问
 * 拒绝发生在任何删除幂等读取之前。首次请求严格按“锁行、访问检查、幂等读取、
 * 状态检查、Manifest latest 校验、单次时钟、幂等预占、可选删除 revision、
 * Meeting CAS、Outbox、commit”执行，全部事实要么同时提交，要么同时回滚。</p>
 *
 * <p>已有完整 Manifest 时，本事务通过 {@link ManifestDeletionRevisionFactory}
 * 逐字段保留其正文，只把 revision 加一并把状态改成 {@code DELETION_PENDING}；
 * 从未产生 revision 时不构造空壳正文。同键重放只返回首次持久化的
 * pending/pending 快照，不重复追加 revision 或 Outbox；当前行已经由 Worker
 * 推进为 deleted/deleted 时，只用于验证首次事实仍然可信，不能改写响应正文。
 * 本类不物理删除 Meeting、Artifact 或对象存储，也不实现 T14 Worker。</p>
 *
 * <p>日志只包含固定阶段、稳定结果、枚举原因和经过白名单校验的 SQLState；
 * 不输出 meetingId、部署、主体、幂等 scope/key/digest、响应正文、receipt、
 * 数据库地址、对象存储位置或动态异常文本。</p>
 */
public final class JdbcMeetingDeletionPort implements MeetingDeletionPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingDeletionPort.class.getName());
    private static final EnumSet<MeetingState> FIRST_ACCEPTED_STATES = EnumSet.of(
            MeetingState.READY,
            MeetingState.READY_WITH_WARNINGS,
            MeetingState.FAILED,
            MeetingState.CANCELLED);
    private static final EnumSet<ManifestStatus> FIRST_ACCEPTED_MANIFEST_STATES = EnumSet.of(
            ManifestStatus.NOT_STARTED,
            ManifestStatus.GENERATING,
            ManifestStatus.READY,
            ManifestStatus.READY_WITH_WARNINGS,
            ManifestStatus.FAILED);

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcMeetingDeletionPort(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 受理或重放删除。
     *
     * <p>commit 调用一旦开始，任何 SQL 或运行时异常都返回可重试的
     * {@link DeleteResult.UnavailableReason#OUTCOME_UNKNOWN}，不再尝试 rollback
     * 或恢复 auto-commit。commit 前错误会尝试回滚；只有数据库明确确认 commit
     * 或 rollback 后，连接才允许恢复原 auto-commit 状态。</p>
     */
    @Override
    public DeleteResult delete(DeleteRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean previousAutoCommit = true;
        TransactionResolution transactionResolution = new TransactionResolution();
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            phase = Phase.MEETING_LOCK_AND_ACCESS_CHECK;
            JdbcMeetingRepository.DeletionLockResult lock =
                    JdbcMeetingRepository.lockForDeletion(
                            connection, request.meetingId(), request.context());
            if (lock instanceof JdbcMeetingRepository.DeletionNotFound) {
                return rollbackResult(
                        connection, transactionResolution, new DeleteResult.NotFound(), phase);
            }
            if (lock instanceof JdbcMeetingRepository.DeletionAccessDenied) {
                return rollbackResult(
                        connection, transactionResolution,
                        new DeleteResult.AccessDenied(), phase);
            }
            if (lock instanceof JdbcMeetingRepository.DeletionSnapshotInconsistent) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }
            JdbcMeetingRepository.DeletionMeeting current =
                    ((JdbcMeetingRepository.DeletionFound) lock).meeting();

            String scope = IdempotencyScopeCodec.meetingDelete(
                    request.context().deploymentScope(),
                    request.context().identity().subject().subjectScope());
            phase = Phase.IDEMPOTENCY_READ;
            Optional<JdbcIdempotencyStore.DeletionRawRecord> previous =
                    JdbcIdempotencyStore.findDeletion(
                            connection, scope, request.idempotencyKey());
            if (previous.isPresent()) {
                // 摘要冲突必须先于 Manifest 正文读取裁决；调用方不能用一个已
                // 冲突的幂等键探测该会议是否存在、是否损坏或包含删除 revision。
                if (!sameDigest(
                        previous.orElseThrow().requestDigest(),
                        request.canonicalRequestDigest())) {
                    return rollbackResult(
                            connection,
                            transactionResolution,
                            conflict(DeleteResult.ConflictReason.REQUEST_DIGEST_MISMATCH),
                            phase);
                }
                phase = Phase.MANIFEST_READ;
                Optional<MeetingManifest> latest =
                        new JdbcManifestFactStore(connection)
                                .findLatestForLockedMeeting(request.meetingId());
                DeleteResult replay = classifyExisting(
                        previous.get(), request, current, latest);
                return rollbackResult(
                        connection, transactionResolution, replay, phase);
            }

            phase = Phase.STATE_CHECK;
            if (!FIRST_ACCEPTED_STATES.contains(current.snapshot().state())) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.INVALID_MEETING_STATE),
                        phase);
            }
            if (!FIRST_ACCEPTED_MANIFEST_STATES.contains(current.manifestState())) {
                // Meeting 仍是可首次删除状态，但 Manifest 已进入删除中或已删除，
                // 表明两条生命周期事实交叉损坏；不能把它当作首次请求覆盖修复。
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }

            phase = Phase.MANIFEST_READ;
            JdbcManifestFactStore manifestStore = new JdbcManifestFactStore(connection);
            Optional<MeetingManifest> latest =
                    manifestStore.findLatestForLockedMeeting(request.meetingId());
            Optional<MeetingManifest> deletionRevision;
            if (latest.isEmpty()) {
                // 取消或早期失败可能从未产生完整 Manifest。这里有意只推进
                // Meeting 删除状态，不能猜测语言、时间、时长或 Artifact。
                deletionRevision = Optional.empty();
            } else if (!isConsistentDeletionSource(current, latest.orElseThrow())) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            } else {
                try {
                    deletionRevision = Optional.of(
                            ManifestDeletionRevisionFactory.deletionPendingAfter(
                                    latest.orElseThrow()));
                } catch (IllegalArgumentException malformedSource) {
                    return rollbackResult(
                            connection,
                            transactionResolution,
                            conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                            phase);
                }
            }

            phase = Phase.CLOCK_READ;
            Instant acceptedAt = Objects.requireNonNull(
                            clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            if (acceptedAt.isBefore(current.snapshot().updatedAt())) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }
            final MeetingAggregate.MeetingSnapshot advanced;
            final MeetingStatus accepted;
            try {
                // 删除状态必须由领域聚合推进，不能由 JDBC 适配器直接拼出。
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(current.snapshot());
                MeetingState next = aggregate.apply(MeetingEvent.DELETE_REQUESTED, acceptedAt);
                if (next != MeetingState.DELETION_PENDING) {
                    return rollbackResult(
                            connection,
                            transactionResolution,
                            conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                            phase);
                }
                advanced = aggregate.snapshot();
                accepted = current.pendingStatus(aggregate);
            } catch (RuntimeException invalidDomainFact) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }

            phase = Phase.IDEMPOTENCY_RESERVE;
            JdbcIdempotencyStore.DeletionReservation reservation =
                    JdbcIdempotencyStore.reserveDeletion(
                            connection,
                            scope,
                            request.idempotencyKey(),
                            request.canonicalRequestDigest(),
                            accepted,
                            acceptedAt);
            if (!reservation.acquired()) {
                DeleteResult replay = classifyExisting(
                        reservation.record(), request, current, latest);
                return rollbackResult(
                        connection, transactionResolution, replay, phase);
            }
            final MeetingStatus persistedAccepted;
            try {
                persistedAccepted = decodeDeletion(reservation.record());
            } catch (RuntimeException malformed) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }
            if (!sameDigest(
                    reservation.record().requestDigest(),
                    request.canonicalRequestDigest())
                    || !accepted.equals(persistedAccepted)) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }

            if (deletionRevision.isPresent()) {
                MeetingManifest candidate = deletionRevision.orElseThrow();
                MeetingManifest source = latest.orElseThrow();
                phase = Phase.MANIFEST_APPEND;
                ManifestFactStore.AppendResult appended = manifestStore.append(
                        new ManifestFactStore.AppendRequest(
                                candidate,
                                new ManifestFactStore.ExpectedPreviousRevision.Present(
                                        source.manifestRevision())));
                // meeting 行锁已经串行化 publish/delete；目标 revision 若已存在、
                // 前序变化或正文冲突，都说明数据库事实不再满足首次删除前提。
                if (appended.status() != ManifestFactStore.AppendStatus.APPENDED) {
                    return rollbackResult(
                            connection,
                            transactionResolution,
                            conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                            phase);
                }
            }

            phase = Phase.MEETING_UPDATE;
            if (!JdbcMeetingRepository.markDeletionPending(
                    connection, current, advanced, accepted)) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }

            phase = Phase.OUTBOX_APPEND;
            OutboxStore.AppendResult append = JdbcOutboxStore.append(
                    connection,
                    MeetingCommandEventFactory.meetingDeletionRequested(
                            current.snapshot().deploymentScope(), accepted));
            if (append.status() != OutboxStore.AppendStatus.APPENDED) {
                return rollbackResult(
                        connection,
                        transactionResolution,
                        conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT),
                        phase);
            }

            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE, "会议删除受理事务已提交 result=ACCEPTED");
            return new DeleteResult.Accepted(accepted);
        } catch (JdbcManifestFactStore.StoredFactException manifestFailure) {
            if (commitStarted) {
                return loggedUnavailable(
                        DeleteResult.UnavailableReason.OUTCOME_UNKNOWN,
                        Phase.COMMIT,
                        null);
            }
            DeleteResult result = manifestFailure.kind()
                    == JdbcManifestFactStore.FailureKind.CORRUPT_DATA
                    ? conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT)
                    : unavailable(DeleteResult.UnavailableReason.STORAGE_FAILURE);
            return transactionStarted
                    ? rollbackResult(connection, transactionResolution, result, phase)
                    : result;
        } catch (SQLException sqlFailure) {
            if (commitStarted) {
                return loggedUnavailable(
                        DeleteResult.UnavailableReason.OUTCOME_UNKNOWN,
                        Phase.COMMIT,
                        sqlFailure.getSQLState());
            }
            JdbcReadLookupSupport.FailureKind failureKind =
                    JdbcReadLookupSupport.classify(sqlFailure);
            DeleteResult result = switch (failureKind) {
                case INCONSISTENT -> loggedSqlConflict(phase, sqlFailure);
                case RETRYABLE_UNAVAILABLE -> loggedUnavailable(
                        DeleteResult.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                        phase,
                        sqlFailure.getSQLState());
                case NON_RETRYABLE_UNAVAILABLE -> loggedUnavailable(
                        DeleteResult.UnavailableReason.STORAGE_FAILURE,
                        phase,
                        sqlFailure.getSQLState());
            };
            return transactionStarted
                    ? rollbackResult(connection, transactionResolution, result, phase)
                    : result;
        } catch (RuntimeException runtimeFailure) {
            // DataSource、Clock、JDBC 代理和驱动均可能用运行时异常报告故障。
            // 动态异常文本和 cause 不进入结果或日志。
            if (commitStarted) {
                return loggedUnavailable(
                        DeleteResult.UnavailableReason.OUTCOME_UNKNOWN,
                        Phase.COMMIT,
                        null);
            }
            DeleteResult result = loggedUnavailable(
                    DeleteResult.UnavailableReason.STORAGE_FAILURE, phase, null);
            return transactionStarted
                    ? rollbackResult(connection, transactionResolution, result, phase)
                    : result;
        } finally {
            if (commitCompleted || transactionResolution.rollbackConfirmed()) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            } else {
                // commit 结果未知、rollback 未确认或事务启动失败时，连接不得回池。
                abortQuietly(connection);
            }
            closeQuietly(connection);
        }
    }

    private static DeleteResult classifyExisting(
            JdbcIdempotencyStore.DeletionRawRecord record,
            DeleteRequest request,
            JdbcMeetingRepository.DeletionMeeting current,
            Optional<MeetingManifest> latest) {
        // 必须先比较摘要；摘要冲突时不能解析潜在损坏的响应正文。
        if (!sameDigest(record.requestDigest(), request.canonicalRequestDigest())) {
            return conflict(DeleteResult.ConflictReason.REQUEST_DIGEST_MISMATCH);
        }
        final MeetingStatus stored;
        try {
            stored = decodeDeletion(record);
        } catch (RuntimeException malformed) {
            return conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        }
        if (!request.meetingId().equals(stored.meetingId())) {
            return conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        }

        MeetingStatus now = current.status();
        if (now.state() == MeetingState.DELETION_PENDING
                && now.manifestState() == ManifestStatus.DELETION_PENDING) {
            return stored.equals(now) && isConsistentDeletionLatest(current, latest)
                    ? new DeleteResult.Replayed(stored)
                    : conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        }
        if (now.state() == MeetingState.DELETED
                && now.manifestState() == ManifestStatus.DELETED
                && !now.updatedAt().isBefore(stored.updatedAt())
                && now.finalization().equals(stored.finalization())
                && isConsistentDeletionLatest(current, latest)) {
            return new DeleteResult.Replayed(stored);
        }
        return conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
    }

    /** 证明现有完整 Manifest 可以作为本次删除 revision 的唯一源正文。 */
    private static boolean isConsistentDeletionSource(
            JdbcMeetingRepository.DeletionMeeting current,
            MeetingManifest latest) {
        if (!current.snapshot().meetingId().equals(latest.meetingId())
                || !current.snapshot().profileId().equals(latest.profile().id())
                || current.snapshot().profileRevision() != latest.profile().revision()
                || current.manifestState() != latest.status()) {
            return false;
        }
        return switch (latest.status()) {
            case READY -> current.snapshot().state() == MeetingState.READY;
            case READY_WITH_WARNINGS ->
                    current.snapshot().state() == MeetingState.READY_WITH_WARNINGS;
            case FAILED -> current.snapshot().state() == MeetingState.FAILED;
            default -> false;
        };
    }

    /**
     * 校验重放时 Meeting 删除状态与 latest revision 的最小一致性。
     *
     * <p>latest 为空表示首次删除时原本没有完整 Manifest，是合法分支；latest
     * 存在时必须是由至少一版完整正文派生出的删除 revision。未来删除 Worker
     * 可以继续追加 {@code DELETED}，但不得让旧完整结果重新成为 latest。</p>
     */
    private static boolean isConsistentDeletionLatest(
            JdbcMeetingRepository.DeletionMeeting current,
            Optional<MeetingManifest> latest) {
        if (latest.isEmpty()) {
            return true;
        }
        MeetingManifest manifest = latest.orElseThrow();
        return current.snapshot().meetingId().equals(manifest.meetingId())
                && current.snapshot().profileId().equals(manifest.profile().id())
                && current.snapshot().profileRevision() == manifest.profile().revision()
                && manifest.manifestRevision() > 1
                && (manifest.status() == ManifestStatus.DELETION_PENDING
                    || (current.snapshot().state() == MeetingState.DELETED
                        && manifest.status() == ManifestStatus.DELETED));
    }

    private static MeetingStatus decodeDeletion(
            JdbcIdempotencyStore.DeletionRawRecord record) {
        return JdbcIdempotencyStore.decodeDeletion(record);
    }

    private static boolean sameDigest(String stored, String requested) {
        // 规范摘要不是凭据；直接比较 Java 字符串可避免宽松 UTF-8 编码对非法
        // surrogate 的替换碰撞，也不会把摘要写入日志或异常。
        return Objects.equals(stored, requested);
    }

    private static DeleteResult rollbackResult(
            Connection connection,
            TransactionResolution resolution,
            DeleteResult result,
            Phase phase) {
        if (!resolution.rollback(connection)) {
            return loggedUnavailable(
                    DeleteResult.UnavailableReason.OUTCOME_UNKNOWN, phase, null);
        }
        LOGGER.log(Level.FINE,
                "会议删除受理事务结束 phase={0} status={1}",
                new Object[]{phase.name(), result.status().name()});
        return result;
    }

    private static DeleteResult.Conflict conflict(DeleteResult.ConflictReason reason) {
        return new DeleteResult.Conflict(reason);
    }

    private static DeleteResult.Unavailable unavailable(
            DeleteResult.UnavailableReason reason) {
        boolean retryable = reason == DeleteResult.UnavailableReason.TRANSIENT_STORAGE_FAILURE
                || reason == DeleteResult.UnavailableReason.OUTCOME_UNKNOWN;
        return new DeleteResult.Unavailable(reason, retryable);
    }

    private static DeleteResult.Unavailable loggedUnavailable(
            DeleteResult.UnavailableReason reason,
            Phase phase,
            String sqlState) {
        DeleteResult.Unavailable result = unavailable(reason);
        LOGGER.log(result.retryable() ? Level.WARNING : Level.SEVERE,
                "会议删除受理事务不可用 phase={0} reason={1} sqlState={2}",
                new Object[]{phase.name(), reason.name(), safeSqlState(sqlState)});
        return result;
    }

    private static DeleteResult.Conflict loggedSqlConflict(
            Phase phase,
            SQLException failure) {
        LOGGER.log(Level.WARNING,
                "会议删除受理事务拒绝 phase={0} reason={1} sqlState={2}",
                new Object[]{
                        phase.name(),
                        DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT.name(),
                        JdbcReadLookupSupport.safeSqlState(failure)});
        return conflict(DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
    }

    private static String safeSqlState(String sqlState) {
        if (sqlState == null || sqlState.isBlank()) {
            return "UNKNOWN";
        }
        return sqlState.matches("[A-Z0-9]{5}") ? sqlState : "INVALID";
    }

    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            LOGGER.log(Level.WARNING, "会议删除受理事务回滚未确认");
            return false;
        }
    }

    private static void restoreAutoCommitQuietly(
            Connection connection,
            boolean previousAutoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException restoreFailure) {
            LOGGER.log(Level.WARNING, "会议删除受理事务恢复连接状态失败");
        }
    }

    private static void abortQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException abortFailure) {
            LOGGER.log(Level.WARNING, "会议删除受理事务废弃连接失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            LOGGER.log(Level.WARNING, "会议删除受理事务关闭连接失败");
        }
    }

    /** 记录 rollback 是否由数据库明确确认，约束 finally 是否可恢复连接。 */
    private static final class TransactionResolution {
        private boolean rollbackConfirmed;

        boolean rollback(Connection connection) {
            if (!rollbackConfirmed) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            return rollbackConfirmed;
        }

        boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }

    private enum Phase {
        OPEN_CONNECTION,
        MEETING_LOCK_AND_ACCESS_CHECK,
        IDEMPOTENCY_READ,
        STATE_CHECK,
        MANIFEST_READ,
        CLOCK_READ,
        IDEMPOTENCY_RESERVE,
        MANIFEST_APPEND,
        MEETING_UPDATE,
        OUTBOX_APPEND,
        COMMIT
    }
}
