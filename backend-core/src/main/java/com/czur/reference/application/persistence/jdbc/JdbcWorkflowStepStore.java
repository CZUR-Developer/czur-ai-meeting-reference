package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL {@link WorkflowStepStore} 参考实现。
 *
 * <p>公共方法自行取得连接并管理短事务；包可见的 connection-scoped 原语要求
 * 调用方已经关闭 auto-commit，并且绝不提交、回滚、关闭连接或另取第二个连接。
 * 该边界允许后续工作单元把 checkpoint 与其它本地事实放入同一个事务。</p>
 *
 * <p>V010 将注册 Definition 的 {@code initial_available_at} 与可变调度字段
 * {@code next_attempt_at} 分开保存。因此步骤被领取、失败重试或进入终态后，注册重放
 * 仍可按完整原始 Definition 裁决。每次 lease 操作同时比较 owner、数据库 token、
 * version、expiresAt 和 claimedAt；旧消费者不能覆盖新赢家。</p>
 *
 * <p>PostgreSQL {@code TIMESTAMPTZ} 只能无损保存到微秒。所有进入持久化比较的
 * {@link Instant} 都必须已经是微秒精度，本实现不会静默四舍五入。日志只记录固定
 * 分类、状态和数量，不输出 Key、checkpoint、错误正文、SQL 或 token。</p>
 */
