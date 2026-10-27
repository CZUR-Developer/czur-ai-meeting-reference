package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestFactStore.ExpectedPreviousRevision;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.ManifestPublicationUnitOfWork;
import com.czur.reference.application.port.MeetingManifest;
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
 * Manifest 发布的参考 JDBC 工作单元。
 *
 * <p>本类刻意独立于 {@link JdbcMeetingUnitOfWork}：Manifest 事实表和会议状态
 * 必须在一条显式事务中提交，而现有会议工作单元不暴露 T14-03-01 的事实存储
 * 原语。这里使用包内 {@link JdbcManifestFactStore}，不把该原语暴露给 runtime
 * 或公共端口。</p>
 *
 * <p>每次调用先锁定 meeting 行，再验证身份/Profile/input 事实，最后在同一连接
 * 中追加 revision 和 CAS 更新 meeting。commit 一旦开始而 JDBC 无法确认结果，
 * 连接会被 abort 后关闭，不会回到连接池；调用方只能收到
 * {@link ManifestPublicationUnitOfWork.UnavailableReason#OUTCOME_UNKNOWN}。</p>
 */
public final class JdbcManifestPublicationUnitOfWork
        implements ManifestPublicationUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcManifestPublicationUnitOfWork.class.getName());
    private static final EnumSet<MeetingState> COMPLETION_STATES = EnumSet.of(
            MeetingState.READY,
            MeetingState.READY_WITH_WARNINGS,
            MeetingState.FAILED);
    private static final EnumSet<ManifestStatus> COMPLETION_MANIFEST_STATES = EnumSet.of(
            ManifestStatus.READY,
            ManifestStatus.READY_WITH_WARNINGS,
            ManifestStatus.FAILED);

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcManifestPublicationUnitOfWork(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcManifestPublicationUnitOfWork(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BeginResult begin(BeginRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        Tx tx = new Tx();
        try {
            connection = dataSource.getConnection();
            tx.open(connection);
            Optional<JdbcMeetingRepository.PublicationMeeting> locked =
                    JdbcMeetingRepository.findManifestPublicationForUpdate(
                            connection, request.meetingId());
            if (locked.isEmpty()) {
                return tx.rollback(new NotFound());
            }
            JdbcMeetingRepository.PublicationMeeting current = locked.orElseThrow();
            ConflictReason mismatch = compareExpected(current, request.expectedFacts());
            if (mismatch != null) {
                return tx.rollback(new Conflict(mismatch));
            }

            Optional<MeetingManifest> latest = findLatest(connection, request.meetingId());
            if (latest.isPresent()) {
                return tx.rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
            }
            if (current.snapshot().state() != MeetingState.FINALIZING
                    || current.finalization().inputState() != InputState.INPUT_ACCEPTED) {
                return tx.rollback(new Conflict(ConflictReason.INVALID_MEETING_STATE));
            }
            if (current.manifestState() == ManifestStatus.GENERATING) {
                return tx.rollback(new BeginReplayed(0));
            }
            if (current.manifestState() != ManifestStatus.NOT_STARTED) {
                return tx.rollback(new Conflict(ConflictReason.INVALID_MANIFEST_STATE));
            }

            Instant updatedAt = nextTimestamp(current.snapshot().updatedAt());
            if (!JdbcMeetingRepository.updateManifestPublication(
                    connection,
                    current,
                    MeetingState.FINALIZING,
                    ManifestStatus.GENERATING,
                    updatedAt)) {
                return tx.rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
            }
            return tx.commit(new BeginApplied(0));
        } catch (JdbcMeetingRepository.PublicationSnapshotException malformed) {
            return tx.failureBeforeCommit(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
        } catch (JdbcManifestFactStore.StoredFactException storageFailure) {
            return tx.manifestFailure(storageFailure);
        } catch (SQLException sqlFailure) {
            return tx.sqlFailure(sqlFailure);
        } catch (RuntimeException runtimeFailure) {
            LOGGER.log(Level.WARNING, "Manifest begin 事务失败 exception={0}",
                    runtimeFailure.getClass().getSimpleName());
            return tx.runtimeFailure();
        } finally {
            tx.finish(connection);
        }
    }

    @Override
    public PublishResult publish(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        Tx tx = new Tx();
        try {
            connection = dataSource.getConnection();
            tx.open(connection);
            Optional<JdbcMeetingRepository.PublicationMeeting> locked =
                    JdbcMeetingRepository.findManifestPublicationForUpdate(
                            connection, request.meetingId());
            if (locked.isEmpty()) {
                return tx.rollback(new NotFound());
            }
            JdbcMeetingRepository.PublicationMeeting current = locked.orElseThrow();
            ConflictReason mismatch = compareExpected(current, request.expectedFacts());
            if (mismatch != null) {
                return tx.rollback(new Conflict(mismatch));
            }

            MeetingManifest candidate = request.manifest();
            if (!candidate.profile().id().equals(current.snapshot().profileId())
                    || candidate.profile().revision() != current.snapshot().profileRevision()) {
                return tx.rollback(new Conflict(ConflictReason.PROFILE_MISMATCH));
            }

            Optional<MeetingManifest> latest = findLatest(connection, request.meetingId());
            int targetRevision = candidate.manifestRevision();
            ExpectedPreviousRevision previous = targetRevision == 1
                    ? new ExpectedPreviousRevision.None()
                    : new ExpectedPreviousRevision.Present(targetRevision - 1);
            ManifestFactStore.AppendResult appended = new JdbcManifestFactStore(connection).append(
                    new ManifestFactStore.AppendRequest(candidate, previous));
            if (appended.status() == ManifestFactStore.AppendStatus.REPLAYED) {
                if (!isCompletionStateFor(current, candidate.status())) {
                    return tx.rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
                }
                return tx.rollback(new PublishReplayed(
                        targetRevision, current.snapshot().state(), current.manifestState()));
            }
            if (appended.status() == ManifestFactStore.AppendStatus.CONTENT_CONFLICT) {
                return tx.rollback(new Conflict(ConflictReason.CONTENT_CONFLICT));
            }
            if (appended.status() == ManifestFactStore.AppendStatus.VERSION_CONFLICT) {
                return tx.rollback(new Conflict(ConflictReason.REVISION_CONFLICT));
            }
            if (appended.status() != ManifestFactStore.AppendStatus.APPENDED) {
                return tx.rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
            }

            MeetingState nextMeetingState;
            ManifestStatus nextManifestState;
            if (latest.isEmpty()) {
                if (targetRevision != 1
                        || current.snapshot().state() != MeetingState.FINALIZING
                        || current.manifestState() != ManifestStatus.GENERATING) {
                    return tx.rollback(new Conflict(ConflictReason.INVALID_MANIFEST_STATE));
                }
                nextMeetingState = stateFor(candidate.status());
                nextManifestState = candidate.status();
            } else {
                if (!isCompletionStateFor(current, candidate.status())
                        || targetRevision != latest.orElseThrow().manifestRevision() + 1) {
                    return tx.rollback(new Conflict(ConflictReason.REVISION_CONFLICT));
                }
                nextMeetingState = current.snapshot().state();
                nextManifestState = current.manifestState();
            }
            Instant updatedAt = nextTimestamp(current.snapshot().updatedAt());
            if (!JdbcMeetingRepository.updateManifestPublication(
                    connection, current, nextMeetingState, nextManifestState, updatedAt)) {
                return tx.rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
            }
            return tx.commit(new PublishApplied(targetRevision, nextMeetingState, nextManifestState));
        } catch (JdbcMeetingRepository.PublicationSnapshotException malformed) {
            return tx.failureBeforeCommit(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
        } catch (JdbcManifestFactStore.StoredFactException storageFailure) {
            return tx.manifestFailurePublish(storageFailure);
        } catch (SQLException sqlFailure) {
            return tx.sqlFailurePublish(sqlFailure);
        } catch (RuntimeException runtimeFailure) {
            LOGGER.log(Level.WARNING, "Manifest publish 事务失败 exception={0}",
                    runtimeFailure.getClass().getSimpleName());
            return tx.runtimeFailurePublish();
        } finally {
            tx.finish(connection);
        }
    }

    private Optional<MeetingManifest> findLatest(Connection connection, String meetingId) {
        return new JdbcManifestFactStore(connection).findLatest(meetingId);
    }

    private Instant nextTimestamp(Instant current) {
        Instant value = Objects.requireNonNull(clock.instant(), "clock.instant")
                .truncatedTo(ChronoUnit.MICROS);
        if (value.isBefore(current)) {
            throw new IllegalStateException("publication clock moved backwards");
        }
        return value;
    }

    private static ConflictReason compareExpected(
            JdbcMeetingRepository.PublicationMeeting current,
            ExpectedMeetingFacts expected) {
        if (!current.snapshot().deploymentScope().equals(expected.deploymentScope())
                || !current.snapshot().subjectScope().equals(expected.subjectScope())
                || !current.snapshot().clientSessionId().equals(expected.clientSessionId())) {
            return ConflictReason.IDENTITY_MISMATCH;
        }
        if (!current.snapshot().profileId().equals(expected.profileId())
                || current.snapshot().profileRevision() != expected.profileRevision()) {
            return ConflictReason.PROFILE_MISMATCH;
        }
        if (!current.finalization().equals(expected.finalization())) {
            return ConflictReason.INPUT_MISMATCH;
        }
        return null;
    }

    private static boolean isCompletionStateFor(
            JdbcMeetingRepository.PublicationMeeting current,
            ManifestStatus candidateStatus) {
        return COMPLETION_MANIFEST_STATES.contains(current.manifestState())
                && current.manifestState() == candidateStatus
                && COMPLETION_STATES.contains(current.snapshot().state())
                && stateFor(candidateStatus) == current.snapshot().state();
    }

    private static MeetingState stateFor(ManifestStatus status) {
        return switch (status) {
            case READY -> MeetingState.READY;
            case READY_WITH_WARNINGS -> MeetingState.READY_WITH_WARNINGS;
            case FAILED -> MeetingState.FAILED;
            default -> throw new IllegalArgumentException("manifest status is not complete");
        };
    }

    private static final class Tx {
        private Connection connection;
        private boolean transactionStarted;
        private boolean commitStarted;
        private boolean commitCompleted;
        private boolean rollbackConfirmed;
        private boolean outcomeUnknown;

        void open(Connection value) throws SQLException {
            connection = Objects.requireNonNull(value, "connection");
            boolean previousAutoCommit = connection.getAutoCommit();
            if (!previousAutoCommit) {
                throw new IllegalStateException("publication connection must start in autoCommit");
            }
            connection.setAutoCommit(false);
            transactionStarted = true;
        }

        @SuppressWarnings("unchecked")
        <T> T rollback(T result) {
            if (!transactionStarted || connection == null) {
                return result;
            }
            if (!rollbackConfirmed && !commitStarted) {
                try {
                    connection.rollback();
                    rollbackConfirmed = true;
                } catch (SQLException | RuntimeException failure) {
                    outcomeUnknown = true;
                    LOGGER.log(Level.WARNING, "Manifest 发布事务回滚未确认");
                    // 回滚未获数据库确认时，不能把已追加的 revision 当作已撤销；
                    // BeginResult 与 PublishResult 都允许该固定不可用结果。
                    return (T) new Unavailable(UnavailableReason.OUTCOME_UNKNOWN);
                }
            }
            return result;
        }

        <T> T commit(T result) throws SQLException {
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE, "Manifest 发布事务已提交 result={0}", result.getClass().getSimpleName());
            return result;
        }

        <T> T failureBeforeCommit(T result) {
            if (!commitStarted) {
                return rollback(result);
            }
            return result;
        }

        BeginResult manifestFailure(JdbcManifestFactStore.StoredFactException failure) {
            return failure.kind() == JdbcManifestFactStore.FailureKind.CORRUPT_DATA
                    ? rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT))
                    : rollback(new Unavailable(UnavailableReason.STORAGE_FAILURE));
        }

        PublishResult manifestFailurePublish(JdbcManifestFactStore.StoredFactException failure) {
            return failure.kind() == JdbcManifestFactStore.FailureKind.CORRUPT_DATA
                    ? rollback(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT))
                    : rollback(new Unavailable(UnavailableReason.STORAGE_FAILURE));
        }

        BeginResult sqlFailure(SQLException failure) {
            if (commitStarted) {
                return new Unavailable(UnavailableReason.OUTCOME_UNKNOWN);
            }
            if (!transactionStarted) {
                return new Unavailable(UnavailableReason.STORAGE_FAILURE);
            }
            return rollback(new Unavailable(
                    JdbcReadLookupSupport.classify(failure)
                            == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE
                            ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                            : UnavailableReason.STORAGE_FAILURE));
        }

        PublishResult sqlFailurePublish(SQLException failure) {
            if (commitStarted) {
                return new Unavailable(UnavailableReason.OUTCOME_UNKNOWN);
            }
            if (!transactionStarted) {
                return new Unavailable(UnavailableReason.STORAGE_FAILURE);
            }
            return rollback(new Unavailable(
                    JdbcReadLookupSupport.classify(failure)
                            == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE
                            ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                            : UnavailableReason.STORAGE_FAILURE));
        }

        BeginResult runtimeFailure() {
            return commitStarted
                    ? new Unavailable(UnavailableReason.OUTCOME_UNKNOWN)
                    : rollback(new Unavailable(UnavailableReason.STORAGE_FAILURE));
        }

        PublishResult runtimeFailurePublish() {
            return commitStarted
                    ? new Unavailable(UnavailableReason.OUTCOME_UNKNOWN)
                    : rollback(new Unavailable(UnavailableReason.STORAGE_FAILURE));
        }

        void finish(Connection ignored) {
            if (connection == null) {
                return;
            }
            if (commitCompleted || rollbackConfirmed) {
                try {
                    if (!connection.isClosed()) {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException | RuntimeException failure) {
                    LOGGER.log(Level.WARNING, "Manifest 发布事务恢复连接状态失败");
                }
            } else {
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException failure) {
                    LOGGER.log(Level.WARNING, "Manifest 发布事务废弃连接失败");
                }
            }
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                LOGGER.log(Level.WARNING, "Manifest 发布事务关闭连接失败");
            }
        }
    }
}
