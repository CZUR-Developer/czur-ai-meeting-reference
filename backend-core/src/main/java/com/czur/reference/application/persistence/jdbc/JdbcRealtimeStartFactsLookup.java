package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V013/V011 的实时 Start 专用只读投影；不执行 Start 业务编排或写事务。
 *
 * <p>每次在一个只读 REPEATABLE_READ 快照中先读 Meeting 原始部署/主体；异部署或
 * 旧无主会议在解码 V013/V011 前拒绝。V011 存在时返回其完整冻结事实，不读取 V013；
 * 首次路径则严格解码 V013，并与 Meeting 的会话、Profile、创建时间和接受音频
 * Profile 交叉核对。Token、URL、正文及会议 ID 均不进入日志。</p>
 */
public final class JdbcRealtimeStartFactsLookup implements RealtimeStartFactsLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcRealtimeStartFactsLookup.class.getName());
    private static final String MEETING_SQL = """
            SELECT deployment_scope, subject_scope, client_session_id,
                   profile_id, profile_revision, created_at
              FROM %s WHERE meeting_id = ?
            """;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    public JdbcRealtimeStartFactsLookup(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.POSTGRESQL);
    }

    /** 从唯一上下文读取同场事实；保留只读 RR 快照，不参与 Start 写事务。 */
    public JdbcRealtimeStartFactsLookup(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcRealtimeStartFactsLookup(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** 只读查找；无论成功或失败均不修补事实或改变会议状态。 */
    @Override
    public Result find(String trustedDeploymentScope, String meetingId) {
        if (trustedDeploymentScope == null || trustedDeploymentScope.isBlank()) {
            throw new IllegalArgumentException("trustedDeploymentScope is required");
        }
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            pinSchema(connection);
            MeetingRow meeting = readMeeting(connection, meetingId);
            if (meeting == null) {
                return reject(Reason.MISSING);
            }
            if (!trustedDeploymentScope.equals(meeting.deploymentScope())
                    || meeting.subjectScope() == null || meeting.subjectScope().isBlank()
                    || MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                            meeting.subjectScope())) {
                return reject(Reason.ACCESS_DENIED);
            }
            FinalizationExecutionFact execution =
                    new JdbcFinalizationExecutionFactStore(connection, engine)
                            .find(meetingId).orElse(null);
            Result result;
            if (execution != null) {
                result = new Started(meeting.subjectScope(), execution);
            } else {
                CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection, engine)
                        .findByMeetingIdReadOnly(meetingId).orElse(null);
                if (first == null || !sameFirstFacts(meetingId, meeting, first)) {
                    return reject(Reason.CREATE_FACT_INCONSISTENT);
                }
                var request = first.request().requestSnapshot();
                result = new First(meeting.subjectScope(), request.title(),
                        request.profile().id(), request.profile().revision(),
                        request.requestedCapabilities());
            }
            // 只读事务也必须确认结束；rollback 失败不能返回看似已核验的事实。
            connection.rollback();
            return result;
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            return reject(failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                    ? Reason.CREATE_FACT_INCONSISTENT : Reason.UNAVAILABLE);
        } catch (JdbcFinalizationExecutionFactStore.StoredFactException failure) {
            return reject(failure.kind()
                    == JdbcFinalizationExecutionFactStore.FailureKind.CORRUPT_DATA
                    ? Reason.FACT_INCONSISTENT : Reason.UNAVAILABLE);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "实时 Start 事实读取失败 sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return reject(JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                    ? Reason.FACT_INCONSISTENT : Reason.UNAVAILABLE);
        } catch (InvalidFact failure) {
            LOGGER.log(Level.WARNING, "实时 Start 事实读取失败 category=FACT_INCONSISTENT");
            return reject(Reason.FACT_INCONSISTENT);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "实时 Start 事实读取失败 category=INTERNAL");
            return reject(Reason.UNAVAILABLE);
        }
    }

    private MeetingRow readMeeting(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MEETING_SQL.formatted(
                JdbcDatabaseTable.MEETING.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Instant created = JdbcValues.instant(rows, "created_at");
                MeetingRow row = new MeetingRow(JdbcValues.text(rows, "deployment_scope"),
                        JdbcValues.text(rows, "subject_scope"),
                        JdbcValues.text(rows, "client_session_id"),
                        rows.getString("profile_id"), rows.getInt("profile_revision"),
                        created);
                if (rows.next()) throw new InvalidFact();
                return row;
            }
        }
    }

    private static boolean sameFirstFacts(String meetingId, MeetingRow meeting,
            CreateMeetingFactStore.Record first) {
        var request = first.request().requestSnapshot();
        var response = first.response();
        return first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                        meeting.deploymentScope(), meeting.subjectScope()))
                && response.meetingId().equals(meetingId)
                && request.clientSessionId().equals(meeting.clientSessionId())
                && response.acceptedProfile().equals(request.profile())
                && response.acceptedProfile().id().equals(meeting.profileId())
                && response.acceptedProfile().revision() == meeting.profileRevision()
                && response.acceptedAudioProfile().equals(request.audioProfile())
                && first.createdAt().equals(meeting.createdAt());
    }

    private void pinSchema(Connection connection) throws SQLException {
        if (engine == JdbcDatabaseEngine.MYSQL) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_schema()");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getString(1) == null
                    || rows.getString(1).startsWith("pg_temp")) {
                throw new SQLException("deployment schema unavailable");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
                """)) {
            statement.execute();
        }
    }

    private static Rejected reject(Reason reason) {
        LOGGER.log(reason == Reason.UNAVAILABLE ? Level.WARNING : Level.FINE,
                "实时 Start 事实拒绝 category={0}", reason.name());
        return new Rejected(reason);
    }

    private record MeetingRow(String deploymentScope, String subjectScope,
            String clientSessionId, String profileId, int profileRevision, Instant createdAt) { }

    private static final class InvalidFact extends RuntimeException { }
}
