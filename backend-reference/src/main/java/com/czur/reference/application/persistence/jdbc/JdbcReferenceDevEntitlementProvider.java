package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.LeaseAwareCancelReservationRequest;
import com.czur.reference.application.port.entitlement.LeaseAwareReservationCanceller;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReservationTransitionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL reference-dev 权益 Provider。
 *
 * <p>{@link #authorizeStart(AuthorizeStartRequest)} 直接委托纯 Java 策略，绝不
 * 获取 JDBC 连接。reserve、commit 和 cancel 才访问 V003 的 Reservation 与
 * usage operation 表，并在完整的 deployment + subject + operation + key
 * 四元组内提供持久幂等。</p>
 *
 * <p>首次 reserve 把候选 Reservation 和稳定 operation 结果放在同一事务。
 * 如果 operation 唯一键已有并发赢家，当前事务会完整回滚候选 Reservation，
 * 然后重新读取赢家；不会遗留孤儿预占。commit/cancel 在范围内锁行后第二次
 * 检查 operation，保证等待并发事务后优先稳定重放，而不是把首次成功误报成
 * 终态冲突。</p>
 *
 * <p>SQL/连接/提交结果不确定统一映射为可重试 PROVIDER_UNAVAILABLE，绝不
 * 伪装成功。损坏 JSON、非精确 NUMERIC 或违反领域状态的数据库行映射为不可
 * 重试 INTERNAL。日志只有固定操作名、稳定原因码和 SQLState，不记录主体、
 * Reservation、幂等键、摘要、结果正文或数据库地址。</p>
 */
public final class JdbcReferenceDevEntitlementProvider
        implements EntitlementProvider, LeaseAwareReservationCanceller {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcReferenceDevEntitlementProvider.class.getName());

    private final DataSource dataSource;
    private final ReferenceDevEntitlementPolicy policy;
    private final Clock clock;

    public JdbcReferenceDevEntitlementProvider(
            DataSource dataSource,
            ReferenceDevEntitlementPolicy policy,
            Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        LOGGER.log(Level.INFO, "JDBC reference-dev 权益 Provider 已创建");
    }

    /** 纯策略授权；本方法不得调用 {@link DataSource#getConnection()}。 */
    @Override
    public EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request) {
        return policy.authorizeStart(request);
    }

    @Override
    public EntitlementProviderResult<UsageOperationResult> reserveUsage(
            ReserveUsageRequest request) {
        if (request == null) {
            return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                    "RESERVE_REQUEST_MISSING");
        }
        OperationIdentity identity = OperationIdentity.reserve(request);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<JdbcUsageReservationStore.UsageOperationRow> existing =
                        findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                EntitlementProviderResult<UsageReservation> prepared =
                        policy.prepareReservation(request);
                if (!prepared.isSuccess()) {
                    connection.rollback();
                    return EntitlementProviderResult.failure(prepared.errorOrThrow());
                }
                UsageOperationResult candidate = identity.reserveResult(
                        prepared.valueOrThrow(), request.grant());
                JdbcUsageReservationStore.insertReservation(connection, candidate.reservation());
                if (!JdbcUsageReservationStore.insertOperation(
                        connection, candidate, clock.instant())) {
                    return rollbackAndReadWinner(connection, identity);
                }
                connection.commit();
                LOGGER.log(Level.FINE, "JDBC 权益操作首次完成 operation=RESERVE");
                return EntitlementProviderResult.success(candidate);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                return corrupted("RESERVE_CORRUPT_DATA");
            } catch (IllegalArgumentException invalidPersistedData) {
                rollbackQuietly(connection);
                return corrupted("RESERVE_INVALID_PERSISTED_RESULT");
            } catch (SQLException sqlFailure) {
                rollbackQuietly(connection);
                return unavailable("RESERVE", sqlFailure);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException connectionFailure) {
            return unavailable("RESERVE_CONNECTION", connectionFailure);
        }
    }

    @Override
    public EntitlementProviderResult<UsageOperationResult> commitUsage(
            CommitUsageRequest request) {
        if (request == null) {
            return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                    "COMMIT_REQUEST_MISSING");
        }
        OperationIdentity identity = OperationIdentity.commit(request);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<JdbcUsageReservationStore.UsageOperationRow> existing =
                        findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                Optional<UsageReservation> locked = JdbcUsageReservationStore.lockReservation(
                        connection, request.deploymentScope(), request.subjectScope(),
                        request.reservationId());
                if (locked.isEmpty()) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_NOT_FOUND, false,
                            "COMMIT_RESERVATION_NOT_FOUND");
                }
                // 锁等待期间其它事务可能已经提交相同 operation；必须再次读取。
                existing = findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                UsageReservation committed;
                try {
                    committed = locked.get().commit(
                            request.actual(), clock.instant(), request.expectedVersion());
                } catch (UsageReservationTransitionException transition) {
                    connection.rollback();
                    return transitionFailure(transition, "COMMIT");
                } catch (IllegalArgumentException invalidRequest) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                            "COMMIT_USAGE_INVALID");
                }
                if (!JdbcUsageReservationStore.commit(connection, locked.get(), committed)) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.VERSION_CONFLICT, false,
                            "COMMIT_CAS_REJECTED");
                }
                UsageOperationResult candidate = identity.terminalResult(committed);
                if (!JdbcUsageReservationStore.insertOperation(
                        connection, candidate, clock.instant())) {
                    return rollbackAndReadWinner(connection, identity);
                }
                connection.commit();
                LOGGER.log(Level.FINE, "JDBC 权益操作首次完成 operation=COMMIT");
                return EntitlementProviderResult.success(candidate);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                return corrupted("COMMIT_CORRUPT_DATA");
            } catch (IllegalArgumentException invalidPersistedData) {
                rollbackQuietly(connection);
                return corrupted("COMMIT_INVALID_PERSISTED_RESULT");
            } catch (SQLException sqlFailure) {
                rollbackQuietly(connection);
                return unavailable("COMMIT", sqlFailure);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException connectionFailure) {
            return unavailable("COMMIT_CONNECTION", connectionFailure);
        }
    }

    @Override
    public EntitlementProviderResult<UsageOperationResult> cancelReservation(
            CancelReservationRequest request) {
        if (request == null) {
            return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                    "CANCEL_REQUEST_MISSING");
        }
        OperationIdentity identity = OperationIdentity.cancel(request);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<JdbcUsageReservationStore.UsageOperationRow> existing =
                        findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                Optional<UsageReservation> locked = JdbcUsageReservationStore.lockReservation(
                        connection, request.deploymentScope(), request.subjectScope(),
                        request.reservationId());
                if (locked.isEmpty()) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_NOT_FOUND, false,
                            "CANCEL_RESERVATION_NOT_FOUND");
                }
                existing = findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }
                if (!locked.get().expectedMeetingId().equals(request.expectedMeetingId())) {
                    connection.rollback();
                    return failure(
                            EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT, false,
                            "CANCEL_EXPECTED_MEETING_MISMATCH");
                }

                UsageReservation cancelled;
                try {
                    cancelled = locked.get().cancel(
                            request.reasonCode(), clock.instant(), request.expectedVersion());
                } catch (UsageReservationTransitionException transition) {
                    connection.rollback();
                    return transitionFailure(transition, "CANCEL");
                } catch (IllegalArgumentException invalidRequest) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                            "CANCEL_REQUEST_INVALID");
                }
                if (!JdbcUsageReservationStore.cancel(connection, locked.get(), cancelled)) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.VERSION_CONFLICT, false,
                            "CANCEL_CAS_REJECTED");
                }
                UsageOperationResult candidate = identity.terminalResult(cancelled);
                if (!JdbcUsageReservationStore.insertOperation(
                        connection, candidate, clock.instant())) {
                    return rollbackAndReadWinner(connection, identity);
                }
                connection.commit();
                LOGGER.log(Level.FINE, "JDBC 权益操作首次完成 operation=CANCEL");
                return EntitlementProviderResult.success(candidate);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                return corrupted("CANCEL_CORRUPT_DATA");
            } catch (IllegalArgumentException invalidPersistedData) {
                rollbackQuietly(connection);
                return corrupted("CANCEL_INVALID_PERSISTED_RESULT");
            } catch (SQLException sqlFailure) {
                rollbackQuietly(connection);
                return unavailable("CANCEL", sqlFailure);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException connectionFailure) {
            return unavailable("CANCEL_CONNECTION", connectionFailure);
        }
    }

    /**
     * 使用过期 Reservation Worker 的 lease 完成清理取消。
     *
     * <p>普通 cancel 路径故意不能替代此方法：清理请求必须在 Reservation
     * 行锁事务内同时校验当前 lease 栅栏，并把取消结果写入同一事务的
     * {@code usage_operation}。幂等结果先查且相同摘要可重放，因此 Worker
     * 在成功提交后即使 lease 已过期，也不会因为重试而重新修改 Reservation。</p>
     */
    @Override
    public EntitlementProviderResult<UsageOperationResult> cancelReservationWithLease(
            LeaseAwareCancelReservationRequest request) {
        if (request == null) {
            return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                    "CANCEL_LEASE_REQUEST_MISSING");
        }
        CancelReservationRequest cancel = request.cancel();
        ExpiredReservationStore.LeaseToken lease = request.lease();
        OperationIdentity identity = OperationIdentity.cancel(cancel);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<JdbcUsageReservationStore.UsageOperationRow> existing =
                        findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                Optional<JdbcUsageReservationStore.LockedReservation> lockedOptional =
                        JdbcUsageReservationStore.lockReservationForOwnedCreation(
                                connection,
                                cancel.deploymentScope(),
                                cancel.subjectScope(),
                                cancel.reservationId());
                if (lockedOptional.isEmpty()) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_NOT_FOUND, false,
                            "CANCEL_LEASE_RESERVATION_NOT_FOUND");
                }

                // 行锁等待期间同一幂等键可能已经由另一个 Worker 提交；优先稳定重放。
                existing = findOperation(connection, identity);
                if (existing.isPresent()) {
                    EntitlementProviderResult<UsageOperationResult> replay =
                            replayOrConflict(existing.get(), identity);
                    connection.rollback();
                    return replay;
                }

                JdbcUsageReservationStore.LockedReservation locked = lockedOptional.get();
                UsageReservation current = locked.reservation();
                if (!current.expectedMeetingId().equals(cancel.expectedMeetingId())) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT,
                            false, "CANCEL_LEASE_EXPECTED_MEETING_MISMATCH");
                }
                if (current.state() != UsageReservation.State.RESERVED
                        || current.isBound()) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT,
                            false, "CANCEL_LEASE_TERMINAL_OR_BOUND");
                }

                Instant cancelledAt = Objects.requireNonNull(clock.instant(), "clock.instant()");
                if (!matchesLease(locked, lease, cancelledAt)) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT,
                            false, "CANCEL_LEASE_FENCE_REJECTED");
                }

                Instant persistedCancelledAt = persistedOperationTime(cancelledAt);
                UsageReservation cancelled;
                try {
                    // PostgreSQL timestamptz 只保留微秒；先用原始时刻校验 lease，
                    // 再规范持久化时刻，避免 +1ns 被驱动舍入回有效窗口。
                    cancelled = current.cancel(
                            cancel.reasonCode(), persistedCancelledAt, cancel.expectedVersion());
                    if (!JdbcUsageReservationStore.cancelWithLease(
                            connection, current, cancelled, lease, cancelledAt)) {
                        connection.rollback();
                        return failure(EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT,
                                false, "CANCEL_LEASE_CAS_REJECTED");
                    }
                } catch (UsageReservationTransitionException transition) {
                    connection.rollback();
                    return transitionFailure(transition, "CANCEL_LEASE");
                } catch (IllegalArgumentException invalidRequest) {
                    connection.rollback();
                    return failure(EntitlementProviderError.Code.INVALID_REQUEST, false,
                            "CANCEL_LEASE_REQUEST_INVALID");
                }

                UsageOperationResult candidate = identity.terminalResult(cancelled);
                if (!JdbcUsageReservationStore.insertOperation(
                        connection, candidate, persistedOperationTime(cancelledAt))) {
                    return rollbackAndReadWinner(connection, identity);
                }
                connection.commit();
                LOGGER.log(Level.FINE,
                        "JDBC 权益清理取消首次完成 operation=CANCEL_LEASE");
                return EntitlementProviderResult.success(candidate);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                return corrupted("CANCEL_LEASE_CORRUPT_DATA");
            } catch (IllegalArgumentException invalidPersistedData) {
                rollbackQuietly(connection);
                return corrupted("CANCEL_LEASE_INVALID_PERSISTED_RESULT");
            } catch (SQLException sqlFailure) {
                rollbackQuietly(connection);
                return unavailable("CANCEL_LEASE", sqlFailure);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException connectionFailure) {
            return unavailable("CANCEL_LEASE_CONNECTION", connectionFailure);
        }
    }

    private static boolean matchesLease(
            JdbcUsageReservationStore.LockedReservation current,
            ExpiredReservationStore.LeaseToken requested,
            Instant cancelledAt) {
        return requested.owner().equals(current.leaseOwner())
                && requested.token().equals(current.leaseToken())
                && requested.leaseVersion() == current.leaseVersion()
                && requested.expiresAt().equals(current.leaseUntil())
                && current.leaseUntil() != null
                && cancelledAt.isBefore(current.leaseUntil());
    }

    private static Instant persistedOperationTime(Instant operationTime) {
        return operationTime.truncatedTo(ChronoUnit.MICROS);
    }

    private static Optional<JdbcUsageReservationStore.UsageOperationRow> findOperation(
            Connection connection, OperationIdentity identity) throws SQLException {
        return JdbcUsageReservationStore.findOperation(
                connection,
                identity.deploymentScope(),
                identity.subjectScope(),
                identity.operationScope(),
                identity.idempotencyKey());
    }

    private static EntitlementProviderResult<UsageOperationResult> rollbackAndReadWinner(
            Connection connection, OperationIdentity identity) throws SQLException {
        connection.rollback();
        Optional<JdbcUsageReservationStore.UsageOperationRow> winner =
                findOperation(connection, identity);
        if (winner.isEmpty()) {
            connection.rollback();
            return failure(EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true,
                    "IDEMPOTENCY_WINNER_NOT_VISIBLE");
        }
        EntitlementProviderResult<UsageOperationResult> result =
                replayOrConflict(winner.get(), identity);
        connection.rollback();
        return result;
    }

    private static EntitlementProviderResult<UsageOperationResult> replayOrConflict(
            JdbcUsageReservationStore.UsageOperationRow row, OperationIdentity identity) {
        if (!row.requestDigest().equals(identity.requestDigest())) {
            return failure(EntitlementProviderError.Code.IDEMPOTENCY_CONFLICT, false,
                    "IDEMPOTENCY_DIGEST_CONFLICT");
        }
        try {
            UsageOperationResult result = UsageOperationResultJson.decode(
                    row.resultBody(),
                    identity.deploymentScope(),
                    identity.subjectScope(),
                    identity.operationScope(),
                    identity.idempotencyKey(),
                    identity.requestDigest());
            LOGGER.log(Level.FINE, "JDBC 权益操作稳定重放 operation={0}",
                    identity.logOperation());
            return EntitlementProviderResult.success(result);
        } catch (IllegalArgumentException invalidJson) {
            throw new JdbcUsageReservationStore.CorruptUsageDataException(
                    "usage operation result is invalid", invalidJson);
        }
    }

    private static EntitlementProviderResult<UsageOperationResult> transitionFailure(
            UsageReservationTransitionException failure, String operation) {
        EntitlementProviderError.Code code = switch (failure.reasonCode()) {
            case VERSION_CONFLICT -> EntitlementProviderError.Code.VERSION_CONFLICT;
            case RESERVATION_EXPIRED -> EntitlementProviderError.Code.RESERVATION_EXPIRED;
            case EXPECTED_MEETING_MISMATCH, ALREADY_BOUND, BIND_REQUIRED, TERMINAL_STATE ->
                    EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT;
        };
        return failure(code, false, operation + "_" + failure.reasonCode().name());
    }

    private static EntitlementProviderResult<UsageOperationResult> corrupted(String reasonCode) {
        LOGGER.log(Level.SEVERE, "JDBC 权益持久事实损坏 reason={0}", reasonCode);
        return EntitlementProviderResult.failure(new EntitlementProviderError(
                EntitlementProviderError.Code.INTERNAL, false));
    }

    private static <T> EntitlementProviderResult<T> unavailable(
            String operation, SQLException failure) {
        String sqlState = failure.getSQLState();
        LOGGER.log(Level.WARNING,
                "JDBC reference-dev 权益数据库不可用 operation={0} sqlState={1}",
                new Object[]{operation, sqlState == null ? "UNKNOWN" : sqlState});
        return EntitlementProviderResult.failure(new EntitlementProviderError(
                EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true));
    }

    private static <T> EntitlementProviderResult<T> failure(
            EntitlementProviderError.Code code, boolean retryable, String reasonCode) {
        LOGGER.log(Level.FINE, "JDBC reference-dev 权益请求拒绝 reason={0}", reasonCode);
        return EntitlementProviderResult.failure(new EntitlementProviderError(code, retryable));
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            LOGGER.log(Level.WARNING, "JDBC reference-dev 权益事务回滚失败");
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit)
            throws SQLException {
        if (!connection.isClosed()) {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** 四元组幂等身份；只在内存调用栈中存在，不写日志或异常。 */
    private record OperationIdentity(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String logOperation) {

        static OperationIdentity reserve(ReserveUsageRequest request) {
            return new OperationIdentity(
                    request.deploymentScope(), request.subjectScope(), request.operationScope(),
                    request.idempotencyKey(), request.requestDigest(), "RESERVE");
        }

        static OperationIdentity commit(CommitUsageRequest request) {
            return new OperationIdentity(
                    request.deploymentScope(), request.subjectScope(), request.operationScope(),
                    request.idempotencyKey(), request.requestDigest(), "COMMIT");
        }

        static OperationIdentity cancel(CancelReservationRequest request) {
            return new OperationIdentity(
                    request.deploymentScope(), request.subjectScope(), request.operationScope(),
                    request.idempotencyKey(), request.requestDigest(), "CANCEL");
        }

        UsageOperationResult reserveResult(
                UsageReservation reservation, MeetingGrant firstGrant) {
            return UsageOperationResult.reserve(
                    deploymentScope,
                    subjectScope,
                    idempotencyKey,
                    requestDigest,
                    firstGrant.expiresAt(),
                    AuthorizedProfileSnapshot.from(firstGrant),
                    reservation);
        }

        UsageOperationResult terminalResult(UsageReservation reservation) {
            return UsageOperationResult.terminal(
                    deploymentScope,
                    subjectScope,
                    operationScope,
                    idempotencyKey,
                    requestDigest,
                    reservation);
        }
    }
}