public final class JdbcWorkflowStepStore implements WorkflowStepStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcWorkflowStepStore.class.getName());

    private static final String FIND_SQL = """
            SELECT meeting_id, step_type, step_key, checkpoint_type, canonical_payload,
                   content_digest, initial_available_at, status, attempt_count,
                   next_attempt_at, lease_owner, lease_until, lease_token, lease_version,
                   error_code, error_message, updated_at
              FROM workflow_step
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
            """;
    private static final String FIND_FOR_UPDATE_SQL = FIND_SQL + " FOR UPDATE";
    private static final String INSERT_SQL = """
            INSERT INTO workflow_step(
                meeting_id, step_type, step_key, checkpoint_type, canonical_payload,
                content_digest, initial_available_at, status, attempt_count,
                next_attempt_at, lease_version, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
            ON CONFLICT (meeting_id, step_type, step_key) DO NOTHING
            """;
    private static final String CLAIM_SELECT_SQL = """
            SELECT meeting_id, step_type, step_key, checkpoint_type, canonical_payload,
                   content_digest, initial_available_at, status, attempt_count,
                   next_attempt_at, lease_owner, lease_until, lease_token, lease_version,
                   error_code, error_message, updated_at
              FROM workflow_step
             WHERE step_type = ANY (?::text[])
               AND ((status = 'PENDING' AND next_attempt_at <= ?)
                    OR (status = 'LEASED' AND lease_until <= ?))
             ORDER BY CASE WHEN status = 'PENDING' THEN next_attempt_at ELSE lease_until END,
                      meeting_id, step_type, step_key
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;
    private static final String CLAIM_UPDATE_SQL = """
            UPDATE workflow_step
               SET status = 'LEASED', next_attempt_at = NULL, lease_owner = ?,
                   lease_until = ?, lease_token = ?, lease_version = ?, updated_at = ?
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
            """;
    private static final String RENEW_SQL = """
            UPDATE workflow_step
               SET lease_until = ?, lease_token = ?, lease_version = ?, updated_at = ?
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
               AND status = 'LEASED' AND lease_owner = ? AND lease_token = ?
               AND lease_version = ? AND lease_until = ? AND updated_at = ?
               AND lease_until > ? AND ? > lease_until
            """;
    private static final String PERSIST_LEASE_MARKER_SQL = """
            UPDATE workflow_step
               SET error_code = ?, error_message = ?
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
               AND status = 'LEASED' AND lease_owner = ? AND lease_token = ?
               AND lease_version = ? AND lease_until = ? AND updated_at = ?
               AND error_code IS NOT DISTINCT FROM CAST(? AS text)
               AND error_message IS NOT DISTINCT FROM CAST(? AS text)
               AND updated_at <= ? AND lease_until > ?
            """;
    private static final String COMPLETE_SQL = """
            UPDATE workflow_step
               SET status = 'COMPLETED', next_attempt_at = NULL, lease_owner = NULL,
                   lease_until = NULL, lease_token = NULL, error_code = NULL,
                   error_message = NULL, updated_at = ?
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
               AND status = 'LEASED' AND lease_owner = ? AND lease_token = ?
               AND lease_version = ? AND lease_until = ? AND updated_at = ?
               AND lease_until > ?
            """;
    private static final String FAIL_SQL = """
            UPDATE workflow_step
               SET status = CASE WHEN attempt_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                   attempt_count = attempt_count + 1,
                   next_attempt_at = CASE WHEN attempt_count + 1 >= ?
                                          THEN NULL ELSE ?::timestamptz END,
                   lease_owner = NULL, lease_until = NULL, lease_token = NULL,
                   error_code = ?, error_message = ?, updated_at = ?
             WHERE meeting_id = ? AND step_type = ? AND step_key = ?
               AND status = 'LEASED' AND lease_owner = ? AND lease_token = ?
               AND lease_version = ? AND lease_until = ? AND updated_at = ?
               AND lease_until > ?
            """;

    private final DataSource dataSource;

    /** 创建共享指定连接池的工作流步骤适配器。 */
    public JdbcWorkflowStepStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        LOGGER.log(Level.INFO, "JDBC 工作流步骤 Store 已创建");
    }

    @Override
    public RegisterResult register(WorkflowStep candidate) {
        validateInitial(candidate);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                RegisterResult result = register(connection, candidate);
                connection.commit();
                LOGGER.log(result.status() == RegisterStatus.CONFLICT
                                ? Level.WARNING : Level.FINE,
                        "JDBC 工作流步骤注册裁决 status={0}", result.status());
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("注册工作流步骤失败");
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建工作流步骤数据库连接失败");
        }
    }

    @Override
    public Optional<WorkflowStep> find(WorkflowStep.Key key) {
        Objects.requireNonNull(key, "key");
        try (Connection connection = dataSource.getConnection()) {
            return queryStored(connection, key, false).map(StoredStep::step);
        } catch (SQLException failure) {
            throw sanitized("查询工作流步骤失败");
        }
    }

    @Override
    public List<ClaimedStep> claim(ClaimRequest request) {
        validateClaimRequest(request);
        if (request.allowedStepTypes().isEmpty()) {
            return List.of();
        }
        // 在取得连接前验证持久精度和溢出；连接级原语仍会按同一请求自行计算，
        // 不接受调用者传入可能与 leaseDuration 不一致的截止时间。
        leaseExpiry(request.now(), request.leaseDuration());
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ClaimedStep> claimed = claim(connection, request);
                connection.commit();
                if (!claimed.isEmpty()) {
                    LOGGER.log(Level.FINE,
                            "JDBC 工作流步骤领取完成 count={0}", claimed.size());
                }
                return claimed;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("领取工作流步骤失败");
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建工作流步骤数据库连接失败");
        }
    }

    @Override
    public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
        validateLease(lease);
        requirePersistentTimestamp("renewedAt", renewedAt);
        requirePositive(leaseDuration);
        renewalExpiry(lease, renewedAt, leaseDuration);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                RenewResult result = renew(connection, lease, renewedAt, leaseDuration);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("续租工作流步骤失败");
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建工作流步骤数据库连接失败");
        }
    }

    @Override
    public PersistLeaseMarkerResult persistLeaseMarker(
            PersistLeaseMarkerRequest request) {
        validateMarkerRequest(request);
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;
            PersistLeaseMarkerResult result = persistLeaseMarker(connection, request);
            if (result.status() != PersistLeaseMarkerStatus.PERSISTED) {
                // 没有写入时用 rollback 结束锁读事务；无需把只读事务的 commit
                // acknowledgement 故障误报为 marker 写入结果未知。
                connection.rollback();
                transactionStarted = false;
                return result;
            }
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE, "JDBC 工作流终态操作 marker 已持久化");
            return result;
        } catch (SQLException | RuntimeException failure) {
            if (commitStarted) {
                LOGGER.log(Level.WARNING,
                        "JDBC 工作流终态操作 marker 提交结果未知");
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.OUTCOME_UNKNOWN, null);
            }
            if (transactionStarted) {
                rollbackQuietly(connection);
            }
            LOGGER.log(Level.WARNING,
                    "JDBC 工作流终态操作 marker 暂不可写");
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.STORAGE_UNAVAILABLE, null);
        } finally {
            if (commitStarted && !commitCompleted) {
                abortQuietly(connection);
            } else {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }
            closeQuietly(connection);
        }
    }

    @Override
    public PersistLeaseMarkerResult recoverLeaseMarker(
            PersistLeaseMarkerRequest request) {
        validateMarkerRequest(request);
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean previousReadOnly = false;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean transactionStarted = false;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            previousReadOnly = connection.isReadOnly();
            previousIsolation = connection.getTransactionIsolation();
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            transactionStarted = true;

            StoredStep current = queryStored(
                    connection, request.lease().key(), false).orElse(null);
            PersistLeaseMarkerResult result = recoverLeaseMarker(current, request);
            connection.rollback();
            transactionStarted = false;
            return result;
        } catch (SQLException | RuntimeException failure) {
            if (transactionStarted) {
                rollbackQuietly(connection);
            }
            LOGGER.log(Level.WARNING,
                    "JDBC 工作流终态操作 marker 只读恢复暂不可用");
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.STORAGE_UNAVAILABLE, null);
        } finally {
            restoreReadOnlyQuietly(
                    connection,
                    previousAutoCommit,
                    previousReadOnly,
                    previousIsolation);
            closeQuietly(connection);
        }
    }

    @Override
    public CompleteResult complete(LeaseToken lease, Instant completedAt) {
        validateLease(lease);
        requirePersistentTimestamp("completedAt", completedAt);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                CompleteResult result = complete(connection, lease, completedAt);
                connection.commit();
                if (result.status() == CompleteStatus.COMPLETED) {
                    LOGGER.log(Level.INFO, "JDBC 工作流步骤完成 status=COMPLETED");
                }
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("完成工作流步骤失败");
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建工作流步骤数据库连接失败");
        }
    }

    @Override
    public FailResult fail(
            LeaseToken lease,
            Instant failedAt,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            int maxAttempts) {
        validateLease(lease);
        requirePersistentTimestamp("failedAt", failedAt);
        requirePersistentTimestamp("nextAttemptAt", nextAttemptAt);
        requireText("errorCode", errorCode);
        requireText("errorSummary", errorSummary);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (nextAttemptAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("nextAttemptAt must not be before failedAt");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FailResult result = fail(connection, lease, failedAt, nextAttemptAt,
                        errorCode, errorSummary, maxAttempts);
                connection.commit();
                LOGGER.log(result.status() == FailStatus.DEAD ? Level.WARNING : Level.FINE,
                        "JDBC 工作流步骤失败裁决 status={0}", result.status());
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("记录工作流步骤失败");
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建工作流步骤数据库连接失败");
        }
    }

    /**
     * 在调用方事务连接上注册初始 checkpoint；不提交、回滚或关闭连接。
     * 注册 Definition 使用独立的 initial_available_at，与当前调度时间无关。
     */
    static RegisterResult register(Connection connection, WorkflowStep candidate)
            throws SQLException {
        requireTransactionalConnection(connection);
        validateInitial(candidate);
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            WorkflowStep.Key key = candidate.key();
            statement.setString(1, key.meetingId());
            statement.setString(2, key.stepType());
            statement.setString(3, key.stepKey());
            statement.setString(4, candidate.checkpointType());
            statement.setString(5, candidate.canonicalPayload());
            statement.setString(6, candidate.contentDigest());
            statement.setTimestamp(7, Timestamp.from(candidate.nextAttemptAt()));
            statement.setTimestamp(8, Timestamp.from(candidate.nextAttemptAt()));
            statement.setTimestamp(9, Timestamp.from(candidate.updatedAt()));
            inserted = statement.executeUpdate();
        }
        StoredStep current = queryStored(connection, candidate.key(), false)
                .orElseThrow(() -> new IllegalStateException(
                        "工作流步骤注册后不可见"));
        if (inserted == 1) {
            return new RegisterResult(RegisterStatus.APPLIED, current.step());
        }
        boolean same = current.sameDefinition(candidate);
        return new RegisterResult(
                same ? RegisterStatus.REPLAYED : RegisterStatus.CONFLICT,
                current.step());
    }

    /** 在调用方事务连接上读取步骤；不提交、回滚或关闭连接。 */
    static Optional<WorkflowStep> find(Connection connection, WorkflowStep.Key key)
            throws SQLException {
        requireTransactionalConnection(connection);
        Objects.requireNonNull(key, "key");
        return queryStored(connection, key, false).map(StoredStep::step);
    }

    /**
     * 只读核对已经持久化的 Workflow Definition 是否与候选完全一致。
     *
     * <p>该原语不会执行 INSERT，也不会改变 lease、状态或调度时间。Admission 在
     * commit acknowledgement unknown 后只能用它恢复：即使步骤已经被 Worker 领取
     * 或完成，比较仍使用数据库中不可变的 {@code initial_available_at}，而不是把
     * 当前 {@code next_attempt_at} 误当作首次 Definition。</p>
     *
     * @return 空表示不存在；true 表示完整 Definition 相同；false 表示稳定冲突
     */
    static Optional<Boolean> definitionMatches(
            Connection connection,
            WorkflowStep candidate) throws SQLException {
        requireTransactionalConnection(connection);
        validateInitial(candidate);
        return queryStored(connection, candidate.key(), false)
                .map(stored -> stored.sameDefinition(candidate));
    }

    /**
     * 在调用方事务中锁定 Workflow 行并只核对当前 lease 栅栏，不改变任何事实。
     *
     * <p>最终收口工作单元必须先锁 Meeting，再调用本原语锁 Workflow，之后才能
     * 持久化 Artifact metadata 或 Manifest。这样旧、过期或已经被重领的 lease
     * 会在第一笔业务写入之前被拒绝；事务末尾仍需调用 {@link #complete(Connection,
     * LeaseToken, Instant)} 或 {@link #fail(Connection, LeaseToken, Instant, Instant,
     * String, String, int)} 执行同一栅栏的终态条件更新。</p>
     */
    static LeaseFenceResult lockActiveLease(
            Connection connection,
            LeaseToken lease,
            Instant operationAt) throws SQLException {
        requireTransactionalConnection(connection);
        validateLease(lease);
        requirePersistentTimestamp("operationAt", operationAt);
        StoredStep current = queryStored(connection, lease.key(), true).orElse(null);
        if (current == null) {
            return new LeaseFenceResult(LeaseFenceStatus.NOT_FOUND, null);
        }
        if (current.step().status() == WorkflowStep.Status.COMPLETED) {
            return new LeaseFenceResult(
                    LeaseFenceStatus.ALREADY_COMPLETED, current.step());
        }
        if (current.step().status() == WorkflowStep.Status.DEAD) {
            return new LeaseFenceResult(LeaseFenceStatus.ALREADY_DEAD, current.step());
        }
        if (!owns(current, lease)) {
            return new LeaseFenceResult(LeaseFenceStatus.NOT_OWNER, current.step());
        }
        if (operationAt.isBefore(current.step().updatedAt())
                || !operationAt.isBefore(current.step().leaseUntil())) {
            return new LeaseFenceResult(LeaseFenceStatus.LEASE_EXPIRED, current.step());
        }
        return new LeaseFenceResult(LeaseFenceStatus.ACTIVE, current.step());
    }

    /**
     * 在只读恢复事务中核对完整 lease identity，包括数据库私有 token。
     * 本方法不加锁、不更新行，也不把 token 放入返回值或日志。
     */
    static boolean activeLeaseMatches(
            Connection connection,
            LeaseToken lease) throws SQLException {
        requireTransactionalConnection(connection);
        validateLease(lease);
        return queryStored(connection, lease.key(), false)
                .map(current -> owns(current, lease))
                .orElse(false);
    }

    /** 在调用方事务连接上领取；整个批次由调用方统一提交或回滚。 */
    static List<ClaimedStep> claim(
            Connection connection,
            ClaimRequest request) throws SQLException {
        requireTransactionalConnection(connection);
        validateClaimRequest(request);
        if (request.allowedStepTypes().isEmpty()) {
            return List.of();
        }
        Instant expiry = leaseExpiry(request.now(), request.leaseDuration());

        List<ClaimPlan> plans = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(CLAIM_SELECT_SQL)) {
            java.sql.Array types = connection.createArrayOf(
                    "text", request.allowedStepTypes().toArray(String[]::new));
            try {
                select.setArray(1, types);
                select.setTimestamp(2, Timestamp.from(request.now()));
                select.setTimestamp(3, Timestamp.from(request.now()));
                select.setInt(4, request.limit());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        StoredStep stored = readStoredStep(rows);
                        WorkflowStep previous = stored.step();
                        long version = Math.addExact(previous.leaseVersion(), 1L);
                        String token = UUID.randomUUID().toString();
                        WorkflowStep leased = new WorkflowStep(
                                previous.key(), previous.checkpointType(),
                                previous.canonicalPayload(), previous.contentDigest(),
                                WorkflowStep.Status.LEASED, previous.attempt(), null,
                                request.leaseOwner(), expiry, version,
                                previous.errorCode(), previous.errorSummary(), request.now());
                        LeaseToken lease = new LeaseToken(
                                leased.key(), request.leaseOwner(), token, expiry,
                                request.now(), version);
                        plans.add(new ClaimPlan(leased, token, lease));
                    }
                }
            } finally {
                types.free();
            }
        }

        List<ClaimedStep> result = new ArrayList<>(plans.size());
        for (ClaimPlan plan : plans) {
            try (PreparedStatement update = connection.prepareStatement(CLAIM_UPDATE_SQL)) {
                update.setString(1, plan.lease().owner());
                update.setTimestamp(2, Timestamp.from(plan.lease().expiresAt()));
                update.setString(3, plan.token());
                update.setLong(4, plan.step().leaseVersion());
                update.setTimestamp(5, Timestamp.from(plan.step().updatedAt()));
                bindKey(update, 6, plan.step().key());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("工作流步骤领取条件更新失败");
                }
            }
            result.add(new ClaimedStep(plan.step(), plan.lease()));
        }
        return List.copyOf(result);
    }

    /** 在调用方事务连接上续租；成功后 token/version 一起旋转。 */
    static RenewResult renew(
            Connection connection,
            LeaseToken lease,
            Instant renewedAt,
            Duration leaseDuration) throws SQLException {
        requireTransactionalConnection(connection);
        validateLease(lease);
        requirePersistentTimestamp("renewedAt", renewedAt);
        requirePositive(leaseDuration);
        // 端口参数规则必须在读取当前行前裁决。这样 missing/terminal/active Key
        // 不会因为数据库状态不同而改变同一个非法请求的异常类型。
        Instant expiry = renewalExpiry(lease, renewedAt, leaseDuration);
        StoredStep current = queryStored(connection, lease.key(), true).orElse(null);
        if (current == null) {
            return new RenewResult(RenewStatus.NOT_FOUND, null, null);
        }
        RenewStatus terminal = renewTerminal(current.step());
        if (terminal != null) {
            return new RenewResult(terminal, current.step(), null);
        }
        if (!owns(current, lease)) {
            return new RenewResult(RenewStatus.NOT_OWNER, current.step(), null);
        }
        if (renewedAt.isBefore(current.step().updatedAt())
                || !renewedAt.isBefore(current.step().leaseUntil())) {
            return new RenewResult(RenewStatus.LEASE_EXPIRED, current.step(), null);
        }
        String token = UUID.randomUUID().toString();
        long version = Math.addExact(current.step().leaseVersion(), 1L);
        try (PreparedStatement update = connection.prepareStatement(RENEW_SQL)) {
            update.setTimestamp(1, Timestamp.from(expiry));
            update.setString(2, token);
            update.setLong(3, version);
            update.setTimestamp(4, Timestamp.from(renewedAt));
            bindKey(update, 5, lease.key());
            update.setString(8, lease.owner());
            update.setString(9, lease.token());
            update.setLong(10, lease.leaseVersion());
            update.setTimestamp(11, Timestamp.from(lease.expiresAt()));
            update.setTimestamp(12, Timestamp.from(lease.claimedAt()));
            update.setTimestamp(13, Timestamp.from(renewedAt));
            update.setTimestamp(14, Timestamp.from(expiry));
            requireSingleFencedUpdate(update, "续租");
        }
        WorkflowStep next = readCurrent(connection, lease.key()).step();
        LeaseToken rotated = new LeaseToken(
                lease.key(), lease.owner(), token, expiry, renewedAt, version);
        return new RenewResult(RenewStatus.RENEWED, next, rotated);
    }

    /**
     * 在调用方事务内以完整 lease 栅栏 CAS marker；只修改错误字段。
     *
     * <p>特别不能更新 {@code updated_at}：该列同时是 claimedAt 栅栏，修改它会让
     * 已经发给 Worker 的 LeaseToken 立即失效，也会破坏后续对旧终态请求的恢复。</p>
     */
    static PersistLeaseMarkerResult persistLeaseMarker(
            Connection connection,
            PersistLeaseMarkerRequest request) throws SQLException {
        requireTransactionalConnection(connection);
        validateMarkerRequest(request);
        StoredStep current = queryStored(
                connection, request.lease().key(), true).orElse(null);
        if (current == null) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.NOT_FOUND, null);
        }
        PersistLeaseMarkerStatus terminal = markerTerminal(current.step());
        if (terminal != null) {
            return new PersistLeaseMarkerResult(terminal, current.step());
        }
        if (!owns(current, request.lease())) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.NOT_OWNER, current.step());
        }
        if (request.markedAt().isBefore(current.step().updatedAt())
                || !request.markedAt().isBefore(current.step().leaseUntil())) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.LEASE_EXPIRED, current.step());
        }
        if (markerEquals(current.step(), request.markerCode(), request.markerSummary())) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.REPLAYED, current.step());
        }
        if (!markerEquals(
                current.step(),
                request.expectedErrorCode(),
                request.expectedErrorSummary())) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.CONFLICT, current.step());
        }
        try (PreparedStatement update = connection.prepareStatement(
                PERSIST_LEASE_MARKER_SQL)) {
            update.setString(1, request.markerCode());
            update.setString(2, request.markerSummary());
            bindKey(update, 3, request.lease().key());
            update.setString(6, request.lease().owner());
            update.setString(7, request.lease().token());
            update.setLong(8, request.lease().leaseVersion());
            update.setTimestamp(9, Timestamp.from(request.lease().expiresAt()));
            update.setTimestamp(10, Timestamp.from(request.lease().claimedAt()));
            update.setString(11, request.expectedErrorCode());
            update.setString(12, request.expectedErrorSummary());
            update.setTimestamp(13, Timestamp.from(request.markedAt()));
            update.setTimestamp(14, Timestamp.from(request.markedAt()));
            requireSingleFencedUpdate(update, "持久化终态操作 marker");
        }
        WorkflowStep marked = readCurrent(connection, request.lease().key()).step();
        return new PersistLeaseMarkerResult(
                PersistLeaseMarkerStatus.PERSISTED, marked);
    }

    /** 在调用方事务连接上完成；终态不可逆。 */
    static CompleteResult complete(
            Connection connection,
            LeaseToken lease,
            Instant completedAt) throws SQLException {
        requireTransactionalConnection(connection);
        validateLease(lease);
        requirePersistentTimestamp("completedAt", completedAt);
        StoredStep current = queryStored(connection, lease.key(), true).orElse(null);
        if (current == null) {
            return new CompleteResult(CompleteStatus.NOT_FOUND, null);
        }
        CompleteStatus terminal = completeTerminal(current.step());
        if (terminal != null) {
            return new CompleteResult(terminal, current.step());
        }
        if (!owns(current, lease)) {
            return new CompleteResult(CompleteStatus.NOT_OWNER, current.step());
        }
        if (completedAt.isBefore(current.step().updatedAt())
                || !completedAt.isBefore(current.step().leaseUntil())) {
            return new CompleteResult(CompleteStatus.LEASE_EXPIRED, current.step());
        }
        try (PreparedStatement update = connection.prepareStatement(COMPLETE_SQL)) {
            update.setTimestamp(1, Timestamp.from(completedAt));
            bindKey(update, 2, lease.key());
            update.setString(5, lease.owner());
            update.setString(6, lease.token());
            update.setLong(7, lease.leaseVersion());
            update.setTimestamp(8, Timestamp.from(lease.expiresAt()));
            update.setTimestamp(9, Timestamp.from(lease.claimedAt()));
            update.setTimestamp(10, Timestamp.from(completedAt));
            requireSingleFencedUpdate(update, "完成");
        }
        return new CompleteResult(
                CompleteStatus.COMPLETED,
                readCurrent(connection, lease.key()).step());
    }

    /** 在调用方事务连接上报告失败；只有有效 lease 才增加 attempt。 */
    static FailResult fail(
            Connection connection,
            LeaseToken lease,
            Instant failedAt,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            int maxAttempts) throws SQLException {
        requireTransactionalConnection(connection);
        validateLease(lease);
        requirePersistentTimestamp("failedAt", failedAt);
        requirePersistentTimestamp("nextAttemptAt", nextAttemptAt);
        requireText("errorCode", errorCode);
        requireText("errorSummary", errorSummary);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (nextAttemptAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("nextAttemptAt must not be before failedAt");
        }

        StoredStep current = queryStored(connection, lease.key(), true).orElse(null);
        if (current == null) {
            return new FailResult(FailStatus.NOT_FOUND, null);
        }
        FailStatus terminal = failTerminal(current.step());
        if (terminal != null) {
            return new FailResult(terminal, current.step());
        }
        if (!owns(current, lease)) {
            return new FailResult(FailStatus.NOT_OWNER, current.step());
        }
        if (failedAt.isBefore(current.step().updatedAt())
                || !failedAt.isBefore(current.step().leaseUntil())) {
            return new FailResult(FailStatus.LEASE_EXPIRED, current.step());
        }
        int attempt = Math.addExact(current.step().attempt(), 1);
        boolean dead = attempt >= maxAttempts;
        try (PreparedStatement update = connection.prepareStatement(FAIL_SQL)) {
            update.setInt(1, maxAttempts);
            update.setInt(2, maxAttempts);
            update.setTimestamp(3, Timestamp.from(nextAttemptAt));
            update.setString(4, errorCode);
            update.setString(5, errorSummary);
            update.setTimestamp(6, Timestamp.from(failedAt));
            bindKey(update, 7, lease.key());
            update.setString(10, lease.owner());
            update.setString(11, lease.token());
            update.setLong(12, lease.leaseVersion());
            update.setTimestamp(13, Timestamp.from(lease.expiresAt()));
            update.setTimestamp(14, Timestamp.from(lease.claimedAt()));
            update.setTimestamp(15, Timestamp.from(failedAt));
            requireSingleFencedUpdate(update, "失败");
        }
        WorkflowStep result = readCurrent(connection, lease.key()).step();
        return new FailResult(
                dead ? FailStatus.DEAD : FailStatus.RETRY_SCHEDULED,
                result);
    }

    private static Optional<StoredStep> queryStored(
            Connection connection,
            WorkflowStep.Key key,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(key, "key");
        try (PreparedStatement statement = connection.prepareStatement(
                forUpdate ? FIND_FOR_UPDATE_SQL : FIND_SQL)) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readStoredStep(rows)) : Optional.empty();
            }
        }
    }

    private static StoredStep readCurrent(
            Connection connection,
            WorkflowStep.Key key) throws SQLException {
        return queryStored(connection, key, false)
                .orElseThrow(() -> new IllegalStateException(
                        "工作流步骤更新后不可见"));
    }

    /**
     * 同时读取公开步骤快照和只用于栅栏/注册裁决的数据库私有字段。
     * lease token 从不进入公开 {@link WorkflowStep} 或诊断输出。
     */
    private static StoredStep readStoredStep(ResultSet rows) throws SQLException {
        WorkflowStep.Key key = new WorkflowStep.Key(
                rows.getString("meeting_id"),
                rows.getString("step_type"),
                rows.getString("step_key"));
        Instant initialAvailableAt = requiredInstant(rows, "initial_available_at");
        String token = rows.getString("lease_token");
        WorkflowStep step = new WorkflowStep(
                key,
                rows.getString("checkpoint_type"),
                rows.getString("canonical_payload"),
                rows.getString("content_digest"),
                WorkflowStep.Status.valueOf(rows.getString("status")),
                rows.getInt("attempt_count"),
                optionalInstant(rows, "next_attempt_at"),
                rows.getString("lease_owner"),
                optionalInstant(rows, "lease_until"),
                rows.getLong("lease_version"),
                rows.getString("error_code"),
                rows.getString("error_message"),
                requiredInstant(rows, "updated_at"));
        if ((step.status() == WorkflowStep.Status.LEASED) != (token != null)) {
            throw new IllegalStateException("workflow step lease token is inconsistent");
        }
        if (token != null) {
            requireText("stored lease token", token);
        }
        return new StoredStep(step, initialAvailableAt, token);
    }

    private static Instant requiredInstant(ResultSet rows, String column) throws SQLException {
        Instant value = optionalInstant(rows, column);
        if (value == null) {
            throw new IllegalStateException("workflow step timestamp is inconsistent");
        }
        return value;
    }

    private static Instant optionalInstant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        if (value == null) {
            return null;
        }
        Instant instant = value.toInstant();
        requirePersistentTimestamp(column, instant);
        return instant;
    }

    private static boolean owns(StoredStep current, LeaseToken lease) {
        WorkflowStep step = current.step();
        return step.status() == WorkflowStep.Status.LEASED
                && step.key().equals(lease.key())
                && lease.owner().equals(step.leaseOwner())
                && lease.token().equals(current.leaseToken())
                && lease.expiresAt().equals(step.leaseUntil())
                && lease.leaseVersion() == step.leaseVersion()
                && lease.claimedAt().equals(step.updatedAt());
    }

    private static PersistLeaseMarkerResult recoverLeaseMarker(
            StoredStep current,
            PersistLeaseMarkerRequest request) {
        if (current == null) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.NOT_FOUND, null);
        }
        PersistLeaseMarkerStatus terminal = markerTerminal(current.step());
        if (terminal != null) {
            return new PersistLeaseMarkerResult(terminal, current.step());
        }
        if (!owns(current, request.lease())) {
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.NOT_OWNER, current.step());
        }
        PersistLeaseMarkerStatus status = markerEquals(
                current.step(), request.markerCode(), request.markerSummary())
                ? PersistLeaseMarkerStatus.REPLAYED
                : PersistLeaseMarkerStatus.CONFLICT;
        return new PersistLeaseMarkerResult(status, current.step());
    }

    private static PersistLeaseMarkerStatus markerTerminal(WorkflowStep step) {
        return switch (step.status()) {
            case COMPLETED -> PersistLeaseMarkerStatus.ALREADY_COMPLETED;
            case DEAD -> PersistLeaseMarkerStatus.ALREADY_DEAD;
            default -> null;
        };
    }

    private static boolean markerEquals(
            WorkflowStep step,
            String code,
            String summary) {
        return Objects.equals(step.errorCode(), code)
                && Objects.equals(step.errorSummary(), summary);
    }

    private static RenewStatus renewTerminal(WorkflowStep step) {
        return switch (step.status()) {
            case COMPLETED -> RenewStatus.ALREADY_COMPLETED;
            case DEAD -> RenewStatus.ALREADY_DEAD;
            default -> null;
        };
    }

    private static CompleteStatus completeTerminal(WorkflowStep step) {
        return switch (step.status()) {
            case COMPLETED -> CompleteStatus.ALREADY_COMPLETED;
            case DEAD -> CompleteStatus.ALREADY_DEAD;
            default -> null;
        };
    }

    private static FailStatus failTerminal(WorkflowStep step) {
        return switch (step.status()) {
            case COMPLETED -> FailStatus.ALREADY_COMPLETED;
            case DEAD -> FailStatus.ALREADY_DEAD;
            default -> null;
        };
    }

    private static void validateInitial(WorkflowStep candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.status() != WorkflowStep.Status.PENDING
                || candidate.attempt() != 0
                || candidate.leaseVersion() != 0
                || candidate.leaseOwner() != null
                || candidate.leaseUntil() != null
                || candidate.errorCode() != null
                || candidate.errorSummary() != null) {
            throw new IllegalArgumentException(
                    "register requires initial PENDING checkpoint");
        }
        requirePersistentTimestamp("initial nextAttemptAt", candidate.nextAttemptAt());
        requirePersistentTimestamp("registeredAt", candidate.updatedAt());
    }

    private static void validateClaimRequest(ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        requirePersistentTimestamp("claim now", request.now());
        requirePositive(request.leaseDuration());
    }

    private static void validateLease(LeaseToken lease) {
        Objects.requireNonNull(lease, "lease");
        requirePersistentTimestamp("lease claimedAt", lease.claimedAt());
        requirePersistentTimestamp("lease expiresAt", lease.expiresAt());
    }

    private static void validateMarkerRequest(PersistLeaseMarkerRequest request) {
        Objects.requireNonNull(request, "request");
        validateLease(request.lease());
        requirePersistentTimestamp("markedAt", request.markedAt());
    }

    private static void bindKey(
            PreparedStatement statement,
            WorkflowStep.Key key) throws SQLException {
        bindKey(statement, 1, key);
    }

    private static void bindKey(
            PreparedStatement statement,
            int index,
            WorkflowStep.Key key) throws SQLException {
        statement.setString(index, key.meetingId());
        statement.setString(index + 1, key.stepType());
        statement.setString(index + 2, key.stepKey());
    }

    private static void requireSingleFencedUpdate(
            PreparedStatement statement,
            String operation) throws SQLException {
        if (statement.executeUpdate() != 1) {
            // 当前事务已经 FOR UPDATE 锁定并完整核对 owner/token/version/time。
            // 条件更新仍失败表示触发器或 schema 漂移，不应伪装成普通 NOT_OWNER。
            throw new SQLException("工作流步骤" + operation + "条件更新失败");
        }
    }

    private static void requireTransactionalConnection(Connection connection)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    "workflow step operation requires autoCommit=false");
        }
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePersistentTimestamp(String name, Instant value) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " must use PostgreSQL microsecond precision");
        }
    }

    /** 从端口请求本身计算 lease 截止时间，拒绝溢出、非前进和精度损失。 */
    private static Instant leaseExpiry(Instant start, Duration duration) {
        requirePersistentTimestamp("lease start", start);
        requirePositive(duration);
        Instant expiry = start.plus(duration);
        requirePersistentTimestamp("lease expiresAt", expiry);
        if (!expiry.isAfter(start)) {
            throw new IllegalArgumentException("leaseDuration must advance expiry");
        }
        return expiry;
    }

    /** 按传入 lease 校验续租确实延长截止时间；不得依赖数据库当前状态裁决。 */
    private static Instant renewalExpiry(
            LeaseToken lease,
            Instant renewedAt,
            Duration leaseDuration) {
        Instant expiry = leaseExpiry(renewedAt, leaseDuration);
        if (!expiry.isAfter(lease.expiresAt())) {
            throw new IllegalArgumentException("renew must extend lease expiry");
        }
        return expiry;
    }

    private static RuntimeException sanitized(String message) {
        return new IllegalStateException(message);
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            LOGGER.log(Level.WARNING, "JDBC 工作流步骤事务回滚失败");
        }
    }

    private static void abortQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING,
                    "JDBC 工作流终态操作 marker 未确认连接废弃失败");
        }
    }

    private static void restoreAutoCommitQuietly(
            Connection connection,
            boolean autoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "JDBC 工作流连接状态恢复失败");
        }
    }

    private static void restoreReadOnlyQuietly(
            Connection connection,
            boolean autoCommit,
            boolean readOnly,
            int isolation) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(autoCommit);
                connection.setTransactionIsolation(isolation);
                connection.setReadOnly(readOnly);
            }
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "JDBC 工作流只读连接状态恢复失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "JDBC 工作流连接关闭失败");
        }
    }

    private static void restoreAutoCommit(
            Connection connection,
            boolean autoCommit) throws SQLException {
        if (!connection.isClosed()) {
            connection.setAutoCommit(autoCommit);
        }
    }

    private record StoredStep(
            WorkflowStep step,
            Instant initialAvailableAt,
            String leaseToken) {
        private boolean sameDefinition(WorkflowStep candidate) {
            return step.checkpointType().equals(candidate.checkpointType())
                    && step.canonicalPayload().equals(candidate.canonicalPayload())
                    && step.contentDigest().equals(candidate.contentDigest())
                    && initialAvailableAt.equals(candidate.nextAttemptAt());
        }
    }

    private record ClaimPlan(
            WorkflowStep step,
            String token,
            LeaseToken lease) {
    }

    /** 只供同包原子工作单元使用的 lease 锁读裁决。 */
    enum LeaseFenceStatus {
        ACTIVE,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        ALREADY_COMPLETED,
        ALREADY_DEAD
    }

    /** 失败状态携带当前非敏感 Workflow 快照；NOT_FOUND 不携带虚构事实。 */
    record LeaseFenceResult(LeaseFenceStatus status, WorkflowStep step) {
        LeaseFenceResult {
            Objects.requireNonNull(status, "status");
            if ((status == LeaseFenceStatus.NOT_FOUND) != (step == null)) {
                throw new IllegalArgumentException(
                        "only NOT_FOUND lease fence may omit workflow step");
            }
        }
    }
}
