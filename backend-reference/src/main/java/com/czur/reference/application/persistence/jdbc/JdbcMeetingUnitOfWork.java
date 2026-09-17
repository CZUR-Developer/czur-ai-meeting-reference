package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.application.port.MeetingUnitOfWork;
import com.czur.reference.application.port.OutboxStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDBC {@link Connection} 的会议工作单元。
 *
 * <p>每次 {@link #execute(Function)} 只获取一个连接，并把会议快照、幂等记录
 * 和 Outbox 追加操作绑定到该连接。回调正常结束后统一提交；回调、SQL 或提交
 * 过程抛出异常时统一回滚。该类不执行数据库迁移，也不调用模型、对象存储、
 * 邮件或消息队列等外部系统。</p>
 *
 * <p>事务内的 Outbox 视图只允许 {@code append}/{@code find}。领取、发布确认和
 * 失败重试属于提交后的 Worker 生命周期，必须在独立短事务中通过
 * {@link JdbcOutboxStore} 执行，不能塞进会议命令事务。</p>
 */
public final class JdbcMeetingUnitOfWork implements MeetingUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingUnitOfWork.class.getName());

    private final DataSource dataSource;

    public JdbcMeetingUnitOfWork(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public <T> T execute(Function<Transaction, T> work) {
        Function<Transaction, T> checkedWork = MeetingUnitOfWork.requireWork(work);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = checkedWork.apply(new JdbcTransaction(connection));
                connection.commit();
                LOGGER.log(Level.FINE, "会议工作单元已提交");
                return result;
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            } catch (Exception failure) {
                rollback(connection, failure);
                throw new IllegalStateException("会议工作单元提交失败", failure);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("创建会议工作单元数据库连接失败", failure);
        }
    }

    private static final class JdbcTransaction implements Transaction {
        private final MeetingRepository meetings;
        private final IdempotencyStore idempotency;
        private final OutboxStore outbox;

        private JdbcTransaction(Connection connection) {
            this.meetings = new TransactionalMeetingRepository(connection);
            this.idempotency = new TransactionalIdempotencyStore(connection);
            this.outbox = new TransactionalOutboxStore(connection);
        }

        @Override
        public MeetingRepository meetings() {
            return meetings;
        }

        @Override
        public IdempotencyStore idempotency() {
            return idempotency;
        }

        @Override
        public OutboxStore outbox() {
            return outbox;
        }
    }

    private static final class TransactionalMeetingRepository implements MeetingRepository {
        private final Connection connection;

        private TransactionalMeetingRepository(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<com.czur.reference.domain.meeting.MeetingAggregate.MeetingSnapshot> find(
                String meetingId) {
            try {
                // 事务命令读取会议后会基于当前状态决定是否写入快照和 Outbox。
                // 使用行锁让多个服务实例按数据库提交顺序重新观察最新状态。
                return JdbcMeetingRepository.findForUpdate(connection, meetingId);
            } catch (SQLException failure) {
                throw sqlFailure("事务内查询会议失败", failure);
            }
        }

        @Override
        public Optional<com.czur.reference.domain.meeting.MeetingAggregate.MeetingSnapshot>
                findByDeploymentScopeAndClientSessionId(String deploymentScope, String clientSessionId) {
            try {
                return JdbcMeetingRepository.findByDeploymentScopeAndClientSessionId(
                        connection, deploymentScope, clientSessionId);
            } catch (SQLException failure) {
                throw sqlFailure("事务内按部署范围查询会议失败", failure);
            }
        }

        @Override
        public void save(com.czur.reference.domain.meeting.MeetingAggregate.MeetingSnapshot meeting) {
            try {
                JdbcMeetingRepository.save(connection, meeting);
            } catch (SQLException failure) {
                throw sqlFailure("事务内保存会议失败", failure);
            }
        }
    }

    private static final class TransactionalIdempotencyStore implements IdempotencyStore {
        private final Connection connection;

        private TransactionalIdempotencyStore(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<IdempotencyRecord> find(String scope, String key) {
            try {
                return JdbcIdempotencyStore.find(connection, scope, key);
            } catch (SQLException failure) {
                throw sqlFailure("事务内查询幂等记录失败", failure);
            }
        }

        @Override
        public void save(IdempotencyRecord record) {
            try {
                JdbcIdempotencyStore.save(connection, record);
            } catch (SQLException failure) {
                throw sqlFailure("事务内保存幂等记录失败", failure);
            }
        }

        @Override
        public Reservation reserve(IdempotencyRecord candidate) {
            try {
                return JdbcIdempotencyStore.reserve(connection, candidate);
            } catch (SQLException failure) {
                throw sqlFailure("事务内预占幂等键失败", failure);
            }
        }

        @Override
        public void release(IdempotencyRecord candidate) {
            try {
                JdbcIdempotencyStore.release(connection, candidate);
            } catch (SQLException failure) {
                throw sqlFailure("事务内释放幂等键失败", failure);
            }
        }
    }

    private static final class TransactionalOutboxStore implements OutboxStore {
        private final Connection connection;

        private TransactionalOutboxStore(Connection connection) {
            this.connection = connection;
        }

        @Override
        public AppendResult append(OutboxEvent event) {
            try {
                return JdbcOutboxStore.append(connection, event);
            } catch (SQLException failure) {
                throw sqlFailure("事务内追加 Outbox 事件失败", failure);
            }
        }

        @Override
        public Optional<OutboxEvent> find(String eventId) {
            try {
                return JdbcOutboxStore.find(connection, eventId);
            } catch (SQLException failure) {
                throw sqlFailure("事务内查询 Outbox 事件失败", failure);
            }
        }

        @Override
        public List<ClaimedEvent> claim(
                String leaseOwner, Instant now, Duration leaseDuration, int limit) {
            throw workerOperationOutsideUnitOfWork();
        }

        @Override
        public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
            throw workerOperationOutsideUnitOfWork();
        }

        @Override
        public FailureResult markFailed(
                LeaseToken lease,
                Instant failedAt,
                Instant nextAvailableAt,
                String error,
                int maxAttempts) {
            throw workerOperationOutsideUnitOfWork();
        }

        private static UnsupportedOperationException workerOperationOutsideUnitOfWork() {
            return new UnsupportedOperationException(
                    "Outbox Worker 操作必须在会议工作单元提交后使用独立短事务执行");
        }
    }

    private static IllegalStateException sqlFailure(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }

    private static void rollback(Connection connection, Throwable originalFailure) {
        try {
            connection.rollback();
            LOGGER.log(Level.FINE, "会议工作单元已回滚");
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            LOGGER.log(Level.SEVERE, "会议工作单元回滚失败", rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit) {
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            // 连接随后由 try-with-resources 关闭；仅记录诊断，不覆盖原始业务异常。
            LOGGER.log(Level.WARNING, "恢复数据库连接自动提交状态失败", failure);
        }
    }
}
