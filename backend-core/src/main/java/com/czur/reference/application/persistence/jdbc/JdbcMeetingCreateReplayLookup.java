package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.OutcomeRecord;
import com.czur.reference.application.port.MeetingCreateReplayLookup;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从 {@code idempotency_record} 只读查询会议创建终态的 JDBC 适配器。
 *
 * <p>查询范围只由 {@link IdempotencyScopeCodec#meetingCreate(String, String)}
 * 生成。读取原始行后先以常量时间比较摘要：异摘要直接返回冲突，绝不解析
 * status/body；同摘要才复用 {@link JdbcMeetingCreateOutcomeStore} 的唯一严格解码
 * 入口，避免保存路径与查询路径产生格式漂移。</p>
 *
 * <p>本类只执行 SELECT，不开启显式事务，不写入、锁定或修复数据库事实。日志
 * 只包含固定状态、原因和经过格式校验的 SQLState，不输出部署、主体、会话、
 * 幂等键、摘要、Outcome 正文或数据库地址。</p>
 */
public final class JdbcMeetingCreateReplayLookup implements MeetingCreateReplayLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingCreateReplayLookup.class.getName());

    private final DataSource dataSource;

    public JdbcMeetingCreateReplayLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Result lookup(Request request) {
        Objects.requireNonNull(request, "request");
        String scope = IdempotencyScopeCodec.meetingCreate(
                request.deploymentScope(), request.subjectScope());
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Optional<JdbcMeetingCreateOutcomeStore.RawOutcomeRow> stored =
                    JdbcMeetingCreateOutcomeStore.findRaw(
                            connection, scope, request.idempotencyKey());
            if (stored.isEmpty()) {
                return logged(new NotFound());
            }
            JdbcMeetingCreateOutcomeStore.RawOutcomeRow raw = stored.get();
            if (!JdbcReadLookupSupport.sameDigest(
                    raw.requestDigest(), request.requestDigest())) {
                return logged(new Conflict());
            }
            OutcomeRecord record = JdbcMeetingCreateOutcomeStore.decodeRaw(raw);
            return logged(classifyOutcome(record.outcome(), request));
        } catch (SQLException failure) {
            return fromSqlFailure(failure);
        } catch (RuntimeException failure) {
            // DataSource/driver 的非 SQL 运行时失败没有稳定 SQLState，不能承诺可重试。
            return logged(new Unavailable(
                    UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection);
        }
    }

    private static Result classifyOutcome(MeetingCreateOutcome outcome, Request request) {
        if (outcome instanceof MeetingCreateOutcome.Failure) {
            // 确定失败没有会议归属字段；同摘要后必须原样重放该 Outcome。
            return new Replayed(outcome);
        }
        MeetingCommandResult result = ((MeetingCreateOutcome.Success) outcome).result();
        MeetingAggregate.MeetingSnapshot meeting = result.meeting();
        if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(meeting.subjectScope())) {
            return new Inconsistent(InconsistencyReason.SUCCESS_NOT_OWNED);
        }
        if (!request.deploymentScope().equals(meeting.deploymentScope())
                || !request.subjectScope().equals(meeting.subjectScope())) {
            return new Inconsistent(InconsistencyReason.SUCCESS_SCOPE_MISMATCH);
        }
        if (!request.clientSessionId().equals(meeting.clientSessionId())) {
            return new Inconsistent(InconsistencyReason.SUCCESS_SESSION_MISMATCH);
        }
        return new Replayed(outcome);
    }

    private static Result fromSqlFailure(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(new Inconsistent(InconsistencyReason.STORED_OUTCOME_INVALID), failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        UnavailableReason reason = retryable
                ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                : UnavailableReason.STORAGE_FAILURE;
        return logged(new Unavailable(reason, retryable), failure);
    }

    private static Result logged(Result result) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "会议创建终态只读查询完成 status={0}", result.status());
        return result;
    }

    private static Result logged(Result result, SQLException failure) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "会议创建终态只读查询完成 status={0} sqlState={1}",
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
            LOGGER.log(Level.WARNING, "会议创建终态只读查询关闭连接失败");
        }
    }
}
