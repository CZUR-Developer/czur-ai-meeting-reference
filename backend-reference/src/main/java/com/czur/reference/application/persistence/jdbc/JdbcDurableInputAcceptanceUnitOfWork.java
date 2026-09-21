package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableInputAcceptanceUnitOfWork;
import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.MeetingFinalizationStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * durable input fact 与会议级 receipt 的 PostgreSQL 协同工作单元。
 *
 * <p>每次 {@link #accept(AcceptRequest)} 只取得一条连接，并在该连接的本地事务
 * 中按固定顺序锁定 meeting、写入或重放 V008 durable input fact，再调用已有的
 * connection-scoped finalization 原语生成或重放 receipt。非 accepted 结果会回滚
 * 这次事务；因此首次输入事实不会在 receipt 收口失败时留下半提交事实。</p>
 *
 * <p>本类不修改旧的
 * {@link MeetingFinalizationStore#acceptInput(String, Instant)} 公共 API。它只在
 * JDBC 包内复用不管理事务生命周期的原语，不把 {@link Connection} 暴露到应用
 * 端口。receipt 生成器只在确认 durable fact 可用并且 meeting 仍处于待接管状态
 * 后调用；已有 accepted 事实永远不会重新生成 receipt。</p>
 *
 * <p>commit 调用开始后如果确认结果未知，本类抛出不携带底层 cause、SQL、会议
 * 标识、输入正文或 receipt 的脱敏异常，并废弃无法确认状态的连接；调用方只能
 * 重新读取 durable input fact 与 meeting-level finalization 事实，不能根据异常猜测
 * receipt 是否已经生成。</p>
 */
public final class JdbcDurableInputAcceptanceUnitOfWork
        implements DurableInputAcceptanceUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcDurableInputAcceptanceUnitOfWork.class.getName());

    private final DataSource dataSource;
    private final FinalizationReceiptGenerator receiptGenerator;

    public JdbcDurableInputAcceptanceUnitOfWork(
            DataSource dataSource,
            FinalizationReceiptGenerator receiptGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.receiptGenerator = Objects.requireNonNull(receiptGenerator, "receiptGenerator");
    }

    @Override
    public Result accept(AcceptRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean rollbackConfirmed = true;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            Result result = acceptInTransaction(connection, request);
            if (!result.receiptAvailable()) {
                rollbackConfirmed = rollbackQuietly(connection);
                transactionStarted = false;
                if (!rollbackConfirmed) {
                    throw storageFailure("输入接管冲突回滚失败");
                }
                return result;
            }

            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            transactionStarted = false;
            LOGGER.log(Level.FINE, "durable input acceptance committed inputStatus={0}",
                    result.inputStatus());
            return result;
        } catch (RuntimeException failure) {
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
                throw outcomeUnknown();
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
                transactionStarted = false;
            }
            throw storageFailure("提交输入接管事实失败");
        } catch (SQLException failure) {
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
                throw outcomeUnknown();
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
                transactionStarted = false;
            }
            throw storageFailure("提交输入接管事实失败");
        } catch (Error failure) {
            // An Error during commit is also an unknown outcome; never restore a
            // potentially dirty connection to the pool in that case.
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
            } else if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            throw failure;
        } finally {
            finishConnection(connection, previousAutoCommit, rollbackConfirmed);
            closeQuietly(connection);
        }
    }

    /** 在调用方已经开启事务的同一连接上执行完整协同流程。 */
    private Result acceptInTransaction(Connection connection, AcceptRequest request)
            throws SQLException {
        FinalizationInputFact canonicalFact = canonicalize(request.inputFact());
        MeetingFinalizationStore.Result preflight =
                JdbcMeetingFinalizationStore.preflightAcceptInput(
                        connection, request.meetingId(), canonicalFact.acceptedAt());
        if (preflight instanceof MeetingFinalizationStore.Replayed) {
            // An already accepted meeting is only a safe replay when the V008
            // fact exists and is byte-for-byte equal to the candidate. Do not
            // repair a missing V008 row from a receipt-bearing meeting.
            Optional<FinalizationInputFact> existing =
                    JdbcDurableInputFactStore.find(connection, request.meetingId());
            if (existing.isPresent() && existing.orElseThrow().equals(canonicalFact)) {
                MeetingFinalizationStore.Result finalization =
                        JdbcMeetingFinalizationStore.acceptInput(
                                connection,
                                request.meetingId(),
                                canonicalFact.acceptedAt(),
                                receiptGenerator);
                return new Result(
                        DurableInputFactStore.PersistStatus.REPLAYED, finalization);
            }
            return new Result(
                    DurableInputFactStore.PersistStatus.CONFLICT,
                    new MeetingFinalizationStore.Conflict(
                            MeetingFinalizationStore.ConflictReason.FACT_INCONSISTENT));
        }
        if (!(preflight instanceof MeetingFinalizationStore.Found)) {
            return new Result(
                    DurableInputFactStore.PersistStatus.CONFLICT, preflight);
        }

        DurableInputFactStore.PersistResult persisted =
                JdbcDurableInputFactStore.persist(connection, canonicalFact);
        if (persisted.status() == DurableInputFactStore.PersistStatus.CONFLICT) {
            return new Result(
                    DurableInputFactStore.PersistStatus.CONFLICT,
                    new MeetingFinalizationStore.Conflict(
                            MeetingFinalizationStore.ConflictReason.FACT_INCONSISTENT));
        }

        MeetingFinalizationStore.Result finalization = JdbcMeetingFinalizationStore.acceptInput(
                connection,
                request.meetingId(),
                canonicalFact.acceptedAt(),
                receiptGenerator);
        if (finalizationAccepted(finalization)) {
            return new Result(persisted.status(), finalization);
        }

        // APPLIED durable fact is part of this uncommitted transaction. Convert the
        // non-accepted finalization to an explicit conflict result; the caller will
        // roll back before returning so APPLIED can never describe a half-commit.
        DurableInputFactStore.PersistStatus resultStatus =
                persisted.status() == DurableInputFactStore.PersistStatus.APPLIED
                        ? DurableInputFactStore.PersistStatus.CONFLICT
                        : persisted.status();
        return new Result(resultStatus, finalization);
    }

    private static FinalizationInputFact canonicalize(FinalizationInputFact fact) {
        Objects.requireNonNull(fact, "fact");
        Instant acceptedAt = fact.acceptedAt().truncatedTo(ChronoUnit.MICROS);
        if (acceptedAt.equals(fact.acceptedAt())) {
            return fact;
        }
        return new FinalizationInputFact(
                fact.meetingId(),
                fact.inputBinding(),
                fact.lastAcceptedSequence(),
                fact.audioDurationMs(),
                fact.sourceLanguage(),
                fact.targetLanguages(),
                acceptedAt,
                fact.contentDigest());
    }

    private static boolean finalizationAccepted(MeetingFinalizationStore.Result result) {
        if (result instanceof MeetingFinalizationStore.Applied applied) {
            return applied.fact().isAccepted();
        }
        if (result instanceof MeetingFinalizationStore.Replayed replayed) {
            return replayed.fact().isAccepted();
        }
        return false;
    }

    private static RuntimeException storageFailure(String message) {
        return new IllegalStateException(message + " storage=UNAVAILABLE");
    }

    private static RuntimeException outcomeUnknown() {
        return new IllegalStateException("提交输入接管事实 outcome=UNKNOWN");
    }

    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return true;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "输入接管事务回滚失败 storage=UNAVAILABLE");
            return false;
        }
    }

    private static void finishConnection(
            Connection connection,
            boolean previousAutoCommit,
            boolean rollbackConfirmed) {
        if (connection == null) {
            return;
        }
        if (!rollbackConfirmed) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException failure) {
                LOGGER.log(Level.WARNING, "输入接管连接废弃失败 storage=UNAVAILABLE");
            }
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "输入接管连接状态恢复失败 storage=UNAVAILABLE");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "输入接管连接关闭失败 storage=UNAVAILABLE");
        }
    }
}
