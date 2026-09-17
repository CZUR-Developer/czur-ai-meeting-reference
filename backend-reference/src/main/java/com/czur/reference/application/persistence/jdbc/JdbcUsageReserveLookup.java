package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从 {@code usage_operation} 只读查询首次 reserve 结果的 JDBC 适配器。
 *
 * <p>数据库查询的 operation scope 固定为 {@link UsageOperationScopes#RESERVE}。
 * 适配器先比较未解码行的摘要，再调用既有 {@link UsageOperationResultJson} 严格
 * 解码；异摘要永远优先返回冲突，即使正文的业务格式已经损坏。</p>
 *
 * <p>校验只针对首次保存的 {@code usage_operation.result_body} 与其原始
 * {@code reservation_id} 引用，不读取当前 Reservation 行。会议后续状态变化
 * 因而不会被误写回或冒充首次 reserve 快照。本类只执行 SELECT。</p>
 */
public final class JdbcUsageReserveLookup implements UsageReserveLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcUsageReserveLookup.class.getName());

    private final DataSource dataSource;

    public JdbcUsageReserveLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Result lookup(Request request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Optional<JdbcUsageReservationStore.UsageOperationRow> stored =
                    JdbcUsageReservationStore.findOperation(
                            connection,
                            request.deploymentScope(),
                            request.subjectScope(),
                            UsageOperationScopes.RESERVE,
                            request.reserveIdempotencyKey());
            if (stored.isEmpty()) {
                return logged(new NotFound());
            }
            JdbcUsageReservationStore.UsageOperationRow row = stored.get();
            if (!JdbcReadLookupSupport.sameDigest(
                    row.requestDigest(), request.reserveRequestDigest())) {
                return logged(new Conflict());
            }

            final UsageOperationResult result;
            try {
                result = UsageOperationResultJson.decode(
                        row.resultBody(),
                        request.deploymentScope(),
                        request.subjectScope(),
                        UsageOperationScopes.RESERVE,
                        request.reserveIdempotencyKey(),
                        request.reserveRequestDigest());
            } catch (RuntimeException invalidStoredResult) {
                // 解码器只处理已经持久化的纯数据；任一领域/JSON 运行时拒绝都表示
                // 该首次事实不可解释，不能误报成数据库暂时不可用。
                return logged(new Inconsistent(
                        InconsistencyReason.STORED_RESULT_INVALID));
            }

            if (!row.reservationId().equals(result.reservation().reservationId())) {
                return logged(new Inconsistent(
                        InconsistencyReason.RESERVATION_REFERENCE_MISMATCH));
            }
            if (!satisfiesOwnedReserveInvariants(result)) {
                return logged(new Inconsistent(
                        InconsistencyReason.OWNED_RESERVE_INVARIANT_VIOLATION));
            }
            return logged(new Replayed(result));
        } catch (JdbcUsageReservationStore.CorruptUsageDataException invalidStoredRow) {
            return logged(new Inconsistent(InconsistencyReason.STORED_RESULT_INVALID));
        } catch (SQLException failure) {
            return fromSqlFailure(failure);
        } catch (RuntimeException failure) {
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection);
        }
    }

    /** 与 owned 创建工作单元保持一致，但只校验首次快照，不读取当前行。 */
    private static boolean satisfiesOwnedReserveInvariants(UsageOperationResult result) {
        if (!result.supportsOwnedMeetingCreation()
                || !UsageOperationScopes.RESERVE.equals(result.operationScope())) {
            return false;
        }
        UsageReservation reservation = result.reservation();
        if (reservation.state() != UsageReservation.State.RESERVED
                || reservation.isBound()
                || reservation.version() != 0) {
            return false;
        }
        AuthorizedProfileSnapshot profile = result.requireAuthorizedProfileSnapshot();
        return profile.grantedCapabilities().containsAll(profile.requestedCapabilities())
                && reservation.reservedUsage().unit() == UsageUnit.MEETING_DURATION_MS
                && reservation.reservedUsage().amount() <= profile.maxDurationMs();
    }

    private static Result fromSqlFailure(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(new Inconsistent(InconsistencyReason.STORED_RESULT_INVALID), failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        UnavailableReason reason = retryable
                ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                : UnavailableReason.STORAGE_FAILURE;
        return logged(new Unavailable(reason, retryable), failure);
    }

    private static Result logged(Result result) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "用量预占首次结果只读查询完成 status={0}", result.status());
        return result;
    }

    private static Result logged(Result result, SQLException failure) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "用量预占首次结果只读查询完成 status={0} sqlState={1}",
                new Object[]{result.status(), JdbcReadLookupSupport.safeSqlState(failure)});
        return result;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "用量预占首次结果只读查询关闭连接失败");
        }
    }
}
