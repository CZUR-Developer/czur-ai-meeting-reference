package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompleteMeetingCreateReplayLookup;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V013 完整 Create 首次响应的只读 JDBC 适配器。
 *
 * <p>每次调用独占一条连接和一个仅执行查询的事务；复用已验收 Store 的请求侧严格
 * {@code probeRequestDigest}，先校验规范请求正文与持久摘要，异摘要立即冲突；
 * 只有同摘要才调用完整 {@code find} 解码响应。事务结束后关闭连接。查询不会
 * 修改会议、权益或 Outbox；日志只包含固定结果与 SQLState，不输出请求及响应。</p>
 */
public final class JdbcCompleteMeetingCreateReplayLookup
        implements CompleteMeetingCreateReplayLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcCompleteMeetingCreateReplayLookup.class.getName());

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    /** @param dataSource 已完成 V013 readiness 校验的 PostgreSQL 数据源 */
    public JdbcCompleteMeetingCreateReplayLookup(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    public JdbcCompleteMeetingCreateReplayLookup(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcCompleteMeetingCreateReplayLookup(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Result lookup(Request request) {
        Objects.requireNonNull(request, "request");
        String scope = IdempotencyScopeCodec.meetingCreateV2(
                request.deploymentScope(), request.subjectScope());
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                JdbcCreateMeetingFactStore facts = new JdbcCreateMeetingFactStore(connection, engine);
                Optional<String> digest = facts.probeRequestDigest(scope, request.idempotencyKey());
                Result result;
                if (digest.isEmpty()) {
                    result = new NotFound();
                } else if (!JdbcReadLookupSupport.sameDigest(
                        digest.get(), request.requestDigest())) {
                    result = new Conflict();
                } else {
                    Optional<CreateMeetingFactStore.Record> stored = facts.find(
                            scope, request.idempotencyKey());
                    if (stored.isEmpty() || !JdbcReadLookupSupport.sameDigest(
                            stored.get().request().canonicalRequestDigest(),
                            request.requestDigest())) {
                        result = new Inconsistent();
                    } else {
                        result = new Replayed(stored.get().response());
                    }
                }
                // 结束快照；回滚失败时外层返回 UNAVAILABLE，不能宣称重放安全。
                connection.rollback();
                logResult(result.status());
                return result;
            } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
                boolean rolledBack = rollbackQuietly(connection);
                Result result = rolledBack
                        && failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                        ? new Inconsistent() : new Unavailable();
                logResult(result.status());
                return result;
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 重放查询失败 status=UNAVAILABLE sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return new Unavailable();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 重放查询失败 status=UNAVAILABLE");
            return new Unavailable();
        }
    }

    private static boolean rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 重放查询回滚失败 status=UNAVAILABLE");
            return false;
        }
    }

    private static void logResult(Status status) {
        LOGGER.log(status == Status.UNAVAILABLE || status == Status.INCONSISTENT
                ? Level.WARNING : Level.FINE,
                "完整 Create 重放查询完成 status={0}", status);
    }
}
